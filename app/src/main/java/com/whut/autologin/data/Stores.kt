package com.whut.autologin.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.configDataStore by preferencesDataStore(name = "config")
val Context.stateDataStore by preferencesDataStore(name = "runtime_state")

object ConfigKeys {
    val USERNAME: Preferences.Key<String> = stringPreferencesKey("username")
    val PASSWORD: Preferences.Key<String> = stringPreferencesKey("password")
    val DOMAIN: Preferences.Key<String> = stringPreferencesKey("domain")
    val DETECT_URL: Preferences.Key<String> = stringPreferencesKey("detect_url")
    val REQUIRE_WIFI: Preferences.Key<Boolean> = booleanPreferencesKey("require_wifi")
    val SSID_PATTERN: Preferences.Key<String> = stringPreferencesKey("ssid_pattern")
    val PORTAL_HOST_PATTERN: Preferences.Key<String> = stringPreferencesKey("portal_host_pattern")
    val PORTAL_BASE: Preferences.Key<String> = stringPreferencesKey("portal_base")
    val CHECK_INTERVAL: Preferences.Key<Long> = longPreferencesKey("check_interval")
    val RETRY_INTERVAL: Preferences.Key<Long> = longPreferencesKey("retry_interval")
    val BILLING_PATTERN: Preferences.Key<String> = stringPreferencesKey("billing_pattern")
    val NON_BILLING_PATTERN: Preferences.Key<String> = stringPreferencesKey("non_billing_pattern")
    val AUTO_SERVICE_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("auto_service_enabled")
}

object StateKeys {
    val ONLINE: Preferences.Key<Boolean> = booleanPreferencesKey("online")
    val TARGET_NETWORK: Preferences.Key<Boolean> = booleanPreferencesKey("target_network")
    val PORTAL_DETECTED: Preferences.Key<Boolean> = booleanPreferencesKey("portal_detected")
    val PAUSED: Preferences.Key<Boolean> = booleanPreferencesKey("paused")
    val PAUSE_REASON: Preferences.Key<String> = stringPreferencesKey("pause_reason")
    val PAUSE_MESSAGE: Preferences.Key<String> = stringPreferencesKey("pause_message")
    val LAST_PROBE_CODE: Preferences.Key<Long> = longPreferencesKey("last_probe_code")
    val EFFECTIVE_URL: Preferences.Key<String> = stringPreferencesKey("effective_url")
    val LAST_RESULT: Preferences.Key<String> = stringPreferencesKey("last_result")
    val LAST_MESSAGE: Preferences.Key<String> = stringPreferencesKey("last_message")
    val LAST_CHECK_AT: Preferences.Key<Long> = longPreferencesKey("last_check_at")
    val LOGS: Preferences.Key<String> = stringPreferencesKey("logs")
}
