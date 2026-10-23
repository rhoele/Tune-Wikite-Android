# FCM 接入文档（Android）

> 范围：本项目当前仅有 Android 工程，因此本文只覆盖 Android 端与自有推送服务端。
> iOS 端需要独立的 Xcode 工程，接入方式见文末「iOS 差异说明」。

---

## 一、配置流程

### 1.1 Firebase 控制台

1. 打开 [Firebase 控制台](https://console.firebase.google.com/) → 新建项目（或选择已有项目）；
2. 在项目内「添加应用」→ 选择 **Android**；
3. **Android 包名必须填写 `com.auramusic.wiki.classic`**（与本项目 `applicationId` 完全一致，否则令牌无法签发）；
4. 按提示下载 `google-services.json`；
5. 在「项目设置 → 云消息传递」中确认 **Firebase Cloud Messaging API (V1)** 已启用；
6. 在「项目设置 → 服务账号」生成新的私钥（JSON），供服务端 Admin SDK 使用。**该文件是敏感凭据，禁止提交到仓库。**

### 1.2 客户端配置文件

把 `google-services.json` 放到：

```
D:\Download\app\app\google-services.json
```

本项目采用**条件化接入**（见 `app/build.gradle`）：

```groovy
if (file('google-services.json').exists()) {
    apply plugin: 'com.google.gms.google-services'
}
```

- 文件不存在：工程照常构建，`FcmManager` 判定为未配置，所有 FCM 调用安全跳过；
- 文件存在：插件自动注入 Firebase 资源，无需改动任何代码。

### 1.3 推送服务地址

客户端只从构建参数读取服务端地址，仓库中不保存任何地址硬编码：

```bash
./gradlew :app:assembleDebug -PFCM_SERVER_BASE_URL=https://your-host
```

也可以写入 `gradle.properties`：

```properties
FCM_SERVER_BASE_URL=https://your-host
```

留空表示「暂不上报令牌」，推送本身不受影响。

---

## 二、客户端实现

### 2.1 依赖

```groovy
implementation platform('com.google.firebase:firebase-bom:33.7.0')
implementation 'com.google.firebase:firebase-messaging'
```

BOM 统一 messaging 与底层依赖版本，避免传递依赖漂移。

### 2.2 关键类

| 类 | 职责 |
| --- | --- |
| `FcmManager` | 判断 Firebase 是否已初始化；获取令牌并交给上报器 |
| `FcmTokenUploader` | 令牌上报（去重、失败静默、仅上报必要字段） |
| `FcmMessagingService` | `onNewToken` 刷新令牌；`onMessageReceived` 解析并展示通知 |
| `FcmPayload` | 纯逻辑解析 data 载荷（路由 / 查询词 / 分组），有单元测试覆盖 |
| `NotificationHelper#postPush` | 构建通知、点击跳转、分组 |
| `MainActivity#handlePushIntent` | 处理推送点击落点（我的贡献 / 歌曲百科 + 查询词） |

### 2.3 令牌生命周期

1. 应用启动：`WikiApp#onCreate` → `FcmManager.ensureToken`；
2. 令牌变化（首装、清数据、重装、服务端轮换）：`FcmMessagingService#onNewToken`；
3. 上报去重：与 `WikiPrefs.fcmUploadedToken` 比较，值相同则不再请求；
4. 上报内容：`token / platform / deviceId / appVersion / userId(可选)`；
5. 上报失败不影响推送（令牌仍然有效），下次启动自动重试。

### 2.4 三种进程状态

| 状态 | 行为 |
| --- | --- |
| 前台 | `onMessageReceived` 回调，客户端构建通知 |
| 后台 / 进程被杀 | 服务端发送 **data-only** 消息，仍回调 `onMessageReceived` |
| 用户「强行停止」 | 系统冻结应用，任何推送都无法送达（平台限制） |

> 服务端默认 `dataOnly=true`。若改成带 `notification` 字段，后台会被系统代显，
> 但客户端无法定制样式与点击逻辑，因此不建议混用。

### 2.5 点击跳转

服务端 data 约定：

```json
{
  "route": "wiki",
  "query": "晴天",
  "group": "audit"
}
```

- `route=wiki` 且带 `query`/`songId` → 打开「歌曲百科」并直接发起查询；
- 其它情况 → 打开「我的贡献」；
- `group` 相同的通知会在通知栏折叠。

### 2.6 通知渠道与权限

- 渠道 ID：`fcm_push`（`NotificationHelper.CHANNEL_PUSH`），重要性 HIGH；
- AndroidManifest 已声明默认渠道与默认图标，系统代显时同样生效；
- Android 13+ 通知权限复用现有的 `POST_NOTIFICATIONS` 申请流程。

---

## 三、服务端

代码位置：`server/fcm/`

### 3.1 目录

```
server/fcm/
  package.json
  .env.example         # 配置模板（.env 已被 gitignore）
  service-account.json # Firebase 服务账号私钥（不入库）
  src/index.js         # HTTP 接口
  src/token-store.js   # 令牌存储（JSON 原子写入）
  data/tokens.json     # 运行时生成
```

### 3.2 部署

```bash
cd server/fcm
npm install
cp .env.example .env        # 填入 FCM_ADMIN_TOKEN、服务账号路径；国内网络再加 HTTPS_PROXY
npm start                   # 等价于 node --env-file-if-exists=.env src/index.js
```

`.env` 由 Node 内置的 `--env-file-if-exists` 读取，无需额外依赖；文件缺失时按纯环境变量运行。

国内网络额外注意：

```dotenv
HTTPS_PROXY=http://127.0.0.1:7890
```

该变量同时触发两件事：替换 `https.globalAgent`、把 FCM 发送切到 HTTP/1.1。
只设前者会导致「取 Token 成功、发送超时」。

生产环境建议：

- 用 `pm2` / systemd / 容器托管，前置 Nginx 或网关启用 **HTTPS**；
- 服务账号 JSON 通过挂载文件或环境变量注入，**不要打进镜像**；
- 多实例部署时把 `TokenStore` 换成 Redis / 数据库（保持同名方法即可）。

### 3.3 接口

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | `/health` | 无 | 存活检查、令牌数量、Admin 是否就绪 |
| POST | `/api/fcm/register` | 无（IP 限流 60/min） | 登记 / 更新令牌 |
| POST | `/api/fcm/unregister` | 无（IP 限流） | 删除令牌 |
| POST | `/api/fcm/send` | `Bearer FCM_ADMIN_TOKEN` | 批量发送 |
| POST | `/api/fcm/ack` | 令牌存在性校验 | 客户端送达回执 |
| GET | `/api/fcm/ack` | `Bearer FCM_ADMIN_TOKEN` | 查看最近回执 |

发送示例：

```bash
curl -X POST https://your-host/api/fcm/send \
  -H "Authorization: Bearer $FCM_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "userId": "123456",
        "title": "审核通过",
        "body": "你的歌词已通过审核",
        "data": { "route": "contributions", "group": "audit" }
      }'
```

返回示例：

```json
{
  "ok": true,
  "mode": "tokens",
  "requested": 3,
  "success": 2,
  "failure": 1,
  "invalidTokensRemoved": 1,
  "failures": [{ "token": "cGx4…", "code": "messaging/registration-token-not-registered" }]
}
```

### 3.4 回执与「投递成功率」的边界

- `sendEachForMulticast` 的成功只代表 **FCM 已接受**，不等于设备已展示；
- 真实送达可用本服务的 `/api/fcm/ack`（客户端展示后回执）统计；
- 需要审计级数据时，在 Firebase 控制台开启 **BigQuery 导出**，按 `message_id` 关联。

### 3.5 异常处理

- 失效令牌（`registration-token-not-registered` / `invalid-registration-token` / `invalid-argument`）
  会自动从存储中删除，避免持续失败；
- 单次批量按 **500** 条分片，符合 FCM 上限；
- 发送失败返回 500 + 原因，调用方应做重试（建议指数退避）；
- Admin 未就绪时发送接口返回 503，登记接口仍可用。

---

## 四、测试

### 4.1 已完成

- `:app:assembleDebug`：通过（`app/google-services.json` 已就位，google-services 插件实际生效）；
- `:app:testDebugUnitTest`：全绿，其中新增 `FcmPayloadTest` 7 项，覆盖：
  - 空载荷兜底、标题/正文取值与 `message` 兼容字段；
  - 路由大小写归一（`WIKI` / `song` / `Query` / 未知）；
  - `wiki` 路由携带 `songId`；无查询词时回退「我的贡献」；
  - 分组字段裁剪与空值处理。
- `:app:lintDebug`：0 Error。

### 4.2 服务端冒烟测试（已执行）

在本机以 `PORT=8799` 启动服务并逐项调用：

| 调用 | 结果 |
| --- | --- |
| `GET /health` | `{"ok":true,"tokens":0,"messagingReady":true}` |
| `POST /api/fcm/register`（合法令牌） | `{"ok":true}`，随后 `/health` 的 `tokens` 变为 1 |
| `POST /api/fcm/unregister` | `{"ok":true,"removed":true}`，`tokens` 回到 0 |
| `POST /api/fcm/send`（错误 Bearer） | HTTP 401 |
| `POST /api/fcm/send`（正确 Bearer、无令牌） | HTTP 400 `no_tokens` |

### 4.2.1 端到端真机实测（已执行，2026-10-18）

环境：`ncmwiki` Firebase 项目 + 真机 `3B1F66EA9BBUDV2V` + 本机 Node 服务（`127.0.0.1:8787`）。

| 环节 | 结果 |
| --- | --- |
| 构建安装 | `assembleDebug` → `adb install -r` Success |
| 令牌上报 | `data/tokens.json` 写入 1 条，`platform=android`、`userId=574571544`、`appVersion=1.0.0` |
| 真实发送 | `{"ok":true,"mode":"tokens","requested":1,"success":1,"failure":0}` |
| 通知落地 | `dumpsys notification` 出现 `channel=fcm_push`、`importance=4`、`category=msg`、`showBadge=true` |
| 冷启动跳转 | `--es extra_song_query 186016` → 直接进入「歌曲百科」并展示「晴天 / 周杰伦」 |
| 热启动跳转 | 运行中再次投递 → `onNewIntent` 生效，页面切换并重新查询 |

> 实测中发现两个与代码无关的环境问题，已记入第六章：服务账号凭据缺失、HTTP/2 不走代理。

### 4.3 真机验证矩阵

| 场景 | 步骤 | 预期 | 状态 |
| --- | --- | --- | --- |
| 令牌获取 | 首次启动并登录 | 服务端 `/health` 的 tokens +1，`/register` 日志成功 | 已通过 |
| 令牌刷新 | 清除应用数据后重启 | 新令牌被登记，旧令牌在发送失败后被清理 | 待验证 |
| 前台接收 | App 在前台发送推送 | 收到系统通知，样式与渠道正确 | 已通过 |
| 后台接收 | 按 Home 后发送 | 收到通知 | 已通过 |
| 杀死状态 | 进程被系统回收后发送（未强行停止） | 收到通知，进程被 FCM 拉起 | 已通过 |
| 点击跳转 | 分别发送 `route=wiki/query` 与默认 | 分别进入百科并查询 / 进入我的贡献 | 已通过 |
| 通知权限 | Android 13+ 拒绝权限 | 不展示通知，业务不崩溃 | 待验证 |
| 通知分组 | 连续发送同一 `group` | 通知栏折叠为一组 | 待验证 |
| 角标 | 连续发送多条 | 桌面角标随通知数量变化（受启动器支持情况影响） | 待验证 |
| 强行停止 | 设置中强行停止后发送 | 收不到（平台限制，记录为已知边界） | 符合预期 |

> ColorOS / MIUI 等系统在「最近任务划掉」后可能等效于强行停止，此时 data-only 消息不会触发
> `onMessageReceived`。这是 ROM 行为，不是接入缺陷；若业务要求必达，应引导用户开启
> 「允许后台活动 / 自启动」，或退回轮询兜底。

---

## 五、安全与合规

1. **不上报敏感凭据**：令牌上报只包含 FCM token、随机设备 ID、平台、版本与可选用户 ID，
   不包含网易云 Cookie、手机号或密码；
2. **配置不入库**：`google-services.json`、服务账号 JSON、`.env` 均不得提交；
   `server/fcm/.gitignore` 已覆盖；
3. **传输加密**：服务端必须部署在 HTTPS 之后，客户端只接受 https 地址；
4. **管理接口鉴权**：`/api/fcm/send` 使用长随机 Bearer Token，建议定期轮换；
5. **最小权限**：服务账号只授予 Firebase Cloud Messaging 所需权限；
6. **令牌生命周期**：用户退出登录时应调用 `/api/fcm/unregister`（可在 `SessionManager.clear`
   之后接入），避免账号切换后收到他人的推送；
7. **日志脱敏**：服务端日志与回执只记录令牌前缀（前 8 位），不落完整令牌。

---

## 六、后续维护

- **升级 Firebase SDK**：只改 `app/build.gradle` 的 BOM 版本，messaging 版本随 BOM 走；
- **更换推送服务地址**：改 `FCM_SERVER_BASE_URL` 构建参数，无需改代码；
- **新增路由**：在 `FcmPayload#normalizeRoute` 与 `MainActivity#handlePushIntent` 同步扩展，
  并补一条 `FcmPayloadTest`；
- **多实例扩容**：先把 `TokenStore` 替换为共享存储，再水平扩容；
- **国内网络必须配置出网代理**：`HTTPS_PROXY` 未设置时，到 `oauth2.googleapis.com` /
  `fcm.googleapis.com` 会 `ETIMEDOUT`。设了代理还不够——FCM 默认用 HTTP/2，而 HTTP/2
  不经过 `https.globalAgent`，日志会表现为「Token 换取成功但发送超时」；
  `src/index.js` 已在检测到 `HTTPS_PROXY` 时调用 `enableLegacyHttpTransport()` 切到 HTTP/1.1；
- **`messagingReady` 不等于可用**：它只表示 `admin.messaging()` 创建成功，而 Google 凭据是
  惰性解析的。缺少服务账号时该项仍为 `true`，真正发送才报
  `Unable to detect a Project Id`。排障时以实际发送结果为准；
- **排查「收不到推送」**：
  1. `/health` 检查令牌数量，再实际调用一次 `/send` 看 `failures.code`；
  2. 服务端发送返回的 `failures.code`（`app/invalid-credential` 多为凭据缺失或代理）；
  3. 客户端 `adb logcat -s WikiFcm FirebaseMessaging`；
  4. `adb shell dumpsys notification --noredact | findstr fcm_push` 确认通知是否已 post；
  5. 确认应用未被「强行停止」、通知权限已授予、设备可访问 Google 服务。

---

## 七、iOS 差异说明

当前仓库没有 iOS 工程，因此未包含 `Podfile` / `GoogleService-Info.plist` 的实际改动。
若后续新增 iOS 端，需要：

1. 在 Firebase 控制台添加 iOS 应用（Bundle ID 需与 Xcode 一致），下载 `GoogleService-Info.plist` 并加入 Runner target；
2. `Podfile` 增加 `pod 'Firebase/Messaging'`；
3. 在 `AppDelegate` 中 `FirebaseApp.configure()`，注册 APNs、实现
   `UNUserNotificationCenterDelegate` 与 `application(_:didRegisterForRemoteNotificationsWithDeviceToken:)`；
4. 服务端复用同一套 `/api/fcm/register`（`platform=ios`）与 `/send` 接口。

---

## 八、与现有轮询机制的关系

应用内已有 `AuditPollingService` 闹钟轮询（未启动 / 进程退出时仍可检查）。
FCM 接入后两者是**互补**关系：

- FCM 负责「实时、可点击、可分组」的推送；
- 轮询作为兜底，在设备无法访问 Google 服务（部分国内设备）时仍能提醒。

如需彻底切换为 FCM，可在设置中保留「后台审核提醒」开关，并让
`AuditPollingService.startIfEnabled` 在 FCM 可用时降低轮询频率。
