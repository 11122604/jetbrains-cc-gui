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
        String messageId = message.has("id") ? message.get("id").getAsString() : "";
        String ts = top.has("timestamp") ? top.get("timestamp").getAsString() : "";

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
                extractEdit(result, provider, sessionId, messageId, filePath, input, ts);
            } else if (TOOL_WRITE.equals(toolName)) {
                extractWrite(result, provider, sessionId, messageId, filePath, input, ts);
            }
        }
        return result;
    }

    private static void extractEdit(List<EditSnippet> result, String provider, String sessionId,
                                    String messageId, String filePath, JsonObject input, String ts) {
        String newString = input.has("new_string") ? input.get("new_string").getAsString() : "";
        String oldString = input.has("old_string") ? input.get("old_string").getAsString() : "";
        if (!newString.isEmpty()) {
            result.add(new EditSnippet(provider, sessionId, messageId, filePath,
                    EditSnippetType.NEW_STRING, truncate(newString), ts));
        }
        if (!oldString.isEmpty()) {
            result.add(new EditSnippet(provider, sessionId, messageId, filePath,
                    EditSnippetType.OLD_STRING, truncate(oldString), ts));
        }
    }

    private static void extractWrite(List<EditSnippet> result, String provider, String sessionId,
                                     String messageId, String filePath, JsonObject input, String ts) {
        String content = input.has("content") ? input.get("content").getAsString() : "";
        if (!content.isEmpty()) {
            result.add(new EditSnippet(provider, sessionId, messageId, filePath,
                    EditSnippetType.WRITE_CONTENT, truncate(content), ts));
        }
    }

    private static String truncate(String s) {
        if (s == null || s.length() <= MAX_SNIPPET_LEN) {
            return s;
        }
        return s.substring(0, MAX_SNIPPET_LEN);
    }
}
