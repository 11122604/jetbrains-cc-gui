package com.github.claudecodegui.util.codeindex;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.github.claudecodegui.bridge.NodeDetector;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 从 Claude 历史文件（~/.claude/projects/&lt;编码路径&gt;/&lt;sessionId&gt;.jsonl）构建代码片段索引。
 *
 * <p>增量策略：按文件的 mtime+size 跳过未变化的文件（UNIQUE 约束兜底去重）。
 * 应在后台线程调用，避免阻塞 EDT。
 *
 * @author luliang
 */
public class ClaudeSnippetIndexBuilder {

    private static final Logger LOG = Logger.getInstance(ClaudeSnippetIndexBuilder.class);
    private static final String PROVIDER = "claude";
    private static final String TOOL_RESULT = "tool_result";

    private final EditSnippetIndexer indexer;

    public ClaudeSnippetIndexBuilder(EditSnippetIndexer indexer) {
        this.indexer = indexer;
    }

    /**
     * 全量/增量构建：扫描所有 Claude 项目会话文件，仅重新索引内容变化的文件。
     *
     * @return 本次新增索引的片段数
     */
    public int buildAll() {
        Path projectsDir = Paths.get(NodeDetector.resolveHomeForFileOps(), ".claude", "projects");
        if (!Files.isDirectory(projectsDir)) {
            return 0;
        }
        int indexed = 0;
        try (Stream<Path> paths = Files.walk(projectsDir)) {
            List<Path> files = paths.filter(p -> p.toString().endsWith(".jsonl"))
                    .collect(Collectors.toList());
            for (Path file : files) {
                indexed += buildFile(file);
            }
        } catch (IOException e) {
            LOG.warn("[EditSnippet] Failed to walk Claude projects dir", e);
        }
        return indexed;
    }

    private int buildFile(Path file) {
        try {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            long size = Files.size(file);
            if (indexer.isFileIndexed(file.toString(), mtime, size)) {
                return 0;
            }
            int count = 0;
            // The directory the session file lives in (Claude's project key). Needed to
            // reopen the session: a message's own cwd can be a SUBDIRECTORY of that
            // project, and resolving the file from a subdirectory finds nothing.
            Path parent = file.getParent();
            String projectDir = parent != null && parent.getFileName() != null
                    ? parent.getFileName().toString() : null;
            EditSnippetExtractor.FileContext fileContext =
                    new EditSnippetExtractor.FileContext(projectDir, findProjectRootCwd(file, projectDir));
            // The webview merges consecutive assistant messages into ONE rendered node that
            // keeps the first message's uuid, so every snippet in a merge run must be indexed
            // under that run's first uuid. Track it here, mirroring the webview's grouping:
            // tool_result carriers and non-rendered lines keep a run alive, a real user
            // prompt ends it.
            String runFirstUuid = null;
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LineKind kind = classify(line);
                    if (kind == LineKind.ASSISTANT) {
                        if (runFirstUuid == null) {
                            runFirstUuid = topLevelUuid(line);
                        }
                    } else if (kind == LineKind.USER_PROMPT) {
                        runFirstUuid = null;
                    }
                    // TRANSPARENT lines neither start nor end a run.

                    List<EditSnippet> snippets =
                            EditSnippetExtractor.extractLine(line, PROVIDER, runFirstUuid, fileContext);
                    if (!snippets.isEmpty()) {
                        indexer.insertAll(snippets);
                        count += snippets.size();
                    }
                }
            }
            indexer.markFileIndexed(file.toString(), mtime, size);
            return count;
        } catch (Exception e) {
            LOG.warn("[EditSnippet] Failed to index file: " + file, e);
            return 0;
        }
    }

    /**
     * A real path that resolves to {@code projectDir}: the first cwd in the transcript
     * whose Claude project key equals that directory, e.g. the project root
     * {@code E:\projects2023\questionV2} for {@code E--projects2023-questionV2}.
     *
     * <p>Reopening the session with this path both finds the file and keeps a working
     * directory the CLI can actually use; falling back to the directory name would
     * resolve the same file but as a nonsense working directory.
     *
     * @return the path, or null when the transcript contains no such cwd
     */
    private static String findProjectRootCwd(Path file, String projectDir) {
        if (projectDir == null) {
            return null;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String cwd = stringField(parse(line), "cwd");
                if (cwd != null && projectKey(cwd).equals(projectDir)) {
                    return cwd;
                }
            }
        } catch (Exception e) {
            LOG.warn("[EditSnippet] Failed to scan project root for: " + file, e);
        }
        return null;
    }

    /** Claude's project key for a path: every non-alphanumeric character becomes '-'. */
    private static String projectKey(String path) {
        return path.replaceAll("[^a-zA-Z0-9]", "-");
    }

    /** How a jsonl line participates in the webview's assistant-merge grouping. */
    private enum LineKind {
        /** Rendered assistant line: starts or continues a merge run. */
        ASSISTANT,
        /** Rendered user prompt: ends the run. */
        USER_PROMPT,
        /** Does not render (or is a tool_result carrier): transparent to the grouping. */
        TRANSPARENT
    }

    private static LineKind classify(String jsonlLine) {
        JsonObject top = parse(jsonlLine);
        if (top == null) {
            return LineKind.TRANSPARENT;
        }
        // Filtered out before merging, so they cannot break a run.
        if (isTrue(top, "isMeta") || isTrue(top, "isSidechain")) {
            return LineKind.TRANSPARENT;
        }
        String type = stringField(top, "type");
        if ("assistant".equals(type)) {
            return LineKind.ASSISTANT;
        }
        if (!"user".equals(type)) {
            return LineKind.TRANSPARENT;
        }
        return isToolResultOnlyUser(top) ? LineKind.TRANSPARENT : LineKind.USER_PROMPT;
    }

    /** True when the user line carries nothing but tool_result blocks. */
    private static boolean isToolResultOnlyUser(JsonObject top) {
        JsonObject message = objectField(top, "message");
        if (message == null) {
            return false;
        }
        JsonElement contentEl = message.get("content");
        if (contentEl == null || !contentEl.isJsonArray()) {
            return false;
        }
        JsonArray blocks = contentEl.getAsJsonArray();
        if (blocks.isEmpty()) {
            return false;
        }
        for (JsonElement el : blocks) {
            if (!el.isJsonObject()) {
                return false;
            }
            if (!TOOL_RESULT.equals(stringField(el.getAsJsonObject(), "type"))) {
                return false;
            }
        }
        return true;
    }

    /** Top-level {@code uuid} of a line, or null when absent or unparseable. */
    private static String topLevelUuid(String jsonlLine) {
        JsonObject top = parse(jsonlLine);
        return top == null ? null : stringField(top, "uuid");
    }

    private static JsonObject parse(String jsonlLine) {
        if (jsonlLine == null || jsonlLine.trim().isEmpty()) {
            return null;
        }
        try {
            return JsonParser.parseString(jsonlLine).getAsJsonObject();
        } catch (RuntimeException e) {
            // 无效 JSON 行直接忽略，不中断整个文件的扫描
            return null;
        }
    }

    private static JsonObject objectField(JsonObject parent, String name) {
        JsonElement el = parent.get(name);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    private static String stringField(JsonObject parent, String name) {
        JsonElement el = parent.get(name);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private static boolean isTrue(JsonObject parent, String name) {
        JsonElement el = parent.get(name);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()
                && el.getAsBoolean();
    }
}
