package com.github.claudecodegui.action.editor;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 「选择项目」多选对话框：列出有 AI 编辑历史的项目根，确定后返回勾选的项目路径。
 *
 * @author luliang
 */
public class ProjectPickerDialog extends DialogWrapper {

    private final List<String> allProjects;
    private final Set<String> selectedKeys;
    private final List<JBCheckBox> boxes = new ArrayList<>();

    /**
     * @param allProjects   全部可选项目根（原始路径）
     * @param selectedKeys  默认勾选项的归一化 key 集合（{@link #normalizeKey(String)}）
     */
    public ProjectPickerDialog(@Nullable Project project, List<String> allProjects, Set<String> selectedKeys) {
        super(project, false);
        this.allProjects = new ArrayList<>(allProjects);
        this.selectedKeys = selectedKeys;
        setTitle(ClaudeCodeGuiBundle.message("action.findAiHistory.chooseProjects.title"));
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridLayout(0, 1, 0, 4));
        panel.setBorder(JBUI.Borders.empty(8));
        for (String path : allProjects) {
            JBCheckBox box = new JBCheckBox(path);
            box.setSelected(selectedKeys.contains(normalizeKey(path)));
            boxes.add(box);
            panel.add(box);
        }
        JScrollPane scrollPane = new JScrollPane(panel);
        int height = Math.min(360, 40 + allProjects.size() * 28);
        scrollPane.setPreferredSize(JBUI.size(520, height));
        return scrollPane;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (getSelectedProjects().isEmpty()) {
            return new ValidationInfo(
                    ClaudeCodeGuiBundle.message("action.findAiHistory.chooseProjects.requireOne"));
        }
        return null;
    }

    /** 确定后获取勾选的项目根（原始路径），顺序与传入列表一致。 */
    public List<String> getSelectedProjects() {
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            if (boxes.get(i).isSelected()) {
                selected.add(allProjects.get(i));
            }
        }
        return selected;
    }

    /** 与索引侧一致的路径归一化：反斜杠转正斜杠、小写、去尾斜杠。 */
    static String normalizeKey(String path) {
        if (path == null) {
            return "";
        }
        String key = path.replace('\\', '/').toLowerCase();
        while (key.endsWith("/")) {
            key = key.substring(0, key.length() - 1);
        }
        return key;
    }
}
