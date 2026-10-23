# 曲库共建（Classic Wiki）

一个面向网易云音乐「曲库共建」场景的 Android 客户端：查歌曲百科、看自己的贡献与审核结果、从 SoundCloud 取封面与音源、批量补全专辑信息并提交审核。

界面基于 Material 3 Expressive，支持深色模式、动态取色、悬浮/贴底底栏与动效强度调节。

## 系统要求

- Android 8.0（API 26）及以上
- 需要网络；歌曲百科可免登录查询，其余功能需要登录网易云账号

## 安装与升级

1. 在 GitHub Releases 页面下载最新 APK。
2. 点击安装（首次需要允许「安装未知来源应用」）。
3. **升级时直接覆盖安装即可，不要卸载旧版**：卸载会一并清除本机凭据、贡献缓存和已下载文件。
4. 覆盖升级要求新旧 APK 使用同一签名密钥。若提示「应用未安装 / 签名不一致」，说明安装包来自不同签名，请先备份数据再处理。

## 首次运行可能需要的权限

| 权限 | 用途 | 是否必须 |
|---|---|---|
| 通知（Android 13+） | 审核结果提醒 | 可选，拒绝后仍可在应用内查看 |
| 忽略电池优化 | 后台定时检查审核结果 | 可选，在「设置 → 后台审核提醒」中开启 |
| 网络 | 全部在线功能 | 必须 |

应用**不申请**存储读写权限：下载文件保存在应用专用外部目录，导出副本通过系统文件选择器（SAF）完成。

## 数据与隐私

- 登录凭据（`MUSIC_U` Cookie）只保存在本机应用私有目录的 SharedPreferences 中，仅用于向网易云音乐发送登录态请求，不会上传到其它服务器。
- 应用已关闭 Android 自动备份（`allowBackup=false`），凭据与下载文件不会进入云端备份或换机迁移。
- 下载的封面/音频默认位于 `Android/data/com.auramusic.wiki.classic/files/` 下，卸载应用会一并清理；选择自定义目录后会在该目录额外导出一份副本（由用户自行管理）。
- 可选的推送能力：仅当构建时显式注入推送服务地址（`FCM_SERVER_BASE_URL`）时，才会把 FCM 令牌、设备标识与网易云用户 ID 上报到该地址；默认构建不启用。
- 应用不含广告 SDK、统计 SDK 或崩溃上报 SDK。

## 从源码构建

```powershell
# 调试包
.\gradlew.bat assembleDebug

# 正式包（需要签名配置，见下）
.\gradlew.bat assembleRelease
```

构建环境：JDK 17、Android SDK（compileSdk 35）、Gradle Wrapper 自带版本。

### 签名配置

正式版必须使用固定的签名密钥，且**密钥与密码不得提交到仓库**。二选一：

1. 在仓库根目录创建 `keystore.properties`（已被 `.gitignore` 忽略）：

```properties
storeFile=C:/secure/kukugongjian-release.jks
storePassword=******
keyAlias=kukugongjian
keyPassword=******
```

2. 或通过环境变量注入（适合 CI）：`KEYSTORE_FILE`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。

首次生成密钥（只做一次，然后离线备份 `.jks` 与密码）：

```powershell
keytool -genkeypair -v -keystore kukugongjian-release.jks -alias kukugongjian `
  -keyalg RSA -keysize 4096 -validity 36500 `
  -storepass <密码> -keypass <密码> `
  -dname "CN=KuKuGongJian, O=KuKuGongJian, C=CN"
```

未提供签名配置时 `assembleRelease` 仍可成功，但产物是 `app-release-unsigned.apk`，不能分发。

### 推送（可选）

默认构建不启用推送。需要时：

```powershell
.\gradlew.bat assembleRelease -PFCM_SERVER_BASE_URL=https://your-host
```

`release` 只接受 `https://` 地址；`debug` 默认使用 `http://127.0.0.1:8787`，配合 `adb reverse tcp:8787 tcp:8787` 做本地联调。服务端位于 `server/fcm`（Node.js）。

## 版本与发布

- 包名：`com.auramusic.wiki.classic`
- 版本号在 `app/build.gradle` 的 `defaultConfig` 中维护；每次发布需递增 `versionCode`，否则无法覆盖安装。
- 发布产物命名约定：`KuKuGongJian-v<版本号>-release.apk`，发布时附上 SHA-256 校验值。

## 已知限制

- 声云解析依赖第三方服务（lucida.to），其可用性不受本应用控制。
- 未启用 R8 代码压缩（1.0.0 以稳定性优先）。
- 未适配 Google Play 的分发要求（`targetSdk` 仍为 34）。

## 反馈

若通过 GitHub 仓库分发，请在仓库的 Issues 中提交问题，并附上机型、系统版本与复现步骤。
