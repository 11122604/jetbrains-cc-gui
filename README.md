# Feature Proposal: Find AI Edit History

> Reverse lookup: from a piece of code back to the AI conversations that edited it.

## 1. The Problem

When you write code with CC GUI, the AI edits a region and moves on. Come back to that code a few days later and three questions come up:

1. **Who changed this?** A human, or the AI in some session?
2. **Why was it changed this way?** The reasoning, the constraints, the rejected alternatives — all of it lives in that conversation.
3. **Which conversation was it?** Sessions are ordered by time. With dozens or hundreds of them, scrolling back is hopeless.

Today the plugin can only go one way: **from a conversation to the code it changed**. There is no way to go **from code back to the conversation**.

## 2. Scenarios and the Efficiency Gain

### Scenario 1 — Continuing a feature across several sessions

A feature is rarely built in one pass: the first session scaffolds it, the second adds validation, the third reworks the interaction. **Each round tends to be its own session.**

When you pick up "continue working on X", the first thing you need is *where the previous round left off, and why.*

| | Before | After |
|---|---|---|
| How | Guess which session built this feature by timestamp, open them one by one | Select the code from the previous round → right-click → hit it directly |
| Time | **If you can't find it, re-read the code and reverse-engineer the intent** — or just redo it | **Seconds** to land in the previous conversation, with full context intact |
| Outcome | Re-implement logic that already exists, or undo a deliberate earlier decision | Continue along the same line of reasoning — **no need to rebuild the context** |

**This is the most common way AI-assisted work actually happens**: the code is the product of several rounds, but nobody remembers where the boundaries were.

### Scenario 2 — Debugging

A piece of code misbehaves and you need to know *which change* introduced it.

A debugger tells you what the code does now. It cannot tell you why it became that way.

| | Before | After |
|---|---|---|
| How | Add logging, set breakpoints, read `git log` (often a single line like `fix: ...`), fall back to bisecting | Select the suspicious code → right-click, get every session that touched it |
| Time | **The more tangled the bug and the more rounds of edits, the worse it gets** | **Seconds** to a short list of candidate changes |
| Outcome | Guesswork; may fix the symptom and not the cause | Read the **motivation and constraints** behind each change and identify the culprit |

The same applies to production incidents: once you have located the offending code, you can immediately review why it changed instead of relying on a one-line commit message.

### Scenario 3 — Inheriting code from someone else (or from yourself, six months ago)

You find an oddly convoluted piece of code and want to know whether it is deliberate or just legacy baggage.

| | Before | After |
|---|---|---|
| How | Open the session list, click through them one by one, Ctrl+F for the snippet | Select the code → right-click → pick a result |
| Time | **Minutes to tens of minutes**; with many sessions people give up halfway | **Seconds** |
| Outcome | "Whatever, I'll just rewrite it" — possibly discarding a deliberate decision | See the discussion and constraints up front, then decide |

### Scenario 4 — The same logic across projects

A utility was copy-pasted into several projects and then diverged. You want to know how another project handled it.

| | Before | After |
|---|---|---|
| How | Switch projects → open that project's history → scroll by time | `Choose Projects`, tick the working directories, search once |
| Time | **Requires switching projects first**; effectively impractical | **Done in the same popup**, results per project kept separate |

### Scenario 5 — Projects that use several agents

The same project has been worked on by Claude Code, Codex, Grok and others, so edits are scattered across providers.

| | Before | After |
|---|---|---|
| How | No idea which agent's history to look at, so you go through them all | Tick the agents you care about and the list refreshes; tick all of them to see everything |
| Time | **Multiplied** (agents × sessions) | **A single query** |

### Summary of the efficiency gain

- **From scrolling to searching** — the entry point moves from "browse sessions by time" to "search sessions by code"; locating a change drops from **minutes to seconds**
- **From guessing to reading** — motivation no longer depends on how well the commit message was written; you go straight back to the conversation
- **No break between rounds** — continuing a feature no longer means re-reading code to rebuild context
- **From single-project to cross-project** — previously impractical, now a multi-select away
- **From single-agent to all-agents** — the cost of looking back becomes additive instead of multiplicative

## 3. What It Does

Select a piece of code in the editor → right-click → **Find AI Edit History**. The plugin lists every session that ever edited that code; one click jumps to the matching point in that conversation.

In one line: **git blame for AI edits.**

## 4. How to Use It

### 4.1 Select code, invoke from the context menu

> 📷 Fig. 1 — **Find AI Edit History** in the editor context menu after selecting code
> <img width="937" height="518" alt="71729614d414aa6fbb4557ef34b1e7f4_compress" src="https://github.com/user-attachments/assets/75715f59-edc0-463f-8fa8-3fe496e88ad3" />


### 4.2 Read the hit list

The popup has three parts:

