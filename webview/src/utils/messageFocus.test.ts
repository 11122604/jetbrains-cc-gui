import { describe, expect, it, vi } from 'vitest';
import {
  collectMessageIds,
  expandCollapsedToolBlocks,
  findFocusTarget,
} from './messageFocus';

/** Build a container element from an HTML string. */
function container(html: string): HTMLElement {
  const div = document.createElement('div');
  div.innerHTML = html;
  return div;
}

describe('findFocusTarget', () => {
  it('prefers a data-message-uuid match', () => {
    const c = container(`
      <div class="message assistant" data-message-id="msg_1" data-message-uuid="uuid-a"></div>
      <div class="message assistant" data-message-id="uuid-b" data-message-uuid="uuid-c"></div>
    `);
    const hit = findFocusTarget(c, 'uuid-a');
    expect(hit?.dataset.messageUuid).toBe('uuid-a');
  });

  it('falls back to a data-message-id match', () => {
    const c = container(`<div class="message assistant" data-message-id="msg_42"></div>`);
    const hit = findFocusTarget(c, 'msg_42');
    expect(hit?.dataset.messageId).toBe('msg_42');
  });

  it('falls back to the last assistant message when nothing matches', () => {
    const c = container(`
      <div class="message user" data-message-id="m1"></div>
      <div class="message assistant" data-message-id="m2"></div>
      <div class="message assistant" data-message-id="m3"></div>
    `);
    const hit = findFocusTarget(c, 'no-such-id');
    expect(hit?.dataset.messageId).toBe('m3');
  });

  it('returns null for an empty container', () => {
    expect(findFocusTarget(container(''), 'anything')).toBeNull();
  });

  it('returns null when there is no assistant message to fall back to', () => {
    const c = container(`<div class="message user" data-message-id="m1"></div>`);
    expect(findFocusTarget(c, 'no-such-id')).toBeNull();
  });
});

describe('collectMessageIds', () => {
  it('collects both id kinds with their prefix', () => {
    const c = container(`
      <div class="message assistant" data-message-id="msg_1" data-message-uuid="uuid-a"></div>
    `);
    expect(collectMessageIds(c)).toEqual(['uuid:uuid-a', 'id:msg_1']);
  });

  it('respects the limit', () => {
    const c = container(`
      <div data-message-id="m1"></div>
      <div data-message-id="m2"></div>
      <div data-message-id="m3"></div>
    `);
    expect(collectMessageIds(c, 2)).toHaveLength(2);
  });

  it('returns an empty array for an empty container', () => {
    expect(collectMessageIds(container(''))).toEqual([]);
  });
});

describe('expandCollapsedToolBlocks', () => {
  /** Count header clicks without letting jsdom's default click do anything else. */
  const spyOnHeader = (root: HTMLElement) => {
    const header = root.querySelector<HTMLElement>('.task-header');
    if (!header) throw new Error('test fixture has no .task-header');
    const spy = vi.fn();
    header.addEventListener('click', spy);
    return spy;
  };

  it('opens a Generic block collapsed to zero height (details still in the DOM)', () => {
    // Regression: a collapsed Generic block keeps `.task-details` mounted and hides it
    // with grid-template-rows: 0fr, so "has .task-details" must not mean "expanded".
    const c = container(`
      <div class="task-container">
        <div class="task-header"></div>
        <div class="task-details-accordion">
          <div class="task-details">
            <div class="task-field"><div class="task-field-content">package org.crawler;</div></div>
          </div>
        </div>
      </div>
    `);
    const spy = spyOnHeader(c);
    expect(expandCollapsedToolBlocks(c)).toBe(1);
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('leaves an expanded Generic block alone', () => {
    const c = container(`
      <div class="task-container">
        <div class="task-header"></div>
        <div class="task-details-accordion expanded"><div class="task-details"></div></div>
      </div>
    `);
    const spy = spyOnHeader(c);
    expect(expandCollapsedToolBlocks(c)).toBe(0);
    expect(spy).not.toHaveBeenCalled();
  });

  it('opens an Edit block that mounts no details while collapsed', () => {
    const c = container(`<div class="task-container"><div class="task-header"></div></div>`);
    const spy = spyOnHeader(c);
    expect(expandCollapsedToolBlocks(c)).toBe(1);
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('leaves an Edit block with rendered details alone', () => {
    const c = container(`
      <div class="task-container">
        <div class="task-header"></div>
        <div class="task-details"><pre>const a = 1;</pre></div>
      </div>
    `);
    const spy = spyOnHeader(c);
    expect(expandCollapsedToolBlocks(c)).toBe(0);
    expect(spy).not.toHaveBeenCalled();
  });
});
