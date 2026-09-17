# DSH 提问在 cc-gui 中「答了等于没答」问题分析与修复记录

**日期**: 2026-09-17
**版本**: v0.5.6 之后
**状态**: 根因已定位并修复（答案编码）；3 项次生风险已记录待评估
**涉及 Provider**: `dsh`（DeepSeek Harness，modern 线协议 ≥ 0.1.5）

---

## 一、用户反馈

> 在 cc-gui 里没法作答 DSH 提的问题，只能到 dsh-web 那边操作；虽然弹了问题，但答复无效。

先说结论：**弹窗、选项点选、提交、Java 侧 IPC 这条链路是通的**（下面的真实会话记录可以证明），坏在最后一跳——Node 桥把答案回填给 DSH host 时，**答案的 `id` 用错了**，用成了「问题正文」，而 DSH 要求回填提问方自己声明的 `question.id`。模型拿到的是一批它从未声明过的 id，无法把答案对回到自己的问题上；多问题批次、需要 free-text 的场景直接读不出有效答复，用户于是回到 dsh-web 重新回答。

---

## 二、根因

答案的编码在两处语义不一致：

| 环节 | 代码 | 行为 |
| --- | --- | --- |
| 前端弹窗（与 Claude 共用） | `webview/src/components/AskUserQuestionDialog/answerState.ts#formatAnswers` | 以**问题正文**为 key 产出答案对象；「其他」自由文本被塞进标签数组 |
| DSH 桥（修复前） | `ai-bridge/services/dsh/events.js#mapQuestionAnswers` | 直接把这个 key 当成 DSH 的答案 `id` 发出 |

DSH 的协议契约（`@deepseek-ai/dsh-user-questions`）：

- `AskUserQuestionItem.id`：「Stable caller-provided question id, **echoed in the answer**」——必须原样回填；
- `AskUserQuestionAnswerItem`：`{ id, selected: string[], custom?: string }`；
- **单选**题里 `custom` 会**取代**所选项，此时 `selected` 必须为空；多选题 `custom` 可与 `selected` 并存。

而 cc-gui 桥发出去的是 `{id: "<问题正文>", selected: [...]}`——正文当 id，自由文本当选项。对照 DSH 官方 Web 客户端（`dsh-client-ui-user-questions`）：它发的永远是 `{ id: item.id, selected, custom? }`。

---

## 三、证据

### 1. 真实会话记录（`~/.dsh/sessions/**/session*.jsonl.zstd`）

这些日志是**多帧 zstd 容器**（每条记录一帧），必须先按帧头结构切分再逐帧解码，单次 `zstdDecompressSync` 只能拿到 header 帧。

扫描 119 个 transcript（36 个含 `ask_user_question` 调用），对所有工具返回做形状分类：

| 答案形状 | 数量 | 来源 |
| --- | --- | --- |
| `id` = 提问方声明的 id（合法） | 72 | dsh-web 客户端 |
| `id` = **问题正文**（本次 bug） | 2 | cc-gui 插件 |
| `{"answers":[]}` | 2 | 取消 / 兜底 |
| 调用被中断（aborted / interrupted） | 14 | 用户打断轮次 |

两处插件形状的实例，都是 cc-gui 驱动的会话：

- `session-73f99e35`（`D:\code\广排\psgs-analyze-server`，2026-09-16）
- `session-bacd2ec0`（`D:\code\广排\psgs-server-v2`，2026-09-17）

以 2026-09-17 那次（`id: prescheck` / `otherfix` 两个问题）为例，DSH 实际收到的是：

```json
[
  { "id": "兜底现在是「静默跳过」（…）。要不要再加一道前置检查改成主动提示？", "selected": ["只做提示，部分生效（推荐）"] },
  { "id": "另外两个发现要不要我一并修掉？（可多选）", "selected": ["追溯接口 isReceived 恒为 0（String 用了 ==）", "…"] }
]
```

模型的反应也印证了这一点（transcript seq=522）：它只能靠选项文案和顺序硬猜归属，并抱怨「你选了那条描述里说要一并告知的选项，但没告诉我」——即答复内容不完整、不可核验。

用修复后的映射重放同一份真实输入：

