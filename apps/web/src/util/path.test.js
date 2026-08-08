import { describe, expect, it } from 'vitest';
import { joinPath, parentPath, shortenCwd } from './path';

describe('joinPath', () => {
  it('joins without doubling the separator', () => {
    expect(joinPath('/home/user', 'proj')).toBe('/home/user/proj');
    expect(joinPath('/home/user/', 'proj')).toBe('/home/user/proj');
  });

  it('joins onto root', () => {
    expect(joinPath('/', 'etc')).toBe('/etc');
  });
});

describe('parentPath', () => {
  it('walks one level up', () => {
    expect(parentPath('/home/user/proj')).toBe('/home/user');
    expect(parentPath('/home/user/proj/')).toBe('/home/user');
  });

  it('stops at root', () => {
    expect(parentPath('/home')).toBe('/');
    expect(parentPath('/')).toBe('/');
    expect(parentPath('')).toBe('/');
  });
});

describe('shortenCwd', () => {
  it('leaves short paths alone', () => {
    expect(shortenCwd('/home/user/proj')).toBe('/home/user/proj');
  });

  it('returns empty for nothing', () => {
    expect(shortenCwd('')).toBe('');
    expect(shortenCwd(null)).toBe('');
  });

  it('keeps the tail of a long path and marks the elision', () => {
    const long = '/home/user/very/long/path/that/keeps/going/deeper';
    const short = shortenCwd(long);
    expect(short.startsWith('…')).toBe(true);
    expect(short.length).toBe(28);
    expect(long.endsWith(short.slice(1))).toBe(true);
  });
});
