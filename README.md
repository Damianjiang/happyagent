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
| AI 真正理解/回答 | 按供应商调真接口（OpenAI / Google Gemini / Anthropic Claude），带工具调用 | 需要填对应 Key |
| 离线本地模式 | 没 Key 时降级，任务能出结果、不卡死 | 否 |
| 多模型供应商切换 | 配置页三选一，请求/响应格式各自适配 | 需要填对应 Key |
| 多步 ReAct agent | 模型驱动 think→act→observe 最多 10 步 | 需要填对应 Key |
| 任务暂停 / 继续 / 取消 | 聊天页控制条，随时叫停 agent 正在跑的步骤 | 否（控制本身） |
| 真实文件工具 | 读/写/列目录/找文件/grep/文件信息，带沙箱路径校验 | 否（工具本身） |
| Shell 沙箱 | `/system/bin/sh` 白名单命令 + 超时，proot/Termux 探测 | 否 |
| 工具 / 插件开关 | 行内即时生效，影响任务调用哪些工具 | 否 |
| 运行配置 | 供应商 / 模型 / 温度 / max token / 自动提交 / 工作区 / 各家 Key / Base URL | 否 |
| 系统诊断 | 会话数、工具启用/停用数、模型、当前供应商与 Key 状态 | 否 |
| 崩溃兜底 | 任意线程崩溃自动进崩溃页，一键复制日志 | 否 |

> 说明：聊天、工具、配置、诊断、崩溃兜底这些**不填 Key 也能用**；"AI 真正聪明地回答 / 驱动多步工具"这一项需要你在配置页选供应商并填对应 Key（OpenAI 或任意兼容接口 / Google / Anthropic）。

## 二、安装

1. 到 [Releases](https://github.com/Damianjiang/happyagent/releases) 下载 `HappyAgent-v1.0-release.apk`（已 v1+v2 签名，约 10.6 MB）。
2. 传到手机安装；若提示"未知来源"，允许后再装一次。
3. 桌面图标为黄色微笑圆点（安卓 7+ 自适应，安卓 6 回退 mipmap）。

> **老机安装说明**：本包 minSdk=23（安卓 6）。10.6 MB 的体积对安卓 6 无压力（真正卡老机的是 minSdk，已满足）。

## 三、5 分钟上手

1. 打开 App → 会话页，点右下角 **+** 新建对话。
2. 底部输入框写一句话，点发送 → 看到用户气泡 + AI 回复气泡（没 Key 时是离线回执）。
3. 想让它真聪明：到「配置」页先选**供应商**（OpenAI / Google / Anthropic），再填对应 **API Key**（OpenAI 还能填 Base URL 指向兼容接口，如 Ollama/中转），保存。
4. 再发一句话，AI 会用模型真正理解并回答；需要时会自动调工具（文件/Shell），工具调用过程在聊天里可见。
5. 任务跑着嫌慢或方向不对：聊天页顶部控制条可**暂停 / 继续 / 取消**（取消会立刻打断当前步骤）。
6. 到「工具」页开/关你希望它使用的工具；到「诊断」页看当前会话数、工具启用数、供应商与 Key 状态。

## 四、特色设计（对应企业级）

- **企业级视觉**：Material 3 统一色板、8dp 网格、卡片层级、克制的品牌蓝；深浅色双主题；统一 dimens/文字层级 token。
- **多模型供应商**：OpenAI / Google Gemini / Anthropic Claude 三选一，请求体和响应解析各按官方格式适配，凭据分开存。
- **任务级控制**：ReAct 每步自查「取消/暂停」标志，取消即打断网络读取、暂停则阻塞等待继续——长任务可随时叫停。
- **崩溃兜底**：全局未捕获异常 → 自动跳崩溃页，日志一键复制/分享/返回首页。
- **聊天不卡**：消息用 RecyclerView 局部刷新（只 `notifyItemInserted`），LLM 只喂最近 12 条历史（窗口化），长对话不膨胀。
- **工具调用稳**：模型给不全必填参数时，**不崩**——校验后把"缺哪些"作为反馈喂回模型补齐（对齐 Operit 的 ToolPackage 机制）。
- **端侧 agent**：ReAct 多步循环（最多 10 步），离线也能走本地引擎。

## 五、目录结构

```
app/src/main/java/com/happyagent/mobile/
├─ HappyAgentApplication.java   启动：装崩溃兜底、套主题、后台读存档
├─ CrashHandler.java            全局异常处理
├─ data/
│  ├─ AgentBackend.java         会话/工具/配置 + ReAct 接线 + 持久化
│  └─ Prefs.java               个性化设置持久化
├─ model/Models.java            数据壳（Session/Message/Tool/Config）
├─ tools/                       端侧能力
│  ├─ ReactAgent.java           多步 agent 循环 + 工具参数校验/补齐 + 供应商适配 + 协作式取消
│  ├─ TaskControl.java          任务级 暂停/继续/取消 标志
│  ├─ FileTools.java            真实文件工具（沙箱路径校验）
│  └─ ShellExecutor.java        Shell 沙箱 + proot 探测（API23 安全）
└─ ui/                          各页面（聊天、列表、配置、诊断、设置、崩溃）
```

## 六、构建

需要 **JDK 17 + Android SDK（platform-34 / build-tools 34）**，仓库源走国内镜像。

```bash
gradle :app:assembleRelease
```

产物在 `app/build/outputs/apk/`。签名库在 `gradle/`，正式上架前换成你自己的并改密码。

## 七、关于"照搬 Operit"的边界

Happy Agent 借鉴并实现了 Operit 的**agent 内核**（ReAct 循环、工具调用 + 缺参补齐、文件工具、Shell/proot、多轮聊天）。**尚未照搬**的是 Operit 里那几块重能力：GUI 自动化（无障碍操控手机 UI）、语音、插件市场、世界书/角色卡。这些属于更大的独立模块，需要 `AccessibilityService` 权限等，作为后续版本逐步补齐。当前版本是一个**真正能对话、能调工具、能诊断**的最小可用 agent 客户端。
