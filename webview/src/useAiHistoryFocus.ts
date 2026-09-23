import { useEffect, useRef } from 'react';
import type { MutableRefObject } from 'react';
import type { MessageListRevealHandle } from './components/ConversationSearch/types';
import {
  expandCollapsedToolBlocks,
  findExactTarget,
  findFallbackTarget,
  findSnippetElement,
  findToolContentElement,
} from './utils/messageFocus';

/** Tuning for the "jump to a Find AI Edit History hit" effect. */
const POLL_INTERVAL_MS = 200;
/** Hard cap on locating the message before falling back. */
const MAX_POLL_MS = 60_000;
/** Give up after the DOM has stopped growing for this many polls. */
const STABLE_POLLS = 20;
/** Polls to wait before asking the list to open a window around the hit. */
const REVEAL_AFTER_ATTEMPTS = 3;
/**
 * How long the located message/line stays highlighted. Generous on purpose: the
 * jump also expands a long tool block and scrolls it into view, so the highlight
 * must outlast reading the surrounding code, not just the scroll animation.
 */
const HIGHLIGHT_MS = 5 * 60 * 1000;
/** Time allowed for the smooth jump before resuming normal scroll handling. */
const SCROLL_SETTLE_MS = 800;
/** Retry cadence while collapsed Edit blocks re-render their diff lines. */
const LINE_RETRY_MS = 400;
/** How many times to retry locating the matched code line. */
const LINE_MAX_ATTEMPTS = 8;

/** Payload injected by Java when a history hit is opened. Mirrors the backend object. */
interface OpenHistoryHitPayload {
  sessionId: string;
  provider: string;
  messageId?: string;
  messageIdAlt?: string;
  matchText?: string;
  /** Path of the edited file, used when the matched line itself is not rendered. */
  filePath?: string;
  cwd?: string;
  readOnly?: boolean;
}

export interface UseAiHistoryFocusOptions {
  messages: { length: number };
  messagesContainerRef: MutableRefObject<HTMLDivElement | null>;
  messageListRef: MutableRefObject<MessageListRevealHandle | null>;
  isAutoScrollingRef: MutableRefObject<boolean>;
  userPausedRef: MutableRefObject<boolean>;
  loadHistorySession: (
    sessionId: string,
    provider?: string,
    model?: string,
    agent?: string,
    options?: { cwd?: string; readOnly?: boolean; fullHistory?: boolean },
  ) => void;
}

/**
 * Wires the "Find AI Edit History" jump.
 *
 * Java calls {@code window.openHistorySession} once the new tab is ready. That
 * handler loads the (possibly cross-project) session, then an effect locates the
 * rendered message - opening a small window around the hit instead of expanding
 * the whole transcript - and finally highlights the exact edited line.
 */
