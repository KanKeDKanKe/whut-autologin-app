package com.whut.autologin

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.whut.autologin.data.AppConfig
import com.whut.autologin.data.ConfigStore
import com.whut.autologin.data.RuntimeState
import com.whut.autologin.data.StateStore
import com.whut.autologin.databinding.ActivityMainBinding
import com.whut.autologin.service.AutoLoginService
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var configStore: ConfigStore
    private lateinit var stateStore: StateStore

    private var latestState: RuntimeState = RuntimeState()
    private var suppressAutoSwitchCallback: Boolean = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configStore = ConfigStore(applicationContext)
        stateStore = StateStore(applicationContext)

        bindActions()
        requestRuntimePermissionsIfNeeded()

        lifecycleScope.launch {
            renderConfig(configStore.get())
            renderState(stateStore.get())
        }

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                stateStore.flow.collect { state ->
                    latestState = state
                    renderState(state)
                }
            }
        }
    }

    private fun bindActions() {
        binding.switchAutoService.setOnCheckedChangeListener { _, checked ->
            if (suppressAutoSwitchCallback) return@setOnCheckedChangeListener

            lifecycleScope.launch {
                configStore.setAutoServiceEnabled(checked)
                if (checked) {
                    AutoLoginService.start(this@MainActivity)
                    toast("自动检测已开启")
                } else {
                    AutoLoginService.stop(this@MainActivity)
                    toast("自动检测已关闭")
                }
            }
        }

        binding.btnSave.setOnClickListener {
            lifecycleScope.launch {
                saveConfigFromForm()
            }
        }

        binding.btnLoginNow.setOnClickListener {
            AutoLoginService.runNow(this)
            toast("已触发一次立即登录")
        }

        binding.btnPauseResume.setOnClickListener {
            lifecycleScope.launch {
                val autoEnabled = binding.switchAutoService.isChecked
                if (latestState.paused) {
                    stateStore.setPaused(false)
                    if (autoEnabled) {
                        AutoLoginService.resume(this@MainActivity)
                    }
                    toast("已恢复检测")
                } else {
                    stateStore.setPaused(true, "manual", "用户手动暂停")
                    if (autoEnabled) {
                        AutoLoginService.pause(this@MainActivity)
                    }
                    toast("已暂停检测")
                }
            }
        }

        binding.btnRefresh.setOnClickListener {
            lifecycleScope.launch {
                renderConfig(configStore.get())
                renderState(stateStore.get())
                toast("状态已刷新")
            }
        }

        binding.btnClearLogs.setOnClickListener {
            lifecycleScope.launch {
                stateStore.clearLogs()
                renderState(stateStore.get())
                toast("日志已清空")
            }
        }
    }

    private suspend fun saveConfigFromForm() {
        val current = configStore.get()
        val passwordInput = binding.etPassword.text?.toString().orEmpty()

        val updated = current.copy(
            username = binding.etUsername.text?.toString().orEmpty().trim(),
            password = if (passwordInput.isBlank()) current.password else passwordInput,
            domain = binding.etDomain.text?.toString().orEmpty().trim(),
            detectUrl = binding.etDetectUrl.text?.toString().orEmpty().trim(),
            requireWifi = binding.switchRequireWifi.isChecked,
            ssidPattern = binding.etSsidPattern.text?.toString().orEmpty().trim(),
            portalHostPattern = binding.etPortalHostPattern.text?.toString().orEmpty().trim(),
            portalBase = binding.etPortalBase.text?.toString().orEmpty().trim(),
            checkIntervalSeconds = binding.etCheckInterval.text?.toString().orEmpty().toLongOrNull() ?: current.checkIntervalSeconds,
            retryIntervalSeconds = binding.etRetryInterval.text?.toString().orEmpty().toLongOrNull() ?: current.retryIntervalSeconds,
            autoServiceEnabled = binding.switchAutoService.isChecked
        ).normalized()

        configStore.save(updated)

        if (updated.autoServiceEnabled) {
            AutoLoginService.start(this)
        } else {
            AutoLoginService.stop(this)
        }

        if (passwordInput.isNotBlank()) {
            binding.etPassword.setText("")
        }

        toast("配置已保存")
    }

    private fun renderConfig(config: AppConfig) {
        binding.etUsername.setText(config.username)
        binding.etDomain.setText(config.domain)
        binding.etDetectUrl.setText(config.detectUrl)
        binding.switchRequireWifi.isChecked = config.requireWifi
        binding.etSsidPattern.setText(config.ssidPattern)
        binding.etPortalHostPattern.setText(config.portalHostPattern)
        binding.etPortalBase.setText(config.portalBase)
        binding.etCheckInterval.setText(config.checkIntervalSeconds.toString())
        binding.etRetryInterval.setText(config.retryIntervalSeconds.toString())

        suppressAutoSwitchCallback = true
        binding.switchAutoService.isChecked = config.autoServiceEnabled
        suppressAutoSwitchCallback = false

        binding.etPassword.hint = if (config.password.isBlank()) "密码" else "已保存（留空不修改）"
    }

    private fun renderState(state: RuntimeState) {
        binding.tvStatusSummary.text = getString(
            R.string.status_template,
            yesNo(state.online),
            yesNo(state.targetNetwork),
            yesNo(state.portalDetected),
            yesNo(state.paused)
        )
        binding.tvLastMessage.text = "消息: ${state.lastMessage.ifBlank { "-" }}"
        binding.tvLastResult.text = "最近结果: ${state.lastResult.ifBlank { "-" }}"
        binding.tvLastCheck.text = "最近检测: ${formatTime(state.lastCheckAt)}"

        val recentLogs = state.logs.takeLast(30)
        binding.tvLogs.text = if (recentLogs.isEmpty()) {
            "日志:\n(暂无)"
        } else {
            "日志:\n" + recentLogs.joinToString("\n")
        }

        binding.btnPauseResume.text = if (state.paused) "恢复检测" else "暂停检测"
    }

    private fun yesNo(value: Boolean): String = if (value) "是" else "否"

    private fun formatTime(epochMillis: Long): String {
        if (epochMillis <= 0L) return "-"
        return DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(epochMillis)).toString()
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val pendingPermissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingPermissions += Manifest.permission.ACCESS_FINE_LOCATION
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingPermissions += Manifest.permission.POST_NOTIFICATIONS
        }

        if (pendingPermissions.isNotEmpty()) {
            permissionLauncher.launch(pendingPermissions.toTypedArray())
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
