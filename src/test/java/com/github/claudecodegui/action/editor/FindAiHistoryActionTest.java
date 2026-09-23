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
        // Current-file rows are marked by a colour stripe instead of a text tag.
        String title = FindAiHistoryAction.buildItemTitle(
                hit("C:\\work\\BinarySearchDemo.java", "12-15", true, ""));
        assertFalse(title.contains("[当前文件]"));
    }

    @Test
    public void previewIsTruncatedTo80CharsWithEllipsis() {
        String longPreview = "x".repeat(120);
        String preview = FindAiHistoryAction.buildItemPreview(
                hit("a.java", "1-2", false, longPreview));
        assertEquals(81, preview.length()); // 80 chars + ellipsis
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
