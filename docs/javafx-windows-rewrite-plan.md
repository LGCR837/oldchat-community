# OldChat Windows 桌面版（JavaFX）重写计划

> 状态：可行性已确认，方案拍板（JavaFX）。本计划基于 `app/` 源码实测 + `api/` 层去 Android 依赖探针。
> 最后更新：2026-10-11

## 0. 决策与约束

| 项 | 决定 |
|---|---|
| 平台 | Windows 桌面客户端，JavaFX（非 Tauri/Web；Kivotos 那条线接口脱节、Bug 多、Web 渲染卡，已弃） |
| 后端 | **冻结**，Go 服务端零改动；协议/API 照 `server/` 文档对接 |
| 删除（扩展） | Lua 小程序（`lua/`）、Bili 第三方登录（`bili/`）、音乐广场（8 Activity + `MusicPlaybackService`）、Cip/Lua 开发套件、资源广场、OldView 视频广场、资讯流（均属"内容广场/运营"类，与音乐广场同质） |
| 保留（核心 IM） | 登录、单聊/群聊、媒体消息、引用/转发/撤回、搜索、本地缓存；`pts`/`group_seq` 增量同步、WS 实时推送；频道；朋友圈(动态)、红包；表情广场、收藏、QR、资料/设置、举报/公堂 |
| 保留（补充） | **签到/刮卡**（用户明确留，判定基本 IM 且工作量小） |
| 待最终确认 | 资源广场/OldView/资讯当前**按扩展删除**处理（沿用"Lua/Bili/音乐广场这种"规则）；如要保留请告知 |

## 1. 现有资产复用评估（探针实测）

核心包（协议 + 模型 + 本地缓存）共 **75 文件**：

| 包 | 文件 | 含 Android 依赖 | 主要耦合点 |
|---|---|---|---|
| `api` | 24 | 20 | `Context`(14) `Handler`(12) `Looper`(6) `SharedPreferences`(4) `Intent`(2) `Base64`(2) `Toast`(1) `AsyncTask`(1) |
| `models` | 42 | 13 | `Context`(13) `SharedPreferences`(12) `SQLiteOpenHelper/Database/Statement`(各1) `TextUtils`(1) `SystemClock`(1) |
| `data` | 9 | 9 | `Context`(9) `SharedPreferences`(6) `SQLiteDatabase/Cursor/ContentValues/OpenHelper` `Uri`(1) `Build`(1) |

跨核心包关键耦合统计：`Context` 36 文件、`SharedPreferences` 24、`android.database.sqlite` 4、`Handler/Looper` 13/6、`Base64`/`Log`/`TextUtils`/`SystemClock`/`Build` 各 1–2。

**UI 包真实构成（修正，非 320 屏）**：`ui/` 共 350 个 Java 类，其中 `Activity`(真正屏幕) ~87 个、`Fragment` 9、`自定义 View` 26、`Adapter` 42、`ViewHolder` 7，其余 ~179 为对话框/工具/管理器/绑定类。删扩展后 Activity 屏约 **57 个**（Manifest 85 个 `.ui.*` 删 28 个扩展屏 + 根包 Splash/Main 2 个 = 59）。`res/` 下 **161 个 layout XML** 布局不可用（需改 FXML/代码），非此前误引的 753（753 为 app 整树 xml 含 304 drawable-xml）。

**结论：网络核心是可抽离的纯 Java 宝藏**
- `HttpUtil`(OkHttp3)、`SimpleWebSocketClient`、`SimpleWebSocketFrameCodec`(自定义 WS 帧编解码)、鉴权握手、`UpdatesManager`(增量同步派发) 逻辑与平台无关，剥离 Android 依赖后即复用。
- 依赖全为纯 Java 可原样用：OkHttp3、Gson、SpongyCastle/BC、luaj（已删则不需要）。

**四类耦合，全部可抽象/替换（Phase 0 工作量有界）：**

| Android 耦合 | 文件数 | JavaFX/Desktop 替代 |
|---|---|---|
| `SharedPreferences`（token/endpoint/配置） | 24 | 抽 `KeyValueStore` 接口 → `Properties`/JSON 文件实现 |
| `Context`（多数仅用于取 SharedPreferences/资源/系统服务） | 36 | 改为构造注入依赖；资源改为自带 bundle |
| `Handler`/`Looper`（主线程 post） | 13/6 | 抽 `Scheduler` 接口 → JavaFX `Platform.runLater` |
| `android.database.sqlite`（本地消息缓存） | 4 | 移植 JDBC SQLite；SQL schema 复用，驱动/Helper 层重写 |
| `Base64`/`Log`/`TextUtils`/`SystemClock`/`Build` | 各 1–2 | 直接换 `java.util` 等价物 |

## 2. 目标架构（JavaFX 端）

