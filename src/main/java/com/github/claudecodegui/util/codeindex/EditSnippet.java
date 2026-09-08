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
    private final String filePath;
    private final EditSnippetType snippetType;
    private final String snippetText;
    private final String ts;

    public EditSnippet(String provider, String sessionId, String messageId,
                       String filePath, EditSnippetType snippetType,
                       String snippetText, String ts) {
        this.provider = provider;
        this.sessionId = sessionId;
        this.messageId = messageId;
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
