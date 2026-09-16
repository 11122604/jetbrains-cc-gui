package com.github.claudecodegui.action.editor;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.ui.toolwindow.ClaudeChatWindow;
import com.github.claudecodegui.ui.toolwindow.ClaudeSDKToolWindow;
import com.github.claudecodegui.util.codeindex.EditSnippetIndexer;
import com.github.claudecodegui.util.codeindex.SnippetIndexHolder;
import com.github.claudecodegui.util.codeindex.SnippetSearchService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBList;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 右键「查找 AI 修改历史」：选中代码，搜索历史会话中 Edit/Write 写过或改过这段代码的会话。
 * 搜索结果在【IDE 光标处】弹原生列表（ListPopup），用户选择后在新标签页打开该历史会话，
 * 并定位到命中消息（滚动 + 高亮）。
 *
 * @author luliang
 */
public class FindAiHistoryAction extends AnAction implements DumbAware {

    private static final Logger LOG = Logger.getInstance(FindAiHistoryAction.class);

    /** Max characters of a flattened preview shown on the second row of a popup item. */
    private static final int PREVIEW_MAX_CHARS = 80;

    /** Fixed height (px) of a two-line popup row. */
    private static final int ROW_HEIGHT = 44;

    /** Max rows visible before the popup list starts scrolling. */
    private static final int MAX_VISIBLE_ROWS = 10;

