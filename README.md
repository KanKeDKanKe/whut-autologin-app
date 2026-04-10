# WHUT 自动登录 App（MVP）

## 项目简介

这是一个面向武汉理工校园网场景的 Android 自动登录应用（Kotlin）。  
核心能力：

- 本地配置账号参数（DataStore）
- 网络探测与 Portal 识别
- 自动调用登录接口（含多路径容错）
- 前台服务常驻检测（网络变化触发 + 周期保底检查）
- 欠费识别后自动暂停并通知
- 本地状态与日志查看、日志清空
- 开机恢复（`BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`）

项目路径：`E:/whut-autologin-app`

## 目录结构

- `app/src/main/java/com/whut/autologin/net/PortalClient.kt`：Portal 探测与登录逻辑
- `app/src/main/java/com/whut/autologin/service/AutoLoginService.kt`：前台服务与自动检测调度
- `app/src/main/java/com/whut/autologin/service/BootReceiver.kt`：开机恢复
- `app/src/main/java/com/whut/autologin/data/*`：配置与运行态持久化
- `app/src/main/java/com/whut/autologin/MainActivity.kt`：主界面交互

## 本地运行

1. 用 Android Studio 打开 `E:/whut-autologin-app`
2. 等待 Gradle 同步
3. 连接真机或模拟器后运行 `app` 模块

## 打包 Debug APK

- Android Studio 菜单：`Build -> Build APK(s)`
- 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

## 打包签名 Release APK

### 方案 A（推荐，IDE 操作）

1. Android Studio：`Build -> Generate Signed Bundle / APK...`
2. 选择 `APK`
3. 首次可点 `Create new...` 新建 keystore
4. 选择 `release` 构建并完成打包

### 方案 B（脚本/终端）

1. 生成 keystore 与 `key.properties`
   - `powershell -ExecutionPolicy Bypass -File .\scripts\generate-release-keystore.ps1`
   - 如未识别 Java，可补 `-JavaHome "C:\Path\To\Android Studio\jbr"`
2. 构建 release
   - `.\gradlew.bat assembleRelease`
   - 或：`powershell -ExecutionPolicy Bypass -File .\scripts\build-apk.ps1 -Variant Release`
3. 输出路径
   - `app/build/outputs/apk/release/app-release.apk`

## 上传 GitHub 前检查

请不要上传以下文件：

- `keystore/release.jks`
- `key.properties`
- `local.properties`
- `.gradle/`、`.kotlin/`、`**/build/`、`.idea/`

项目内已通过 `.gitignore` 默认忽略上述敏感/缓存文件。

## 快速上传命令

```bash
git init
git branch -M main
git add .
git commit -m "Initial WHUT Auto Login app"
git remote add origin <your-repo-url>
git push -u origin main
```

## 注意事项

- `keystore` 和签名密码必须妥善备份，丢失后无法无缝升级已发布应用。
- 校园网 Portal 参数/接口若调整，请重点修改：
  - `app/src/main/java/com/whut/autologin/net/PortalClient.kt`

---

## English (Brief)

An Android app for WHUT captive-portal auto login with:

- DataStore config
- Portal detection + login API calls
- Foreground auto-check service (network callback + periodic checks)
- Billing-aware pause + notification
- Boot restore and runtime logs

Build signed release via Android Studio (`Generate Signed Bundle / APK`) or scripts in `scripts/`.
