/**
 * Locating the message node that a "Find AI Edit History" hit points at.
 *
 * Extracted from App.tsx so it can be unit tested — the previous implementation
 * was inlined in a useEffect and could not be covered by tests.
 */

/**
 * Exact match only: `data-message-uuid` first, then `data-message-id`.
 *
 * Returns null when nothing matches, which is the signal that the caller should
 * try revealing collapsed history before falling back.
 */
export function findExactTarget(
  container: HTMLElement,
  targetId: string,
): HTMLElement | null {
  const nodes = container.querySelectorAll<HTMLElement>(
    '[data-message-id], [data-message-uuid]',
  );

  for (const node of nodes) {
    if (node.dataset.messageUuid === targetId) {
      return node;
    }
  }

  for (const node of nodes) {
    if (node.dataset.messageId === targetId) {
      return node;
    }
  }

  return null;
}

/**
 * Last-resort fallback: the final assistant message, so an id that matches
 * nothing still scrolls the user somewhere near the edit instead of doing
 * nothing at all.
 */
export function findFallbackTarget(container: HTMLElement): HTMLElement | null {
  const assistantNodes = container.querySelectorAll<HTMLElement>('.message.assistant');
  if (assistantNodes.length > 0) {
    return assistantNodes[assistantNodes.length - 1];
  }
  return null;
}

/**
 * Exact match, then the fallback. Kept for simple callers and tests; the focus
 * effect uses the two halves separately so it can reveal collapsed history
 * before giving up.
 */
export function findFocusTarget(
  container: HTMLElement,
  targetId: string,
): HTMLElement | null {
  return findExactTarget(container, targetId) ?? findFallbackTarget(container);
}

/**
 * Collect the ids actually present in the container, for diagnostics when focus
 * fails — this is what distinguishes "id mismatch" from "node never rendered"
 * (e.g. the message is still behind the collapsed-history indicator).
 */
export function collectMessageIds(container: HTMLElement, limit = 20): string[] {
  const nodes = container.querySelectorAll<HTMLElement>(
    '[data-message-id], [data-message-uuid]',
  );
  const ids: string[] = [];
  for (const node of nodes) {
    if (node.dataset.messageUuid) {
      ids.push('uuid:' + node.dataset.messageUuid);
    }
    if (node.dataset.messageId) {
      ids.push('id:' + node.dataset.messageId);
    }
    if (ids.length >= limit) {
      break;
    }
  }
  return ids;
}
