import test from 'node:test';
import assert from 'node:assert/strict';

import { buildSessionTitleRequest } from './session-title-service.js';

// ---------- buildSessionTitleRequest (#1693 follow-up) ----------

test('buildSessionTitleRequest disables thinking so reasoning models still emit text', () => {
  // The Haiku alias can be user-mapped to a reasoning model (e.g. DeepSeek via
  // relay). With thinking left on, the budget is consumed by `thinking` blocks
  // and no title text is ever returned. The ceiling also has to leave room for
  // relays that ignore thinking:{type:'disabled'}.
  const request = buildSessionTitleRequest('deepseek-reasoner', 'fix the login bug');
  assert.deepEqual(request.thinking, { type: 'disabled' });
  assert.equal(request.model, 'deepseek-reasoner');
  assert.equal(request.max_tokens, 2048);
  assert.deepEqual(request.messages, [{ role: 'user', content: 'fix the login bug' }]);
});

test('buildSessionTitleRequest always attaches the title system prompt', () => {
  const request = buildSessionTitleRequest('claude-haiku-4-5-20251001', 'hello');
  assert.equal(typeof request.system, 'string');
  assert.match(request.system, /concise title/);
});
