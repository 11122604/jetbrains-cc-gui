package com.github.claudecodegui.util.codeindex;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 Claude 历史 jsonl 行中提取 Edit/Write 工具的代码片段。
 *
 * <p>真实消息结构：assistant 消息的 message.content[] 中包含 tool_use 块，
 * {@code {type, id, name, input}}；Edit 的 input 为
 * {@code {replace_all, file_path, old_string, new_string}}，
 * Write 的 input 为 {@code {file_path, content}}。
 *
 * @author luliang
 */
public final class EditSnippetExtractor {

    /**
     * Per-file context shared by every line of one session transcript.
     *
     * <p>Reopening a session needs the directory the transcript actually lives in: a
     * message's own cwd may be a SUBDIRECTORY of that project, and resolving the file
     * from a subdirectory finds nothing. {@code projectRoot} is a real path whose Claude
     * project key equals that directory (so the file resolves and the session keeps a
     * sane working directory); {@code projectDir} is the directory name itself, used when
     * no such path is present in the transcript.
     */
    public static final class FileContext {
        private final String projectDir;
        private final String projectRoot;

        public FileContext(String projectDir, String projectRoot) {
            this.projectDir = projectDir;
            this.projectRoot = projectRoot;
        }

        public String getProjectDir() {
            return projectDir;
        }

        public String getProjectRoot() {
            return projectRoot;
        }
    }

    /** 单条片段最大长度，防止 Write 整文件写入导致索引膨胀（设计确认 50KB）。 */
    public static final int MAX_SNIPPET_LEN = 50 * 1024;

    private static final String TOOL_EDIT = "Edit";
    private static final String TOOL_WRITE = "Write";

    private EditSnippetExtractor() {
    }

    /**
     * 从一行 jsonl 提取片段。
     * 仅处理 assistant 消息中的 Edit/Write 工具调用；无效 JSON、非工具消息、缺 file_path 均忽略。
     *
     * @param jsonlLine 历史文件的一行；可为 null 或空
     * @param provider  provider 名（如 "claude"）
     */
    public static List<EditSnippet> extractLine(String jsonlLine, String provider) {
        return extractLine(jsonlLine, provider, null, null);
    }

    /**
     * As {@link #extractLine(String, String)}, but stores {@code renderedMessageUuid} as the
     * message id whenever it is non-null.
     *
     * <p>The webview merges consecutive assistant messages into a single rendered node
     * (see {@code buildMergedAssistantMessage} in messageUtils.ts): it combines the content
     * blocks of every message in the merge run and keeps the FIRST message's fields,
     * including its {@code raw.uuid}. A tool_use on a later line of that run therefore
     * renders inside a node identified by the run's first uuid — indexing such a line under
     * its own uuid stores an id no DOM node ever carries, and the jump can never match.
     */
    public static List<EditSnippet> extractLine(String jsonlLine, String provider,
                                                String renderedMessageUuid, FileContext fileContext) {
        List<EditSnippet> result = new ArrayList<>();
        if (jsonlLine == null || jsonlLine.trim().isEmpty()) {
            return result;
        }
        JsonObject top;
        try {
            top = JsonParser.parseString(jsonlLine).getAsJsonObject();
        } catch (RuntimeException e) {
            // 无效 JSON 行直接忽略，不中断整个文件的扫描
            return result;
        }
        if (!top.has("type") || !"assistant".equals(top.get("type").getAsString())) {
            return result;
        }
        JsonElement messageEl = top.get("message");
        if (messageEl == null || !messageEl.isJsonObject()) {
            return result;
        }
        JsonObject message = messageEl.getAsJsonObject();
        JsonElement contentEl = message.get("content");
        if (contentEl == null || !contentEl.isJsonArray()) {
            return result;
        }

        String sessionId = top.has("sessionId") ? top.get("sessionId").getAsString() : "";
        // The webview locates a message by its top-level `uuid` (MessageItem renders it as
        // data-message-uuid); `message.id` is a different identifier and absent on user
        // messages, so storing it made every lookup miss and fall back to the last message.
        //
        // This line's own uuid is the primary id. The merge run's first uuid is kept as an
        // alternative, because the webview may render this line's blocks inside the run's
        // single merged node — the locator tries both, so either grouping works.
        String ownUuid = top.has("uuid") ? top.get("uuid").getAsString()
                : (message.has("id") ? message.get("id").getAsString() : "");
        String altUuid = renderedMessageUuid != null && !renderedMessageUuid.equals(ownUuid)
                ? renderedMessageUuid : "";
        String ts = top.has("timestamp") ? top.get("timestamp").getAsString() : "";
        // 会话工作目录（项目归属）：assistant 行顶层必带 cwd
        String cwd = top.has("cwd") ? top.get("cwd").getAsString() : "";

        JsonArray content = contentEl.getAsJsonArray();
        for (JsonElement el : content) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject block = el.getAsJsonObject();
            if (!"tool_use".equals(block.get("type").getAsString())) {
                continue;
            }
            String toolName = block.has("name") ? block.get("name").getAsString() : "";
            JsonElement inputEl = block.get("input");
            if (inputEl == null || !inputEl.isJsonObject()) {
                continue;
            }
            JsonObject input = inputEl.getAsJsonObject();
            String filePath = input.has("file_path") ? input.get("file_path").getAsString() : "";
            if (filePath.isEmpty()) {
                // 缺 file_path 无法定位代码，跳过该块
                continue;
            }
            if (TOOL_EDIT.equals(toolName)) {
                extractEdit(result, provider, sessionId, ownUuid, altUuid, cwd, fileContext,
                        filePath, input, ts);
            } else if (TOOL_WRITE.equals(toolName)) {
                extractWrite(result, provider, sessionId, ownUuid, altUuid, cwd, fileContext,
                        filePath, input, ts);
            }
        }
        return result;
    }

    private static void extractEdit(List<EditSnippet> result, String provider, String sessionId,
                                    String messageId, String messageIdAlt, String cwd, FileContext ctx,
                                    String filePath, JsonObject input, String ts) {
        String newString = input.has("new_string") ? input.get("new_string").getAsString() : "";
        String oldString = input.has("old_string") ? input.get("old_string").getAsString() : "";
        if (!newString.isEmpty()) {
            result.add(new EditSnippet(provider, sessionId, messageId, messageIdAlt, cwd, ctx,
                    filePath, EditSnippetType.NEW_STRING, truncate(newString), ts));
        }
        if (!oldString.isEmpty()) {
            result.add(new EditSnippet(provider, sessionId, messageId, messageIdAlt, cwd, ctx,
                    filePath, EditSnippetType.OLD_STRING, truncate(oldString), ts));
        }
    }

    private static void extractWrite(List<EditSnippet> result, String provider, String sessionId,
                                     String messageId, String messageIdAlt, String cwd, FileContext ctx,
                                     String filePath, JsonObject input, String ts) {
        String content = input.has("content") ? input.get("content").getAsString() : "";
        if (!content.isEmpty()) {
            result.add(new EditSnippet(provider, sessionId, messageId, messageIdAlt, cwd, ctx,
                    filePath, EditSnippetType.WRITE_CONTENT, truncate(content), ts));
        }
    }

    private static String truncate(String s) {
        if (s == null || s.length() <= MAX_SNIPPET_LEN) {
            return s;
        }
        return s.substring(0, MAX_SNIPPET_LEN);
    }
}