    public FindAiHistoryAction() {
        super(ClaudeCodeGuiBundle.message("action.findAiHistory.text"),
                ClaudeCodeGuiBundle.message("action.findAiHistory.description"), null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) {
            return;
        }
        String selectedText = editor.getSelectionModel().getSelectedText();
        if (selectedText == null || selectedText.trim().isEmpty()) {
            showInfo(project, ClaudeCodeGuiBundle.message("action.findAiHistory.noSelection"));
            return;
        }
        VirtualFile vf = e.getData(CommonDataKeys.VIRTUAL_FILE);
        String filePath = vf != null ? vf.getPath() : null;

        // 触发后台增量构建（幂等，构建中并发合并）
        SnippetIndexHolder.ensureIndexBuilt();

        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                EditSnippetIndexer indexer = SnippetIndexHolder.get();
                SnippetSearchService service = new SnippetSearchService(indexer);
                JsonArray results = service.searchAsJson(selectedText, filePath);
                List<JsonObject> hits = new ArrayList<>();
                for (JsonElement el : results) {
                    if (el.isJsonObject()) {
                        hits.add(el.getAsJsonObject());
                    }
                }
                LOG.info("[FindAiHistory] 命中 " + hits.size() + " 条");
                ApplicationManager.getApplication().invokeLater(() -> showHitListPopup(project, editor, hits));
            } catch (Exception ex) {
                LOG.error("[FindAiHistory] 搜索失败", ex);
                String msg = ex.getMessage() != null ? ex.getMessage() : "未知错误";
                ApplicationManager.getApplication().invokeLater(() -> showError(project, msg));
            }
        });
    }

    /** 在编辑器光标附近弹出命中列表；单击选中高亮，双击或回车确认并在新标签页载入会话。 */
    private void showHitListPopup(Project project, Editor editor, List<JsonObject> hits) {
        if (hits.isEmpty()) {
            showInfo(project, ClaudeCodeGuiBundle.message("action.findAiHistory.noResult"));
            return;
        }
        JBList<JsonObject> list = new JBList<>(hits);
        list.setCellRenderer(new SnippetCellRenderer());
        list.setFixedCellHeight(ROW_HEIGHT);
        list.setVisibleRowCount(MAX_VISIBLE_ROWS);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        final JBPopup[] popupHolder = new JBPopup[1];
        Runnable choose = () -> {
            JsonObject hit = list.getSelectedValue();
            if (hit != null) {
                JBPopup p = popupHolder[0];
                if (p != null && p.isVisible()) {
                    p.cancel();
                }
                openHistoryInNewTab(project, hit);
            }
        };
        // 双击确认
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 2 && e.getButton() == MouseEvent.BUTTON1) {
                    choose.run();
                }
            }
        });
        // 回车确认
        list.registerKeyboardAction(e -> choose.run(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED);

        JBPopup popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(list, list)
                .setTitle(ClaudeCodeGuiBundle.message("action.findAiHistory.text"))
                .setRequestFocus(true)
                .setFocusable(true)
                .createPopup();
        popupHolder[0] = popup;
        popup.showInBestPositionFor(editor);
    }

    /** Popup row, line 1: file name and matched line range. The "current file" case is
     *  conveyed by a colour stripe in the renderer, not by an inline text tag. */
    static String buildItemTitle(JsonObject hit) {
        String filePath = hit.has("filePath") ? hit.get("filePath").getAsString() : "";
        String fileName = filePath;
        int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        if (slash >= 0) {
            fileName = filePath.substring(slash + 1);
        }
        String matched = hit.has("matchedLines") ? hit.get("matchedLines").getAsString() : "";
        return fileName + " · 命中 " + matched + " 行";
    }

    /** Popup row, line 2: flattened code preview, truncated to PREVIEW_MAX_CHARS. */
    static String buildItemPreview(JsonObject hit) {
        String preview = hit.has("preview") ? hit.get("preview").getAsString() : "";
        if (preview.isEmpty()) {
            return "";
        }
        // Normalise CRLF first so a "\r\n" pair collapses to a single space, not two.
        String flat = preview.replace("\r\n", "\n").replace('\r', '\n').replace('\n', ' ');
        if (flat.length() > PREVIEW_MAX_CHARS) {
            flat = flat.substring(0, PREVIEW_MAX_CHARS) + "…";
        }
        return flat;
    }

    /** Two-line row: title (file + matched lines) on top, code preview underneath. */
    private static final class SnippetCellRenderer extends JPanel implements ListCellRenderer<JsonObject> {
        private static final int SAME_FILE_STRIPE_WIDTH = 3;

        private final JLabel titleLabel = new JLabel();
        private final JLabel previewLabel = new JLabel();
        private final JPanel stripe = new JPanel();

        SnippetCellRenderer() {
            setLayout(new BorderLayout(8, 0));
            setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

            stripe.setPreferredSize(new Dimension(SAME_FILE_STRIPE_WIDTH, 0));
            stripe.setOpaque(true);

            JPanel text = new JPanel(new GridLayout(2, 1, 0, 2));
            text.setOpaque(false);

            Font base = UIUtil.getLabelFont();
            titleLabel.setFont(base.deriveFont(Font.PLAIN));
            previewLabel.setFont(base.deriveFont(base.getSize() - 1f));
            previewLabel.setForeground(UIUtil.getContextHelpForeground());

            text.add(titleLabel);
            text.add(previewLabel);
            add(stripe, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends JsonObject> list, JsonObject value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            boolean sameFile = value != null && value.has("sameFile")
                    && value.get("sameFile").getAsBoolean();

            titleLabel.setText(value == null ? "" : buildItemTitle(value));
            previewLabel.setText(value == null ? "" : buildItemPreview(value));

            Color bg = isSelected ? UIUtil.getListSelectionBackground(true) : UIUtil.getListBackground();
            setBackground(bg);
            setOpaque(true);
            stripe.setBackground(sameFile ? UIUtil.getListSelectionBackground(true) : bg);

            Color fg = isSelected ? UIUtil.getListSelectionForeground(true) : UIUtil.getLabelForeground();
            titleLabel.setForeground(fg);
            previewLabel.setForeground(isSelected ? fg : UIUtil.getContextHelpForeground());
            return this;
        }
    }

    /** 在新标签页载入命中的历史会话，前端定位到命中消息。 */
    private void openHistoryInNewTab(Project project, JsonObject hit) {
        String sessionId = hit.has("sessionId") ? hit.get("sessionId").getAsString() : "";
        String provider = hit.has("provider") ? hit.get("provider").getAsString() : "claude";
        String messageId = hit.has("messageId") ? hit.get("messageId").getAsString() : "";
        if (sessionId.isEmpty()) {
            return;
        }
        // 创建 tab 属 Swing UI 操作，必须在 EDT
        ApplicationManager.getApplication().invokeLater(() -> {
            ClaudeChatWindow newWindow = ClaudeSDKToolWindow.createNewTab(project);
            if (newWindow == null) {
                LOG.warn("[FindAiHistory] 创建新 tab 失败（工具窗口不存在）");
                return;
            }
            // 后台等待新 tab 前端真正就绪（frontend_ready），最多约 15s
            AppExecutorUtil.getAppExecutorService().execute(() -> {
                int waited = 0;
                while (waited < 50 && !newWindow.isFrontendReady() && !newWindow.isDisposed()) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    waited++;
                }
                if (newWindow.isDisposed()) {
                    return;
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("sessionId", sessionId);
                payload.addProperty("provider", provider);
                if (!messageId.isEmpty()) {
                    payload.addProperty("messageId", messageId);
                }
                // 自轮询脚本：等 window.openHistorySession 注册后调用（前端 React 可能尚未挂载）
                String payloadJson = payload.toString();
                String jsCode = "(function(){var p=" + payloadJson + ";var n=0;"
                        + "(function w(){if(window.openHistorySession){window.openHistorySession(JSON.stringify(p));return;}"
                        + "if(++n<=50){setTimeout(w,300);}})();})();";
                ApplicationManager.getApplication().invokeLater(() ->
                        newWindow.executeJavaScriptCode(jsCode));
                LOG.info("[FindAiHistory] 已在新 tab 触发载入会话: " + sessionId);
            });
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        boolean visible = editor != null;
        if (editor != null) {
            String selected = editor.getSelectionModel().getSelectedText();
            visible = selected != null && !selected.trim().isEmpty();
        }
        e.getPresentation().setEnabledAndVisible(visible);
    }

    private void showInfo(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(() ->
                com.intellij.openapi.ui.Messages.showInfoMessage(project, message,
                        ClaudeCodeGuiBundle.message("dialog.info.title")));
    }

    private void showError(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(() ->
                com.intellij.openapi.ui.Messages.showErrorDialog(project, message,
                        ClaudeCodeGuiBundle.message("dialog.error.title")));
    }
}
