package com.github.claudecodegui.util.codeindex;

/**
 * 代码片段索引的类型。
 *
 * @author luliang
 */
public enum EditSnippetType {
    /** Edit 工具修改后的内容（AI 写出的代码）。 */
    NEW_STRING,
    /** Edit 工具修改前的内容（被 AI 改过的代码）。 */
    OLD_STRING,
    /** Write 工具整文件写入的内容。 */
    WRITE_CONTENT
}
