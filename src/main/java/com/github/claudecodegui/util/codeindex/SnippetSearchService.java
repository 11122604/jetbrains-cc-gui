package com.github.claudecodegui.util.codeindex;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.sql.SQLException;
import java.util.Collection;
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

    /** 全局搜索（不限项目）。 */
    public JsonArray searchAsJson(String selectedCode, String currentFilePath) throws SQLException {
        return searchAsJson(selectedCode, currentFilePath, null);
    }

    /**
     * 按选中代码搜索历史会话，返回按相关度排序的命中 JSON 数组。
     *
     * @param selectedCode     编辑器选中的代码
     * @param currentFilePath  当前编辑文件路径（用于同文件加权），可为 null
     * @param projectRoots     限定的项目根路径集合；null/空表示全局
     */
    public JsonArray searchAsJson(String selectedCode, String currentFilePath,
                                  Collection<String> projectRoots) throws SQLException {
        List<String> lines = EditSnippetIndexer.normalizeLines(selectedCode);
        List<SearchHit> hits = indexer.search(lines, currentFilePath, projectRoots);
        JsonArray arr = new JsonArray();
        for (SearchHit hit : hits) {
            arr.add(toJson(hit));
        }
        return arr;
    }

    /** 列出有 AI 编辑历史的项目根路径。 */
    public List<String> listProjectRoots() throws SQLException {
        return indexer.listProjectRoots();
    }

    private JsonObject toJson(SearchHit hit) {
        EditSnippet snippet = hit.getSnippet();
        JsonObject o = new JsonObject();
        o.addProperty("provider", snippet.getProvider());
        o.addProperty("sessionId", snippet.getSessionId());
        o.addProperty("messageId", snippet.getMessageId());
        // Second locator candidate: whichever of the two ids a rendered node carries,
        // the frontend can find it (see EditSnippetExtractor).
        if (snippet.getMessageIdAlt() != null && !snippet.getMessageIdAlt().isEmpty()) {
            o.addProperty("messageIdAlt", snippet.getMessageIdAlt());
        }
        o.addProperty("cwd", snippet.getCwd());
        // 会话文件所在目录 / 真实项目根：加载会话时用它解析路径
        // （消息自身的 cwd 可能是项目子目录，直接拿它拼路径会找不到文件）
        if (snippet.getProjectDir() != null && !snippet.getProjectDir().isEmpty()) {
            o.addProperty("projectDir", snippet.getProjectDir());
        }
        if (snippet.getProjectRoot() != null && !snippet.getProjectRoot().isEmpty()) {
            o.addProperty("projectRoot", snippet.getProjectRoot());
        }
        o.addProperty("filePath", snippet.getFilePath());
        o.addProperty("snippetType", snippet.getSnippetType().name());
        o.addProperty("matchedLines", hit.getMatchedLines());
        o.addProperty("sameFile", hit.isSameFile());
        o.addProperty("score", hit.getScore());
        // 命中的代码行原文：跳转后用它精确定位并高亮该行
        if (hit.getMatchText() != null && !hit.getMatchText().isEmpty()) {
            o.addProperty("matchText", hit.getMatchText());
        }
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