```
oldchat-core/      ← 纯 Java，零 Android、零 JavaFX
  ├ models/        (42，去 Context/SP)
  ├ net/           (api/ 24，去 Context/Handler/SP，WS+握手+增量同步)
  ├ cache/         (data/ 9，JDBC SQLite)
  └ platform/      ← 抽象接口：KeyValueStore / Scheduler / FileStore / NetworkMonitor / Logger
oldchat-fx/        ← JavaFX UI + 应用壳
  ├ ui/            Stage/Scene/FXML 控制器（Activity→Stage，Fragment→Pane）
  ├ bg/            后台任务（消息推送/上传/下载，替代 Android Service）
  ├ tray/          SystemTray 通知
  └ updater/       Windows 自动更新（复用 server update.json 机制，自写 Win 更新器）
```

- `platform/` 在 `oldchat-fx` 中提供具体实现（如 `FxScheduler`→`Platform.runLater`、桌面 `FileStore`、文件型 `KeyValueStore`）。
- 构建：Gradle（java + javafx plugin），JDK 21 / JavaFX 21；`jpackage` 出 exe/msi + 内嵌 JRE（~80–150MB）。

## 3. 分阶段实施

### Phase 0 — 纯 Java core 剥离（验证可行性，出可编译 jar）
- 0.1 定义并落地 `platform/` 抽象接口（KeyValueStore / Scheduler / FileStore / Logger）
- 0.2 `SharedPreferences`(24) → `KeyValueStore`；`Context`(36) → 依赖注入
- 0.3 `Handler`/`Looper`(13/6) → `Scheduler`
- 0.4 `android.database.sqlite`(4) → JDBC SQLite（本地消息缓存，schema 复用）
- 0.5 工具类 JDK 化（Base64/Log/TextUtils/SystemClock/Build）
- 0.6 **垂直冒烟测试**：用（真实/测试）账号跑 handshake + WS 收一条推送，证明 core 端到端可用
- 产出：`oldchat-core.jar`；估时 **2–3 周 / 1 人**

### Phase 1 — MVP：登录 + 主框架 + 单聊
- `LoginActivity`→`LoginStage`；`MainActivity` 主壳（会话列表 + 多窗格）
- `ChatActivity`→`ChatPane`：消息列表（VirtualFlow 虚拟滚动）、输入、发送、媒体气泡（图片/语音/视频基础播放）
- 实时推送经 core WS 接到 UI（Scheduler 桥接 `Platform.runLater`）
- 估时 **3–4 周**

### Phase 2 — 群聊 + 频道 + 朋友圈 + 红包 + 签到/刮卡
- 群聊/群管、频道(Channel)、动态(Moments)、红包(RedPacket)、签到墙/刮卡
- 估时 **3–5 周**

### Phase 3 — 表情广场 + 收藏 + QR + 设置 + 通知 + 更新
- 表情广场/收藏、`QrScan`(摄像头库+ZXing)、设置簇、SystemTray 通知、自动更新器
- 估时 **3–4 周**

### Phase 4 — 打磨
- 主题/CSS、虚拟列表长消息流性能、内存与打包体积优化
- 估时 **1–2 周**

## 4. 工作量估算（量级）

| 阶段 | 估时 | 说明 |
|---|---|---|
| Phase 0 core 剥离 | 2–3 周 | 1 人，风险低、有界 |
| Phase 1–3 UI | 9–13 周 | 1–2 人；**UI 是绝对大头**（~87 个 Activity 屏需重建，删扩展后约 57–59 屏；161 个 layout XML 不可用需改 FXML/代码；另 Fragment 9 / 自定义 View 26 / Adapter 42 等支撑类） |
| Phase 4 打磨 | 1–2 周 | — |
| **合计** | **约 12–18 周（1 人）/ 约 7–9 周（2 人）** | 多人月级工程 |

## 5. 关键风险

1. **UI 体量**：`ui/` 包 350 个 Java 类中，Activity 屏 ~87 个（删扩展后约 57–59 屏），另有 Fragment 9 / 自定义 View 26 / Adapter 42 / ViewHolder 7 及对话框·工具等支撑类；161 个 layout XML 布局不可用，需改 FXML/代码并重建视觉与 CSS。最大成本，但**屏数远少于 320**，量级更可控。
2. **长消息流性能**：JavaFX 需用 `VirtualFlow`/自定义 cell 做虚拟滚动，否则万级消息卡顿。
3. **聊天气泡媒体播放**：用 `javafx.scene.media` 或 WebView/MPV 兜底音视频。
4. **协议一致性（硬约束）**：**必须复用现有 `api/` 逻辑**，禁止从零重写握手/WS 帧编解码，避免 v1↔v2 映射 bug。
5. **范围待确认**：资源广场/OldView/资讯当前按删除假设，需最终拍板。

## 6. 建议的下一步动作

- **优先落地 Phase 0**（core 剥离 + 冒烟测试），用最小代价验证端到端可行，再全面铺开 UI。
- 可先做 **Login + 单聊垂直切片**跑通，作为 Phase 0/1 的串联验证。
- 确认后我可立即开始：脚手架 Gradle 多模块工程（`oldchat-core` + `oldchat-fx`）+ Phase 0 的 `platform/` 抽象与 `SharedPreferences`/`Handler` 替换。
