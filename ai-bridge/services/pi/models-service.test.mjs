import test from 'node:test';
import assert from 'node:assert/strict';
import { parsePiModelsOutput, pickParseSource } from './models-service.js';

// Matches the real fixed-width table printed by `pi --list-models`.
const TABLE = [
  'provider     model                  context  max-out  thinking  images',
  'coding-plan  ark-code-latest        128K     16.4K    no        no',
  'deepseek     deepseek-v3.2          128K     8.2K     yes       no',
  'kimi         kimi-for-coding        256K     32.8K    yes       yes',
].join('\n');

test('parses the fixed-width table and skips the header row', () => {
  const models = parsePiModelsOutput(TABLE);
  assert.deepEqual(
    models.map((m) => m.id),
    ['coding-plan/ark-code-latest', 'deepseek/deepseek-v3.2', 'kimi/kimi-for-coding'],
  );
});

test('description carries context, thinking, and vision flags', () => {
  const models = parsePiModelsOutput(TABLE);
  assert.equal(models[0].description, 'ctx 128K');
  assert.equal(models[1].description, 'ctx 128K · thinking');
  assert.equal(models[2].description, 'ctx 256K · thinking · vision');
});

test('handles CRLF line endings and ANSI escape codes', () => {
  const colored = TABLE.split('\n')
    .map((line) => `[32m${line}[0m`)
    .join('\r\n');
  const models = parsePiModelsOutput(colored);
  assert.equal(models.length, 3);
  assert.equal(models[0].id, 'coding-plan/ark-code-latest');
});

test('duplicate provider/model rows are deduplicated', () => {
  const models = parsePiModelsOutput(`${TABLE}\ncoding-plan  ark-code-latest        128K     16.4K    no        no`);
  assert.equal(models.length, 3);
});

test('unrelated or empty output yields no models', () => {
  assert.deepEqual(parsePiModelsOutput(''), []);
  assert.deepEqual(parsePiModelsOutput('not a table'), []);
});

test('pickParseSource prefers stdout when it has content', () => {
  assert.equal(pickParseSource(TABLE, 'WARN: something'), TABLE);
});

test('pickParseSource falls back to stderr when stdout is empty or blank', () => {
  // The Windows pi.cmd shim routes the whole table through stderr.
  assert.equal(pickParseSource('', TABLE), TABLE);
  assert.equal(pickParseSource('  \r\n  ', TABLE), TABLE);
});

test('windows scenario end-to-end: table on stderr still parses', () => {
  const models = parsePiModelsOutput(pickParseSource('', TABLE));
  assert.equal(models.length, 3);
});
