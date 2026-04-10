package com.whut.autologin.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.whut.autologin.data.ConfigStore
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val autoEnabled = runBlocking {
            ConfigStore(context.applicationContext).get().autoServiceEnabled
        }

        if (autoEnabled) {
            AutoLoginService.start(context.applicationContext)
        }
    }
}
