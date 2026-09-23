import { memo, useState, useEffect, useLayoutEffect, useRef, useMemo, useCallback, forwardRef, useImperativeHandle } from 'react';
import type { TFunction } from 'i18next';
import type { ClaudeMessage, ClaudeContentBlock, CodexHistoryPageInfo, ToolResultBlock } from '../types';
import { sendBridgeEvent } from '../utils/bridge';
import { MessageItem } from './MessageItem';
import WaitingIndicator from './WaitingIndicator';
import { ContextMenu } from './ContextMenu';
import { useContextMenu, copySelection } from '../hooks/useContextMenu.js';
import { quoteToChatInput } from '../utils/quoteUtils';
import type { MessageListRevealHandle } from './ConversationSearch/types';
import {
  DETAILED_OUTPUT_ENABLED_EVENT,
  getDetailedOutputEnabled,
  type DetailedOutputEnabledChangedDetail,
} from '../utils/detailedOutputPreference';

/** Keep pagination aligned to complete user turns so assistant/tool chains are never split. */
const INITIAL_VISIBLE_TURNS = 5;
const REVEAL_TURN_PAGE_SIZE = 5;
const HISTORY_DISK_PAGE_SIZE = 30;
/** Focus-window mode: messages rendered either side of the focused hit. */
const FOCUS_WINDOW_RADIUS = 5;
/** Focus-window mode: messages added per scroll-triggered expansion. */
const FOCUS_WINDOW_STEP = 20;
/** Distance (px) from a focus-window edge at which the next slice is loaded. */
const FOCUS_WINDOW_ROOT_MARGIN = '150px';

/**
 * True when the message exposes this id. Mirrors the identifiers MessageItem puts
 * on the DOM (data-message-uuid / data-message-id), so a jump target stored from
 * either one resolves to the same node.
 */
function messageCarriesId(message: ClaudeMessage, id: string): boolean {
  const raw = typeof message.raw === 'object' && message.raw !== null
    ? message.raw as Record<string, unknown>
    : null;
  const nested = raw?.message;
  const nestedId = typeof nested === 'object' && nested !== null
    ? (nested as { id?: string }).id
    : undefined;
  return (message as { uuid?: string }).uuid === id
    || raw?.uuid === id
    || (typeof message.id === 'string' && message.id === id)
    || nestedId === id;
}

function isHumanUserMessage(message: ClaudeMessage): boolean {
  if (message.type !== 'user') return false;

  const raw = typeof message.raw === 'object' && message.raw !== null ? message.raw : null;
  const nestedMessage = raw?.message;
  const rawContent = raw?.content ?? (
    typeof nestedMessage === 'object' && nestedMessage !== null ? nestedMessage.content : undefined
  );

  if (Array.isArray(rawContent)) {
    return rawContent.some((block) => block
      && typeof block === 'object'
      && (block.type === 'text' || block.type === 'image'));
  }

  return message.content !== '[tool_result]';
}

function getFirstMessageBoundaryKey(message: ClaudeMessage | undefined): string | undefined {
  if (!message) return undefined;
  if (typeof message.id === 'string') return `id:${message.id}`;
  if (typeof message.raw === 'object' && message.raw !== null && typeof message.raw.uuid === 'string') {
    return `uuid:${message.raw.uuid}`;
  }
  if (message.timestamp) return `timestamp:${message.type}:${message.timestamp}`;
  return `content:${message.type}:${message.content ?? ''}`;
}

function extractToolResultPreview(result: ToolResultBlock | null | undefined): string {
  if (!result) return 'pending';

  let text = '';
  if (typeof result.content === 'string') {
    text = result.content;
  } else if (Array.isArray(result.content)) {
    text = result.content
      .flatMap((item) => (item && typeof item.text === 'string' && item.text ? [item.text] : []))
      .join('\n');
  }

  const preview = text.length > 200 ? text.slice(0, 200) : text;
  return `${result.is_error === true ? 'error' : 'ok'}:${text.length}:${preview}`;
}

