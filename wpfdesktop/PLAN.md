# OldChat 桌面客户端策划

net48 + C# + WPF，对接 oldchat-community 服务端。
本文只写决策与实现要点，逐字段的接口契约看桌面上的
`OldChat-WPF-客户端开发规格.md`（下称"规格"），那份文档的接口表是对的。

## 一、技术选型（含理由）

- 运行时 `net48`，**SDK 风格 csproj**（不是 legacy 工程）。
  理由：legacy csproj 在本机 `dotnet build` 下不触发 XAML 编译，必须开 VS 才能编；
  SDK 风格已验证可用，`dotnet build` 直接出 exe。
- `LangVersion` 定 7.3。放宽到 9.0 需要补 `IsExternalInit` 才能用 record，收益不大，先不加。
- 平台 `AnyCPU` + `Prefer32Bit=false`，SQLite 走 x86/x64 子目录原生库。
- 不引 MVVM 框架。手写 `ObservableObject` 基类 + `RelayCommand` 两个文件就够。
- 序列化 `Newtonsoft.Json 13`。
- 本地库 `System.Data.SQLite.Core`，WAL。
- 单 exe 打包 `Costura.Fody`（net48 下比 ILMerge 稳）。

已确认：运行时 `net48`；UI 深浅两套主题在 M0 同步建立，不先只做深色。

## 二、工程结构

`wpfdesktop/` 作解决方案根，四个工程：

```
wpfdesktop/
  OldChat.Desktop.sln
  src/OldChat.Core/        net48 类库：协议、网络、存储、业务服务，零 WPF 依赖
  src/OldChat.App/         net48 WinExe：WPF 视图层、主题、控件
  src/OldChat.Updater/     net48 WinExe：独立更新器
  tests/OldChat.Core.Tests/ net48 + xUnit：协议层单测
```

`Core` 不引 WPF 是关键约束：协议层可以脱离 UI 跑命令行自检，
否则调接口必须先点界面，迭代成本高一个量级。

## 三、Core 分层

```
Config/     Origin 与偏好设置；token 用 DPAPI(ProtectedData) 落盘
Net/        ApiClient 单例 HttpClient；Bearer；401 单次重放；超时统一 CTS
            WsClient ClientWebSocket；重连状态机；心跳；帧分发
            Hosts/ 候选源探测（60 → files → oc → 默认源）
Protocol/   DTO + JsonSettings；MessageBody 结构化 payload 编解码
Storage/    SQLite schema v1 + Repository；outbox 待发队列
Services/   AuthService、ChatService、SyncService、MediaService、PresenceService
```

### 硬点

1. **鉴权**：`POST /v1/auth/login`，`apk_signature` 必须空串。
   服务端有 IP 与标识符双限流，调试时别反复试错。
   登录返回 `access_token + refresh_token + user`；token 过期前 5 分钟主动 refresh。
2. **不走 /v2**。`/v2` 全组强制 `X-Session`，无 ECDH 会话一律 401，且注释明确写了不再降级。
   首版所有业务走 `/v1` 明文。媒体走 `/v1/uploads/*`，不走 `/v2/files/download/{id}`。
3. **WS**：`GET /v1/ws`，Bearer 头。握手后服务端直接开始推送，**不需要客户端发 auth 帧**
   （`OnMessage` 只处理 `channel_subscriptions_refresh`，发别的会被忽略）。
   服务端 54s Ping，客户端必须回 Pong，否则连接会被清。
4. **增量**：`/v1/updates/difference?pts=` 只管私聊与群指针；
   `reset:true` 时全量重建会话。
   群消息不进账号事件流，群增量走 `/v1/groups/messages/after?group_id=&after_seq=`。
   好友与群列表变动不在事件流里，靠 `/v1/groups/events/after` 加进群/切页时主动刷新。
5. **发送对账**：`POST /v1/groups/message/send` 返回 201 + 完整 DTO（含 `group_seq`、`sort_seq`）。
   乐观上屏用临时 id，收到 201 或 WS 回显时按 id 替换。
6. **消息体**：纯文本直接发字符串；带引用/@/转发时发 `{"v":2,...}`。
   接收端先判断首字符是否 `{` 再决定按 JSON 还是纯文本解析。

## 四、UI 方案

单窗口三栏，不用多窗口。

```
┌──────────────────────────────────────────────┐
│ 自绘标题栏 48px  当前会话名 · 在线状态 · 最小化/最大化/关闭 │
├────┬──────────────┬──────────────────────────┤
│导  │ 会话列表      │ 消息区                    │
│航  │ 280px        │                          │
│栏  │ 搜索          │                          │
│56  │ 置顶/普通     │ ──────────────────────── │
│px  │ 头像 名 摘要  │ 输入框  工具栏 发送        │
│    │ 时间 未读     ├──────────────────────────┤
│    │              │ 详情面板 300px（可收）      │
└────┴──────────────┴──────────────────────────┘
```

- 导航栏：消息、好友、发现、我的。点后两个时中栏换成对应页面，右栏收起。
- 配色对齐安卓端：主色 `#111111`，背景 `#F9FAFB`，自己气泡深色、对方白色。
- 所有颜色走 `DynamicResource` 键，M0 就建深色与浅色两套字典，运行时切换。
- 气泡 `ItemsControl` + `VirtualizingStackPanel`，消息超过 200 条开虚拟化。
- 图片占位用固定宽高比容器，避免解码后跳版。
- 托盘：常驻，未读角标，关闭窗口最小化到托盘。

## 五、里程碑

### M0 骨架
SDK 风格解决方案、四个工程、日志、DPAPI 配置、单实例、全局异常、
空三栏窗口与主题字典。
验收：`dotnet build` 通过，exe 启动有界面，日志落盘。

### M1 协议层打通（关键卡点，先做）
ApiClient、DTO、AuthService、`--selftest` 命令行开关。
验收：`OldChat.exe --selftest login` 能登录线上源并打印 `/me` 结果，不依赖界面。

### M2 数据层与会话列表
SQLite schema、Repository、好友/群/会话三次拉取与差分合并、会话列表 UI。
验收：断网启动仍显示上次会话列表。

### M3 聊天主链路（最重）
历史分页、乐观发送与对账、WS 连接与重连、pts 增量、群 seq 补拉、
气泡控件（文本/图片/语音/文件/引用/系统提示）、时间分隔。
验收：两台机器实时互发；杀进程重开不乱序；断网十分钟后重连补齐。

### M4 媒体与桌面体验
图片上传下载与两级缓存、大图查看、语音播放（含 AMR 降级）、
文件收发、托盘通知、未读汇总、群成员与群设置面板。

### M5 打磨与分发
自动更新（服务端新增 `update_win.json`）、Costura 单 exe、绿色版 zip。
`/v2` 加密签名列为可选后续，不进首版。

## 六、风险

1. **AMR 语音**：WPF 无内置解码。降级方案是显示时长与"在浏览器打开"按钮，
   中期用 NAudio + AMR 解码库，或用服务端 `/v1/voice/asr` 出文字。
2. **Win7 无法验证**：net48 名义支持，但本机没有测试环境，只能声明不保证。
3. **pts 与本地库一致性**：以服务端 `current_pts` 为唯一真值，不一致即 reset 全量重建，
   不做局部补丁式修复。
4. **富文本**：引用/@/转发的 payload 校验在服务端很严，字段缺一个就 400，
   M3 先只做纯文本与图片，引用放在 M4。

## 七、明确不做

朋友圈、频道、资源广场、音乐广场、小程序、红包、阅后即焚、签到。
这些接口齐全，但没有桌面端价值密度，等主干稳定后单独评估。
