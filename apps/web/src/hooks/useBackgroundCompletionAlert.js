import { useEffect, useRef } from 'react';

/**
 * When a turn completes while the tab is hidden, swap the document
 * title and favicon to grab the user's eye in their tab list. Restore
 * both on visibilitychange→visible.
 *
 * Two-state machine internally:
 *   - watching=false: nothing to do
 *   - watching=true:  apply alert title + favicon, listen for visible
 *
 * `pending` is the gate. We set `wasHiddenWhilePending=true` if the
 * tab goes hidden during a pending turn, and only fire the alert on
 * the falling edge if that flag is set — so passive watchers (window
 * stayed visible the whole time) don't get an alert they don't need.
 */
export function useBackgroundCompletionAlert(state) {
  const pendingRef = useRef(false);
  const wasHiddenRef = useRef(false);
  const originalTitleRef = useRef(null);
  const originalFaviconRef = useRef(null);
  const watchingRef = useRef(false);

  // Track tab visibility: any time we become hidden mid-turn, remember
  // it so the falling edge can decide whether to alert.
  useEffect(() => {
    if (originalTitleRef.current === null) {
      originalTitleRef.current = document.title;
    }
    if (originalFaviconRef.current === null) {
      const link = document.querySelector("link[rel='icon']");
      originalFaviconRef.current = link?.getAttribute('href') || '/favicon.ico';
    }
    const onVis = () => {
      if (document.hidden) {
        if (pendingRef.current) wasHiddenRef.current = true;
      } else if (watchingRef.current) {
        clearAlert();
      }
    };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      document.removeEventListener('visibilitychange', onVis);
      clearAlert();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Watch state.pending edges to drive the alert.
  useEffect(() => {
    const wasPending = pendingRef.current;
    pendingRef.current = state.pending;
    if (state.pending) {
      // rising edge — reset the hidden flag so a quick foreground turn
      // doesn't fire the alert later in this session.
      if (!wasPending) wasHiddenRef.current = false;
      return;
    }
    if (!wasPending) return;
    // falling edge: pending true → false. Alert iff the user backgrounded
    // the tab at any point while the turn was running.
    if (!document.hidden) return;
    if (!wasHiddenRef.current) return;
    setAlert(state);
  }, [state.pending, state]);

  function setAlert(s) {
    watchingRef.current = true;
    const title = chatLabelFor(s);
    document.title = `● Codex done · ${title}`;
    setFavicon(makeAlertFaviconDataUri());
  }

  function clearAlert() {
    if (!watchingRef.current) return;
    watchingRef.current = false;
    if (originalTitleRef.current != null) document.title = originalTitleRef.current;
    if (originalFaviconRef.current != null) setFavicon(originalFaviconRef.current);
  }
}

function chatLabelFor(state) {
  const tid = state.session?.threadId || state.session?.thread_id;
  if (tid) {
    const thread = state.threads?.find((t) => t.id === tid);
    if (thread?.title && !thread.titleIsGeneric) return ellipsize(thread.title, 28);
    if (thread?.preview) return ellipsize(thread.preview, 28);
  }
  if (state.session?.sessionId) return state.session.sessionId.slice(0, 10) + '…';
  return 'chat';
}

function ellipsize(s, max) {
  if (s.length <= max) return s;
  return s.slice(0, max - 1).trimEnd() + '…';
}

function setFavicon(href) {
  let link = document.querySelector("link[rel='icon']");
  if (!link) {
    link = document.createElement('link');
    link.rel = 'icon';
    document.head.appendChild(link);
  }
  link.setAttribute('href', href);
}

/**
 * Generate a small alert favicon as an inline data URI: the brand cyan
 * disc on dark with a red dot in the corner. Avoids shipping a second
 * PNG asset and ensures the alert matches the brand colors.
 */
let _cachedAlertFavicon = null;
function makeAlertFaviconDataUri() {
  if (_cachedAlertFavicon) return _cachedAlertFavicon;
  const canvas = document.createElement('canvas');
  canvas.width = 32;
  canvas.height = 32;
  const c = canvas.getContext('2d');
  if (!c) return '';
  // base disc
  c.fillStyle = '#0a1120';
  c.fillRect(0, 0, 32, 32);
  c.fillStyle = '#5ee1ff';
  c.beginPath();
  c.arc(16, 16, 11, 0, Math.PI * 2);
  c.fill();
  // alert dot top-right
  c.fillStyle = '#ff5050';
  c.beginPath();
  c.arc(25, 7, 6, 0, Math.PI * 2);
  c.fill();
  _cachedAlertFavicon = canvas.toDataURL('image/png');
  return _cachedAlertFavicon;
}
