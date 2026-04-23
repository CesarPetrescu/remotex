// Small client-side router — serializes the useRemotex state we care about
// into a real URL and back. Lets the user bookmark/share a search, use the
// browser back button, and refresh any screen without losing their place.
//
// URL map:
//   /                                         → Hosts
//   /host/:hostId                             → Threads for that host
//   /host/:hostId/files?path=...              → File browser
//   /search?q=...&mode=...&rerank=...&host_id → Search
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

  if (s === SCREENS.Search) {
    if (state.searchQuery) params.set('q', state.searchQuery);
    if (state.searchMode && state.searchMode !== 'hybrid') params.set('mode', state.searchMode);
    if (state.searchRerank && state.searchRerank !== 'auto') params.set('rerank', state.searchRerank);
    if (state.selectedHostId) params.set('host_id', state.selectedHostId);
    const qs = params.toString();
    return `/search${qs ? '?' + qs : ''}`;
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
  if (path === '/search') {
    return {
      screen: SCREENS.Search,
      query: qs.get('q') || '',
      mode: qs.get('mode') || 'hybrid',
      rerank: qs.get('rerank') || 'auto',
      hostId: qs.get('host_id') || null,
    };
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
