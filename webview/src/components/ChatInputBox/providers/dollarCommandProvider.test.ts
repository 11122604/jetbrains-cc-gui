import {
  dollarCommandProvider,
  resetDollarCommandsState,
} from './dollarCommandProvider.js';

describe('dollarCommandProvider', () => {
  beforeEach(() => {
    resetDollarCommandsState();
    delete window.updateDollarCommands;
    delete window.__pendingDollarCommands;
  });

  it('waits for the backend payload instead of returning a loading row', async () => {
    const resultPromise = dollarCommandProvider('', new AbortController().signal);

    window.updateDollarCommands?.(JSON.stringify([
      { name: '$review-code', description: 'Review', source: 'codex-skill' },
    ]));

    await expect(resultPromise).resolves.toEqual([
      expect.objectContaining({
        id: 'review-code',
        label: '$review-code',
        contentType: 'skill',
      }),
    ]);
  });

  it('marks Codex commands that arrived on the dollar channel as commands', async () => {
    const resultPromise = dollarCommandProvider('', new AbortController().signal);

    window.updateDollarCommands?.(JSON.stringify([
      { name: 'review', type: 'command', source: 'codex-command' },
    ]));

    await expect(resultPromise).resolves.toEqual([
      expect.objectContaining({
        id: 'review',
        label: '$review',
        contentType: 'command',
      }),
    ]);
  });

  it('ignores malformed optional metadata without failing the whole payload', async () => {
    const resultPromise = dollarCommandProvider('', new AbortController().signal);

    window.updateDollarCommands?.(JSON.stringify([
      { name: '$review-code', type: { unexpected: true }, source: 42 },
    ]));

    await expect(resultPromise).resolves.toEqual([
      expect.objectContaining({
        id: 'review-code',
        contentType: 'skill',
      }),
    ]);
  });
});
