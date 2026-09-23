package com.github.claudecodegui.startup;

import com.github.claudecodegui.util.codeindex.SnippetIndexHolder;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;

/**
 * 项目启动后台预热代码片段索引（G0），避免首次右键「查找 AI 修改历史」时等待构建。
 * 构建跑在 AppExecutor 线程池（SnippetIndexHolder 内部），不阻塞启动。
 *
 * @author luliang
 */
public class SnippetIndexPreloader implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(SnippetIndexPreloader.class);

    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        // 幂等触发后台增量构建（已有 buildStarted 去重）
        SnippetIndexHolder.ensureIndexBuilt();
        LOG.debug("[EditSnippet] 启动预热已触发");
        return Unit.INSTANCE;
    }
}
