import { describe, expect, it } from 'vitest';
import { collectMessageIds, findFocusTarget } from './messageFocus';

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
