package com.github.claudecodegui.util.codeindex;

/**
 * 搜索结果：一条命中的代码片段及其相关度信息。
 *
 * @author luliang
 */
public class SearchHit {

    private static final int SAME_FILE_WEIGHT = 3;

    private final EditSnippet snippet;
    private final int matchedLines;
    private final boolean sameFile;

    public SearchHit(EditSnippet snippet, int matchedLines, boolean sameFile) {
        this.snippet = snippet;
        this.matchedLines = matchedLines;
        this.sameFile = sameFile;
    }

    public EditSnippet getSnippet() {
        return snippet;
    }

    /** 命中的查询行数。 */
    public int getMatchedLines() {
        return matchedLines;
    }

    /** 是否与当前编辑文件一致。 */
    public boolean isSameFile() {
        return sameFile;
    }

    /** 相关度分：命中行数优先，同文件加权。 */
    public int getScore() {
        return matchedLines + (sameFile ? SAME_FILE_WEIGHT : 0);
    }
}
