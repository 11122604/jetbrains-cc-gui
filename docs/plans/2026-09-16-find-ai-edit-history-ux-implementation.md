# Find AI Edit History UX 优化实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复「选中历史条目后未定位/未高亮」的缺陷，并把右键弹窗列表项改为双行分层、高亮改为更醒目持久。

**Architecture:** 三处独立改动、互不依赖：Swing 侧替换 `JList` 的 cell renderer；React 侧把「单次 400ms 查找」改为「轮询 + 多级兜底 + 失败诊断」，并把匹配逻辑抽成纯函数以便单测；Less 侧增强高亮样式。不改索引器、不改 SQLite schema、不改 `openHistorySession` 桥接协议。

**Tech Stack:** Java 17 / IntelliJ Platform SDK（Swing `ListCellRenderer`）、React 19 + TypeScript、Vitest（webview 已有测试设施）、Less

---

## 与设计文档的一处偏差（需知悉）

设计文档写「兜底匹配含 `tool_use` 的 assistant 消息」，但 `MessageItem.tsx:780-788` 的根节点**只输出 `data-message-anchor-id` / `data-message-id` / `data-message-uuid` 三个属性，没有 tool_use 标记**。要按原文实现需额外给 `MessageItem` 加 `data-has-tool-use` 属性。

本计划改为兜底到**「容器内最后一条 assistant 消息节点」**——语义相近（AI 的修改通常出现在最后一条 assistant 消息中）、且不扩大改动面。若需精确到 tool_use，可在 Task 2 之后追加改动。

## 文件结构

| 文件 | 责任 | 改动 |
|---|---|---|
| `src/main/java/com/github/claudecodegui/action/editor/FindAiHistoryAction.java` | 弹窗展示与打开会话 | 新增 renderer；抽出纯函数 |
| `src/test/java/com/github/claudecodegui/action/editor/FindAiHistoryActionTest.java` | 列表项文本生成逻辑单测 | 新建 |
| `webview/src/utils/messageFocus.ts` | 焦点目标查找（纯函数，可单测） | 新建 |
| `webview/src/utils/messageFocus.test.ts` | 上述纯函数单测 | 新建 |
| `webview/src/App.tsx` | 定位 effect 接线 | 修改 `:565-584` |
| `webview/src/styles/app.less` | 高亮样式 | 修改 `:40-46` |

---

### Task 1: 弹窗列表项双行分层

**Files:**
- Modify: `src/main/java/com/github/claudecodegui/action/editor/FindAiHistoryAction.java:155-171`
- Test: `src/test/java/com/github/claudecodegui/action/editor/FindAiHistoryActionTest.java`（新建）

- [ ] **Step 1: 写失败的测试**

新建测试文件，验证列表项文本生成的三条规则（主行/副行分离、当前文件不带文字标记、预览截断）：

