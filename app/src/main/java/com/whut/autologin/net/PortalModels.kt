package com.whut.autologin.net

data class ProbeResult(
    val requestUrl: String,
    val code: Int,
    val effectiveUrl: String
)

enum class LoginOutcome {
    SUCCESS,
    CAPTCHA_REQUIRED,
    BILLING_PAUSED,
    POLICY_BLOCKED,
    FAILED
}

data class LoginResult(
    val outcome: LoginOutcome,
    val message: String,
    val responseCode: Int? = null,
    val token: String = ""
)
