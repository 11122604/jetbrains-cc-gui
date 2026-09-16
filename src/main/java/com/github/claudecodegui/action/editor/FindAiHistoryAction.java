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
import com.intellij.ui.HyperlinkLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 右键「查找 AI 修改历史」：选中代码，搜索历史会话中 Edit/Write 写过或改过这段代码的会话。
 * 默认只搜【当前项目】；弹窗顶部可「选择项目…」勾选其他有 AI 历史的项目后重新搜索。
 * 选中结果后在新标签页打开该历史会话并定位到命中消息；其他项目的会话以只读方式打开。
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
        String basePath = project.getBasePath();

        // 触发后台增量构建（幂等，构建中并发合并）
        SnippetIndexHolder.ensureIndexBuilt();

        // 默认搜索范围：当前项目（流程零额外操作）
        List<String> scope = basePath != null
                ? new ArrayList<>(Collections.singletonList(basePath)) : new ArrayList<>();
        // 即使当前项目无命中也弹窗：空列表 + 顶部「选择项目…」让用户能改范围重搜
        runSearch(project, selectedText, filePath, scope, hits ->
                ApplicationManager.getApplication().invokeLater(() ->
                        showHitListPopup(project, editor, selectedText, filePath, basePath, scope, hits)));
    }

    /** 后台搜索，完成后回调（回调内自行切 EDT）。 */
    private void runSearch(Project project, String selectedText, String filePath, List<String> scope,
                           Consumer<List<JsonObject>> onDone) {
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                EditSnippetIndexer indexer = SnippetIndexHolder.get();
                SnippetSearchService service = new SnippetSearchService(indexer);
                JsonArray results = service.searchAsJson(selectedText, filePath, scope);
                List<JsonObject> hits = new ArrayList<>();
                for (JsonElement el : results) {
                    if (el.isJsonObject()) {
                        hits.add(el.getAsJsonObject());
                    }
                }
                LOG.info("[FindAiHistory] 命中 " + hits.size() + " 条，范围 " + scope);
                onDone.accept(hits);
            } catch (Exception ex) {
                LOG.error("[FindAiHistory] 搜索失败", ex);
                String msg = ex.getMessage() != null ? ex.getMessage() : "未知错误";
                showError(project, msg);
            }
        });
    }

    /** 弹出「范围条 + 命中列表」；范围可改，列表随重搜原地刷新。 */
    private void showHitListPopup(Project project, Editor editor, String selectedText, String filePath,
                                  String basePath, List<String> initialScope, List<JsonObject> initialHits) {
        DefaultListModel<JsonObject> model = new DefaultListModel<>();
        initialHits.forEach(model::addElement);
        // 注：初始可能为空，弹窗仍展示范围条，用户可「选择项目…」重新搜索

        JBList<JsonObject> list = new JBList<>(model);
        list.setCellRenderer(new SnippetCellRenderer());
        list.setFixedCellHeight(ROW_HEIGHT);
        list.setVisibleRowCount(MAX_VISIBLE_ROWS);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.getEmptyText().setText(ClaudeCodeGuiBundle.message("action.findAiHistory.noResult"));

        // 当前结果窗的搜索范围（改范围会整体重开一个新窗，不在此原地变更）
        List<String> scope = new ArrayList<>(initialScope);

        JLabel scopeLabel = new JLabel(buildScopeText(basePath, scope));

        HyperlinkLabel chooseLink = new HyperlinkLabel(
                ClaudeCodeGuiBundle.message("action.findAiHistory.chooseProjects.link"));
        // 模态多选框会抢走焦点、导致当前 JBPopup 自动关闭，因此无法原地刷新旧列表。
        // 改为：选完项目（或取消）后用对应范围重新搜索并弹出一个全新的结果窗。
        chooseLink.addHyperlinkListener(e ->
                chooseProjects(project, basePath, new ArrayList<>(scope), selectedRoots ->
                        reopenWithScope(project, editor, selectedText, filePath, basePath, selectedRoots)));

        JPanel scopeBar = new JPanel(new BorderLayout(8, 0));
        scopeBar.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        scopeBar.add(scopeLabel, BorderLayout.CENTER);
        scopeBar.add(chooseLink, BorderLayout.EAST);

        JBScrollPane scrollPane = new JBScrollPane(list);
        scrollPane.setPreferredSize(new Dimension(620, Math.min(MAX_VISIBLE_ROWS * ROW_HEIGHT + 8, 448)));

        JPanel content = new JPanel(new BorderLayout());
        content.add(scopeBar, BorderLayout.NORTH);
        content.add(scrollPane, BorderLayout.CENTER);

        final JBPopup[] popupHolder = new JBPopup[1];
        Runnable choose = () -> {
            JsonObject hit = list.getSelectedValue();
            if (hit != null) {
                JBPopup p = popupHolder[0];
                if (p != null && p.isVisible()) {
                    p.cancel();
                }
                openHistoryInNewTab(project, hit, basePath);
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
                .createComponentPopupBuilder(content, list)
                .setTitle(ClaudeCodeGuiBundle.message("action.findAiHistory.text"))
                .setRequestFocus(true)
                .setFocusable(true)
                .createPopup();
        popupHolder[0] = popup;
        popup.showInBestPositionFor(editor);
    }

    /** 用新项目范围重新后台搜索，完成后弹一个全新的结果窗。 */
    private void reopenWithScope(Project project, Editor editor, String selectedText, String filePath,
                                 String basePath, List<String> scope) {
        runSearch(project, selectedText, filePath, scope, hits ->
                ApplicationManager.getApplication().invokeLater(() ->
                        showHitListPopup(project, editor, selectedText, filePath, basePath, scope, hits)));
    }

    /** 范围条文案：仅当前项目时显示「当前项目」，否则显示项目数量。 */
    private static String buildScopeText(String basePath, List<String> scope) {
        String prefix = ClaudeCodeGuiBundle.message("action.findAiHistory.scope.label");
        if (scope.size() == 1 && basePath != null
                && ProjectPickerDialog.normalizeKey(scope.get(0))
                        .equals(ProjectPickerDialog.normalizeKey(basePath))) {
            return prefix + " " + ClaudeCodeGuiBundle.message("action.findAiHistory.scope.current");
        }
        return prefix + " " + ClaudeCodeGuiBundle.message(
                "action.findAiHistory.scope.multi", String.valueOf(scope.size()));
    }

    /** 后台取可选项目根（确保含当前项目），EDT 弹多选框，确定后回调所选根。 */
    private void chooseProjects(Project project, String basePath, List<String> currentScope,
                                Consumer<List<String>> onSelected) {
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            List<String> all;
            try {
                all = new ArrayList<>(new SnippetSearchService(SnippetIndexHolder.get()).listProjectRoots());
            } catch (Exception ex) {
                LOG.warn("[FindAiHistory] 读取项目列表失败", ex);
                all = new ArrayList<>();
            }
            if (basePath != null) {
                String baseKey = ProjectPickerDialog.normalizeKey(basePath);
                boolean present = all.stream()
                        .anyMatch(p -> ProjectPickerDialog.normalizeKey(p).equals(baseKey));
                if (!present) {
                    all.add(0, basePath);
                }
            }
            Set<String> selectedKeys = new HashSet<>();
            for (String root : currentScope) {
                selectedKeys.add(ProjectPickerDialog.normalizeKey(root));
            }
            final List<String> allRoots = all;
            final List<String> previousScope = new ArrayList<>(currentScope);
            ApplicationManager.getApplication().invokeLater(() -> {
                ProjectPickerDialog dialog = new ProjectPickerDialog(project, allRoots, selectedKeys);
                if (dialog.showAndGet()) {
                    onSelected.accept(dialog.getSelectedProjects());
                } else {
                    // 取消：结果窗已因失焦关闭，按原范围重开一个，避免界面无反馈
                    onSelected.accept(previousScope);
                }
            });
        });
    }

    /** 会话 cwd 是否位于当前项目根内（根或其子目录）。 */
    private static boolean isWithinProject(String cwd, String basePath) {
        if (cwd == null || cwd.isEmpty() || basePath == null) {
            return false;
        }
        String cwdKey = ProjectPickerDialog.normalizeKey(cwd);
        String baseKey = ProjectPickerDialog.normalizeKey(basePath);
        return cwdKey.equals(baseKey) || cwdKey.startsWith(baseKey + "/");
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

    /** 在新标签页载入命中的历史会话，前端定位到命中消息；其他项目的会话标记只读。 */
    private void openHistoryInNewTab(Project project, JsonObject hit, String basePath) {
        String sessionId = hit.has("sessionId") ? hit.get("sessionId").getAsString() : "";
        String provider = hit.has("provider") ? hit.get("provider").getAsString() : "claude";
        String messageId = hit.has("messageId") ? hit.get("messageId").getAsString() : "";
        String messageIdAlt = hit.has("messageIdAlt") ? hit.get("messageIdAlt").getAsString() : "";
        String matchText = hit.has("matchText") ? hit.get("matchText").getAsString() : "";
        String cwd = hit.has("cwd") && !hit.get("cwd").isJsonNull() ? hit.get("cwd").getAsString() : "";
        String projectDir = hit.has("projectDir") ? hit.get("projectDir").getAsString() : "";
        String projectRoot = hit.has("projectRoot") ? hit.get("projectRoot").getAsString() : "";
        boolean readOnly = cwd != null && !cwd.isEmpty() && !isWithinProject(cwd, basePath);
        // Always load through the SESSION's own directory, never through the message's cwd
        // (often a subdirectory such as …\question-service, which resolves to a directory
        // that does not exist and yields an empty session) nor through the current
        // project's working directory. projectRoot is a real path resolving to that
        // directory, so the file is found and the session keeps a usable working
        // directory; the encoded directory name is only a fallback.
        String loadCwd = !projectRoot.isEmpty() ? projectRoot : projectDir;
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
                if (!messageIdAlt.isEmpty()) {
                    // The webview tries both ids, so it locates the message whichever
                    // node the merge actually produced.
                    payload.addProperty("messageIdAlt", messageIdAlt);
                }
                if (!matchText.isEmpty()) {
                    // Lets the webview scroll to and highlight the exact edited line
                    // inside what may be a very long merged message.
                    payload.addProperty("matchText", matchText);
                }
                // 告知前端是否进入只读态（跨项目会话不允许发消息）
                payload.addProperty("readOnly", readOnly);
                if (!loadCwd.isEmpty()) {
                    payload.addProperty("cwd", loadCwd);
                }
                // 自轮询脚本：等 window.openHistorySession 注册后调用（前端 React 可能尚未挂载）
                String payloadJson = payload.toString();
                String jsCode = "(function(){var p=" + payloadJson + ";var n=0;"
                        + "(function w(){if(window.openHistorySession){window.openHistorySession(JSON.stringify(p));return;}"
                        + "if(++n<=50){setTimeout(w,300);}})();})();";
                ApplicationManager.getApplication().invokeLater(() ->
                        newWindow.executeJavaScriptCode(jsCode));
                LOG.info("[FindAiHistory] 已在新 tab 触发载入会话: " + sessionId
                        + ", messageId=" + messageId
                        + ", cwd=" + cwd
                        + (readOnly ? "（只读/其他项目）" : ""));
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