```java
package com.github.claudecodegui.action.editor;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FindAiHistoryActionTest {

    private static JsonObject hit(String filePath, String matchedLines, boolean sameFile, String preview) {
        JsonObject o = new JsonObject();
        o.addProperty("filePath", filePath);
        o.addProperty("matchedLines", matchedLines);
        o.addProperty("sameFile", sameFile);
        o.addProperty("preview", preview);
        return o;
    }

    @Test
    public void titleContainsFileNameAndMatchedLines() {
        String title = FindAiHistoryAction.buildItemTitle(
                hit("C:\\work\\BinarySearchDemo.java", "12-15", false, ""));
        assertEquals("BinarySearchDemo.java · 命中 12-15 行", title);
    }

    @Test
    public void titleHasNoSameFileTextMarker() {
        // 当前文件改用色条标识，标题里不应再出现 [当前文件] 文字
        String title = FindAiHistoryAction.buildItemTitle(
                hit("C:\\work\\BinarySearchDemo.java", "12-15", true, ""));
        assertFalse(title.contains("[当前文件]"));
    }

    @Test
    public void previewIsTruncatedTo80CharsWithEllipsis() {
        String longPreview = "x".repeat(120);
        String preview = FindAiHistoryAction.buildItemPreview(
                hit("a.java", "1-2", false, longPreview));
        assertEquals(81, preview.length()); // 80 个字符 + 省略号
        assertTrue(preview.endsWith("…"));
    }

    @Test
    public void previewCollapsesNewlines() {
        String preview = FindAiHistoryAction.buildItemPreview(
                hit("a.java", "1-2", false, "line1\nline2\r\nline3"));
        assertEquals("line1 line2 line3", preview);
    }

    @Test
    public void previewIsEmptyWhenAbsent() {
        assertEquals("", FindAiHistoryAction.buildItemPreview(hit("a.java", "1-2", false, "")));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `JAVA_HOME="D:/software/temurin-21.0.7" ./gradlew test --tests "*FindAiHistoryActionTest*" --console=plain`
Expected: 编译失败 —— `buildItemTitle` / `buildItemPreview` 方法不存在

- [ ] **Step 3: 实现抽出的纯函数**

在 `FindAiHistoryAction` 中，把原 `buildDisplayText`（`:155-171`）替换为两个可测的静态方法（保留 `private static` 可见性改为包级以便测试）：

```java
    /** 列表项主行：文件名 · 命中 N 行（当前文件靠色条标识，不加文字标记）。 */
    static String buildItemTitle(JsonObject hit) {
        String filePath = hit.has("filePath") ? hit.get("filePath").getAsString() : "";
        String fileName = filePath;
        int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        if (slash >= 0) {
            fileName = filePath.substring(slash + 1);
        }
        String matched = hit.has("matchedLines") ? hit.get("matchedLines").getAsString() : "";
        return fileName + " · 命中 " + matched + " 行";
    }

    /** 列表项副行：代码预览，压平换行并截断到 80 字符。 */
    static String buildItemPreview(JsonObject hit) {
        String preview = hit.has("preview") ? hit.get("preview").getAsString() : "";
        if (preview.isEmpty()) {
            return "";
        }
        String flat = preview.replace('\n', ' ').replace('\r', ' ');
        if (flat.length() > PREVIEW_MAX_CHARS) {
            flat = flat.substring(0, PREVIEW_MAX_CHARS) + "…";
        }
        return flat;
    }
```

并在类字段区新增常量：

```java
    private static final int PREVIEW_MAX_CHARS = 80;
