<p align="center">
  <img src="assets/happy_agent_icon.png" width="160" alt="Happy Agent 图标" />
</p>

# Happy Agent

一个**纯 Java** 写的手机端 AI Agent 客户端，专为**安卓 6（API 23）及以上的老设备**优化。它把会话、工具、配置、诊断搬到手机上，内置**可切换的多模型供应商接入**（OpenAI / Google / Anthropic，填对应 Key 调真模型，留空走离线本地引擎），并带一套**端侧 agent 循环**：多步 ReAct（think → 调工具 → 观察 → 继续）、真实文件工具、Shell 沙箱 + proot 探测、多轮上下文聊天，还带**任务级「暂停 / 继续 / 取消」控制**。界面是克制的企业风（Material 3 规范），崩溃自动兜底。

> 关键词：安卓 6、老机适配、纯 Java、无 Kotlin、AI agent、端侧智能体、多模型供应商、OpenAI 兼容、Gemini、Claude、Anthropic、多步 ReAct、工具调用、暂停 继续 取消、Shell 沙箱、proot、崩溃兜底、企业风 UI、Material 3、会话管理、个性化主题、低 API 兼容、minSdk 23

---

## 一、能力总览（按"能直接用"分档）

| 能力 | 说明 | 是否需要 Key |
| --- | --- | --- |
| 聊天界面（气泡 + 多轮） | 用户靠右、AI 靠左、工具调用中间可见，支持多轮上下文 | 否（界面可用） |
| 图片 / 文件发送 | 选图/选文件发进对话，图片按多模态喂模型，文件存进沙箱可被 agent 读取 | 界面：否；真理解图片：需对应 Key |
| AI 真正理解/回答 | 按供应商调真接口（OpenAI / Google Gemini / Anthropic Claude），带工具调用 | 需要填对应 Key |
| 离线本地模式 | 没 Key 时降级，任务能出结果、不卡死 | 否 |
| 多模型供应商切换 | 配置页三选一，请求/响应格式各自适配 | 需要填对应 Key |
| 多步 ReAct agent | 模型驱动 think→act→observe 最多 10 步 | 需要填对应 Key |
| 任务暂停 / 继续 / 取消 | 聊天页控制条，随时叫停 agent 正在跑的步骤 | 否（控制本身） |
| 真实文件工具 | 读/写/列目录/找文件/grep/文件信息/存在性/移动(重命名)/复制/压缩(zip)/解压(unzip)，带沙箱路径校验 | 否（工具本身） |
| 时间工具 | 获取当前日期时间（无副作用，始终可用） | 否 |
| Shell 沙箱 | `/system/bin/sh` 白名单命令 + 超时，proot/Termux 探测 | 否 |
| 工具 / 插件开关 | 行内即时生效，影响任务调用哪些工具 | 否 |
| 运行配置 | 供应商 / 模型 / 温度 / max token / 自动提交 / 工作区 / 各家 Key / Base URL | 否 |
| 系统诊断 | 会话数、工具启用/停用数、模型、当前供应商与 Key 状态 | 否 |
| 崩溃兜底 | 任意线程崩溃自动进崩溃页，一键复制日志 | 否 |
| Web 服务 | 在手机上起一个本地 HTTP 服务，电脑/手机浏览器打开即可跟智能体对话；可开关，开启时通知栏常驻并显示内网/外网地址与端口 | 否（服务端自身）；真回答需对应 Key |
| 文件管理 | 内置文件浏览器 + 文本编辑器；SAF 选一个文件夹授权后即可浏览/编辑/把文件发给智能体；权限自动申请（系统弹窗） | 否 |
| 首次欢迎页 | 首次打开进入全屏欢迎页：logo + 要点 + 醒目的「开源项目 · 完全免费，向用户收费即属诈骗」警示；点「开始使用」进主界面，只显示一次 | 否 |

> 说明：聊天、工具、配置、诊断、崩溃兜底这些**不填 Key 也能用**；"AI 真正聪明地回答 / 驱动多步工具"这一项需要你在配置页选供应商并填对应 Key（OpenAI 或任意兼容接口 / Google / Anthropic）。

