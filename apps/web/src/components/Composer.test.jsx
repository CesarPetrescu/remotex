import { describe, expect, it } from 'vitest';
import { matchSlashes, parseKnownSlash } from './Composer';

describe('composer slash routing', () => {
  it('discovers collab in autocomplete', () => {
    expect(matchSlashes('/coll').map((command) => command.id)).toEqual(['collab']);
  });

  it('classifies collab as a control command before steer/send handling', () => {
    expect(parseKnownSlash('/collab')).toEqual({ cmd: 'collab', args: '' });
    expect(parseKnownSlash('  /COLLAB  now ')).toEqual({ cmd: 'collab', args: 'now' });
    expect(parseKnownSlash('/not-a-command')).toBeNull();
  });
});
