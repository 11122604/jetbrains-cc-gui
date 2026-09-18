package com.github.claudecodegui.session;

import com.github.claudecodegui.handler.SettingsHandler;
import com.github.claudecodegui.notifications.ClaudeNotifier;
import com.github.claudecodegui.provider.common.SessionHistoryIncompleteException;
import com.github.claudecodegui.provider.common.SessionHistoryNotFoundException;
import com.github.claudecodegui.util.TokenUsageUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Owns session-history loading and post-send message reconciliation.
 */
public class SessionMessageOrchestrator {

    private static final Logger LOG = Logger.getInstance(SessionMessageOrchestrator.class);
    private static final int MAX_UUID_SYNC_RETRIES = 3;

    public interface SessionHistoryAccess {
        List<JsonObject> getProviderSessionMessages(String provider, String sessionId, String cwd);

        JsonObject getLatestClaudeUserMessage(String sessionId, String cwd);
    }

    @FunctionalInterface
    public interface UsageDisplay {
        void show(int usedTokens, int maxTokens);
    }

    private final SessionState state;
    private final MessageParser messageParser;
    private final SessionCallbackFacade callbackFacade;
    private final SessionHistoryAccess historyAccess;
    private final UsageDisplay usageDisplay;
    private final long initialUuidSyncDelayMs;
    private final long uuidRetryDelayMs;

    public SessionMessageOrchestrator(
            Project project,
            SessionState state,
            MessageParser messageParser,
            SessionCallbackFacade callbackFacade,
            SessionHistoryAccess historyAccess
    ) {
        this(
                state,
                messageParser,
                callbackFacade,
                historyAccess,
                (usedTokens, maxTokens) -> {
                    if (project != null) {
                        ClaudeNotifier.setTokenUsage(project, usedTokens, maxTokens);
                    }
                    callbackFacade.notifyUsageUpdate(usedTokens, maxTokens);
                },
                100,
                50
        );
    }

    SessionMessageOrchestrator(
            SessionState state,
            MessageParser messageParser,
            SessionCallbackFacade callbackFacade,
            SessionHistoryAccess historyAccess,
            UsageDisplay usageDisplay,
            long initialUuidSyncDelayMs,
            long uuidRetryDelayMs
    ) {
        this.state = state;
        this.messageParser = messageParser;
        this.callbackFacade = callbackFacade;
        this.historyAccess = historyAccess;
        this.usageDisplay = usageDisplay;
        this.initialUuidSyncDelayMs = initialUuidSyncDelayMs;
        this.uuidRetryDelayMs = uuidRetryDelayMs;
    }

