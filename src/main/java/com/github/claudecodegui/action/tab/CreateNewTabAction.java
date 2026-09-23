package com.github.claudecodegui.action.tab;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.ui.toolwindow.ClaudeChatWindow;
import com.github.claudecodegui.ui.toolwindow.ClaudeSDKToolWindow;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;


/**
 * Action to create a new chat tab in the CC GUI tool window
 */
public class CreateNewTabAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(CreateNewTabAction.class);

    public CreateNewTabAction() {
        super(
            ClaudeCodeGuiBundle.message("action.createNewTab.text"),
            ClaudeCodeGuiBundle.message("action.createNewTab.description"),
            null
        );
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            LOG.error("[CreateNewTabAction] Project is null");
            return;
        }
        ClaudeChatWindow newChatWindow = ClaudeSDKToolWindow.createNewTab(project);
        if (newChatWindow != null) {
            LOG.info("[CreateNewTabAction] Created new tab");
        } else {
            LOG.error("[CreateNewTabAction] Tool window not found");
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // Only enable this action when there's a valid project
        e.getPresentation().setEnabled(e.getProject() != null);
    }
}
