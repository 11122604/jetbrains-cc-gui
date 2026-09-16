package com.github.claudecodegui.util.codeindex;

/**
 * 一条代码片段索引记录：某个会话的某条消息通过 Edit/Write 工具改动的一段代码。
 *
 * @author luliang
 */
public class EditSnippet {

    private final String provider;
    private final String sessionId;
    private final String messageId;
    /**
     * Second candidate id for locating the rendered node, when it can differ from
     * {@link #messageId}: the webview merges consecutive assistant messages into one
     * node keeping the first message's uuid. Empty when identical/unknown.
     */
    private final String messageIdAlt;
    /** 会话所属工作目录（jsonl 行顶层 cwd），用于按项目过滤；可能为空串。 */
    private final String cwd;
    /** 会话文件所在目录与真实项目根，用于重新打开会话（见 FileContext）。 */
    private final EditSnippetExtractor.FileContext fileContext;
    private final String filePath;
    private final EditSnippetType snippetType;
    private final String snippetText;
    private final String ts;

    public EditSnippet(String provider, String sessionId, String messageId, String messageIdAlt,
                       String cwd, EditSnippetExtractor.FileContext fileContext,
                       String filePath, EditSnippetType snippetType,
                       String snippetText, String ts) {
        this.provider = provider;
        this.sessionId = sessionId;
        this.messageId = messageId;
        this.messageIdAlt = messageIdAlt;
        this.cwd = cwd;
        this.fileContext = fileContext;
        this.filePath = filePath;
        this.snippetType = snippetType;
        this.snippetText = snippetText;
        this.ts = ts;
    }

    public String getProvider() {
        return provider;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getMessageIdAlt() {
        return messageIdAlt;
    }

    public String getCwd() {
        return cwd;
    }

    public String getProjectDir() {
        return fileContext == null ? null : fileContext.getProjectDir();
    }

    public String getProjectRoot() {
        return fileContext == null ? null : fileContext.getProjectRoot();
    }

    public String getFilePath() {
        return filePath;
    }

    public EditSnippetType getSnippetType() {
        return snippetType;
    }

    public String getSnippetText() {
        return snippetText;
    }

    public String getTs() {
        return ts;
    }
}