    public CompletableFuture<Void> syncUserMessageUuidsAfterSend() {
        String provider = state.getProvider();
        if ("codex".equals(provider)
                || SessionProviderRouter.isCliProvider(provider)
                || findLatestUnresolvedUserMessage() == null) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            sleep(initialUuidSyncDelayMs);
            updateUserMessageUuids();
        });
    }

    void updateUserMessageUuids() {
        String sessionId = state.getSessionId();
        String cwd = state.getCwd();

        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }

        if (findLatestUnresolvedUserMessage() == null) {
            return;
        }

        for (int attempt = 1; attempt <= MAX_UUID_SYNC_RETRIES; attempt++) {
            try {
                JsonObject latestClaudeUserMessage = historyAccess.getLatestClaudeUserMessage(sessionId, cwd);
                if (latestClaudeUserMessage == null) {
                    if (attempt < MAX_UUID_SYNC_RETRIES) {
                        sleep(uuidRetryDelayMs);
                        continue;
                    }
                    return;
                }

                String patchedContent = null;
                String patchedUuid = null;
                synchronized (state.getMessageStateLock()) {
                    // One lock window covers the patch and the read-back: a non-null
                    // return already guarantees the uuid was stamped.
                    ClaudeSession.Message matchedMessage = patchMatchingUserMessage(latestClaudeUserMessage);
                    if (matchedMessage != null) {
                        patchedContent = matchedMessage.content != null ? matchedMessage.content : "";
                        patchedUuid = matchedMessage.raw.get("uuid").getAsString();
                    }
                }
                if (patchedUuid != null) {
                    callbackFacade.notifyUserMessageUuidPatched(patchedContent, patchedUuid);
                    return;
                }

                if (attempt < MAX_UUID_SYNC_RETRIES) {
                    sleep(uuidRetryDelayMs);
                }
            } catch (Exception e) {
                LOG.warn("[Rewind] Failed to update user message UUIDs (attempt " + attempt + "): " + e.getMessage());
                if (attempt < MAX_UUID_SYNC_RETRIES) {
                    sleep(uuidRetryDelayMs);
                }
            }
        }
    }

    public CompletableFuture<Void> loadFromServer() {
        String requestedSessionId = state.getSessionId();
        if (requestedSessionId == null) {
            return CompletableFuture.completedFuture(null);
        }
        String requestedCwd = state.getCwd();
        String requestedProvider = state.getProvider();
        Object loadingToken = new Object();
        List<ClaudeSession.Message> messagesBeforeLoad;
        synchronized (state.getMessageStateLock()) {
            messagesBeforeLoad = state.getMessages();
            state.claimLoading(loadingToken);
            callbackFacade.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
        }

        return CompletableFuture.runAsync(() -> {
            try {
                LOG.info("Loading session from server: sessionId=" + requestedSessionId + ", cwd=" + requestedCwd);
                List<JsonObject> serverMessages = historyAccess.getProviderSessionMessages(
                        requestedProvider, requestedSessionId, requestedCwd);
                if (serverMessages == null) {
                    throw new IllegalStateException("Session history provider returned no response");
                }

                LOG.debug("Received " + serverMessages.size() + " messages from server");
                List<ClaudeSession.Message> loadedMessages = new ArrayList<>(serverMessages.size());
                for (JsonObject msg : serverMessages) {
                    ClaudeSession.Message message = messageParser.parseServerMessage(msg);
                    if (message != null) {
                        loadedMessages.add(message);
                    }
                }

                List<ClaudeSession.Message> callbackMessages;
                // Measure the freshly parsed history before taking the state lock:
                // the list is thread-local to this load, so its structural walk must
                // not extend the lock window that streaming callbacks contend on.
                Set<String> loadedStructure = MessageStructure.structuralBlockKeys(loadedMessages);
                synchronized (state.getMessageStateLock()) {
                    if (!ownsHistoryLoad(loadingToken, requestedSessionId, requestedCwd, requestedProvider)) {
                        LOG.info("Ignoring history result for a session that changed while loading");
                        return;
                    }
                    List<ClaudeSession.Message> currentMessages = state.getMessagesReference();
                    // A new row added during this read belongs to newer live work.
                    // Metadata patches keep the same row identities and remain valid.
                    if (!currentMessages.equals(messagesBeforeLoad)) {
                        return;
                    }
                    int liveHistoryBacked = countHistoryBackedMessages(currentMessages);
                    if (liveHistoryBacked > 0 && loadedMessages.size() < liveHistoryBacked) {
                        LOG.warn("Ignoring stale shorter history result: loaded="
                                + loadedMessages.size() + ", live=" + liveHistoryBacked);
                        return;
                    }
                    if (!historyPreservesCurrentStructure(loadedStructure, currentMessages)) {
                        LOG.warn("Ignoring history result that would remove live structural blocks");
                        return;
                    }

                    // Replace only after the complete response has been parsed and all
                    // ownership checks pass. A failed or partial read must never clear
                    // the live list first and leave the UI with a shorter transcript.
                    state.replaceMessages(loadedMessages);
                    state.setError(null);
                    callbackMessages = state.getMessagesSnapshot();
                    restoreTokenUsage(serverMessages);
                    callbackFacade.notifyMessageUpdate(callbackMessages);
                }
            } catch (SessionHistoryNotFoundException e) {
                // A missing history file is an explicit stale-session signal, so unlike
                // the stale-result guards above it clears the live transcript.
                synchronized (state.getMessageStateLock()) {
                    if (!ownsHistoryLoad(loadingToken, requestedSessionId, requestedCwd, requestedProvider)) {
                        return;
                    }
                    state.setSessionId(null);
                    state.clearMessages();
                    state.setError(null);
                    callbackFacade.notifyMessageUpdate(state.getMessagesSnapshot());
                }
                LOG.warn("Session history is unavailable; cleared stale session ID: " + e.getMessage());
            } catch (SessionHistoryIncompleteException e) {
                synchronized (state.getMessageStateLock()) {
                    if (!ownsHistoryLoad(loadingToken, requestedSessionId, requestedCwd, requestedProvider)) {
                        return;
                    }
                    // An initial history open has no live transcript to keep. Let
                    // its caller offer a retry instead of reporting an empty success.
                    if (state.getMessagesReference().isEmpty()) {
                        throw new CompletionException(e);
                    }
                }
                LOG.info("Session history is still being written; keeping the live transcript: "
                        + e.getMessage());
            } catch (Exception e) {
                synchronized (state.getMessageStateLock()) {
                    if (!ownsHistoryLoad(loadingToken, requestedSessionId, requestedCwd, requestedProvider)) {
                        return;
                    }
                    state.setError(e.getMessage());
                }
                LOG.error("Error loading session: " + e.getMessage(), e);
                throw new CompletionException(e);
            } finally {
                synchronized (state.getMessageStateLock()) {
                    if (state.releaseLoading(loadingToken)) {
                        callbackFacade.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
                    }
                }
            }
        });
    }

    /**
     * Count the live messages a history read can legitimately reproduce.
     *
     * <p>The staleness guard compares the loaded history against the live list, but
     * the live list also carries locally-synthesized rows that are never persisted:
     * an ERROR bubble added by a failed turn, and a SYSTEM notice. Counting them
     * would make the history permanently shorter than the live list, so the guard
     * would reject every later reload and the failed turn's error bubble would
     * never clear. A history read can only ever produce user/assistant rows, so
     * only those are counted.</p>
     *
     * @param messages live transcript
     * @return how many messages a history read could reproduce
     */
    private static int countHistoryBackedMessages(List<ClaudeSession.Message> messages) {
        int count = 0;
        for (ClaudeSession.Message message : messages) {
            if (message.type == ClaudeSession.Message.Type.USER
                    || message.type == ClaudeSession.Message.Type.ASSISTANT) {
                count++;
            }
        }
        return count;
    }

    private boolean ownsHistoryLoad(Object token, String sessionId, String cwd, String provider) {
        return state.ownsLoading(token)
                && Objects.equals(sessionId, state.getSessionId())
                && Objects.equals(cwd, state.getCwd())
                && Objects.equals(provider, state.getProvider());
    }

    /**
     * Reject missing live structure, but allow persisted payloads to be normalized.
     * Serialized size cannot distinguish a truncated block from a valid shorter one.
     */
    private static boolean historyPreservesCurrentStructure(
            Set<String> loadedStructure,
            List<ClaudeSession.Message> currentMessages
    ) {
        return loadedStructure.containsAll(MessageStructure.structuralBlockKeys(currentMessages));
    }

    /**
     * Stamp the matching unresolved local user message with the history row uuid.
     * Caller must hold the message-state lock: this walks and mutates the live list.
     */
    private ClaudeSession.Message patchMatchingUserMessage(JsonObject historyMessage) {
        if (!historyMessage.has("type") || !"user".equals(historyMessage.get("type").getAsString())) {
            return null;
        }
        if (!historyMessage.has("uuid") || historyMessage.get("uuid").isJsonNull()) {
            return null;
        }

        String historyContent = extractMessageContentForMatching(historyMessage);
        if (historyContent == null || historyContent.isEmpty()) {
            return null;
        }

        String uuid = historyMessage.get("uuid").getAsString();
        List<ClaudeSession.Message> localMessages = state.getMessagesReference();
        for (int i = localMessages.size() - 1; i >= 0; i--) {
            ClaudeSession.Message localMsg = localMessages.get(i);
            if (localMsg.type != ClaudeSession.Message.Type.USER) {
                continue;
            }
            if (localMsg.raw != null && localMsg.raw.has("uuid") && !localMsg.raw.get("uuid").isJsonNull()) {
                continue;
            }
            if (!historyContent.equals(localMsg.content)) {
                continue;
            }

            if (localMsg.raw == null) {
                localMsg.raw = createDefaultUserRaw(localMsg.content);
            }
            localMsg.raw.addProperty("uuid", uuid);
            return localMsg;
        }

        return null;
    }

    static JsonObject createDefaultUserRaw(String content) {
        JsonObject raw = new JsonObject();
        JsonObject message = new JsonObject();
        JsonArray contentArray = new JsonArray();
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", content != null ? content : "");
        contentArray.add(textBlock);
        message.add("content", contentArray);
        raw.add("message", message);
        return raw;
    }

    /**
     * Return the newest user message still missing a uuid. Self-locking so the
     * lockless pre-checks at the call sites — which merely decide whether the
     * async uuid sync is worth starting — stay race-free against concurrent
     * message appends.
     */
    private ClaudeSession.Message findLatestUnresolvedUserMessage() {
        synchronized (state.getMessageStateLock()) {
            List<ClaudeSession.Message> messages = state.getMessagesReference();
            for (int i = messages.size() - 1; i >= 0; i--) {
                ClaudeSession.Message message = messages.get(i);
                if (message.type != ClaudeSession.Message.Type.USER) {
                    continue;
                }
                if (message.content == null || message.content.isEmpty() || "[tool_result]".equals(message.content)) {
                    continue;
                }
                if (message.raw == null) {
                    return message;
                }
                if (!message.raw.has("uuid") || message.raw.get("uuid").isJsonNull()) {
                    return message;
                }
            }
            return null;
        }
    }

    String extractMessageContentForMatching(JsonObject msg) {
        if (!msg.has("message") || !msg.get("message").isJsonObject()) {
            return null;
        }
        JsonObject message = msg.getAsJsonObject("message");
        if (!message.has("content")) {
            return null;
        }

        JsonElement contentElement = message.get("content");
        if (contentElement.isJsonPrimitive()) {
            return contentElement.getAsString();
        }

        if (contentElement.isJsonArray()) {
            JsonArray contentArray = contentElement.getAsJsonArray();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < contentArray.size(); i++) {
                JsonElement element = contentArray.get(i);
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject block = element.getAsJsonObject();
                if (block.has("type") && "text".equals(block.get("type").getAsString()) && block.has("text")) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(block.get("text").getAsString());
                }
            }
            return sb.toString();
        }

        return null;
    }

    private void restoreTokenUsage(List<JsonObject> serverMessages) {
        try {
            JsonObject lastUsage = TokenUsageUtils.findLastUsageFromRawMessages(serverMessages, state.getProvider());
            if (lastUsage == null) {
                return;
            }

            int usedTokens = TokenUsageUtils.extractContextTokens(lastUsage, state.getProvider());
            int fallbackMaxTokens = SettingsHandler.getModelContextLimit(
                    state.getProvider(), state.getModel());
            int maxTokens = TokenUsageUtils.extractMaxTokens(lastUsage, fallbackMaxTokens);
            usageDisplay.show(usedTokens, maxTokens);
            LOG.debug("Restored token usage from history: " + usedTokens + " / " + maxTokens);
        } catch (Exception e) {
            LOG.warn("Failed to extract token usage from history: " + e.getMessage());
        }
    }

    private void sleep(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
