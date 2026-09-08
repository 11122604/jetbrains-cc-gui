package com.github.claudecodegui.util.codeindex;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.sql.SQLException;
import java.util.List;

/**
 * 代码 → 会话 搜索服务（G0）。
 * 将选中的代码片段归一化拆行后交给索引器搜索，输出前端可用的 JSON。
 *
 * @author luliang
 */
public class SnippetSearchService {

    /** 结果中单条片段的预览字符数。 */
    private static final int PREVIEW_LEN = 200;

    private final EditSnippetIndexer indexer;

    public SnippetSearchService(EditSnippetIndexer indexer) {
        this.indexer = indexer;
    }

    /**
     * 按选中代码搜索历史会话，返回按相关度排序的命中 JSON 数组。
     *
     * @param selectedCode     编辑器选中的代码
     * @param currentFilePath  当前编辑文件路径（用于同文件加权），可为 null
     */
    public JsonArray searchAsJson(String selectedCode, String currentFilePath) throws SQLException {
        List<String> lines = EditSnippetIndexer.normalizeLines(selectedCode);
        List<SearchHit> hits = indexer.search(lines, currentFilePath);
        JsonArray arr = new JsonArray();
        for (SearchHit hit : hits) {
            arr.add(toJson(hit));
        }
        return arr;
    }

    private JsonObject toJson(SearchHit hit) {
        EditSnippet snippet = hit.getSnippet();
        JsonObject o = new JsonObject();
        o.addProperty("provider", snippet.getProvider());
        o.addProperty("sessionId", snippet.getSessionId());
        o.addProperty("messageId", snippet.getMessageId());
        o.addProperty("filePath", snippet.getFilePath());
        o.addProperty("snippetType", snippet.getSnippetType().name());
        o.addProperty("matchedLines", hit.getMatchedLines());
        o.addProperty("sameFile", hit.isSameFile());
        o.addProperty("score", hit.getScore());
        o.addProperty("preview", preview(snippet.getSnippetText()));
        return o;
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        int len = Math.min(text.length(), PREVIEW_LEN);
        return text.substring(0, len);
    }
}
