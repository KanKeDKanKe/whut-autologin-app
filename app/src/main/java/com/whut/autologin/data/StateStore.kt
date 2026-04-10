package com.whut.autologin.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StateStore(private val context: Context) {
    val flow: Flow<RuntimeState> = context.stateDataStore.data.map { prefs ->
        RuntimeState(
            online = prefs[StateKeys.ONLINE] ?: false,
            targetNetwork = prefs[StateKeys.TARGET_NETWORK] ?: false,
            portalDetected = prefs[StateKeys.PORTAL_DETECTED] ?: false,
            paused = prefs[StateKeys.PAUSED] ?: false,
            pauseReason = prefs[StateKeys.PAUSE_REASON].orEmpty(),
            pauseMessage = prefs[StateKeys.PAUSE_MESSAGE].orEmpty(),
            lastProbeCode = (prefs[StateKeys.LAST_PROBE_CODE] ?: 0L).toInt(),
            effectiveUrl = prefs[StateKeys.EFFECTIVE_URL].orEmpty(),
            lastResult = prefs[StateKeys.LAST_RESULT].orEmpty(),
            lastMessage = prefs[StateKeys.LAST_MESSAGE].orEmpty(),
            lastCheckAt = prefs[StateKeys.LAST_CHECK_AT] ?: 0L,
            logs = decodeLogs(prefs[StateKeys.LOGS].orEmpty())
        )
    }

    suspend fun get(): RuntimeState = flow.first()

    suspend fun setPaused(paused: Boolean, reason: String = "", message: String = "") {
        context.stateDataStore.edit { prefs ->
            prefs[StateKeys.PAUSED] = paused
            prefs[StateKeys.PAUSE_REASON] = reason
            prefs[StateKeys.PAUSE_MESSAGE] = message
        }
    }

    suspend fun updateSnapshot(
        online: Boolean,
        targetNetwork: Boolean,
        portalDetected: Boolean,
        probeCode: Int,
        effectiveUrl: String,
        result: String,
        message: String
    ) {
        context.stateDataStore.edit { prefs ->
            prefs[StateKeys.ONLINE] = online
            prefs[StateKeys.TARGET_NETWORK] = targetNetwork
            prefs[StateKeys.PORTAL_DETECTED] = portalDetected
            prefs[StateKeys.LAST_PROBE_CODE] = probeCode.toLong()
            prefs[StateKeys.EFFECTIVE_URL] = effectiveUrl
            prefs[StateKeys.LAST_RESULT] = result
            prefs[StateKeys.LAST_MESSAGE] = message
            prefs[StateKeys.LAST_CHECK_AT] = System.currentTimeMillis()
        }
    }

    suspend fun appendLog(line: String) {
        context.stateDataStore.edit { prefs ->
            val logs = decodeLogs(prefs[StateKeys.LOGS].orEmpty()).toMutableList()
            logs += "[${formatNow()}] $line"
            while (logs.size > 120) {
                logs.removeAt(0)
            }
            prefs[StateKeys.LOGS] = logs.joinToString(separator = "\n")
        }
    }

    suspend fun clearLogs() {
        context.stateDataStore.edit { prefs ->
            prefs[StateKeys.LOGS] = ""
        }
    }

    private fun decodeLogs(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }
    }

    private fun formatNow(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return formatter.format(Date())
    }
}
