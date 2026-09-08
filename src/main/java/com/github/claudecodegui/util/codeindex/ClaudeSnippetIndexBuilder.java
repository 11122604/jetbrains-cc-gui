package com.github.claudecodegui.util.codeindex;

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
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    List<EditSnippet> snippets = EditSnippetExtractor.extractLine(line, PROVIDER);
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
}
