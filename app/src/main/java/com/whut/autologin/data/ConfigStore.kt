package com.whut.autologin.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class ConfigStore(private val context: Context) {
    val flow: Flow<AppConfig> = context.configDataStore.data.map { prefs ->
        AppConfig(
            username = prefs[ConfigKeys.USERNAME].orEmpty(),
            password = prefs[ConfigKeys.PASSWORD].orEmpty(),
            domain = prefs[ConfigKeys.DOMAIN].orEmpty(),
            detectUrl = prefs[ConfigKeys.DETECT_URL] ?: "http://1.1.1.1",
            requireWifi = prefs[ConfigKeys.REQUIRE_WIFI] ?: true,
            ssidPattern = prefs[ConfigKeys.SSID_PATTERN] ?: "^WHUT.*$",
            portalHostPattern = prefs[ConfigKeys.PORTAL_HOST_PATTERN] ?: "172.30.21.100",
            portalBase = prefs[ConfigKeys.PORTAL_BASE].orEmpty(),
            checkIntervalSeconds = prefs[ConfigKeys.CHECK_INTERVAL] ?: 600,
            retryIntervalSeconds = prefs[ConfigKeys.RETRY_INTERVAL] ?: 30,
            billingPattern = prefs[ConfigKeys.BILLING_PATTERN] ?: AppConfig.DEFAULT_BILLING_PATTERN,
            nonBillingPattern = prefs[ConfigKeys.NON_BILLING_PATTERN] ?: AppConfig.DEFAULT_NON_BILLING_PATTERN,
            autoServiceEnabled = prefs[ConfigKeys.AUTO_SERVICE_ENABLED] ?: false
        ).normalized()
    }

    suspend fun get(): AppConfig = flow.first()

    suspend fun save(config: AppConfig) {
        val normalized = config.normalized()
        context.configDataStore.edit { prefs ->
            prefs[ConfigKeys.USERNAME] = normalized.username.trim()
            prefs[ConfigKeys.PASSWORD] = normalized.password
            prefs[ConfigKeys.DOMAIN] = normalized.domain.trim()
            prefs[ConfigKeys.DETECT_URL] = normalized.detectUrl.trim()
            prefs[ConfigKeys.REQUIRE_WIFI] = normalized.requireWifi
            prefs[ConfigKeys.SSID_PATTERN] = normalized.ssidPattern.trim()
            prefs[ConfigKeys.PORTAL_HOST_PATTERN] = normalized.portalHostPattern.trim()
            prefs[ConfigKeys.PORTAL_BASE] = normalized.portalBase.trim()
            prefs[ConfigKeys.CHECK_INTERVAL] = normalized.checkIntervalSeconds
            prefs[ConfigKeys.RETRY_INTERVAL] = normalized.retryIntervalSeconds
            prefs[ConfigKeys.BILLING_PATTERN] = normalized.billingPattern
            prefs[ConfigKeys.NON_BILLING_PATTERN] = normalized.nonBillingPattern
            prefs[ConfigKeys.AUTO_SERVICE_ENABLED] = normalized.autoServiceEnabled
        }
    }

    suspend fun setAutoServiceEnabled(enabled: Boolean) {
        context.configDataStore.edit { prefs ->
            prefs[ConfigKeys.AUTO_SERVICE_ENABLED] = enabled
        }
    }
}
