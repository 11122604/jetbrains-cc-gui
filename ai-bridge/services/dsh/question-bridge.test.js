import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

/**
 * Question bridge integration test: simulates the Java half of the plugin's
 * AskUserQuestion IPC (PermissionService claims the request file and writes the
 * response the webview produced) and asserts the `$events/result` value the DSH
 * host actually receives.
 *
 * Regression: the dialog keys its answers by question TEXT, and the bridge used
 * to forward those keys as answer ids, so the asking model received an answer
 * batch under ids it had never issued.
 *
 * permission-ipc resolves CLAUDE_PERMISSION_DIR / CLAUDE_SESSION_ID once, at
 * module load, so the env is set here — before any dynamic import of the bridge
 * — and every test in this file shares the one directory.
 */

const SESSION_ID = 'jest-session';
const DIR = mkdtempSync(join(tmpdir(), 'dsh-question-test-'));
process.env.CLAUDE_PERMISSION_DIR = DIR;
process.env.CLAUDE_SESSION_ID = SESSION_ID;

after(() => rmSync(DIR, { recursive: true, force: true }));

const QUESTIONS = [
  {
    id: 'commit_cadence',
    question: '你希望怎样确认提交？',
    header: '提交确认',
    options: [
      { label: '按阶段批量确认（推荐）' },
      { label: '全部先暂存，最后一次性提交' },
    ],
  },
  {
    id: 'sibling_fix',
    question: '另一处要一起改吗？',
    multiSelect: true,
    options: [{ label: '一起改 (Recommended)' }, { label: '先不动' }],
  },
];

/**
 * Fake Java side: claim the next ask-user-question request file exactly as
 * `PermissionService.handleAskUserQuestionRequest` does (delete on claim, then
 * write the dialog's answer back), answering with the webview's keying — by
 * question text.
 *
 * @param {object} answersByQuestionText - what the user picked in the dialog.
 * @param {(request: object) => void} [onRequest] - observe the request payload.
 */
function answerNextDialog(answersByQuestionText, onRequest) {
  return setInterval(() => {
    const requestFile = readdirSync(DIR).find(
      (name) =>
        name.startsWith(`ask-user-question-${SESSION_ID}-`) &&
        !name.startsWith('ask-user-question-response-') &&
        name.endsWith('.json'),
    );
    if (!requestFile) {
      return;
    }
    const requestId = requestFile
      .replace(`ask-user-question-${SESSION_ID}-`, '')
      .replace('.json', '');
    if (onRequest) {
      onRequest(JSON.parse(readFileSync(join(DIR, requestFile), 'utf8')));
    }
    writeFileSync(
      join(DIR, `ask-user-question-response-${SESSION_ID}-${requestId}.json`),
      JSON.stringify({ answers: answersByQuestionText }),
    );
    rmSync(join(DIR, requestFile), { force: true });
  }, 25);
}

test('the modern question bridge posts declared ids and DSH `custom`', async () => {
  const { bridgeModernQuestion } = await import('./events.js');

  const posted = [];
  const client = {
    async answerRemoteEvent(...args) {
      posted.push(args);
    },
  };

  // The user typed a free-text answer to the single-select question; the dialog
  // reports it as just another label in the question's label list.
  const responder = answerNextDialog({
    '你希望怎样确认提交？': '这个我自己定',
    '另一处要一起改吗？': ['一起改 (Recommended)', '顺带看看日志'],
  });
  const ok = await bridgeModernQuestion(
    client,
    'client-1',
    { eventId: 'event-1', request: { questions: QUESTIONS } },
    () => {},
  );
  clearInterval(responder);

  assert.equal(ok, true);
  assert.equal(posted.length, 1);
  assert.equal(posted[0][0], 'client-1');
  assert.equal(posted[0][1], 'event-1');
  assert.deepEqual(posted[0][2], {
    answers: [
      // Single-select: a custom answer replaces the choice, so selected is [].
      { id: 'commit_cadence', selected: [], custom: '这个我自己定' },
      // Multi-select: custom may accompany the labels.
      { id: 'sibling_fix', selected: ['一起改 (Recommended)'], custom: '顺带看看日志' },
    ],
  });
});

test('the legacy question bridge posts declared ids as well', async () => {
  const { bridgeDshQuestion } = await import('./events.js');

  const respondCalls = [];
  const client = {
    async respond(rpcId, value) {
      respondCalls.push({ rpcId, value });
    },
  };

  const responder = answerNextDialog({ '你希望怎样确认提交？': '全部先暂存，最后一次性提交' });
  const ok = await bridgeDshQuestion(
    client,
    { rpcId: 'rpc-7', questions: QUESTIONS },
    'session-x',
    () => {},
  );
  clearInterval(responder);

  assert.equal(ok, true);
  assert.deepEqual(respondCalls[0].value, {
    sessionId: 'session-x',
    answer: {
      answers: [
        { id: 'commit_cadence', selected: ['全部先暂存，最后一次性提交'] },
        { id: 'sibling_fix', selected: [] },
      ],
    },
  });
});

test('a cancelled dialog posts an empty answer batch', async () => {
  const { bridgeModernQuestion } = await import('./events.js');
  const posted = [];
  const client = {
    async answerRemoteEvent(...args) {
      posted.push(args);
    },
  };
  const responder = answerNextDialog({});
  const ok = await bridgeModernQuestion(
    client,
    'client-2',
    { eventId: 'event-2', request: { questions: QUESTIONS } },
    () => {},
  );
  clearInterval(responder);

  assert.equal(ok, true);
  assert.deepEqual(posted[0][2], { answers: [] });
});

test('the request file carries the model-declared questions verbatim', async () => {
  const { requestAskUserQuestionAnswers } = await import('../../permission-ipc.js');

  let observed = null;
  const responder = answerNextDialog(
    { '你希望怎样确认提交？': '按阶段批量确认（推荐）' },
    (request) => {
      observed = request;
    },
  );
  const answers = await requestAskUserQuestionAnswers({ questions: QUESTIONS });
  clearInterval(responder);

  // The dialog can only map an answer back to its id if the request carries it.
  assert.equal(observed.toolName, 'AskUserQuestion');
  assert.deepEqual(observed.questions, QUESTIONS);
  assert.deepEqual(answers, { '你希望怎样确认提交？': '按阶段批量确认（推荐）' });
});
