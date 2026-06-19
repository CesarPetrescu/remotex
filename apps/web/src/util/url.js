// Small client-side router — serializes the useRemotex state we care about
// into a real URL and back. Lets the user use the browser back button and
// refresh any screen without losing their place.
//
// URL map:
//   /                                         → Hosts
//   /host/:hostId                             → Threads for that host
//   /host/:hostId/files?path=...              → File browser
//   /session/:id                              → Live session (best-effort)

import { SCREENS } from '../config';

export function buildUrl(state) {
  const s = state.screen;
  const params = new URLSearchParams();

  if (s === SCREENS.Hosts) return '/';

  if (s === SCREENS.Threads) {
    const host = state.selectedHostId || '';
    return host ? `/host/${encodeURIComponent(host)}` : '/';
  }

  if (s === SCREENS.Files) {
    const host = state.selectedHostId;
    if (!host) return '/';
    if (state.browsePath) params.set('path', state.browsePath);
    const qs = params.toString();
    return `/host/${encodeURIComponent(host)}/files${qs ? '?' + qs : ''}`;
  }

  if (s === SCREENS.Session) {
    const sid = state.session?.sessionId;
    if (!sid) return '/';
    return `/session/${encodeURIComponent(sid)}`;
  }

  return '/';
}

// Parse a Location-like object into a route descriptor. The result is
// intentionally flat so the consumer can dispatch whichever navigation
// function fits without the router knowing about them.
export function parseUrl(location) {
  const path = (location.pathname || '/').replace(/\/+$/, '') || '/';
  const qs = new URLSearchParams(location.search || '');

  if (path === '/' || path === '') {
    return { screen: SCREENS.Hosts };
  }

  let m = path.match(/^\/host\/([^/]+)\/files$/);
  if (m) {
    return {
      screen: SCREENS.Files,
      hostId: decodeURIComponent(m[1]),
      path: qs.get('path') || null,
    };
  }
  m = path.match(/^\/host\/([^/]+)$/);
  if (m) {
    return { screen: SCREENS.Threads, hostId: decodeURIComponent(m[1]) };
  }
  m = path.match(/^\/session\/([^/]+)$/);
  if (m) {
    return { screen: SCREENS.Session, sessionId: decodeURIComponent(m[1]) };
  }
  return { screen: SCREENS.Hosts };
}