```json
{ "answers": [
  { "id": "prescheck", "selected": ["只做提示，部分生效（推荐）"] },
  { "id": "otherfix",  "selected": ["追溯接口 isReceived 恒为 0（String 用了 ==", "批量取消的 findTopByUsidInOrderByIdDesc 只取 1 条"] }
] }
```

### 2. 线上 host 探针（排除传输层嫌疑）

对正在运行的 `http://127.0.0.1:3080` 用插件自签的浏览器会话 cookie 发 `POST /api/$events/result`：

| 载荷 | host 响应 |
| --- | --- |
| `{args:{clientId,eventId,outcome}}`（插件实际发送的形状） | 通过解析，报 `Remote event result identifies no active event stream`（仅因 clientId 是伪造值） |
| `{clientId,eventId,outcome}`（裸形状） | `Remote event result requires exactly one plain-object args field` |

结论：**封包形状是对的**，`answerWaterfall` 的 `$events/result` 调用没有问题；坏的只是答案内容。

---

## 四、修复

`ai-bridge/services/dsh/events.js`：

1. `mapQuestionAnswers(answers, questions)` 增加 `questions` 入参，按**问题正文 → 声明 id** 反查，输出的 `id` 一律是提问方声明的 id；
2. 用该题的 `options[].label` 集合区分「选项」与「其他自由文本」：命中选项进 `selected`，未命中进 `custom`；单选题存在 `custom` 时 `selected` 置空（与官方 Web 客户端一致）；
3. 批次里未作答的问题补 `{ id, selected: [] }`（镜像 Web 客户端），对话框整单取消时仍发 `{ answers: [] }`，保住「没有答案」与「全部跳过」的区别；
4. 若没有任何问题能匹配上（legacy host、或非 DSH 形状的旧载荷），退回原行为（按 key 原样投递），不丢人的答案。

调用点同步传参：`bridgeDshQuestion`（legacy `respond`）与 `bridgeModernQuestion`（modern `$events/result`）。

### 回归测试

- `ai-bridge/services/dsh/events.test.js`：新增 `mapQuestionAnswers` 单元用例——声明 id 回填、单选 custom 覆盖、多选 custom 并存、无选项问题全自由文本、跳过项、取消为空、无匹配时回退。
- `ai-bridge/services/dsh/question-bridge.test.js`（新增）：模拟 Java 侧 IPC（认领请求文件 → 按前端 key 写答案文件），断言 host 收到的 `$events/result` 值与 `respond` 值；另断言请求文件逐字携带模型声明的 `questions`。

验证：

- ✅ `node --test ai-bridge/services/dsh/*.test.js`：114/114 通过（修复前 105 + 新增 9）
- ✅ `node --test`（全部 ai-bridge 测试）：759 tests / 756 pass / 3 skipped / 0 fail

> 数字以本分支基线 `feature/v0.5.7` 为准（`main` 与本分支的既有测试数量不同）。

---

## 五、次生风险（已核实，未在本次改动范围内）

1. **`turn.clientId` 未就绪时答案被静默丢弃**：`answerWaterfall` 在 `$events` 尚未收到 `ready` 帧时直接 `return false`（日志 `[dsh] dropping a waterfall answer: the $events stream has no clientId yet`，只在 Node stderr，IDE 侧默认不可见）。此时 cc-gui 弹窗已经关闭、宿主仍在等待，用户只能去 dsh-web 答。建议：发答案前有界等待 `ready`，或把失败显式回报给前端并让弹窗可重答。
2. **`dialogToken` 不匹配会被静默吞掉**：`PermissionHandler.handleAskUserQuestionResponse` 在 token 比对失败时直接 `return`，future 要等到安全网超时才由 `showAskUserQuestionDialog` 以空对象结束，最终表现为 `{"answers":[]}`——与「用户取消」无法区分。排查时看是否缺 `[ASK_USER_QUESTION][HANDLE_RESPONSE]` 且随后出现 `Safety-net timeout fired`。
3. **DSH 的 `detail` / `intent` 被前端丢弃**：`normalizeQuestion` 只保留 `question/header/options/multiSelect`。DSH 计划模式复核走的是 `user-questions/request`（`id: "plan-review"`、`detail` = 计划 markdown、`intent.kind = "plan-review"`），在 cc-gui 里只会显示「Approve this plan and leave plan mode?」加两个选项、**看不到计划正文**，标题还是「Claude 有一些问题想问你」。答案本身已被本次修复救回，但复核界面仍不可用。
4. **孤立 IPC 文件（Claude 路径，非 DSH）**：`%TEMP%\claude-permission\ask-user-question-307c1658-…json`（2026-09-15 11:49，cwd `D:\code\广排\psgs-server-v2`）未被任何 watcher 认领。其 questions 没有 `id` 字段，是 Claude 的 AskUserQuestion 形状（DSH 的 `id` 是必填、且经 JSON 无损校验，缺 id 的请求根本到不了插件），说明是 Claude 侧长驻 daemon 的 `CLAUDE_SESSION_ID` 与当时 watcher 的 routing id 漂移。DSH 每轮新起进程、不受此影响，仅作为同类症状的另一条线索记录。

