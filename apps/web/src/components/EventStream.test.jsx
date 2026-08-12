import { describe, expect, it } from 'vitest';
import { STREAM_A11Y_PROPS, isNearTail, showSkeleton } from './EventStream';

// The jump-to-latest pill shows when this is false, so the edge cases matter:
// a pill stuck on screen at the tail was the bug this guards.
describe('isNearTail', () => {
  it('is true at the exact bottom', () => {
    expect(isNearTail({ scrollHeight: 2000, scrollTop: 1400, clientHeight: 600 })).toBe(true);
  });

  it('is true for content too short to scroll', () => {
    expect(isNearTail({ scrollHeight: 600, scrollTop: 0, clientHeight: 600 })).toBe(true);
  });

  it('tolerates a little slack so a settling line still follows', () => {
    expect(isNearTail({ scrollHeight: 2000, scrollTop: 1300, clientHeight: 600 })).toBe(true);
  });

  it('is false once the user has scrolled up past the slack', () => {
    expect(isNearTail({ scrollHeight: 2000, scrollTop: 1000, clientHeight: 600 })).toBe(false);
  });
});

describe('EventStream accessibility', () => {
  it('exposes the transcript as a polite log', () => {
    expect(STREAM_A11Y_PROPS).toEqual({
      role: 'log',
      'aria-live': 'polite',
      'aria-relevant': 'additions',
    });
  });
});

describe('showSkeleton', () => {
  it('shows while attaching with an empty transcript', () => {
    expect(showSkeleton(true, 0)).toBe(true);
  });

  it('never covers an existing transcript during a reconnect', () => {
    expect(showSkeleton(true, 12)).toBe(false);
  });

  it('yields to the real placeholder once connected', () => {
    expect(showSkeleton(false, 0)).toBe(false);
  });
});
