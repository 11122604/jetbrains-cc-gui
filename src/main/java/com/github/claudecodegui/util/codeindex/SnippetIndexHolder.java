package com.github.claudecodegui.util.codeindex;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.notifications.ClaudeStatusBarWidget;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
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

    /** How long the "indexing" hint stays up; replaced by the result hint when it finishes. */
    private static final long INDEXING_TTL_MS = 60_000L;
    /** How long the completion/failure hint stays up before the widget restores its usual display. */
    private static final long RESULT_TTL_MS = 5_000L;

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
            publishIndexStatus(IndexPhase.STARTED, 0);
            AppExecutorUtil.getAppExecutorService().execute(() -> {
                try {
                    int n = new ClaudeSnippetIndexBuilder(get()).buildAll();
                    if (n > 0) {
                        LOG.info("[EditSnippet] 索引构建完成，新增 " + n + " 条");
                    }
                    publishIndexStatus(IndexPhase.DONE, n);
                } catch (Exception e) {
                    LOG.warn("[EditSnippet] 索引构建失败", e);
                    publishIndexStatus(IndexPhase.FAILED, 0);
                } finally {
                    building.set(false);
                }
            });
        }
    }

    /** Build phase, used to pick the status bar wording. */
    private enum IndexPhase {
        STARTED, DONE, FAILED
    }

    /**
     * Write the index state to the status bar of every open project.
     *
     * <p>Shows "indexing" while the build runs, then the number of newly indexed snippets;
     * both carry a TTL so the status bar widget restores its usual display on its own,
     * and the session status field is never touched.
     */
    private static void publishIndexStatus(IndexPhase phase, int count) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (ApplicationManager.getApplication().isDisposed()) {
                return;
            }
            String text;
            String tooltip;
            long ttlMs;
            switch (phase) {
                case STARTED:
                    text = ClaudeCodeGuiBundle.message("status.indexing");
                    tooltip = ClaudeCodeGuiBundle.message("status.tooltip.indexing");
                    ttlMs = INDEXING_TTL_MS;
                    break;
                case DONE:
                    text = ClaudeCodeGuiBundle.message("status.indexDone", count);
                    tooltip = ClaudeCodeGuiBundle.message("status.tooltip.indexDone");
                    ttlMs = RESULT_TTL_MS;
                    break;
                default:
                    text = ClaudeCodeGuiBundle.message("status.indexFailed");
                    tooltip = ClaudeCodeGuiBundle.message("status.tooltip.indexFailed");
                    ttlMs = RESULT_TTL_MS;
                    break;
            }
            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                ClaudeStatusBarWidget widget = ClaudeStatusBarWidget.Factory.getWidget(project);
                if (widget != null) {
                    widget.show(text, tooltip, ttlMs);
                }
            }
        });
    }
}