---

## 六、真机（IDE 内）验证 ✅

2026-09-17 做了两轮验证。

**1. 密闭端到端（不需要真实 host、不花 token）**

本地 mock DSH host（HTTP + `/api/remote.mux`）+ 真实的 `channels/dsh-channel.js → message-service → events.js`，由脚本扮演 Java 侧与弹窗（答案按问题正文做 key、自由文本混进标签——即弹窗的真实形状）。同一份脚本分别跑两份 bridge：

| 目标 | host 实收 id | 结果 |
| --- | --- | --- |
| 仓库（已修复） | `prescheck, otherfix, cadence, unused` | PASS |
| 安装目录副本（修复前） | 三个问题正文（第 4 题整题丢失） | FAIL |

**2. IDE 内真机一轮**

- 15:10:30 插件从 cc-gui 发起 DSH 轮次（日志 `sendToCli provider=dsh, cwd=D:\code\jetbrains\jetbrains-cc-gui-v2`），bridge 进程来自已打补丁的安装目录（`[dsh] Command: …\plugins\idea-claude-code-gui\ai-bridge\channel-manager.js dsh send`）。
- session `session-2754c72b-235a-41c7-b745-6e8157d655f4`：模型声明 `id=abc_test`（3 选项、单选），DSH 实收

  ```json
  {"answers":[{"id":"abc_test","selected":["A. 选项 A —— 能正常点选"]}]}
  ```

- 模型随后正确读到答复并汇报「控件工作正常」——id 与声明一致，提问→渲染→点选→结构化回填闭环。

---

## 七、如何在你的环境里复核

1. **快速复核（不必重新打包）**：`<pluginDir>\ai-bridge\.bridge-version` 记录的是 `0.5.6:<ai-bridge.hash>`，两者一致时 `BridgeDirectoryResolver` 会跳过重新解压，因此直接把修复后的 `ai-bridge/services/dsh/events.js` 覆盖到 `<pluginDir>\ai-bridge\services\dsh\`（先备份原文件）即可；DSH 每轮现开 `channel-manager.js` 进程，下一轮提问即生效，**无需重启 IDE**。正式发布仍走 `./gradlew buildPlugin`（`packageAiBridge` 会重新生成 `ai-bridge.zip` + `ai-bridge.hash`）。
2. 在 cc-gui 里触发一次 `ask_user_question`，选择选项并提交。
3. 到 `~/.dsh/sessions/<workspace>/<session>/session.v3.jsonl.zstd` 里看 `tool/result`：`id` 应等于模型声明的 id（如 `abc_test`），自由文本应出现在 `custom` 字段，未作答的问题应为 `{id, selected: []}`。修复前这里一定是问题正文。

> 读取技巧：该文件每条记录一个 zstd 帧，需按帧结构切分后逐帧解码（可参考 `@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js#scanZstdFrames`）。

---

## 八、核心文件

- `ai-bridge/services/dsh/events.js`（`mapQuestionAnswers` 重写 + 两个 bridge 调用点传参）
- `ai-bridge/services/dsh/events.test.js`（新增 5 个映射用例）
- `ai-bridge/services/dsh/question-bridge.test.js`（新增集成用例 4 个）