function getMessageToolResultSignature(
  message: ClaudeMessage,
  messageIndex: number,
  getContentBlocks: (message: ClaudeMessage) => ClaudeContentBlock[],
  findToolResult: (toolId: string | undefined, messageIndex: number) => ToolResultBlock | null | undefined,
): string {
  const toolUses = getContentBlocks(message).filter(
    (block): block is Extract<ClaudeContentBlock, { type: 'tool_use' }> => block.type === 'tool_use',
  );
  if (toolUses.length === 0) return '';

  return toolUses
    .map((block) => `${block.id ?? 'unknown'}:${extractToolResultPreview(findToolResult(block.id, messageIndex))}`)
    .join('|');
}

interface MessageListProps {
  messages: ClaudeMessage[];
  messageKeys: readonly string[];
  streamingActive: boolean;
  isThinking: boolean;
  loading: boolean;
  loadingStartTime: number | null;
  t: TFunction;
  getMessageText: (message: ClaudeMessage) => string;
  getContentBlocks: (message: ClaudeMessage) => ClaudeContentBlock[];
  findToolResult: (toolId: string | undefined, messageIndex: number) => ToolResultBlock | null | undefined;
  extractMarkdownContent: (message: ClaudeMessage) => string;
  messagesEndRef: React.RefObject<HTMLDivElement | null>;
  onMessageNodeRef?: (id: string, node: HTMLDivElement | null) => void;
  /** Notify parent when the number of collapsed (hidden) messages changes. */
  onCollapsedCountChange?: (count: number) => void;
  onNavigateToProviderSettings?: () => void;
  onNavigateToDependencySettings?: () => void;
  /** Current active provider id; forwarded to MessageItem for streaming-connect label. */
  currentProvider?: string;
  currentSessionId?: string | null;
}

