package com.whut.autologin.data

data class AppConfig(
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val detectUrl: String = "http://1.1.1.1",
    val requireWifi: Boolean = true,
    val ssidPattern: String = "^WHUT.*$",
    val portalHostPattern: String = "172.30.21.100",
    val portalBase: String = "",
    val checkIntervalSeconds: Long = 600,
    val retryIntervalSeconds: Long = 30,
    val billingPattern: String = DEFAULT_BILLING_PATTERN,
    val nonBillingPattern: String = DEFAULT_NON_BILLING_PATTERN,
    val autoServiceEnabled: Boolean = false
) {
    fun normalized(): AppConfig = copy(
        detectUrl = detectUrl.ifBlank { "http://1.1.1.1" },
        checkIntervalSeconds = checkIntervalSeconds.coerceAtLeast(30),
        retryIntervalSeconds = retryIntervalSeconds.coerceAtLeast(10),
        ssidPattern = ssidPattern.ifBlank { "^WHUT.*$" },
        portalHostPattern = portalHostPattern.ifBlank { "172.30.21.100" },
        billingPattern = billingPattern.ifBlank { DEFAULT_BILLING_PATTERN },
        nonBillingPattern = nonBillingPattern.ifBlank { DEFAULT_NON_BILLING_PATTERN }
    )

    companion object {
        const val DEFAULT_BILLING_PATTERN = "欠费|余额不足|停机|充值|缴费|账户欠费|账号欠费|资费"
        const val DEFAULT_NON_BILLING_PATTERN = "未找到符合条件的计费策略|账号与当前网络不匹配|当前网络不匹配|接入方式不匹配|运营商不匹配"
    }
}