## 二、安装

1. 到 [Releases](https://github.com/Damianjiang/happyagent/releases) 下载最新版 APK（`HappyAgent-vX.X-release.apk`，R8 混淆 + 资源缩包后约 8.3 MB，已 v1+v2 签名）。
2. 传到手机安装；若提示"未知来源"，允许后再装一次。
3. 桌面图标为黄色微笑圆点（安卓 7+ 自适应，安卓 6 回退 mipmap）。

> **老机安装说明**：本包 minSdk=23（安卓 6）。已开启 R8（D8 脱糖 + 树摇混淆）和 `shrinkResources`，砍掉 AndroidX/Material 没用到的部分后包体从 11 MB 降到约 8.2 MB。对安卓 6 无压力。

## 三、5 分钟上手

1. 首次打开会看到**欢迎页**：logo + 要点 +「开源项目 · 完全免费，向用户收费即属诈骗」的警示，点「开始使用」进主界面（只显示一次）。
2. 会话页，点右下角 **+** 新建对话。
3. 底部输入框写一句话，点发送 → 看到用户气泡 + AI 回复气泡（没 Key 时是离线回执）。
4. 想让它真聪明：到「配置」页先选**供应商**（OpenAI / Google / Anthropic），再填对应 **API Key**（OpenAI 还能填 Base URL 指向兼容接口，如 Ollama/中转），保存。
5. 再发一句话，AI 会用模型真正理解并回答；需要时会自动调工具（文件/Shell），工具调用过程在聊天里可见。
6. 任务跑着嫌慢或方向不对：聊天页顶部控制条可**暂停 / 继续 / 取消**（取消会立刻打断当前步骤）。
7. 到「工具」页开/关你希望它使用的工具；到「诊断」页看当前会话数、工具启用数、供应商与 Key 状态。

## 四、特色设计（对应企业级）

- **企业级视觉**：Material 3 统一色板、8dp 网格、卡片层级、克制的品牌蓝；深浅色双主题；统一 dimens/文字层级 token。
- **多模型供应商**：OpenAI / Google Gemini / Anthropic Claude 三选一，请求体和响应解析各按官方格式适配，凭据分开存。
- **任务级控制**：ReAct 每步自查「取消/暂停」标志，取消即打断网络读取、暂停则阻塞等待继续——长任务可随时叫停。
- **崩溃兜底**：全局未捕获异常 → 自动跳崩溃页，日志一键复制/分享/返回首页。
- **聊天不卡**：消息用 RecyclerView 局部刷新（只 `notifyItemInserted`），LLM 只喂最近 12 条历史（窗口化），长对话不膨胀。
- **工具调用稳**：模型给不全必填参数时不崩——校验后把"缺哪些"作为反馈喂回模型补齐。
- **连接自愈**：LLM 请求连接失败（断网 / 读超时 / 被限流 / 服务侧 5xx）不再让任务永久失败——自动退避重试：前 3 次各等 1s，之后每次等 2s，一直重连到成功；等待期随时可取消。确定性错误（401 Key 错 / 400 参数错 / 404）重试也不会好，直接让任务失败并给出原因，避免假死到取消。
- **不虚设功能**：工具页的每个开关都真实门控 agent 可用工具（`ReactAgent` 按启用的工具分组喂 schema，停用的即便模型发出也不执行）；震动反馈、摇一摇导出日志都是真接线的（`Haptics` / `ShakeLog`）。凡是做不到真生效的旋钮（旧版的字号缩放、强调色、自动提交、可编辑工作区）已彻底删除，不在界面里留会说谎的开关。

## 五、目录结构

```
app/src/main/java/com/happyagent/mobile/
├─ HappyAgentApplication.java   启动：装崩溃兜底、套主题、后台读存档
├─ CrashHandler.java            全局异常处理
├─ data/
│  ├─ AgentBackend.java         会话/工具/配置 + ReAct 接线 + 持久化
│  ├─ Prefs.java               个性化设置持久化
│  └─ StorageAccess.java       SAF 文件访问（选文件夹授权 + 浏览/读写/发给 agent）
├─ model/Models.java            数据壳（Session/Message/Tool/Config）
├─ tools/                       端侧能力
│  ├─ ReactAgent.java           多步 agent 循环 + 工具参数校验/补齐 + 供应商适配 + 协作式取消
│  ├─ TaskControl.java          任务级 暂停/继续/取消 标志
│  ├─ FileTools.java            真实文件工具（读/写/列/信息/存在/移动/复制/压缩/解压，沙箱路径校验 + zip 越界防护）
│  └─ ShellExecutor.java        Shell 沙箱 + proot 探测（API23 安全）
├─ service/WebUiService.java    本地 HTTP Web 服务（ServerSocket，API23）+ 前台通知 + 内网/外网 IP 端口
└─ ui/                          各页面（聊天、列表、配置、诊断、设置、崩溃）
app/src/main/res/raw/web_chat.html  Web 端聊天页（单文件，深浅色 + 手机/电脑自适应）
```

## 六、Web 服务（手机当服务器，浏览器跟智能体对话）

「设置」页里有个 **Web 服务** 开关。打开后手机就变成一台本地 Web 服务器（默认端口 `8177`），同一局域网里的**电脑或手机浏览器**访问 `http://手机内网IP:8177` 就能跟智能体对话；页面自动按设备宽高适配，且跟随系统深浅色。

- **开关**：设置页「Web 服务」一开一关，关掉服务即停。
- **通知栏**：服务运行时挂一条常驻通知（不可滑掉），点开直接进浏览器。
- **地址显示**：设置页显示**内网地址**（局域网其它设备连手机用的 `192.168.x.x:8177`）和**外网地址**（`公网IP:8177`，需手机联网取，取不到时如实显示「获取中」）。
- **实现**：`service/WebUiService.java` 用 `java.net.ServerSocket`（API 23 安全）起服务，页面是 `res/raw/web_chat.html`（单文件 HTML，无外部依赖）；对外接口 `/api/ipinfo`、`/api/state`、`/api/ask`。
- **注意**：服务监听 `0.0.0.0`，请只在**可信的局域网**里开；外网地址要真正可达还需手机有公网出口/端口映射，App 只负责把查到的地址显示出来。

## 七、文件管理（内置浏览器 + 编辑器，SAF 授权）

「文件」页（底部导航第 4 个）内置了文件浏览器和文本编辑器，让你把真实文件交给智能体处理。

- **自动申请权限**：第一次进「文件」页点"选择文件夹"，弹系统文档选择器；授权只覆盖你选的那个目录，重启后仍有效（SAF 持久授权，跨安卓 6→14 都可用）。
- **浏览器**：浏览目录、进/翻文件夹；点文本文件进编辑器，点其它文件可直接"发给智能体"。
- **编辑器**：编辑授权目录里的文本文件，直接写回原文件。
- **发给智能体**：把文件安全拷进 agent 工作区附件目录（复用沙箱附件通道），开一个会话让它读；非文本文件也能这样交给 agent。
- **实现**：`data/StorageAccess.java` 走 `DocumentsContract` 的 API 19 安全 API（`buildChildDocumentsUri(authority, …)`、`buildDocumentUri(authority, …)`，不碰 API 26 的 `getRootDocumentId`/`buildChildDocumentsUriUsingType`）；`FilesFragment` / `FileEditorActivity` 是界面。

## 八、构建

需要 **JDK 17 + Android SDK（platform-34 / build-tools 34）**，仓库源走国内镜像。

```bash
gradle :app:assembleRelease
```

产物在 `app/build/outputs/apk/`。签名库在 `gradle/`，正式上架前换成你自己的并改密码。

## 九、安卓 6 兼容审计记录

minSdk=23（API 23），逐类核对过会撞版本的项，均已在代码/构建里处理：

| 风险项 | 结论 |
| --- | --- |
| `String.join` / `List.of` / `Optional` / Stream / `CompletableFuture` | 全库 0 处（文件拼接用 `StringBuilder` 手写 join） |
| `Process.waitFor(超时)` / `destroyForcibly`（API 26） | 0 处；Shell 超时 = 无参 `waitFor` + `Future.get(timeout)` + `destroy`，全 API 23 可用 |
| `java.nio.file`（API 26 才有） | 全部走 `java.io.File` |
| `windowLightNavigationBar`（API 27） | 0 处；主题只用 `windowLightStatusBar`（已核对：该属性正是 API 23 引入，6.0 原生支持） |
| 自适应图标 | 放 `mipmap-anydpi-v26`，API 23~25 自动回退普通 mipmap 位图，不崩 |
| Lambda / 方法引用 | D8 脱糖自动转旧字节码（AGP 8 默认行为），API 23 可跑 |
| 落盘反序列化跨版本 | R8 keep 住 `model.*` 与 `AgentBackend$State`，类名/字段名跨版本稳定，老 `state.ser` 仍可读 |
| Web 服务（本地 HTTP） | 用 `java.net.ServerSocket` / `Socket` / `NetworkInterface`（全 API 1~23），前台通知走 `NotificationChannel`（API 26+ 才建通道，低于 26 走旧构造）；`PendingIntent.FLAG_IMMUTABLE` 是 API 31 常量，已做版本分支（`SDK_INT>=29` 才加），安卓 6 上只保留 `FLAG_UPDATE_CURRENT` |
| `startForegroundService` / `stopForeground(int)` | 全是高版本 API，各做版本分支：`SDK_INT>=26` 才 `startForegroundService`，`SDK_INT>=33` 才用带 int 的 `stopForeground`，否则老签名 |
| 内网/外网 IP | 内网 = `NetworkInterface` 取站点内 IPv4（API 1）；外网 = `HttpURLConnection` 请求 `api.ipify.org` 取，取不到就留空、界面如实显示「获取中」，不崩 |
| 文件访问（SAF） | 用 `DocumentsContract` 的 API 19 方法（`buildChildDocumentsUri(authority, …)` / `buildDocumentUri(authority, …)` / `getDocumentId`），**不**用 API 26 的 `getRootDocumentId`/`buildChildDocumentsUriUsingType`/`getDisplayName`；`FLAG_DIR` 用字面量 2；`Collections.sort` 而非 `List.sort`（API 24）。授权走系统文档选择器，不碰 `READ/WRITE_EXTERNAL_STORAGE`（SAF 不依赖） |
| 连接自愈（LLM 重试） | `ReactAgent.postRetry` 对临时错误（超时 / 429 / 5xx）自动退避重连：前 3 次各等 1s、之后每次 2s，等待用分段 `Thread.sleep`（API 1）并随时响应取消；确定性 4xx（401/400/404）不重试直接失败。无高版本 API 依赖 |
| 文件操作工具（move/copy/zip/unzip/exists/time） | 全部走 `java.io.File`（流拷贝、递归）、`java.util.zip`（`ZipOutputStream`/`ZipInputStream`，API 1）与 `java.text.SimpleDateFormat`（API 1）；解压做了"沙箱越界"校验（`getCanonicalFile` + 前缀比对）防 zip 滑出。无 `java.nio.file`、无高版本 API |

三家模型接口（OpenAI `/chat/completions`、Google `generateContent`、Anthropic `/v1/messages`）走 `HttpURLConnection` + `org.json`，纯 JDK/标准库，无运行时版本依赖；请求/响应格式按各家官方文档逐一核对过。

## 十、当前版本范围

Happy Agent 目前实现了**最小可用的 agent 内核**：ReAct 多步循环、工具调用（缺参补齐）、文件工具、Shell/proot 探测、多轮聊天，加上 Web 服务、文件管理、崩溃兜底。**尚未实现**的重能力是 GUI 自动化（无障碍操控手机界面）、语音、插件市场、世界书/角色卡——这些属于更大的独立模块（部分需 `AccessibilityService` 权限等），留作后续版本。当前版本是一个**真正能对话、能调工具、能诊断**的客户端。
