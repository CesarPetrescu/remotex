// Hover/press prefetch cache for saved-thread previews.
//
// The relay endpoint is disk-backed on the daemon (no codex involved), so
// prefetching is cheap server-side; this cache exists so sweeping the
// pointer over the session list doesn't re-fetch rows, and so an opened
// chat can paint its tail instantly while the real replay loads.

const TTL_MS = 5 * 60 * 1000;
const MAX_INFLIGHT = 3;
const SS_PREFIX = 'remotex.preview.';

const mem = new Map(); // key → {at, turns}
const inflight = new Map(); // key → Promise

const keyOf = (hostId, threadId) => `${hostId}/${threadId}`;

function readStore(k) {
  try {
    const raw = sessionStorage.getItem(SS_PREFIX + k);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function writeStore(k, entry) {
  try {
    sessionStorage.setItem(SS_PREFIX + k, JSON.stringify(entry));
  } catch {
    // storage full/private mode — memory cache still works
  }
}

export function getCachedPreview(hostId, threadId) {
  const k = keyOf(hostId, threadId);
  let entry = mem.get(k);
  if (!entry) {
    entry = readStore(k);
    if (entry) mem.set(k, entry);
  }
  if (!entry || Date.now() - entry.at > TTL_MS) return null;
  return entry.turns;
}

// Fire-and-forget. Deduped per thread; capped so a fast pointer sweep
// over the whole list can't open a request per row.
export function prefetchPreview(api, hostId, threadId) {
  if (!api || !hostId || !threadId) return;
  const k = keyOf(hostId, threadId);
  if (getCachedPreview(hostId, threadId) || inflight.has(k)) return;
  if (inflight.size >= MAX_INFLIGHT) return;
  const p = api
    .getThreadPreview(hostId, threadId)
    .then((res) => {
      if (res?.available && Array.isArray(res.turns) && res.turns.length) {
        const entry = { at: Date.now(), turns: res.turns };
        mem.set(k, entry);
        writeStore(k, entry);
      }
    })
    .catch(() => {
      // host offline / foreign thread — nothing to cache
    })
    .finally(() => inflight.delete(k));
  inflight.set(k, p);
}
