package com.whut.autologin.data

data class RuntimeState(
    val online: Boolean = false,
    val targetNetwork: Boolean = false,
    val portalDetected: Boolean = false,
    val paused: Boolean = false,
    val pauseReason: String = "",
    val pauseMessage: String = "",
    val lastProbeCode: Int = 0,
    val effectiveUrl: String = "",
    val lastResult: String = "",
    val lastMessage: String = "",
    val lastCheckAt: Long = 0L,
    val logs: List<String> = emptyList()
)
