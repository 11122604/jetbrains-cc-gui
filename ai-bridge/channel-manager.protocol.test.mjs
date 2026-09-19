import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { getClaudeProjectKey } from './utils/path-utils.js';

const bridgeDir = dirname(fileURLToPath(import.meta.url));
const channelManager = join(bridgeDir, 'channel-manager.js');

test('system command keeps stdout reserved for its JSON response', () => {
  const result = spawnSync(process.execPath, [channelManager, 'system', 'checkCodexSdk'], {
    cwd: bridgeDir,
    input: '',
    encoding: 'utf8',
    timeout: 10_000,
  });

  assert.equal(result.status, 0, result.stderr);
  assert.doesNotMatch(result.stdout, /\[DIAG-|\[sdk-loader\]/);
  const response = JSON.parse(result.stdout.trim());
  assert.equal(typeof response.success, 'boolean');
  assert.equal(typeof response.available, 'boolean');
});

test('Claude session-page command treats empty cursors as the latest page', () => {
  const homeDir = fs.mkdtempSync(join(os.tmpdir(), 'cc-gui-page-cursor-'));
  const cwd = join(homeDir, 'project');
  const sessionId = 'page-cursor-regression';
  const sessionDir = join(homeDir, '.claude', 'projects', getClaudeProjectKey(cwd));
  const sessionFile = join(sessionDir, `${sessionId}.jsonl`);

  try {
    fs.mkdirSync(cwd, { recursive: true });
    fs.mkdirSync(sessionDir, { recursive: true });
    fs.writeFileSync(sessionFile, [
      JSON.stringify({ type: 'user', uuid: 'u1', message: { role: 'user', content: 'first' } }),
      JSON.stringify({ type: 'assistant', message: { role: 'assistant', content: 'first reply' } }),
      JSON.stringify({ type: 'user', uuid: 'u2', message: { role: 'user', content: 'second' } }),
      JSON.stringify({ type: 'assistant', message: { role: 'assistant', content: 'second reply' } }),
    ].join('\n') + '\n');

    const result = spawnSync(
      process.execPath,
      [channelManager, 'claude', 'getSessionPage', sessionId, cwd, '', '30'],
      {
        cwd: bridgeDir,
        env: { ...process.env, HOME: homeDir, USERPROFILE: homeDir },
        encoding: 'utf8',
        timeout: 10_000,
      },
    );

    assert.equal(result.status, 0, result.stderr);
    const response = JSON.parse(result.stdout.trim());
    assert.equal(response.success, true);
    assert.equal(response.fromTurn, 0);
    assert.equal(response.toTurn, 2);
    assert.equal(response.totalTurns, 2);
    assert.equal(response.messages.length, 4);

    const stdinCursors = ['', '   ', false, []];
    for (const beforeTurn of stdinCursors) {
      const stdinResult = spawnSync(
        process.execPath,
        [channelManager, 'claude', 'getSessionPage'],
        {
          cwd: bridgeDir,
          env: { ...process.env, HOME: homeDir, USERPROFILE: homeDir, CLAUDE_USE_STDIN: 'true' },
          input: JSON.stringify({ sessionId, cwd, beforeTurn, limit: 30 }),
          encoding: 'utf8',
          timeout: 10_000,
        },
      );

      assert.equal(stdinResult.status, 0, stdinResult.stderr);
      const stdinResponse = JSON.parse(stdinResult.stdout.trim());
      assert.equal(stdinResponse.success, true);
      assert.equal(stdinResponse.fromTurn, 0);
      assert.equal(stdinResponse.toTurn, 2);
      assert.equal(stdinResponse.totalTurns, 2);
      assert.equal(stdinResponse.messages.length, 4);
    }

    const firstPageResult = spawnSync(
      process.execPath,
      [channelManager, 'claude', 'getSessionPage', sessionId, cwd, '0', '30'],
      {
        cwd: bridgeDir,
        env: { ...process.env, HOME: homeDir, USERPROFILE: homeDir },
        encoding: 'utf8',
        timeout: 10_000,
      },
    );

    assert.equal(firstPageResult.status, 0, firstPageResult.stderr);
    const firstPage = JSON.parse(firstPageResult.stdout.trim());
    assert.equal(firstPage.success, true);
    assert.equal(firstPage.toTurn, 0);
    assert.equal(firstPage.messages.length, 0);
  } finally {
    fs.rmSync(homeDir, { recursive: true, force: true });
  }
});
