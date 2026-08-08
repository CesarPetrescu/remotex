import { describe, expect, it } from 'vitest';
import { fuzzyMatch } from './fuzzy';

describe('fuzzyMatch', () => {
  it('matches an empty query against anything, with no highlights', () => {
    expect(fuzzyMatch('', 'whatever')).toEqual({ score: 0, indices: [] });
  });

  it('returns null when a character is missing', () => {
    expect(fuzzyMatch('xyz', 'remotex')).toBeNull();
  });

  it('returns null when the characters are out of order', () => {
    expect(fuzzyMatch('ba', 'abc')).toBeNull();
  });

  it('is case-insensitive and reports the matched indices', () => {
    const m = fuzzyMatch('RE', 'remotex');
    expect(m).not.toBeNull();
    expect(m.indices).toEqual([0, 1]);
  });

  it('handles a null target without throwing', () => {
    expect(fuzzyMatch('a', null)).toBeNull();
  });

  it('scores a contiguous prefix above a scattered match', () => {
    const prefix = fuzzyMatch('rem', 'remotex');
    const scattered = fuzzyMatch('rem', 'roadmap-elements-mix');
    expect(prefix.score).toBeGreaterThan(scattered.score);
  });

  it('rewards word-boundary matches', () => {
    const boundary = fuzzyMatch('sp', 'src/some-project');
    const inline = fuzzyMatch('sp', 'superb');
    expect(boundary).not.toBeNull();
    expect(inline).not.toBeNull();
    expect(boundary.score).toBeGreaterThan(inline.score);
  });

  it('prefers the shorter of two otherwise-equal targets', () => {
    const short = fuzzyMatch('ab', 'ab');
    const long = fuzzyMatch('ab', 'abcdefghij');
    expect(short.score).toBeGreaterThan(long.score);
  });
});
