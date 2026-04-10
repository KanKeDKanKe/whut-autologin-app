package com.whut.autologin.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.whut.autologin.data.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.net.Inet4Address
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class PortalClient(private val context: Context) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(ConnectivityManager::class.java)
    }

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(WifiManager::class.java)
    }

    suspend fun probe(detectUrl: String): ProbeResult = withContext(Dispatchers.IO) {
        val safeUrl = detectUrl.ifBlank { "http://1.1.1.1" }
        val request = Request.Builder().url(safeUrl).get().build()

        runCatching {
            client.newCall(request).execute().use { response ->
                ProbeResult(
                    requestUrl = safeUrl,
                    code = response.code,
                    effectiveUrl = response.request.url.toString()
                )
            }
        }.getOrElse {
            ProbeResult(
                requestUrl = safeUrl,
                code = 0,
                effectiveUrl = safeUrl
            )
        }
    }

    suspend fun probeWithFallback(detectUrl: String): List<ProbeResult> = withContext(Dispatchers.IO) {
        val candidates = linkedSetOf(
            detectUrl.ifBlank { "http://1.1.1.1" },
            "http://connectivitycheck.gstatic.com/generate_204",
            "http://www.msftconnecttest.com/connecttest.txt",
            "https://www.qq.com/favicon.ico"
        )

        val results = mutableListOf<ProbeResult>()
        for (candidate in candidates) {
            val result = probe(candidate)
            results += result
        }
        results
    }

    fun looksLikePortal(
        effectiveUrl: String,
        detectUrl: String,
        portalHostPattern: String,
        forcedPortalBase: String = ""
    ): Boolean {
        if (effectiveUrl.isBlank()) return false
        if (normalizeUrl(effectiveUrl) == normalizeUrl(detectUrl)) return false

        if (
            effectiveUrl.contains("wlanuserip=") ||
            effectiveUrl.contains("login.html") ||
            effectiveUrl.contains("/tpl/") ||
            effectiveUrl.contains("/account/")
        ) {
            return true
        }

        val portalBase = getPortalBase(effectiveUrl, forcedPortalBase) ?: return false
        val portalHost = portalBase.toHttpUrlOrNull()?.host.orEmpty()
        if (portalHost.isBlank()) return false

        if (forcedPortalBase.isNotBlank()) {
            val forcedBase = getPortalBase(forcedPortalBase, "")
            if (!forcedBase.isNullOrBlank() && normalizeUrl(forcedBase) == normalizeUrl(portalBase)) {
                return true
            }
        }

        return matchesRegex(portalHostPattern, portalHost)
    }

    fun isOnlineFromProbe(probeCode: Int, effectiveUrl: String, portalDetected: Boolean): Boolean {
        val acceptable = probeCode in setOf(200, 204, 301, 302, 307, 308, 401, 403, 404)
        return acceptable &&
            effectiveUrl.isNotBlank() &&
            !portalDetected
    }

    fun isTargetNetwork(config: AppConfig, portalDetected: Boolean): Boolean {
        val wifiConnected = isWifiConnected()

        if (config.requireWifi && !wifiConnected && !portalDetected) {
            return false
        }

        if (config.ssidPattern.isBlank()) {
            return true
        }

        val ssid = currentSsid()
        if (ssid.isNullOrBlank()) {
            // On modern Android/ROMs, SSID may be hidden when location permission/service is unavailable.
            // Avoid false "non-target" when we are clearly on Wi-Fi.
            return wifiConnected || portalDetected
        }

        return matchesRegex(config.ssidPattern, ssid)
    }

    fun canTryDirectLogin(config: AppConfig): Boolean {
        return config.portalBase.isNotBlank() || !portalBaseFromHostPattern(config.portalHostPattern).isNullOrBlank()
    }

    suspend fun login(config: AppConfig, redirectUrl: String?): LoginResult = withContext(Dispatchers.IO) {
        if (config.username.isBlank() || config.password.isBlank()) {
            return@withContext LoginResult(LoginOutcome.FAILED, "账号或密码为空")
        }

        val initialRedirect = redirectUrl?.takeIf { it.isNotBlank() } ?: probe(config.detectUrl).effectiveUrl
        if (initialRedirect.isBlank()) {
            return@withContext LoginResult(LoginOutcome.FAILED, "无法获取 portal 跳转地址")
        }

        var redirect = initialRedirect
        var redirectHttpUrl = redirect.toHttpUrlOrNull()
        var portalBase = getPortalBase(redirect, config.portalBase)
            ?: config.portalBase.ifBlank { portalBaseFromHostPattern(config.portalHostPattern).orEmpty() }

        var userIp = redirectHttpUrl?.let { firstQueryParam(it, "userip", "wlanuserip", "ip") }.orEmpty()
        var nasId = redirectHttpUrl?.let { firstQueryParam(it, "nasId", "wlanacname") }.orEmpty()
        var switchIp = redirectHttpUrl?.let { firstQueryParam(it, "switchip", "acip") }.orEmpty()
        var userMac = redirectHttpUrl?.let { firstQueryParam(it, "mac") }.orEmpty()

        if (userIp.isBlank() || nasId.isBlank()) {
            val discovered = discoverPortalContext(config, redirect)
            if (discovered != null) {
                if (discovered.redirectUrl.isNotBlank()) {
                    redirect = discovered.redirectUrl
                    redirectHttpUrl = discovered.redirectUrl.toHttpUrlOrNull()
                }
                if (portalBase.isBlank()) {
                    portalBase = discovered.portalBase.orEmpty()
                }
                if (userIp.isBlank()) userIp = discovered.userIp.orEmpty()
                if (nasId.isBlank()) nasId = discovered.nasId.orEmpty()
                if (switchIp.isBlank()) switchIp = discovered.switchIp.orEmpty()
                if (userMac.isBlank()) userMac = discovered.userMac.orEmpty()
            }
        }

        if (userIp.isBlank()) {
            userIp = currentIpv4()
        }

        if (portalBase.isBlank()) {
            return@withContext LoginResult(
                LoginOutcome.FAILED,
                "无法解析 portal base，请在配置中填写 Portal Base（如 http://172.30.21.100）"
            )
        }

        if (userIp.isBlank()) {
            return@withContext LoginResult(
                LoginOutcome.FAILED,
                "缺少必要参数(userIp)。当前URL=$redirect，portalBase=$portalBase。请确认 Portal Base，并尝试关闭私有DNS后重试。"
            )
        }

        val csrfContext = requestCsrfToken(portalBase)

        val body = FormBody.Builder()
            .add("username", config.username)
            .add("password", config.password)
            .add("domain", config.domain)
            .add("nasId", nasId)
            .add("userIpv4", userIp)
            .add("switchip", switchIp)
            .add("userMac", userMac)
            .add("captcha", "")
            .add("captchaId", "")
            .build()

        val apiBases = buildApiBaseCandidates(
            if (csrfContext.apiBase.isNotBlank()) csrfContext.apiBase else portalBase
        )
        var lastFailureMessage = ""
        var hadNetworkError = false
        var anyHttp2xx = false

        for (apiBase in apiBases) {
            val loginUrl = "${apiBase.trimEnd('/')}/api/account/login"
            val requestBuilder = Request.Builder()
                .url(loginUrl)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "${apiBase.trimEnd('/')}/")
                .post(body)

            if (csrfContext.token.isNotBlank()) {
                requestBuilder.header("X-CSRF-Token", csrfContext.token)
            }
            if (csrfContext.cookie.isNotBlank()) {
                requestBuilder.header("Cookie", csrfContext.cookie)
            }

            val loginHttp = runCatching {
                client.newCall(requestBuilder.build()).execute().use { response ->
                    LoginHttpResult(
                        statusCode = response.code,
                        body = response.body?.string().orEmpty()
                    )
                }
            }.getOrElse {
                hadNetworkError = true
                lastFailureMessage = "登录请求失败(${loginUrl}): ${it.message.orEmpty()}"
                LoginHttpResult(statusCode = 0, body = "")
            }

            if (loginHttp.statusCode in 200..299) {
                anyHttp2xx = true
            }

            val rawBody = loginHttp.body
            if (rawBody.isBlank()) {
                continue
            }

            val parsed = parseLoginResponse(rawBody)
            val mergedMsg = parsed.authMsg.ifBlank { parsed.msg }.ifBlank { "unknown response" }
            val isSuccess = parsed.code == 0 ||
                (parsed.code == null && (
                    parsed.authCode.startsWith("ok:") ||
                        containsSuccessKeyword(parsed.authMsg) ||
                        containsSuccessKeyword(parsed.msg)
                    ))

            if (isSuccess) {
                return@withContext LoginResult(
                    outcome = LoginOutcome.SUCCESS,
                    message = "登录成功",
                    responseCode = parsed.code,
                    token = parsed.token
                )
            }

            if (parsed.code == 2) {
                return@withContext LoginResult(
                    outcome = LoginOutcome.CAPTCHA_REQUIRED,
                    message = "Portal 需要验证码，自动登录暂停",
                    responseCode = parsed.code
                )
            }

            val mergedForPattern = listOf(parsed.authCode, parsed.authMsg, parsed.msg).joinToString("\n")
            if (matchesRegex(config.billingPattern, mergedForPattern)) {
                return@withContext LoginResult(
                    outcome = LoginOutcome.BILLING_PAUSED,
                    message = mergedMsg,
                    responseCode = parsed.code,
                    token = parsed.token
                )
            }

            if (matchesRegex(config.nonBillingPattern, mergedForPattern)) {
                return@withContext LoginResult(
                    outcome = LoginOutcome.POLICY_BLOCKED,
                    message = mergedMsg,
                    responseCode = parsed.code,
                    token = parsed.token
                )
            }

            lastFailureMessage = "登录失败(${loginUrl}): $mergedMsg"
        }

        if (anyHttp2xx) {
            return@withContext LoginResult(
                outcome = LoginOutcome.SUCCESS,
                message = "登录接口已响应（2xx），按成功处理"
            )
        }

        if (lastFailureMessage.isNotBlank()) {
            return@withContext LoginResult(LoginOutcome.FAILED, lastFailureMessage)
        }

        if (hadNetworkError) {
            return@withContext LoginResult(LoginOutcome.FAILED, "登录请求均失败，请检查门户地址可达性")
        }

        if (csrfContext.token.isBlank()) {
            return@withContext LoginResult(
                LoginOutcome.FAILED,
                "获取 csrf token 失败。已尝试: ${csrfContext.triedEndpoints.ifBlank { "none" }}"
            )
        }

        return@withContext LoginResult(LoginOutcome.FAILED, "登录失败，未获得可解析响应")
    }

    private suspend fun requestCsrfToken(portalBase: String): CsrfContext = withContext(Dispatchers.IO) {
        val apiBases = buildApiBaseCandidates(portalBase)
        val tried = mutableListOf<String>()
        var fallbackCookie = ""

        for (apiBase in apiBases) {
            val csrfPaths = listOf("/api/csrf-token", "/api/csrf_token", "/csrf-token")
            for (path in csrfPaths) {
                val url = "${apiBase.trimEnd('/')}$path"
                tried += url

                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .get()
                    .build()

                val inspect = runCatching {
                    client.newCall(request).execute().use { response ->
                        InspectResult(
                            finalUrl = response.request.url.toString(),
                            location = response.header("Location").orEmpty(),
                            body = response.body?.string().orEmpty()
                        ) to response.headers("Set-Cookie").firstOrNull().orEmpty().substringBefore(';')
                    }
                }.getOrNull() ?: continue

                val cookie = inspect.second
                if (cookie.isNotBlank()) fallbackCookie = cookie

                val token = extractCsrfToken(inspect.first.body)
                if (token.isNotBlank()) {
                    return@withContext CsrfContext(
                        token = token,
                        cookie = cookie.ifBlank { fallbackCookie },
                        apiBase = apiBase,
                        triedEndpoints = tried.joinToString(" | ")
                    )
                }
            }

            val pageCandidates = listOf(
                apiBase,
                "${apiBase.trimEnd('/')}/login.html",
                "${apiBase.trimEnd('/')}/index.html",
                "${apiBase.trimEnd('/')}/eportal/",
                "${apiBase.trimEnd('/')}/portal/"
            )
            for (pageUrl in pageCandidates) {
                if (pageUrl.toHttpUrlOrNull() == null) continue
                tried += pageUrl

                val request = Request.Builder()
                    .url(pageUrl)
                    .get()
                    .build()

                val inspect = runCatching {
                    client.newCall(request).execute().use { response ->
                        InspectResult(
                            finalUrl = response.request.url.toString(),
                            location = response.header("Location").orEmpty(),
                            body = response.body?.string().orEmpty()
                        ) to response.headers("Set-Cookie").firstOrNull().orEmpty().substringBefore(';')
                    }
                }.getOrNull() ?: continue

                val cookie = inspect.second
                if (cookie.isNotBlank()) fallbackCookie = cookie

                val token = extractCsrfToken(inspect.first.body)
                if (token.isNotBlank()) {
                    return@withContext CsrfContext(
                        token = token,
                        cookie = cookie.ifBlank { fallbackCookie },
                        apiBase = apiBase,
                        triedEndpoints = tried.joinToString(" | ")
                    )
                }
            }
        }

        CsrfContext(
            token = "",
            cookie = fallbackCookie,
            apiBase = apiBases.firstOrNull().orEmpty(),
            triedEndpoints = tried.joinToString(" | ")
        )
    }

    private fun isWifiConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun currentSsid(): String? {
        if (!hasLocationPermission() || !isLocationEnabled()) {
            return null
        }

        val rawSsid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val network = connectivityManager.activeNetwork
            val caps = network?.let { connectivityManager.getNetworkCapabilities(it) }
            val wifiInfo = caps?.transportInfo as? WifiInfo
            wifiInfo?.ssid.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            runCatching { wifiManager.connectionInfo?.ssid }.getOrNull().orEmpty()
        }
        if (rawSsid.isBlank()) return null
        val ssid = rawSsid.removePrefix("\"").removeSuffix("\"")
        if (ssid.equals("<unknown ssid>", ignoreCase = true)) return null
        return ssid
    }

    private fun currentIpv4(): String {
        val network = connectivityManager.activeNetwork ?: return ""
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return ""
        val address = linkProperties.linkAddresses
            .mapNotNull { it.address as? Inet4Address }
            .firstOrNull()
        return address?.hostAddress.orEmpty()
    }

    private fun discoverPortalContext(config: AppConfig, seedUrl: String): PortalContext? {
        val candidates = linkedSetOf<String>()
        if (seedUrl.isNotBlank()) {
            candidates += seedUrl
            val seedBase = getPortalBase(seedUrl, "")
            if (!seedBase.isNullOrBlank()) {
                addBaseCandidates(candidates, seedBase)
            }
        }

        if (config.portalBase.isNotBlank()) {
            addBaseCandidates(candidates, config.portalBase.trimEnd('/'))
        }

        val patternBase = portalBaseFromHostPattern(config.portalHostPattern)
        if (!patternBase.isNullOrBlank()) {
            addBaseCandidates(candidates, patternBase)
        }

        candidates += "http://1.1.1.1"
        candidates += "http://connectivitycheck.gstatic.com/generate_204"
        candidates += "http://www.msftconnecttest.com/connecttest.txt"

        val noRedirectClient = client.newBuilder().followRedirects(false).build()

        for (candidate in candidates) {
            val direct = requestInspect(client, candidate)
            val directCtx = buildPortalContext(
                candidate = candidate,
                finalUrl = direct.finalUrl,
                body = direct.body
            )
            if (directCtx.hasRequiredParams()) return directCtx

            val noRedirect = requestInspect(noRedirectClient, candidate)
            val resolved = resolveLocation(candidate, noRedirect.location)
            val locationCtx = buildPortalContext(
                candidate = candidate,
                finalUrl = resolved,
                body = noRedirect.body
            )
            if (locationCtx.hasRequiredParams()) return locationCtx

            if (!resolved.isNullOrBlank()) {
                val resolvedInspect = requestInspect(client, resolved)
                val resolvedCtx = buildPortalContext(
                    candidate = resolved,
                    finalUrl = resolvedInspect.finalUrl,
                    body = resolvedInspect.body
                )
                if (resolvedCtx.hasRequiredParams()) return resolvedCtx
            }
        }

        return null
    }

    private fun addBaseCandidates(candidates: LinkedHashSet<String>, base: String) {
        val normalized = base.trimEnd('/')
        candidates += normalized
        candidates += "$normalized/login.html"
        candidates += "$normalized/index.html"
        candidates += "$normalized/eportal/"
        candidates += "$normalized/eportal/index.jsp"
        candidates += "$normalized/portal/"
        candidates += "$normalized/portal/login"
    }

    private fun buildApiBaseCandidates(portalBase: String): List<String> {
        val candidates = linkedSetOf<String>()
        val normalized = portalBase.trimEnd('/')
        if (normalized.isNotBlank()) {
            candidates += normalized
        }

        val hostBase = hostBaseFromUrl(normalized)
        if (hostBase.isNotBlank()) {
            candidates += hostBase
            candidates += "${hostBase.trimEnd('/')}/eportal"
            candidates += "${hostBase.trimEnd('/')}/portal"
        }

        if (!normalized.contains("/eportal")) {
            candidates += "${normalized.trimEnd('/')}/eportal"
        }
        if (!normalized.contains("/portal")) {
            candidates += "${normalized.trimEnd('/')}/portal"
        }

        return candidates.filter { it.toHttpUrlOrNull() != null }
    }

    private fun hostBaseFromUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return ""
        val defaultPort = when (parsed.scheme) {
            "https" -> 443
            else -> 80
        }
        val portPart = if (parsed.port == defaultPort) "" else ":${parsed.port}"
        return "${parsed.scheme}://${parsed.host}$portPart"
    }

    private fun requestInspect(targetClient: OkHttpClient, url: String): InspectResult {
        if (url.isBlank()) return InspectResult.EMPTY
        if (url.toHttpUrlOrNull() == null) return InspectResult.EMPTY
        val request = Request.Builder().url(url).get().build()
        return runCatching<InspectResult> {
            targetClient.newCall(request).execute().use { response ->
                InspectResult(
                    finalUrl = response.request.url.toString(),
                    location = response.header("Location").orEmpty(),
                    body = response.body?.string().orEmpty()
                )
            }
        }.getOrDefault(InspectResult.EMPTY)
    }

    private fun resolveLocation(baseUrl: String, location: String): String {
        if (location.isBlank()) return ""
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return location
        }
        val base = baseUrl.toHttpUrlOrNull() ?: return ""
        return base.resolve(location)?.toString().orEmpty()
    }

    private fun buildPortalContext(candidate: String, finalUrl: String, body: String): PortalContext {
        val pickedUrl = when {
            finalUrl.isNotBlank() -> finalUrl
            else -> candidate
        }
        val parsed = pickedUrl.toHttpUrlOrNull()
        val fromUrlUserIp = parsed?.let { firstQueryParam(it, "userip", "wlanuserip", "ip", "userIpv4") }.orEmpty()
        val fromUrlNasId = parsed?.let { firstQueryParam(it, "nasId", "wlanacname", "nasid", "acname") }.orEmpty()
        val fromUrlSwitchIp = parsed?.let { firstQueryParam(it, "switchip", "acip", "wlanacip") }.orEmpty()
        val fromUrlMac = parsed?.let { firstQueryParam(it, "mac", "usermac", "userMac") }.orEmpty()

        val fromBodyUserIp = extractParamFromText(body, listOf("userip", "wlanuserip", "userIpv4", "ip"))
        val fromBodyNasId = extractParamFromText(body, listOf("nasId", "wlanacname", "nasid", "acname"))
        val fromBodySwitchIp = extractParamFromText(body, listOf("switchip", "acip", "wlanacip"))
        val fromBodyMac = extractParamFromText(body, listOf("mac", "usermac", "userMac"))

        val portalBase = getPortalBase(pickedUrl, "")

        return PortalContext(
            redirectUrl = pickedUrl,
            portalBase = portalBase,
            userIp = fromUrlUserIp.ifBlank { fromBodyUserIp },
            nasId = fromUrlNasId.ifBlank { fromBodyNasId },
            switchIp = fromUrlSwitchIp.ifBlank { fromBodySwitchIp },
            userMac = fromUrlMac.ifBlank { fromBodyMac }
        )
    }

    private fun extractParamFromText(text: String, keys: List<String>): String {
        if (text.isBlank()) return ""
        for (key in keys) {
            val patterns = listOf(
                Regex("[?&]$key=([^&\\s\"'<>]+)", RegexOption.IGNORE_CASE),
                Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE),
                Regex("'$key'\\s*:\\s*'([^']+)'", RegexOption.IGNORE_CASE),
                Regex("$key\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE),
                Regex("$key\\s*=\\s*'([^']+)'", RegexOption.IGNORE_CASE),
                Regex("name\\s*=\\s*\"$key\"[^>]*value\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE),
                Regex("name\\s*=\\s*'$key'[^>]*value\\s*=\\s*'([^']+)'", RegexOption.IGNORE_CASE),
                Regex("id\\s*=\\s*\"$key\"[^>]*value\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE),
                Regex("id\\s*=\\s*'$key'[^>]*value\\s*=\\s*'([^']+)'", RegexOption.IGNORE_CASE)
            )

            for (pattern in patterns) {
                val value = pattern.find(text)?.groupValues?.getOrNull(1).orEmpty()
                if (value.isNotBlank()) {
                    return decodeMaybeUrl(value)
                }
            }
        }
        return ""
    }

    private fun extractCsrfToken(text: String): String {
        if (text.isBlank()) return ""
        val patterns = listOf(
            Regex("\"csrf_token\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE),
            Regex("\"csrfToken\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE),
            Regex("csrf[_-]?token\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE),
            Regex("csrf[_-]?token\\s*=\\s*'([^']+)'", RegexOption.IGNORE_CASE),
            Regex("name\\s*=\\s*\"csrf[_-]?token\"[^>]*value\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE),
            Regex("name\\s*=\\s*'csrf[_-]?token'[^>]*value\\s*=\\s*'([^']+)'", RegexOption.IGNORE_CASE),
            Regex("X-CSRF-Token[\"']?\\s*[:=]\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val value = pattern.find(text)?.groupValues?.getOrNull(1).orEmpty()
            if (value.isNotBlank()) {
                return decodeMaybeUrl(value)
            }
        }
        return ""
    }

    private fun decodeMaybeUrl(value: String): String {
        return runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun firstQueryParam(httpUrl: okhttp3.HttpUrl, vararg names: String): String? {
        for (name in names) {
            val value = httpUrl.queryParameter(name)
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun getPortalBase(url: String, forcedBase: String): String? {
        if (forcedBase.isNotBlank()) {
            return forcedBase.trimEnd('/')
        }

        val parsed = url.toHttpUrlOrNull() ?: return null
        val defaultPort = when (parsed.scheme) {
            "https" -> 443
            else -> 80
        }
        val portPart = if (parsed.port == defaultPort) "" else ":${parsed.port}"
        return "${parsed.scheme}://${parsed.host}$portPart"
    }

    private fun portalBaseFromHostPattern(pattern: String): String? {
        val raw = pattern.trim()
        if (raw.isBlank()) return null
        val normalized = raw
            .replace("\\\\.", ".")
            .replace("^", "")
            .replace("$", "")

        val host = Regex("([A-Za-z0-9.-]+(?::\\d{1,5})?)")
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

        if (host.isBlank()) return null
        if (!Regex("^[A-Za-z0-9.-]+(?::\\d{1,5})?$").matches(host)) return null
        return "http://$host"
    }

    private fun normalizeUrl(url: String): String {
        return url.trimEnd('/')
    }

    private fun matchesRegex(pattern: String, text: String): Boolean {
        if (pattern.isBlank() || text.isBlank()) return false
        return runCatching { Regex(pattern).containsMatchIn(text) }.getOrDefault(false)
    }

    private fun containsSuccessKeyword(text: String): Boolean {
        if (text.isBlank()) return false
        return text.contains("认证成功") || text.contains("已登录成功") || text.contains("登录成功")
    }

    private fun parseLoginResponse(rawBody: String): ParsedLoginResponse {
        val obj = runCatching { JSONObject(rawBody) }.getOrNull()
        if (obj != null) {
            val code = if (obj.has("code")) obj.optInt("code") else null
            return ParsedLoginResponse(
                code = code,
                token = obj.optString("token").orEmpty(),
                msg = obj.optString("msg").orEmpty(),
                authMsg = obj.optString("authMsg").orEmpty(),
                authCode = obj.optString("authCode").orEmpty()
            )
        }

        val code = Regex("\"code\"\\s*:\\s*(\\d+)").find(rawBody)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val token = Regex("\"token\"\\s*:\\s*\"([^\"]*)\"").find(rawBody)?.groupValues?.getOrNull(1).orEmpty()
        val msg = Regex("\"msg\"\\s*:\\s*\"([^\"]*)\"").find(rawBody)?.groupValues?.getOrNull(1).orEmpty()
        val authMsg = Regex("\"authMsg\"\\s*:\\s*\"([^\"]*)\"").find(rawBody)?.groupValues?.getOrNull(1).orEmpty()
        val authCode = Regex("\"authCode\"\\s*:\\s*\"([^\"]*)\"").find(rawBody)?.groupValues?.getOrNull(1).orEmpty()

        return ParsedLoginResponse(
            code = code,
            token = token,
            msg = msg,
            authMsg = authMsg,
            authCode = authCode
        )
    }

    private data class ParsedLoginResponse(
        val code: Int?,
        val token: String,
        val msg: String,
        val authMsg: String,
        val authCode: String
    )

    private data class LoginHttpResult(
        val statusCode: Int,
        val body: String
    )

    private data class CsrfContext(
        val token: String,
        val cookie: String,
        val apiBase: String,
        val triedEndpoints: String
    )

    private data class InspectResult(
        val finalUrl: String,
        val location: String,
        val body: String
    ) {
        companion object {
            val EMPTY = InspectResult(
                finalUrl = "",
                location = "",
                body = ""
            )
        }
    }

    private data class PortalContext(
        val redirectUrl: String,
        val portalBase: String?,
        val userIp: String?,
        val nasId: String?,
        val switchIp: String?,
        val userMac: String?
    ) {
        fun hasRequiredParams(): Boolean = !userIp.isNullOrBlank() && !nasId.isNullOrBlank()
    }
}