export const MessageList = memo(forwardRef<MessageListRevealHandle, MessageListProps>(function MessageList({
  messages,
  messageKeys,
  streamingActive,
  isThinking,
  loading,
  loadingStartTime,
  t,
  getMessageText,
  getContentBlocks,
  findToolResult,
  extractMarkdownContent,
  messagesEndRef,
  onMessageNodeRef,
  onCollapsedCountChange,
  onNavigateToProviderSettings,
  onNavigateToDependencySettings,
  currentProvider,
  currentSessionId,
}, ref) {
  const [revealedTurnCount, setRevealedTurnCount] = useState(0);
  // Focus-window mode. While `focusAnchor` is set, only a slice of the transcript
  // around that message index is rendered, so jumping to an old hit in a large
  // session does not have to render every message. Left null (the default) the
  // turn-based collapse below behaves exactly as before.
  const [focusAnchor, setFocusAnchor] = useState<number | null>(null);
  const [focusUpExtra, setFocusUpExtra] = useState(0);
  const [focusDownExtra, setFocusDownExtra] = useState(0);
  /**
   * Set once the user has expanded the window all the way to the end of the
   * transcript: from then on the window tracks the tail, so messages arriving
   * during an ongoing conversation still render.
   */
  const [focusFollowTail, setFocusFollowTail] = useState(false);
  const topSentinelRef = useRef<HTMLDivElement | null>(null);
  const bottomSentinelRef = useRef<HTMLDivElement | null>(null);
  /** scrollHeight captured just before an upward expansion, to keep the viewport still. */
  const pendingUpScrollRef = useRef(0);
  const exitFocusWindow = useCallback(() => {
    setFocusAnchor(null);
    setFocusUpExtra(0);
    setFocusDownExtra(0);
    setFocusFollowTail(false);
  }, []);
  const [historyPageInfo, setHistoryPageInfo] = useState<CodexHistoryPageInfo | null>(null);
  const [loadingEarlierHistory, setLoadingEarlierHistory] = useState(false);
  const loadingEarlierHistoryRef = useRef(false);

  // Keep the ref in sync with state. Every writer sets both; the sync lives in
  // an effect because render must stay pure (refs may not be mutated there).
  useEffect(() => {
    loadingEarlierHistoryRef.current = loadingEarlierHistory;
  }, [loadingEarlierHistory]);
  const [detailedOutputEnabled, setDetailedOutputEnabled] = useState(() =>
    getDetailedOutputEnabled()
  );

  // Context menu for message list (copy + quote, when text selected)
  const ctxMenu = useContextMenu();
  const containerRef = useRef<HTMLDivElement | null>(null);

  const handleMessageContextMenu = useCallback((e: React.MouseEvent) => {
    const sel = window.getSelection();
    if (sel && sel.toString().trim().length > 0) {
      ctxMenu.open(e);
    }
  }, [ctxMenu.open]);

  // Hotkey (Ctrl/Cmd+Shift+Q): quote the current selection when it lives inside the message list.
  useEffect(() => {
    const handleQuoteHotkey = (event: KeyboardEvent) => {
      if (event.key.toLowerCase() !== 'q' || !event.shiftKey || !(event.ctrlKey || event.metaKey)) return;
      const sel = window.getSelection();
      const selectedText = sel?.toString() ?? '';
      if (!selectedText.trim()) return;
      const anchor = sel?.anchorNode ?? null;
      const anchorElement = anchor instanceof Element ? anchor : anchor?.parentElement ?? null;
      if (!containerRef.current || !anchorElement || !containerRef.current.contains(anchorElement)) return;
      event.preventDefault();
      quoteToChatInput(selectedText);
    };
    window.addEventListener('keydown', handleQuoteHotkey);
    return () => window.removeEventListener('keydown', handleQuoteHotkey);
  }, []);

  // Session-switch reset as a render-time adjustment (React-sanctioned setState
  // during render): same resets the old prop-change effect performed, without
  // the extra commit. Identity mirrors the old logic — explicit session id
  // when available, else the first message boundary for isolated
  // callers/tests.
  const sessionIdentity = currentSessionId != null
    ? `session:${currentSessionId}`
    : `boundary:${getFirstMessageBoundaryKey(messages[0]) ?? ''}`;
  const [prevSessionIdentity, setPrevSessionIdentity] = useState(sessionIdentity);
  if (prevSessionIdentity !== sessionIdentity) {
    setPrevSessionIdentity(sessionIdentity);
    setRevealedTurnCount(0);
    setLoadingEarlierHistory(false);
    const cached = window.__codexHistoryPageInfo;
    const claudeCached = window.__claudeHistoryPageInfo;
    setHistoryPageInfo(
      currentProvider === 'codex' && cached?.sessionId === currentSessionId ? cached ?? null
        : currentProvider === 'claude' && claudeCached?.sessionId === currentSessionId ? claudeCached ?? null
          : null,
    );
  }

  useEffect(() => {
    const handlePageInfo = (event: Event) => {
      const info = (event as CustomEvent<CodexHistoryPageInfo>).detail;
      if (!info || info.sessionId !== currentSessionId) return;
      // Accept both codex and claude page info events
      if (currentProvider !== 'codex' && currentProvider !== 'claude') return;
      setHistoryPageInfo(info);
      setLoadingEarlierHistory(false);
      loadingEarlierHistoryRef.current = false;
    };
    const handlePageError = (event: Event) => {
      const error = (event as CustomEvent<{ sessionId?: string }>).detail;
      if (!error?.sessionId || error.sessionId === currentSessionId) {
        setLoadingEarlierHistory(false);
        loadingEarlierHistoryRef.current = false;
      }
    };

    window.addEventListener('codex-history-page-info', handlePageInfo);
    window.addEventListener('codex-history-page-error', handlePageError);
    window.addEventListener('claude-history-page-info', handlePageInfo);
    window.addEventListener('claude-history-page-error', handlePageError);
    const cached = window.__codexHistoryPageInfo;
    if (currentProvider === 'codex' && cached?.sessionId === currentSessionId) {
      setHistoryPageInfo(cached ?? null);
    }
    const claudeCached = window.__claudeHistoryPageInfo;
    if (currentProvider === 'claude' && claudeCached?.sessionId === currentSessionId) {
      setHistoryPageInfo(claudeCached ?? null);
    }
    return () => {
      window.removeEventListener('codex-history-page-info', handlePageInfo);
      window.removeEventListener('codex-history-page-error', handlePageError);
      window.removeEventListener('claude-history-page-info', handlePageInfo);
      window.removeEventListener('claude-history-page-error', handlePageError);
    };
  }, [currentProvider, currentSessionId]);

  const userTurnStartIndexes = useMemo(
    () => messages.reduce<number[]>((indexes, message, index) => {
      if (isHumanUserMessage(message)) indexes.push(index);
      return indexes;
    }, []),
    [messages],
  );
  const visibleTurnCount = Math.min(
    userTurnStartIndexes.length,
    INITIAL_VISIBLE_TURNS + revealedTurnCount,
  );
  const hiddenTurnCount = userTurnStartIndexes.length - visibleTurnCount;
  const collapsedCount = hiddenTurnCount > 0 ? userTurnStartIndexes[hiddenTurnCount] : 0;
  const shouldCollapse = collapsedCount > 0;
  const nextTurnCount = Math.min(REVEAL_TURN_PAGE_SIZE, hiddenTurnCount);

  const canLoadEarlierFromDisk = Boolean(
    (currentProvider === 'codex' && historyPageInfo?.sessionId === currentSessionId && historyPageInfo?.hasMore)
    || (currentProvider === 'claude' && historyPageInfo?.sessionId === currentSessionId && historyPageInfo?.hasMore)
  );
  /**
   * Request the next older page of the transcript. Returns whether a request was
   * sent; the in-flight guard keeps concurrent callers (scroll handler and the
   * Find-AI-History jump) from fetching the same page twice.
   */
  const requestEarlierPage = useCallback((): boolean => {
    if (!canLoadEarlierFromDisk || loadingEarlierHistoryRef.current || !currentSessionId || !historyPageInfo) {
      return false;
    }

    loadingEarlierHistoryRef.current = true;
    setLoadingEarlierHistory(true);
    const eventName = currentProvider === 'codex' ? 'load_codex_history_page' : 'load_claude_history_page';
    const sent = sendBridgeEvent(eventName, JSON.stringify({
      sessionId: currentSessionId,
      beforeTurn: historyPageInfo.fromTurn,
    }));
    if (!sent) {
      loadingEarlierHistoryRef.current = false;
      setLoadingEarlierHistory(false);
    }
    return sent;
  }, [canLoadEarlierFromDisk, currentSessionId, historyPageInfo, currentProvider]);

  const handleRevealMore = useCallback(() => {
    if (hiddenTurnCount > 0) {
      setRevealedTurnCount((prev) => prev + REVEAL_TURN_PAGE_SIZE);
      return;
    }
    requestEarlierPage();
  }, [hiddenTurnCount, requestEarlierPage]);

  // ── Focus-window mode ────────────────────────────────────────────────────────
  // Rendered slice while focused: [anchor - radius - upExtra, anchor + radius + downExtra].
  const focusWindow = useMemo(() => {
    if (focusAnchor === null) return null;
    return {
      start: Math.max(0, focusAnchor - FOCUS_WINDOW_RADIUS - focusUpExtra),
      end: focusFollowTail
        ? messages.length
        : Math.min(messages.length, focusAnchor + FOCUS_WINDOW_RADIUS + 1 + focusDownExtra),
    };
  }, [focusAnchor, focusUpExtra, focusDownExtra, focusFollowTail, messages.length]);

  // The scrolling ancestor (ChatScreen owns it) — needed to compensate scrollTop
  // when the window grows upwards, and to detect "scrolled to the end".
  const getScrollContainer = useCallback((): HTMLElement | null => {
    const el = containerRef.current;
    if (!el) return null;
    return (el.closest('.messages-container') as HTMLElement | null) ?? el.parentElement;
  }, []);

  const expandFocusUp = useCallback(() => {
    const container = getScrollContainer();
    // The content above the viewport grows, which would push the anchor down.
    // Remember the pre-expansion height so the layout effect can undo the shift.
    pendingUpScrollRef.current = container ? container.scrollHeight : 0;
    setFocusUpExtra((prev) => prev + FOCUS_WINDOW_STEP);
  }, [getScrollContainer]);

  const expandFocusDown = useCallback(() => {
    setFocusDownExtra((prev) => {
      const next = prev + FOCUS_WINDOW_STEP;
      // Reaching the end switches the window to tail-following, so new messages
      // keep rendering while the conversation continues.
      if (focusAnchor !== null
          && focusAnchor + FOCUS_WINDOW_RADIUS + 1 + next >= messages.length) {
        setFocusFollowTail(true);
      }
      return next;
    });
  }, [focusAnchor, messages.length]);

  useLayoutEffect(() => {
    const heightBefore = pendingUpScrollRef.current;
    if (heightBefore <= 0) return;
    pendingUpScrollRef.current = 0;
    const container = getScrollContainer();
    if (container) {
      container.scrollTop += container.scrollHeight - heightBefore;
    }
  }, [focusUpExtra, getScrollContainer]);

  // Scroll-driven expansion: the sentinels sit just past each edge of the window,
  // so scrolling towards one pulls in the next slice.
  useEffect(() => {
    if (!focusWindow) return undefined;
    const observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        if (entry.target === topSentinelRef.current && focusWindow.start > 0) {
          expandFocusUp();
        } else if (entry.target === bottomSentinelRef.current
            && focusWindow.end < messages.length) {
          expandFocusDown();
        }
      }
    }, { rootMargin: FOCUS_WINDOW_ROOT_MARGIN });
    if (topSentinelRef.current) observer.observe(topSentinelRef.current);
    if (bottomSentinelRef.current) observer.observe(bottomSentinelRef.current);
    return () => observer.disconnect();
  }, [focusWindow, messages.length, expandFocusUp, expandFocusDown]);

  // A different session must never inherit the previous one's window.
  useEffect(() => {
    exitFocusWindow();
  }, [currentSessionId, exitFocusWindow]);

  // Focus mode is deliberately NOT exited automatically: leaving it would restore
  // the turn-based collapse, which renders less than the current window and could
  // hide the very message we just jumped to. It ends on session change, on an
  // explicit revealAll, or implicitly once the user expands to the tail
  // (focusFollowTail keeps that state following new messages).

  // Imperative API so the in-page search can expand everything before scanning.
  // Returns the number of messages that were just revealed (0 when nothing
  // was collapsed). This lets the search panel surface "Expanded N earlier
  // messages" exactly once per panel-open, per the agreed design.
  useImperativeHandle(ref, (): MessageListRevealHandle => ({
    revealAll: () => {
      const previouslyHidden = collapsedCount;
      // Leave focus mode first: a caller asking for "everything" must not keep
      // getting a window. Then reveal all turns as before.
      exitFocusWindow();
      setRevealedTurnCount(userTurnStartIndexes.length);
      return previouslyHidden;
    },
    focusMessage: (messageIds: string | string[]) => {
      const ids = (Array.isArray(messageIds) ? messageIds : [messageIds])
        .filter((id) => typeof id === 'string' && id.length > 0);
      if (ids.length === 0) return false;
      const index = messages.findIndex(
        (message) => ids.some((candidate) => messageCarriesId(message, candidate)),
      );
      if (index < 0) return false;
      setFocusAnchor(index);
      setFocusUpExtra(0);
      setFocusDownExtra(0);
      setFocusFollowTail(false);
      return true;
    },
    loadEarlierPage: requestEarlierPage,
    canLoadEarlierPage: () => canLoadEarlierFromDisk,
  }), [collapsedCount, userTurnStartIndexes.length, messages, exitFocusWindow,
    requestEarlierPage, canLoadEarlierFromDisk]);

  // Notify parent of collapsed count changes (for anchor rail sync)
  useLayoutEffect(() => {
    // In focus mode report what the window hides above it, so the anchor rail
    // still has a meaningful "collapsed" marker to work with.
    onCollapsedCountChange?.(focusWindow ? focusWindow.start : collapsedCount);
  }, [collapsedCount, focusWindow, onCollapsedCountChange]);

  useEffect(() => {
    const handler = (event: Event) => {
      const custom = event as CustomEvent<DetailedOutputEnabledChangedDetail>;
      if (custom.detail && typeof custom.detail.enabled === 'boolean') {
        setDetailedOutputEnabled(custom.detail.enabled);
      }
    };
    window.addEventListener(DETAILED_OUTPUT_ENABLED_EVENT, handler);
    return () => window.removeEventListener(DETAILED_OUTPUT_ENABLED_EVENT, handler);
  }, []);

  // Focus mode renders a slice around the hit; otherwise the turn-based collapse
  // decides. `visibleOffset` maps a rendered item back to its index in `messages`.
  const visibleMessages = useMemo(() => {
    if (focusWindow) return messages.slice(focusWindow.start, focusWindow.end);
    return shouldCollapse ? messages.slice(collapsedCount) : messages;
  }, [messages, shouldCollapse, collapsedCount, focusWindow]);
  const visibleOffset = focusWindow ? focusWindow.start : (shouldCollapse ? collapsedCount : 0);
  return (
    <div ref={containerRef} onContextMenu={handleMessageContextMenu}>
      {ctxMenu.visible && (
        <ContextMenu
          x={ctxMenu.x}
          y={ctxMenu.y}
          onClose={ctxMenu.close}
          items={[
            { label: t('contextMenu.quote', 'Quote'), action: () => quoteToChatInput(ctxMenu.selectedText) },
            { label: t('contextMenu.copy', 'Copy'), action: () => copySelection(ctxMenu.savedRange, ctxMenu.selectedText) },
          ]}
        />
      )}
      {!focusWindow && (shouldCollapse || canLoadEarlierFromDisk) && (
        <div
          className="collapsed-messages-indicator"
          onClick={handleRevealMore}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault();
              handleRevealMore();
            }
          }}
        >
          {loadingEarlierHistory
            ? t('chat.loadingEarlierTurns')
            : shouldCollapse
              ? t('chat.showEarlierTurns', {
                count: nextTurnCount,
                remaining: hiddenTurnCount,
                total: historyPageInfo?.totalTurns,
              })
              : t('chat.loadEarlierTurns', {
                count: Math.min(HISTORY_DISK_PAGE_SIZE, historyPageInfo?.fromTurn ?? 0),
                remaining: historyPageInfo?.fromTurn ?? 0,
                total: historyPageInfo?.totalTurns ?? 0,
              })}
        </div>
      )}

      {focusWindow && focusWindow.start > 0 && (
        <div
          ref={topSentinelRef}
          className="collapsed-messages-indicator"
          onClick={expandFocusUp}
        >
          {t('chat.loadMoreAbove', 'Load earlier messages')}
        </div>
      )}

      {visibleMessages.map((message, visibleIndex) => {
        const messageIndex = visibleIndex + visibleOffset;
        const messageKey = messageKeys[messageIndex];
        const toolResultSignature = getMessageToolResultSignature(message, messageIndex, getContentBlocks, findToolResult);

        return (
          <MessageItem
            key={messageKey}
            message={message}
            messageIndex={messageIndex}
            messageKey={messageKey}
            isLast={messageIndex === messages.length - 1}
            streamingActive={streamingActive}
            isThinking={isThinking}
            t={t}
            getMessageText={getMessageText}
            getContentBlocks={getContentBlocks}
            findToolResult={findToolResult}
            extractMarkdownContent={extractMarkdownContent}
            onNodeRef={onMessageNodeRef}
            onNavigateToProviderSettings={onNavigateToProviderSettings}
            onNavigateToDependencySettings={onNavigateToDependencySettings}
            toolResultSignature={toolResultSignature}
            currentProvider={currentProvider}
            detailedOutputEnabled={detailedOutputEnabled}
          />
        );
      })}

      {focusWindow && focusWindow.end < messages.length && (
        <div
          ref={bottomSentinelRef}
          className="collapsed-messages-indicator"
          onClick={expandFocusDown}
        >
          {t('chat.loadMoreBelow', 'Load later messages')}
        </div>
      )}

      {/* Loading indicator */}
      {loading && <WaitingIndicator startTime={loadingStartTime ?? undefined} />}
      <div ref={messagesEndRef} />
    </div>
  );
}));
