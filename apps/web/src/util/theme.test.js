import { describe, expect, it } from 'vitest';
import { nextTheme } from './theme';

describe('theme cycle', () => {
  it('cycles dark, white, and high contrast', () => {
    expect(nextTheme('dark')).toBe('light');
    expect(nextTheme('light')).toBe('contrast');
    expect(nextTheme('contrast')).toBe('dark');
  });
});