export const useAiHistoryFocus = ({
  messages,
  messagesContainerRef,
  messageListRef,
  isAutoScrollingRef,
  userPausedRef,
  loadHistorySession,
}: UseAiHistoryFocusOptions): void => {
  // Candidate ids for the message and the exact edited line, captured from the
  // Java payload and consumed by the locating effect.
  const pendingIdsRef = useRef<string[] | null>(null);
  const pendingTextRef = useRef<string | null>(null);
  const pendingFileRef = useRef<string | null>(null);

  // Receive the Java-driven "open this hit" signal. Registered once; the payload
  // may arrive before this effect would otherwise run, so the bootstrap in
  // main.tsx's __pendingOpenHistorySession also flows through here.
  useEffect(() => {
    const handler = (json: string) => {
      let req: OpenHistoryHitPayload;
      try {
        req = JSON.parse(json) as OpenHistoryHitPayload;
      } catch {
        return;
      }
      const candidates = [req.messageId, req.messageIdAlt]
        .filter((id): id is string => typeof id === 'string' && id.length > 0);
      pendingIdsRef.current = candidates.length > 0 ? candidates : null;
      pendingTextRef.current =
        typeof req.matchText === 'string' && req.matchText.trim() ? req.matchText : null;
      pendingFileRef.current =
        typeof req.filePath === 'string' && req.filePath.trim() ? req.filePath : null;

      loadHistorySession(
        req.sessionId,
        req.provider,
        undefined,
        undefined,
        {
          ...(req.cwd ? { cwd: req.cwd } : {}),
          readOnly: req.readOnly === true,
          // Load the whole transcript: history is paged, and the hit may be older than
          // the newest page, so a partial load would leave the target unlocatable.
          fullHistory: true,
        },
      );
    };

    window.openHistorySession = handler;
    const pending = window.__pendingOpenHistorySession;
    if (pending) {
      handler(pending);
      window.__pendingOpenHistorySession = undefined;
    }
    return () => {
      if (window.openHistorySession === handler) {
        window.openHistorySession = undefined;
      }
    };
  }, [loadHistorySession]);

  // Locate the rendered message once the session finishes loading, then the line.
  useEffect(() => {
    const targetIds = pendingIdsRef.current;
    if (!targetIds || targetIds.length === 0) return undefined;

    let attempts = 0;
    let windowOpened = false;
    let waitingForPage = false;
    let lastNodeCount = -1;
    let stablePolls = 0;
    const startedAt = Date.now();
    let timer = 0;

    const highlightLine = (node: HTMLElement) => {
      node.scrollIntoView({ block: 'center', behavior: 'smooth' });
      node.classList.add('ai-focus-line');
      window.setTimeout(() => node.classList.remove('ai-focus-line'), HIGHLIGHT_MS);
    };

    const focusSnippetLine = (messageNode: HTMLElement, text: string, attempt: number) => {
      // Expand BEFORE searching. A collapsed Generic block keeps its content in the
      // DOM at zero height, so searching first would match that invisible text and
      // return - leaving the block collapsed with nothing visibly highlighted.
      if (attempt === 0) {
        expandCollapsedToolBlocks(messageNode);
      }

      const found = findSnippetElement(messageNode, text);
      if (found) {
        highlightLine(found);
        return;
      }
      if (attempt < LINE_MAX_ATTEMPTS) {
        window.setTimeout(
          () => focusSnippetLine(messageNode, text, attempt + 1),
          LINE_RETRY_MS,
        );
        return;
      }
      // The line itself is not rendered: a tool parameter is capped, so a match past
      // that cap never reaches the DOM. Fall back to the block holding the file.
      const content = findToolContentElement(messageNode, pendingFileRef.current ?? '');
      if (content) {
        highlightLine(content);
      }
    };

    const focus = (node: HTMLElement) => {
      // Jumping to an old hit leaves the live tail; mark it auto-scroll + pause
      // following so useScrollBehavior's scroll-to-bottom cannot snap the view
      // back once the session finishes loading.
      isAutoScrollingRef.current = true;
      userPausedRef.current = true;
      node.scrollIntoView({ block: 'center', behavior: 'smooth' });
      node.classList.add('ai-focus-highlight');
      window.setTimeout(
        () => node.classList.remove('ai-focus-highlight'),
        HIGHLIGHT_MS,
      );
      window.setTimeout(() => {
        isAutoScrollingRef.current = false;
      }, SCROLL_SETTLE_MS);
      pendingIdsRef.current = null;

      const snippetText = pendingTextRef.current;
      if (snippetText) {
        pendingTextRef.current = null;
        focusSnippetLine(node, snippetText, 0);
      }
    };

    timer = window.setInterval(() => {
      attempts++;
      const container = messagesContainerRef.current;

      let exact: HTMLElement | null = null;
      if (container) {
        for (const candidate of targetIds) {
          exact = findExactTarget(container, candidate);
          if (exact) break;
        }
      }
      if (exact) {
        window.clearInterval(timer);
        focus(exact);
        return;
      }

      // Mid-transition the list is deliberately empty - wait for the loaded
      // snapshot instead of searching (and falling back) against nothing.
      if (messages.length === 0) return;

      if (!windowOpened && attempts >= REVEAL_AFTER_ATTEMPTS) {
        windowOpened = true;
        const applied = messageListRef.current?.focusMessage(targetIds) ?? false;
        if (!applied) {
          // The hit is older than the loaded transcript: history arrives one page of
          // turns at a time, so the target is simply not in memory yet. Request the
          // previous page and keep waiting for it - a page takes seconds to arrive, so
          // the give-up gate must stay off while one is on the way. This effect re-runs
          // as soon as the page lands, retrying with a longer transcript.
          if (messageListRef.current?.canLoadEarlierPage()) {
            waitingForPage = true;
            messageListRef.current.loadEarlierPage();
            windowOpened = false;
            stablePolls = 0;
            lastNodeCount = -1;
          }
        }
      }

      // Rendering thousands of revealed messages is bursty; gate the fallback on
      // the DOM having stopped growing rather than on a fixed attempt count.
      const nodeCount = container
        ? container.querySelectorAll('[data-message-id], [data-message-uuid]').length
        : 0;
      if (nodeCount > lastNodeCount) {
        lastNodeCount = nodeCount;
        stablePolls = 0;
      } else {
        stablePolls++;
      }

      const exhausted =
        Date.now() - startedAt >= MAX_POLL_MS ||
        (!waitingForPage && windowOpened && stablePolls >= STABLE_POLLS);
      if (!exhausted) return;

      window.clearInterval(timer);
      const fallback = container ? findFallbackTarget(container) : null;
      if (fallback) {
        focus(fallback);
      } else {
        pendingIdsRef.current = null;
        pendingTextRef.current = null;
      }
    }, POLL_INTERVAL_MS);

    return () => window.clearInterval(timer);
  }, [
    messages,
    messagesContainerRef,
    messageListRef,
    isAutoScrollingRef,
    userPausedRef,
  ]);
};
