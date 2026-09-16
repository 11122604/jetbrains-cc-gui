/**
 * Locating the message node that a "Find AI Edit History" hit points at.
 *
 * Extracted from App.tsx so it can be unit tested — the previous implementation
 * was inlined in a useEffect and could not be covered by tests.
 */

/**
 * Find the node to focus inside the messages container.
 *
 * Match priority:
 *   1. `data-message-uuid` — the index stores the jsonl `message.id`, and either
 *      identifier may end up on the node depending on the payload shape.
 *   2. `data-message-id`
 *   3. Fallback: the last assistant message, so an id mismatch still scrolls the
 *      user somewhere near the edit instead of silently doing nothing.
 */
export function findFocusTarget(
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

  const assistantNodes = container.querySelectorAll<HTMLElement>('.message.assistant');
  if (assistantNodes.length > 0) {
    return assistantNodes[assistantNodes.length - 1];
  }

  return null;
}

/**
 * Collect the ids actually present in the container, for diagnostics when focus
 * fails — this is what distinguishes "id mismatch" from "node never rendered".
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