```

- [ ] **Step 4: 运行测试确认通过**

Run: `JAVA_HOME="D:/software/temurin-21.0.7" ./gradlew test --tests "*FindAiHistoryActionTest*" --console=plain`
Expected: PASS（5 个测试全部通过）

- [ ] **Step 5: 新增 renderer 并接到 JList 上**

在 `FindAiHistoryAction` 内新增私有静态内部类：

```java
    /** 双行分层渲染：主行（文件名 · 命中行）+ 副行（代码预览）。 */
    private static final class SnippetCellRenderer extends JPanel implements ListCellRenderer<JsonObject> {
        private static final int SAME_FILE_STRIPE_WIDTH = 3;

        private final JLabel titleLabel = new JLabel();
        private final JLabel previewLabel = new JLabel();
        private final JPanel stripe = new JPanel();

        SnippetCellRenderer() {
            setLayout(new BorderLayout(8, 0));
            setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

            stripe.setPreferredSize(new Dimension(SAME_FILE_STRIPE_WIDTH, 0));
            stripe.setOpaque(true);

            JPanel text = new JPanel(new GridLayout(2, 1, 0, 2));
            text.setOpaque(false);

            Font base = UIUtil.getLabelFont();
            titleLabel.setFont(base.deriveFont(Font.PLAIN));
            previewLabel.setFont(base.deriveFont(base.getSize() - 1f));
            previewLabel.setForeground(UIUtil.getContextHelpForeground());

            text.add(titleLabel);
            text.add(previewLabel);
            add(stripe, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends JsonObject> list, JsonObject value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            boolean sameFile = value != null && value.has("sameFile")
                    && value.get("sameFile").getAsBoolean();

            titleLabel.setText(value == null ? "" : buildItemTitle(value));
            previewLabel.setText(value == null ? "" : buildItemPreview(value));

            Color bg = isSelected ? UIUtil.getListSelectionBackground(true)
                    : (sameFile ? UIUtil.getListBackground() : UIUtil.getListBackground());
            setBackground(bg);
            setOpaque(true);
            stripe.setBackground(sameFile ? UIUtil.getListSelectionBackground(true) : bg);

            titleLabel.setForeground(isSelected ? UIUtil.getListSelectionForeground(true)
                    : UIUtil.getLabelForeground());
            previewLabel.setForeground(isSelected ? UIUtil.getListSelectionForeground(true)
                    : UIUtil.getContextHelpForeground());
            return this;
        }
    }
```

接上（在 `JList` 创建之后、`showInBestPositionFor` 之前）：

```java
        list.setCellRenderer(new SnippetCellRenderer());
        list.setFixedCellHeight(44);
        list.setVisibleRowCount(10);
```

- [ ] **Step 6: 编译并人工验证弹窗**

Run: `JAVA_HOME="D:/software/temurin-21.0.7" ./gradlew compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`

人工验证（详见 Task 4）：选中代码 → 右键 → Find AI Edit History，确认每项为两行、当前文件项左侧有色条。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/github/claudecodegui/action/editor/FindAiHistoryAction.java src/test/java/com/github/claudecodegui/action/editor/FindAiHistoryActionTest.java
git commit -m "feat: render Find AI Edit History popup items as two-line rows"
```

---

### Task 2: 定位逻辑改为轮询 + 多级兜底 + 诊断

**Files:**
- Create: `webview/src/utils/messageFocus.ts`
- Test: `webview/src/utils/messageFocus.test.ts`
- Modify: `webview/src/App.tsx:565-584`

- [ ] **Step 1: 写失败的测试**

新建 `webview/src/utils/messageFocus.test.ts`：

```ts
import { describe, expect, it } from 'vitest';
import { findFocusTarget } from './messageFocus';

/** 构造一个带 data-* 属性的容器。 */
function container(html: string): HTMLElement {
  const div = document.createElement('div');
  div.innerHTML = html;
  return div;
}

describe('findFocusTarget', () => {
  it('prefers data-message-uuid match', () => {
    const c = container(`
      <div class="message assistant" data-message-id="msg_1" data-message-uuid="uuid-a"></div>
      <div class="message assistant" data-message-id="uuid-b" data-message-uuid="uuid-c"></div>
    `);
    const hit = findFocusTarget(c, 'uuid-a');
    expect(hit?.dataset.messageUuid).toBe('uuid-a');
  });

  it('falls back to data-message-id match', () => {
    const c = container(`<div class="message assistant" data-message-id="msg_42"></div>`);
    const hit = findFocusTarget(c, 'msg_42');
    expect(hit?.dataset.messageId).toBe('msg_42');
  });

  it('falls back to last assistant message when no id matches', () => {
    const c = container(`
      <div class="message user" data-message-id="m1"></div>
      <div class="message assistant" data-message-id="m2"></div>
      <div class="message assistant" data-message-id="m3"></div>
    `);
    const hit = findFocusTarget(c, 'no-such-id');
    expect(hit?.dataset.messageId).toBe('m3');
  });

  it('returns null for an empty container', () => {
    expect(findFocusTarget(container(''), 'anything')).toBeNull();
  });
});
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd webview && npx vitest run src/utils/messageFocus.test.ts`
Expected: FAIL —— 无法解析模块 `./messageFocus`

- [ ] **Step 3: 实现纯函数**

新建 `webview/src/utils/messageFocus.ts`：

```ts
/**
 * 在消息容器内查找需要聚焦的节点。
 *
 * 匹配优先级：
 *   1. data-message-uuid —— 索引侧存的是 jsonl 的 message.id，历史上两种 id 都出现过
 *   2. data-message-id
 *   3. 兜底：最后一条 assistant 消息节点（id 完全不匹配时至少让用户看到大致位置）
 *
 * 抽成纯函数是为了可单测 —— 原实现内联在 useEffect 里，无法验证。
 */
export function findFocusTarget(
  container: HTMLElement,
  targetId: string,
): HTMLElement | null {
  const nodes = container.querySelectorAll<HTMLElement>(
    '[data-message-id], [data-message-uuid]',
  );

  // 1. uuid 优先
  for (const node of nodes) {
    if (node.dataset.messageUuid === targetId) return node;
  }

  // 2. 再比对 message-id
  for (const node of nodes) {
    if (node.dataset.messageId === targetId) return node;
  }

  // 3. 兜底：最后一条 assistant 消息
  const assistants = container.querySelectorAll<HTMLElement>('.message.assistant');
  if (assistants.length > 0) {
    return assistants[assistants.length - 1];
  }

  return null;
}

/** 收集容器内所有 id，用于定位失败时输出诊断信息。 */
export function collectMessageIds(container: HTMLElement, limit = 20): string[] {
  const nodes = container.querySelectorAll<HTMLElement>(
    '[data-message-id], [data-message-uuid]',
  );
  const ids: string[] = [];
  for (const node of nodes) {
    if (node.dataset.messageUuid) ids.push(`uuid:${node.dataset.messageUuid}`);
    if (node.dataset.messageId) ids.push(`id:${node.dataset.messageId}`);
    if (ids.length >= limit) break;
  }
  return ids;
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd webview && npx vitest run src/utils/messageFocus.test.ts`
Expected: PASS（4 个测试通过）

- [ ] **Step 5: 改写 App.tsx 的定位 effect**

用下面的实现替换 `webview/src/App.tsx:564-584` 的整段（含注释）：

```tsx
  // 会话载入后定位到命中消息（滚动 + 高亮）。
  // 轮询而非单次延时：大对话的 updateMessages 推送与 DOM 渲染可能慢于固定 400ms。
  useEffect(() => {
    const targetId = pendingFocusMessageIdRef.current;
    if (!targetId) return;

    let attempts = 0;
    const timer = window.setInterval(() => {
      attempts++;
      const container = messagesContainerRef.current;
      const node = container ? findFocusTarget(container, targetId) : null;

      if (node) {
        window.clearInterval(timer);
        node.scrollIntoView({ block: 'center', behavior: 'smooth' });
        node.classList.add('ai-focus-highlight');
        window.setTimeout(
          () => node.classList.remove('ai-focus-highlight'),
          FOCUS_HIGHLIGHT_MS,
        );
        pendingFocusMessageIdRef.current = null;
        return;
      }

      if (attempts >= FOCUS_MAX_ATTEMPTS) {
        window.clearInterval(timer);
        // 定位失败时输出诊断：目标 id 与 DOM 里实际存在的 id，便于区分
        // 「id 不匹配」与「节点根本没渲染」两种情况。
        console.warn(
          '[FindAiHistory] focus target not found. target=' + targetId
          + ' domIds=' + JSON.stringify(
            messagesContainerRef.current
              ? collectMessageIds(messagesContainerRef.current)
              : [],
          ),
        );
      }
    }, FOCUS_POLL_INTERVAL_MS);

    return () => window.clearInterval(timer);
  }, [messages, currentSessionId, messagesContainerRef]);
```

在 `App.tsx` 顶部（其余常量的位置，例如 `pendingFocusMessageIdRef` 声明附近）新增：

```tsx
/** 定位轮询参数：每 200ms 一次，最长 6 秒。 */
const FOCUS_POLL_INTERVAL_MS = 200;
const FOCUS_MAX_ATTEMPTS = 30;
/** 高亮保持时长。 */
const FOCUS_HIGHLIGHT_MS = 10_000;
```

并在 import 区加入：

```tsx
import { collectMessageIds, findFocusTarget } from './utils/messageFocus';
```

- [ ] **Step 6: 类型检查与测试**

Run: `cd webview && npx vitest run src/utils/messageFocus.test.ts && npx tsc -p tsconfig.test.json --noEmit`
Expected: 测试 PASS，`tsc` 无输出（无类型错误）

- [ ] **Step 7: 提交**

```bash
git add webview/src/utils/messageFocus.ts webview/src/utils/messageFocus.test.ts webview/src/App.tsx
git commit -m "fix: locate focused history message via polling with fallbacks"
```

---

### Task 3: 高亮增强

**Files:**
- Modify: `webview/src/styles/app.less:40-46`

- [ ] **Step 1: 替换高亮样式**

把 `app.less` 中的 `.ai-focus-highlight` 与 `@keyframes aiFocusPulse` 整段替换为：

```less
// G0: 查找 AI 修改历史 —— 命中消息高亮（持续 10s，由 App.tsx 的 FOCUS_HIGHLIGHT_MS 控制移除）
.ai-focus-highlight {
  background-color: fade(@primary-color, 12%);
  border-left: 3px solid @primary-color;
  border-radius: 4px;
  animation: aiFocusPulse 1.6s ease-in-out 3;
}
@keyframes aiFocusPulse {
  0%   { box-shadow: 0 0 0 0 fade(@primary-color, 45%); }
  50%  { box-shadow: 0 0 0 6px fade(@primary-color, 25%); }
  100% { box-shadow: 0 0 0 0 fade(@primary-color, 0%); }
}
```

> **注意**：`@primary-color` 需确认在本文件可见。若未定义，直接写死颜色值 `rgba(51, 122, 254, …)`（原实现用的就是该色值）。

- [ ] **Step 2: 确认变量可用后构建**

Run: `cd webview && npm run build`
Expected: 构建成功（若 `@primary-color` 未定义会在此步报错，改用写死的 `rgba(51, 122, 254, ...)`）

- [ ] **Step 3: 提交**

```bash
git add webview/src/styles/app.less
git commit -m "style: make focused history message highlight more prominent"
```

---

### Task 4: 端到端验证

**Files:** 无（仅验证）

- [ ] **Step 1: 全量构建**

Run: `JAVA_HOME="D:/software/temurin-21.0.7" ./gradlew compileJava --console=plain && cd webview && npm run build`
Expected: 均成功

- [ ] **Step 2: 启动沙箱**

Run: `env -u CLAUDE_USE_STDIN JAVA_HOME="D:/software/temurin-21.0.7" ./gradlew runIde --console=plain`

> 用 `env -u CLAUDE_USE_STDIN` 剥离本机会话变量，避免子进程继承后用错 stdin 分支（见 `docs/plans/2026-09-16-*` 相关排查记录）。

- [ ] **Step 3: 验证弹窗样式**

在沙箱中打开一个 Java 文件，选中一段此前被 AI 修改过的代码 → 右键 → Find AI Edit History。
Expected: 每项两行；主行为「文件名 · 命中 N-M 行」；副行为代码预览；当前文件项左侧有 3px 色条。

- [ ] **Step 4: 验证定位与高亮**

在弹窗中双击任意命中项。
Expected:
- 新建 tab 并载入对应会话
- 对话滚动到目标消息并居中
- 该消息背景高亮 + 左侧色条，约 10 秒后消失

- [ ] **Step 5: 验证诊断通路**

若 Step 4 未定位成功，打开 vConsole 或查看 webview 控制台，确认出现：
`[FindAiHistory] focus target not found. target=… domIds=[…]`
把 `target` 与 `domIds` 的对比结果记录下来 —— 这将直接指出是 id 不匹配还是节点缺失。

- [ ] **Step 6: 提交验证结论**

把 Step 3/4/5 的实际结果（成功与否、诊断输出）追加到设计文档的「测试要点」一节下，然后：

```bash
git add docs/plans/2026-09-16-find-ai-edit-history-ux-design.md
git commit -m "docs: record Find AI Edit History verification results"
```

---

## Self-Review

**Spec coverage:**

| 设计需求 | 对应 Task |
|---|---|
| 弹窗双行分层 | Task 1 |
| 当前文件用色条标识（非文字） | Task 1 Step 5 |
| 定位改轮询 + 多级兜底 + 诊断 | Task 2 |
| 高亮增强（更强 / 10 秒） | Task 3 |
| 不改索引结构、不加导航 | 无改动（符合「不做」清单） |

**Type consistency check:**

- `buildItemTitle` / `buildItemPreview` —— Task 1 定义与测试中调用一致
- `findFocusTarget` / `collectMessageIds` —— Task 2 定义、测试与 `App.tsx` 接线一致
- `FOCUS_POLL_INTERVAL_MS` / `FOCUS_MAX_ATTEMPTS` / `FOCUS_HIGHLIGHT_MS` —— Task 2 Step 5 定义，Task 3 注释引用 `FOCUS_HIGHLIGHT_MS`
- `PREVIEW_MAX_CHARS = 80` —— Task 1 中常量与测试断言的 81（80 + 省略号）一致

**Placeholder scan:** 无 TBD/TODO；每个改动步骤都给出了完整代码。

**已知待确认项（已在步骤内标注处理方式）:**
- Task 3 的 `@primary-color` 变量是否在 `app.less` 可见 —— Step 2 给出了回退方案
