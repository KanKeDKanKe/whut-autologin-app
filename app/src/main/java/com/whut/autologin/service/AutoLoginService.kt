package com.whut.autologin.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.whut.autologin.R
import com.whut.autologin.data.AppConfig
import com.whut.autologin.data.ConfigStore
import com.whut.autologin.data.StateStore
import com.whut.autologin.net.LoginOutcome
import com.whut.autologin.net.PortalClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AutoLoginService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runMutex = Mutex()

    private lateinit var configStore: ConfigStore
    private lateinit var stateStore: StateStore
    private lateinit var portalClient: PortalClient
    private lateinit var connectivityManager: ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var periodicJob: Job? = null
    private var configObserverJob: Job? = null

    private var latestConfig: AppConfig = AppConfig()
    private var foregroundStarted = false
    private var lastNetworkTriggerAt: Long = 0L

    override fun onCreate() {
        super.onCreate()
        configStore = ConfigStore(applicationContext)
        stateStore = StateStore(applicationContext)
        portalClient = PortalClient(applicationContext)
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        createNotificationChannels()
        observeConfig()
        ensureNetworkCallbackRegistered()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            serviceScope.launch {
                stateStore.appendLog("停止自动检测服务")
            }
            stopSelf()
            return START_NOT_STICKY
        }

        if (!foregroundStarted) {
            startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification("服务初始化中"))
            foregroundStarted = true
        }

        when (action) {
            ACTION_START -> {
                serviceScope.launch {
                    val config = configStore.get()
                    if (config.autoServiceEnabled) {
                        runOnce(force = false, trigger = "service_start")
                    } else {
                        updateForegroundNotification("自动检测未开启")
                    }
                }
            }

            ACTION_RUN_NOW -> {
                serviceScope.launch {
                    val config = configStore.get()
                    runOnce(force = true, trigger = "manual_run")
                    if (!config.autoServiceEnabled) {
                        stopSelf()
                    }
                }
            }

            ACTION_PAUSE -> {
                serviceScope.launch {
                    stateStore.setPaused(true, "manual", "用户手动暂停")
                    stateStore.appendLog("手动暂停自动检测")
                    updateForegroundNotification("已暂停")
                }
            }

            ACTION_RESUME -> {
                serviceScope.launch {
                    stateStore.setPaused(false)
                    stateStore.appendLog("手动恢复自动检测")
                    runOnce(force = true, trigger = "manual_resume")
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        periodicJob?.cancel()
        configObserverJob?.cancel()
        unregisterNetworkCallbackSafe()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeConfig() {
        configObserverJob?.cancel()
        configObserverJob = serviceScope.launch {
            configStore.flow.collect { config ->
                latestConfig = config
                if (config.autoServiceEnabled) {
                    startPeriodicLoop(config.checkIntervalSeconds)
                    updateForegroundNotification("自动检测运行中")
                } else {
                    periodicJob?.cancel()
                    periodicJob = null
                    updateForegroundNotification("自动检测未开启")
                }
            }
        }
    }

    private fun startPeriodicLoop(intervalSeconds: Long) {
        periodicJob?.cancel()
        periodicJob = serviceScope.launch {
            val interval = intervalSeconds.coerceAtLeast(30)
            while (true) {
                delay(interval * 1000)
                runOnce(force = false, trigger = "periodic")
            }
        }
    }

    private suspend fun runOnce(force: Boolean, trigger: String) {
        runMutex.withLock {
            val config = configStore.get()
            latestConfig = config
            val state = stateStore.get()

            if (!force && state.paused) {
                stateStore.updateSnapshot(
                    online = state.online,
                    targetNetwork = state.targetNetwork,
                    portalDetected = state.portalDetected,
                    probeCode = state.lastProbeCode,
                    effectiveUrl = state.effectiveUrl,
                    result = "已暂停",
                    message = state.pauseMessage.ifBlank { "等待手动恢复" }
                )
                stateStore.appendLog("[$trigger] 已暂停，跳过检测")
                updateForegroundNotification("已暂停")
                return
            }

            val probeCandidates = portalClient.probeWithFallback(config.detectUrl)
            var probe = probeCandidates.firstOrNull() ?: portalClient.probe(config.detectUrl)
            var portalDetected = false
            var online = false

            for (attempt in probeCandidates) {
                val isPortal = portalClient.looksLikePortal(
                    effectiveUrl = attempt.effectiveUrl,
                    detectUrl = attempt.requestUrl,
                    portalHostPattern = config.portalHostPattern,
                    forcedPortalBase = config.portalBase
                )
                if (isPortal) {
                    portalDetected = true
                    online = false
                    probe = attempt
                    break
                }

                val isOnline = portalClient.isOnlineFromProbe(
                    probeCode = attempt.code,
                    effectiveUrl = attempt.effectiveUrl,
                    portalDetected = false
                )
                if (isOnline) {
                    online = true
                    portalDetected = false
                    probe = attempt
                    break
                }

                if (probe.code == 0 && attempt.code != 0) {
                    probe = attempt
                }
            }

            val targetNetwork = portalClient.isTargetNetwork(config, portalDetected)

            if (online) {
                stateStore.updateSnapshot(
                    online = true,
                    targetNetwork = targetNetwork,
                    portalDetected = portalDetected,
                    probeCode = probe.code,
                    effectiveUrl = probe.effectiveUrl,
                    result = "网络在线，无需登录",
                    message = "probe=${probe.code}, url=${probe.requestUrl}"
                )
                stateStore.appendLog("[$trigger] 在线，无需登录")
                updateForegroundNotification("在线")
                return
            }

            if (!targetNetwork) {
                stateStore.updateSnapshot(
                    online = false,
                    targetNetwork = false,
                    portalDetected = portalDetected,
                    probeCode = probe.code,
                    effectiveUrl = probe.effectiveUrl,
                    result = "非目标网络，跳过",
                    message = "当前不匹配配置的网络范围"
                )
                stateStore.appendLog("[$trigger] 非目标网络，跳过")
                updateForegroundNotification("等待目标网络")
                return
            }

            if (!online && !portalDetected && state.lastResult.contains("登录成功")) {
                stateStore.updateSnapshot(
                    online = true,
                    targetNetwork = true,
                    portalDetected = false,
                    probeCode = probe.code,
                    effectiveUrl = probe.effectiveUrl,
                    result = "网络探测不稳定，沿用已登录状态",
                    message = "probe=${probe.code}, url=${probe.requestUrl}"
                )
                stateStore.appendLog("[$trigger] 探测不稳定，沿用已登录状态")
                updateForegroundNotification("在线")
                return
            }

            if (!portalDetected) {
                if (!portalClient.canTryDirectLogin(config)) {
                    stateStore.updateSnapshot(
                        online = false,
                        targetNetwork = true,
                        portalDetected = false,
                        probeCode = probe.code,
                        effectiveUrl = probe.effectiveUrl,
                        result = "未识别到校园 portal",
                        message = "建议填写 Portal Base 后重试"
                    )
                    stateStore.appendLog("[$trigger] 未识别到 portal，且未配置可探测的 Portal Base")
                    updateForegroundNotification("等待 portal")
                    return
                }

                stateStore.appendLog("[$trigger] 未识别到 portal，尝试直连登录探测")
            }

            val login = portalClient.login(config, probe.effectiveUrl)
            when (login.outcome) {
                LoginOutcome.SUCCESS -> {
                    stateStore.setPaused(false)
                    stateStore.updateSnapshot(
                        online = true,
                        targetNetwork = true,
                        portalDetected = true,
                        probeCode = probe.code,
                        effectiveUrl = probe.effectiveUrl,
                        result = "登录成功",
                        message = login.message
                    )
                    stateStore.appendLog("[$trigger] 登录成功")
                    updateForegroundNotification("登录成功")
                }

                LoginOutcome.CAPTCHA_REQUIRED -> {
                    stateStore.updateSnapshot(
                        online = false,
                        targetNetwork = true,
                        portalDetected = true,
                        probeCode = probe.code,
                        effectiveUrl = probe.effectiveUrl,
                        result = "需要验证码",
                        message = login.message
                    )
                    stateStore.appendLog("[$trigger] 需要验证码")
                    updateForegroundNotification("需要验证码")
                }

                LoginOutcome.BILLING_PAUSED -> {
                    stateStore.setPaused(true, "billing", login.message)
                    stateStore.updateSnapshot(
                        online = false,
                        targetNetwork = true,
                        portalDetected = true,
                        probeCode = probe.code,
                        effectiveUrl = probe.effectiveUrl,
                        result = "检测到疑似欠费，已暂停",
                        message = login.message
                    )
                    stateStore.appendLog("[$trigger] 疑似欠费，自动暂停")
                    postPauseNotification(login.message)
                    updateForegroundNotification("已暂停")
                }

                LoginOutcome.POLICY_BLOCKED -> {
                    stateStore.updateSnapshot(
                        online = false,
                        targetNetwork = true,
                        portalDetected = true,
                        probeCode = probe.code,
                        effectiveUrl = probe.effectiveUrl,
                        result = "入口策略拦截",
                        message = login.message
                    )
                    stateStore.appendLog("[$trigger] 入口策略拦截: ${login.message}")
                    updateForegroundNotification("登录受限")
                }

                LoginOutcome.FAILED -> {
                    stateStore.updateSnapshot(
                        online = false,
                        targetNetwork = true,
                        portalDetected = portalDetected,
                        probeCode = probe.code,
                        effectiveUrl = probe.effectiveUrl,
                        result = "登录失败",
                        message = login.message
                    )
                    stateStore.appendLog("[$trigger] 登录失败: ${login.message}")
                    updateForegroundNotification("登录失败")
                }
            }
        }
    }

    private fun ensureNetworkCallbackRegistered() {
        if (networkCallback != null) return

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                triggerByNetworkChange("network_available")
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    triggerByNetworkChange("network_changed")
                }
            }
        }

        runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }
    }

    private fun unregisterNetworkCallbackSafe() {
        val callback = networkCallback ?: return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    private fun triggerByNetworkChange(trigger: String) {
        val now = System.currentTimeMillis()
        if (now - lastNetworkTriggerAt < 3000) return
        lastNetworkTriggerAt = now

        serviceScope.launch {
            if (latestConfig.autoServiceEnabled) {
                runOnce(force = false, trigger = trigger)
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)

        val serviceChannel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            "WHUT 自动登录服务",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(serviceChannel)

        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "WHUT 自动登录告警",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(alertChannel)
    }

    private fun buildForegroundNotification(content: String): Notification {
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle("WHUT 自动登录")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }

    private fun updateForegroundNotification(content: String) {
        if (!foregroundStarted) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification(content))
    }

    private fun postPauseNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("校园网自动登录已暂停")
            .setContentText(message.ifBlank { "检测到疑似欠费，请充值后手动恢复" })
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .build()
        manager.notify(PAUSE_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val ACTION_START = "com.whut.autologin.action.START"
        private const val ACTION_STOP = "com.whut.autologin.action.STOP"
        private const val ACTION_RUN_NOW = "com.whut.autologin.action.RUN_NOW"
        private const val ACTION_PAUSE = "com.whut.autologin.action.PAUSE"
        private const val ACTION_RESUME = "com.whut.autologin.action.RESUME"

        private const val SERVICE_CHANNEL_ID = "whut_auto_login_service"
        private const val ALERT_CHANNEL_ID = "whut_auto_login_alert"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val PAUSE_NOTIFICATION_ID = 1002

        fun start(context: Context) {
            startWithAction(context, ACTION_START)
        }

        fun stop(context: Context) {
            startWithAction(context, ACTION_STOP)
        }

        fun runNow(context: Context) {
            startWithAction(context, ACTION_RUN_NOW)
        }

        fun pause(context: Context) {
            startWithAction(context, ACTION_PAUSE)
        }

        fun resume(context: Context) {
            startWithAction(context, ACTION_RESUME)
        }

        private fun startWithAction(context: Context, action: String) {
            val intent = Intent(context, AutoLoginService::class.java).apply {
                this.action = action
            }

            if (action == ACTION_STOP) {
                context.startService(intent)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
