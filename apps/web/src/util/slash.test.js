import { describe, expect, it } from 'vitest';
import { parseSlash } from './slash';

describe('parseSlash', () => {
  it('returns null for plain prose', () => {
    expect(parseSlash('do the thing')).toBeNull();
    expect(parseSlash('')).toBeNull();
    expect(parseSlash('   ')).toBeNull();
  });

  it('returns null for a bare slash', () => {
    expect(parseSlash('/')).toBeNull();
    expect(parseSlash('/   ')).toBeNull();
  });

  it('parses a command with no args', () => {
    expect(parseSlash('/pwd')).toEqual({ cmd: 'pwd', args: '' });
  });

  it('parses a command with args and keeps the args verbatim', () => {
    expect(parseSlash('/cd /home/user/some project')).toEqual({
      cmd: 'cd',
      args: '/home/user/some project',
    });
  });

  it('lowercases the command but not the args', () => {
    expect(parseSlash('/GOAL Ship It')).toEqual({ cmd: 'goal', args: 'Ship It' });
  });

  it('tolerates surrounding whitespace', () => {
    expect(parseSlash('  /compact   now  ')).toEqual({ cmd: 'compact', args: 'now' });
  });

  it('only treats a leading slash as a command', () => {
    expect(parseSlash('please /plan')).toBeNull();
  });
});
