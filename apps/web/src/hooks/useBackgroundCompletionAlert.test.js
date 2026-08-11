import { describe, expect, it } from 'vitest';
import { chatLabelFor } from './useBackgroundCompletionAlert';

function stateFor(thread) {
  return {
    session: { threadId: thread.id, sessionId: 'sess-1234567890' },
    threads: [thread],
  };
}

describe('background completion label', () => {
  it('uses a specific title but falls back to preview for a generic title', () => {
    expect(chatLabelFor(stateFor({
      id: 'thread-1',
      title: 'Fix reconnect',
      title_is_generic: false,
      preview: 'preview text',
    }))).toBe('Fix reconnect');

    expect(chatLabelFor(stateFor({
      id: 'thread-1',
      title: 'New thread',
      title_is_generic: true,
      preview: 'Investigate replay handling',
    }))).toBe('Investigate replay handling');
  });
});
