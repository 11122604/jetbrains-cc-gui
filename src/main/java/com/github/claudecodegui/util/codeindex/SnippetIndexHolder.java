package com.github.claudecodegui.util.codeindex;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 代码片段索引的应用级单例 + 后台构建调度。
 *
 * <p>ensureIndexBuilt 幂等：构建中并发调用合并为一次；构建完成后可再次触发增量构建
 * （ClaudeSnippetIndexBuilder 按文件 mtime+size 跳过未变化文件）。
 *
 * @author luliang
 */
public final class SnippetIndexHolder {

    private static final Logger LOG = Logger.getInstance(SnippetIndexHolder.class);

    private static volatile EditSnippetIndexer indexer;
    private static final AtomicBoolean building = new AtomicBoolean(false);

    private SnippetIndexHolder() {
    }

    /** 获取（必要时初始化）索引器。 */
    public static EditSnippetIndexer get() {
        EditSnippetIndexer local = indexer;
        if (local == null) {
            synchronized (SnippetIndexHolder.class) {
                local = indexer;
                if (local == null) {
                    try {
                        local = new EditSnippetIndexer(EditSnippetIndexer.defaultDbPath());
                    } catch (SQLException e) {
                        throw new IllegalStateException("无法打开代码索引数据库", e);
                    }
                    indexer = local;
                }
            }
        }
        return local;
    }

    /** 触发后台增量构建（在 AppExecutor 线程池，不阻塞 EDT）。 */
    public static void ensureIndexBuilt() {
        if (building.compareAndSet(false, true)) {
            AppExecutorUtil.getAppExecutorService().execute(() -> {
                try {
                    int n = new ClaudeSnippetIndexBuilder(get()).buildAll();
                    if (n > 0) {
                        LOG.info("[EditSnippet] 索引构建完成，新增 " + n + " 条");
                    }
                } catch (Exception e) {
                    LOG.warn("[EditSnippet] 索引构建失败", e);
                } finally {
                    building.set(false);
                }
            });
        }
    }
}
