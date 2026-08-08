import { describe, expect, it } from 'vitest';
import { buildUrl, parseUrl } from './url';
import { SCREENS } from '../config';

describe('buildUrl', () => {
  it('maps the hosts screen to the root', () => {
    expect(buildUrl({ screen: SCREENS.Hosts })).toBe('/');
  });

  it('maps a selected host to /host/:id', () => {
    expect(buildUrl({ screen: SCREENS.Threads, selectedHostId: 'host-1' }))
      .toBe('/host/host-1');
  });

  it('falls back to the root when the screen has no subject', () => {
    expect(buildUrl({ screen: SCREENS.Threads })).toBe('/');
    expect(buildUrl({ screen: SCREENS.Files })).toBe('/');
    expect(buildUrl({ screen: SCREENS.Session, session: null })).toBe('/');
  });

  it('encodes ids and carries the browse path as a query param', () => {
    expect(buildUrl({
      screen: SCREENS.Files,
      selectedHostId: 'host/one',
      browsePath: '/home/user/my proj',
    })).toBe('/host/host%2Fone/files?path=%2Fhome%2Fuser%2Fmy+proj');
  });

  it('maps a live session to /session/:id', () => {
    expect(buildUrl({ screen: SCREENS.Session, session: { sessionId: 'sess-9' } }))
      .toBe('/session/sess-9');
  });
});

describe('parseUrl', () => {
  const at = (pathname, search = '') => parseUrl({ pathname, search });

  it('parses the root', () => {
    expect(at('/')).toEqual({ screen: SCREENS.Hosts });
    expect(at('')).toEqual({ screen: SCREENS.Hosts });
  });

  it('parses a host route', () => {
    expect(at('/host/host-1')).toEqual({ screen: SCREENS.Threads, hostId: 'host-1' });
  });

  it('parses a files route with a path', () => {
    expect(at('/host/host%2Fone/files', '?path=/home/user')).toEqual({
      screen: SCREENS.Files,
      hostId: 'host/one',
      path: '/home/user',
    });
  });

  it('reports a missing files path as null rather than guessing', () => {
    expect(at('/host/host-1/files').path).toBeNull();
  });

  it('parses a session route', () => {
    expect(at('/session/sess-9')).toEqual({ screen: SCREENS.Session, sessionId: 'sess-9' });
  });

  it('ignores a trailing slash', () => {
    expect(at('/host/host-1/')).toEqual({ screen: SCREENS.Threads, hostId: 'host-1' });
  });

  it('falls back to hosts for anything unrecognized', () => {
    expect(at('/nope/nope')).toEqual({ screen: SCREENS.Hosts });
  });

  it('round-trips a host id that needs encoding', () => {
    const url = buildUrl({
      screen: SCREENS.Files,
      selectedHostId: 'host/one',
      browsePath: '/home/user/my proj',
    });
    const [pathname, search] = url.split('?');
    expect(parseUrl({ pathname, search: `?${search}` })).toEqual({
      screen: SCREENS.Files,
      hostId: 'host/one',
      path: '/home/user/my proj',
    });
  });
});