- **Scope** — the current search scope (the current project by default); `Choose Projects` changes it
- **Agent selector** — Claude Code / Codex / DSH / Grok / Kimi / MiniMax / OMP / OpenCode / Pi.
  Tick an agent to see its edits; the list refreshes immediately
- **Result list** — two lines per entry: file name plus matched line range, and a code preview

> 📷 Fig. 2 — The popup: scope row, agent selector row, and the hit list
> <img width="1200" height="595" alt="38e93832a698c726075d6b27e556e441_compress" src="https://github.com/user-attachments/assets/3790c6df-91ec-443a-8477-bbd5ba5c079d" />

### 4.3 Searching across projects

By default only the current project is searched. Use `Choose Projects` to pick additional working directories.

**Every working directory is an independent entry** — a parent and a child directory do not overlap. `E:\projects2023\crawler` and `E:\projects2023\question\question-crawler` can both be selected, and each returns its own complete set of results.

> 📷 Fig. 2 — The "Select Projects to Search" multi-select dialog
> 

### 4.4 Jumping to the conversation

Double-click a hit. A new tab opens on the right, loads that session, and **scrolls to and highlights** the message containing the edited code.

> 📷 Fig. 4 — The conversation opened in a new tab, scrolled and highlighted to the matching message
> <img width="1134" height="597" alt="28d4852988bfe4237960fe4770416b36_compress" src="https://github.com/user-attachments/assets/ea7aa4a5-0cb6-4aa7-9931-9bc998c1df5b" />


## 5. Key Capabilities

| Capability | Description |
|---|---|
| **Reverse lookup by code** | Select any snippet and find every session that edited it |
| **Multi-agent** | Claude Code / Codex / DSH / Grok / Kimi / MiniMax / OMP / OpenCode / Pi |
| **Precise landing** | Opens the exact message and highlights the edited line |
| **Auto-expand** | If the target sits inside collapsed history, it expands and retries instead of silently doing nothing |
| **Cross-project** | Multi-select projects; results per project stay separate |
| **Background indexing** | Built incrementally when a project opens; progress shown in the status bar; never blocks the UI |
| **Zero configuration** | The data source is CC GUI's own session transcripts — nothing to enable |

## 6. Implementation

### Data source

The index reads **CC GUI / Claude Code's own session transcripts**:

```
~/.claude/projects/<encoded-cwd>/<sessionId>.jsonl
```

Each line is parsed for `Edit` / `Write` tool calls in assistant messages, extracting *file path + changed content + owning session* into a local SQLite database:

```
~/.codemoss/edit_snippets.db
```

**Fully local — no network access, nothing uploaded.**

### Indexing strategy

- **Incremental** — session files are skipped by `mtime + size`, so only changed files are re-scanned
- **Format versioning** — when the index format changes (`PRAGMA user_version`), stale data is dropped and rebuilt
- **Off the EDT** — built on an application-level thread pool, with progress in the status bar ("Indexing AI edits…" / "Indexed N snippets")

### Query and jump

1. The selected code is normalized line by line and matched against the index with `LIKE`
2. Results are ranked by number of matched lines and whether the file is the current one
3. On click, a new tab is opened and `sessionId / provider / cwd / messageId` are passed to the frontend over the bridge
4. The frontend matches rendered message nodes by `data-message-uuid`, then `data-message-id`, expanding collapsed history before falling back

### Files involved

| Area | Files |
|---|---|
| Index | `util/codeindex/` — `EditSnippetExtractor` (extraction), `EditSnippetIndexer` (SQLite), `ClaudeSnippetIndexBuilder` (build), `SnippetIndexHolder` (scheduling), `SnippetSearchService` (search) |
| Interaction | `action/editor/FindAiHistoryAction` (entry point + popup), `ProjectPickerDialog` (project multi-select), `AgentType` (agent enum) |
| Frontend | `webview/src/utils/messageFocus.ts` (locating), `App.tsx` (bridge wiring), `styles/app.less` (highlight styles) |
| Registration | `plugin.xml` — the action and the status bar widget strings |

## 7. Compatibility and Blast Radius

- **No existing code path is modified.** One action and a set of bundle strings are added in `plugin.xml`.
- The index lives in its own database; deleting `~/.codemoss/edit_snippets.db` resets it completely.
- Indexing runs off the EDT, so the only cost at project open is one incremental scan.

## 8. Testing

- **Unit tests** — the `messageFocus` locating logic (UUID priority, id fallback, last resort, tool-block expansion) and `EditSnippetExtractor` / `EditSnippetIndexer` extraction and persistence
- **Build** — `./gradlew buildPlugin` passes, including checkstyle
- **Manual** — verified across a real multi-project setup: cross-project search, agent switching, jumping and highlighting

## 9. Possible Follow-ups (out of scope for this proposal)

- Show a short before/after diff in the search results themselves
- Reverse direction: from a message, mark which files and lines it changed
- Optionally exclude certain projects or sessions from indexing
