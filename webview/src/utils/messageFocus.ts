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
 * Locate the rendered element carrying one specific line of the matched snippet.
 *
 * Edit tool blocks render their diff one `<pre>` per line, so an exact text match
 * pins the edited line precisely inside what may be a very long merged message.
 * Falls back to the first element whose own text contains the line, for snippets
 * rendered outside a diff block.
 */
export function findSnippetElement(root: HTMLElement, matchText: string): HTMLElement | null {
  const needle = matchText.trim();
  if (!needle) return null;

  const pres = root.querySelectorAll<HTMLElement>('pre');
  for (const pre of pres) {
    if ((pre.textContent ?? '').trim() === needle) return pre;
  }
  for (const pre of pres) {
    if ((pre.textContent ?? '').includes(needle)) return pre;
  }

  const leaves = root.querySelectorAll<HTMLElement>('p, li, td, span, div');
  for (const el of leaves) {
    if (el.children.length === 0 && (el.textContent ?? '').trim().includes(needle)) {
      return el;
    }
  }
  return null;
}

/**
 * True when the tool block in this container is collapsed.
 *
 * The two layouts differ, so presence of `.task-details` is NOT a usable signal:
 * Edit blocks mount their diff only while expanded (no `.task-details` at all),
 * while Generic blocks always mount the accordion and collapse it with
 * `grid-template-rows: 0fr` — the details stay in the DOM with zero height, so
 * their text is still matchable while invisible.
 */
function isCollapsedToolBlock(container: HTMLElement): boolean {
  const accordion = container.querySelector<HTMLElement>('.task-details-accordion');
  if (accordion) {
    return !accordion.classList.contains('expanded');
  }
  return !container.querySelector('.task-details');
}

/** File name (last path segment) of a path using either separator. */
function baseName(path: string): string {
  const normalized = path.replace(/\\/g, '/');
  const slash = normalized.lastIndexOf('/');
  return slash >= 0 ? normalized.slice(slash + 1) : normalized;
}

/**
 * Last-resort target inside a message: the content area of the tool block that
 * handled this file.
 *
 * A tool parameter is rendered truncated (ToolDetailsAccordion caps it), so when the
 * matched line sits past that cap it is not in the DOM at all and no line-level
 * highlight is possible. Landing on the block's content still shows the code region
 * the hit came from. Returns null when there is nothing unambiguous to point at.
 */
export function findToolContentElement(root: HTMLElement, filePath: string): HTMLElement | null {
  const containers = Array.from(root.querySelectorAll<HTMLElement>('.task-container'))
    .filter((container) => container.querySelector('.task-details'));
  if (containers.length === 0) {
    return null;
  }

  const name = baseName(filePath ?? '').toLowerCase();
  if (name) {
    for (const container of containers) {
      if ((container.textContent ?? '').toLowerCase().includes(name)) {
        return container.querySelector<HTMLElement>('.task-details');
      }
    }
  }
  // No file match: only point at something when the message holds a single block,
  // so an arbitrary one of several blocks is never picked.
  return containers.length === 1
    ? containers[0].querySelector<HTMLElement>('.task-details')
    : null;
}

/**
 * Open the collapsed tool blocks inside a message so their content is rendered
 * and visible: an Edit block's diff lines, or a Generic block's parameters (a
 * Write call's `content`). Clicking `.task-header` is the same control the user
 * clicks. Returns how many were opened.
 */
export function expandCollapsedToolBlocks(root: HTMLElement): number {
  let opened = 0;
  for (const container of root.querySelectorAll<HTMLElement>('.task-container')) {
    if (!isCollapsedToolBlock(container)) continue;
    const header = container.querySelector<HTMLElement>('.task-header');
    if (header) {
      header.click();
      opened += 1;
    }
  }
  return opened;
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
