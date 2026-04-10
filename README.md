# WHUT AutoLogin

> 🌐 武汉理工大学校园网自动登录 Android 应用 — 断网自动重连，开机即守护。

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-8.0+-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![GitHub](https://img.shields.io/badge/GitHub-repo-181717?logo=github)](https://github.com/KanKeDKanKe/whut-autologin-app)

---

## ✨ 功能亮点

| 功能 | 说明 |
|------|------|
| 🔄 **自动登录** | 检测到未认证的校园网门户后，自动完成登录流程 |
| 📡 **网络感知** | Wi-Fi 切换 / 网络变化时即时触发检测，配合周期轮询兜底 |
| 💰 **欠费保护** | 识别欠费关键词后自动暂停并推送通知，避免无效重试 |
| 🔁 **开机恢复** | 监听 `BOOT_COMPLETED`，设备重启后自动恢复守护 |
| 🛡️ **前台服务** | 以前台通知保持存活，显示实时状态 |
| 📋 **状态面板** | 应用内查看在线状态、最近检测结果与运行日志 |

## 📱 适配范围

- **最低版本**: Android 8.0 (API 26)
- **目标 SDK**: Android 14 (API 34, `targetSdk=34`)
- **实测环境**: Android 15（WHUT-DORM）
- **门户适配**: 武汉理工 ePortal 系列（`172.30.21.100`）

> [!NOTE]
> 不同 ROM / 厂商系统对后台服务的管控策略不同，部分深度定制系统（如 MIUI、ColorOS）可能需要手动关闭电池优化或允许自启动。

## ⚠️ 免责声明

- 本项目代码与文档为 **纯 AI 创作** 产物，仅在 **WHUT-DORM** 场景完成实机验证。
- 对其他网络环境或门户接口 **不保证开箱即用**。
- 本项目仅供学习交流，使用者需自行承担因使用本工具产生的一切后果。

## 🚀 快速开始

### 1. 安装

从 [Releases](https://github.com/KanKeDKanKe/whut-autologin-app/releases) 下载最新 APK 安装，或 clone 后用 Android Studio 自行构建。

### 2. 配置

打开应用，填写以下必要信息：

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| **账号** | 校园网登录账号 | *必填* |
| **密码** | 校园网登录密码 | *必填* |
| 域（Domain） | 运营商域参数，通常留空 | 空 |
| Portal Base | ePortal 基础地址 | 自动探测 |
| 检测 URL | 用于判断网络是否在线 | `http://1.1.1.1` |
| SSID 匹配正则 | 匹配目标 Wi-Fi 名称 | `^WHUT.*$` |
| Portal Host 正则 | 匹配门户重定向地址 | `172.30.21.100` |
| 在线检查间隔 | 已在线时的周期检测间隔（秒） | 按需调整 |
| 失败重试间隔 | 登录失败后的重试间隔（秒） | 按需调整 |

### 3. 启动

保存配置 → 开启「自动检测服务」→ 完成 🎉

## 🏗️ 项目结构

```text
app/src/main/java/com/whut/autologin/
├── MainActivity.kt           # 主界面：配置表单 + 状态面板
├── WhutAutoLoginApp.kt        # Application 入口
├── data/
│   ├── AppConfig.kt           # 配置数据模型
│   ├── ConfigStore.kt         # 配置持久化（DataStore）
│   ├── RuntimeState.kt        # 运行时状态模型
│   ├── StateStore.kt          # 状态持久化
│   └── Stores.kt              # DataStore 实例管理
├── net/
│   ├── PortalClient.kt        # 门户交互核心：探测、解析、登录
│   └── PortalModels.kt        # 门户数据模型
└── service/
    ├── AutoLoginService.kt    # 前台守护服务
    └── BootReceiver.kt        # 开机自启广播接收器
```

## 🤝 适配与定制

本项目针对 **WHUT ePortal** 门户风格开发。如需适配其他学校：

1. 修改 [`PortalClient.kt`](app/src/main/java/com/whut/autologin/net/PortalClient.kt) 中的门户交互逻辑
2. 调整默认的 SSID / Portal Host 匹配模式
3. 如门户协议差异较大，可能需要调整 [`PortalModels.kt`](app/src/main/java/com/whut/autologin/net/PortalModels.kt)

欢迎 Fork 并提交适配 PR！

## 📄 License

[MIT License](LICENSE)
