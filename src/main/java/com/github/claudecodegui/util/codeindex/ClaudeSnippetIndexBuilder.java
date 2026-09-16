package com.github.claudecodegui.util.codeindex;

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
            // Claude writes one API message across consecutive jsonl lines that share
            // `message.id` but carry distinct `uuid`s, while the webview renders the group as
            // a single node exposing the first uuid. Track that uuid per group so every line
            // is indexed under the id the DOM actually carries.
            String currentGroupId = null;
            String currentGroupFirstUuid = null;
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String lineGroupId = assistantMessageId(line);
                    if (lineGroupId == null) {
                        currentGroupId = null;
                        currentGroupFirstUuid = null;
                    } else if (!lineGroupId.equals(currentGroupId)) {
                        currentGroupId = lineGroupId;
                        currentGroupFirstUuid = topLevelUuid(line);
                    }

                    List<EditSnippet> snippets =
                            EditSnippetExtractor.extractLine(line, PROVIDER, currentGroupFirstUuid);
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

    /** `message.id` of an assistant line, or null when the line is anything else. */
    private static String assistantMessageId(String jsonlLine) {
        try {
            JsonObject top = JsonParser.parseString(jsonlLine).getAsJsonObject();
            if (!top.has("type") || !"assistant".equals(top.get("type").getAsString())) {
                return null;
            }
            JsonElement messageEl = top.get("message");
            if (messageEl == null || !messageEl.isJsonObject()) {
                return null;
            }
            JsonObject message = messageEl.getAsJsonObject();
            return message.has("id") ? message.get("id").getAsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Top-level `uuid` of a line, or null when absent or unparseable. */
    private static String topLevelUuid(String jsonlLine) {
        try {
            JsonObject top = JsonParser.parseString(jsonlLine).getAsJsonObject();
            return top.has("uuid") ? top.get("uuid").getAsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
