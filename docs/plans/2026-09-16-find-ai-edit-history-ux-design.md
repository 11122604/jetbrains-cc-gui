# Find AI Edit History 交互优化设计

## 概述

「Find AI Edit History」（G0 code-to-session 反查）上线后暴露三处体验问题：右键弹窗样式杂乱、选中条目后新 tab 打开但**未能定位/高亮**到命中消息、长对话中难以找到 AI 修改代码的位置。

本设计只解决**可用性**问题，不改变功能范围：不加侧边栏、不加导航按钮，聚焦「让已有的定位真正生效」并「让它看得见」。

## 需求分析

### 核心需求

1. **弹窗样式**：列表项由单行平铺改为双行分层，信息有主次
2. **定位修复**：选中条目后，新 tab 的对话必须滚动到对应消息并高亮
3. **高亮增强**：高亮需足够醒目、持续足够久，避免用户错过

### 明确不做

- 不加「上一个/下一个修改」导航按钮
- 不加侧边栏修改点列表
- 不改索引数据结构、不改 SQLite schema

### 现状与断点

| 环节 | 实现位置 | 状态 |
|---|---|---|
| 弹窗 | `FindAiHistoryAction.java:121-152` | 可用但样式挤 |
| 打开会话 | `:174-219` | 正常（新 tab 能开、会话能载入） |
| 定位高亮 | `App.tsx:565-584` | **未生效** |

**已排除的可能**（调查结论）：

- 索引数据正常：`edit_snippets` 表有 3419 条记录 / 71 个会话，`message_id` 与 jsonl 的 `message.id` 同源（`EditSnippetExtractor.java:65`）
- 前端属性已渲染：`MessageItem.tsx:785-787` 输出了 `data-message-id` 与 `data-message-uuid`
- JS 侧两个属性都会比对：`App.tsx:574`
- `useEffect` 依赖数组正确（`[messages, currentSessionId, messagesContainerRef]`），不是"只跑一次"的时序 bug
- 高亮样式确实被引入：`main.tsx:10` import 了 `app.less`，`.ai-focus-highlight` 定义在 `app.less:40`

**断点位于运行时**，静态阅读无法确定是「id 值不匹配」还是「DOM 节点缺失」。因此修复策略必须**同时**做到：让定位更鲁棒、并留下可观测的诊断输出。

## 技术方案

### 一、弹窗列表项：双行分层

**改动位置**：`FindAiHistoryAction.java`

现状 `buildDisplayText`（`:156-171`）把四种信息拼成一行：

```
文件名 · 命中 12-15 行 [当前文件]  —  public static int binarySearch(int[] arr, int tar…
```

**改为自定义 `ListCellRenderer`**，每项两行：

```
第 1 行：BinarySearchDemo.java · 命中 12-15 行        ← 主色，稍大字号
第 2 行：public static int binarySearch(int[] arr…    ← 灰色小字，预览放宽到 ~80 字符
```

设计要点：

- **`[当前文件]` 不再用文字标记**，改用**左侧 3px 色条 + 浅色底**，把文字空间让给内容
- **行高固定**，避免列表滚动时跳变
- 设置 `preferredScrollableViewportSize`，限制弹窗高度，避免条目多时占满屏幕
- 文件名与命中行用不同字号/颜色形成层次，替代原先的 `·` `—` 分隔符

### 二、定位修复：单次 400ms → 轮询 + 多级兜底 + 诊断

**改动位置**：`webview/src/App.tsx:565-584`

现状只在 `messages` 变化后延迟 400ms 查一次 DOM：

```tsx
const timer = setTimeout(() => {
  const nodes = container.querySelectorAll('[data-message-id], [data-message-uuid]');
  for (const node of nodes) {
    if (node.dataset.messageId === targetId || node.dataset.messageUuid === targetId) { ... }
  }
}, 400);
```

**改为轮询 + 多级匹配**：

1. **轮询**：每 200ms 尝试一次，最长 6 秒（大对话渲染慢时不再漏）
2. **匹配优先级**：`data-message-uuid` → `data-message-id` → **兜底**：容器内最后一条含 `tool_use` 的 assistant 消息
3. **诊断输出**：全部失败时 `console.warn` 打印「目标 id」与「DOM 中实际存在的前 20 个 id」，一次实测即可判定是 id 不匹配还是节点缺失
4. **成功即停**：命中后清空 `pendingFocusMessageIdRef`，避免重复高亮

保留现有「只定位一次」的语义（`pendingFocusMessageIdRef` 命中后置空）。

### 三、高亮增强

**改动位置**：`webview/src/styles/app.less:40-46`

现状只有 `box-shadow` 脉冲，`1.6s × 2`（约 3.2 秒）后消失，容易错过。

改为：

- **视觉更强**：背景色高亮 + 左侧 3px 色条 + 边框，而非仅阴影
- **持续更久**：由 3.2 秒延长到 **10 秒**（固定时长，到期自动移除 class）
- **滚动保留**：`scrollIntoView({ block: 'center', behavior: 'smooth' })` 不变

## 影响范围

| 文件 | 改动性质 |
|---|---|
| `src/main/java/.../action/editor/FindAiHistoryAction.java` | 新增 `ListCellRenderer`，替换 `buildDisplayText` |
| `webview/src/App.tsx` | 重写定位 `useEffect`（轮询 + 兜底 + 诊断） |
| `webview/src/styles/app.less` | 增强 `.ai-focus-highlight` |

**不改**：索引器、SQLite schema、`openHistorySession` 桥接协议、Java 侧打开 tab 的流程。

## 测试要点

1. 选中代码 → 右键 → Find AI Edit History，弹窗列表项为双行，当前文件条目有色条标识
2. 选中「当前文件」与「其他文件」的命中项，均能新开 tab 并载入对应会话
3. **定位生效**：新 tab 中目标消息滚动到视口中央并高亮，高亮持续约 10 秒
4. **诊断可用性**：人为构造 id 不匹配（或用错误 id）时，控制台能看到目标 id 与 DOM id 列表
5. 连续多次触发不会重复高亮、不会卡住
6. 大对话（数百条消息）下定位仍能命中，不因渲染慢而失败
