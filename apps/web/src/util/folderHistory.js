// Client-side recents + favorites for cwd selection, persisted in
// localStorage and keyed per host. No backend: the relay never has to store
// or scan anything. Recents are ranked by "frecency" (frequency × recency),
// the zoxide model — a folder you open often and recently floats to the top.
//
// Shape per host:  { recents: { [path]: { count, last } }, favorites: [path] }

const KEY = (hostId) => `remotex.folders.${hostId || 'default'}`;
const RECENTS_CAP = 50;

function read(hostId) {
  try {
    const raw = localStorage.getItem(KEY(hostId));
    if (!raw) return { recents: {}, favorites: [] };
    const v = JSON.parse(raw);
    return {
      recents: v && typeof v.recents === 'object' && v.recents ? v.recents : {},
      favorites: Array.isArray(v?.favorites) ? v.favorites : [],
    };
  } catch {
    return { recents: {}, favorites: [] };
  }
}

function write(hostId, data) {
  try {
    localStorage.setItem(KEY(hostId), JSON.stringify(data));
  } catch {
    /* quota / private mode — recents are best-effort */
  }
}

function frecency(meta) {
  const count = meta?.count || 1;
  const last = meta?.last || 0;
  const ageHours = (Date.now() - last) / 3.6e6;
  let mult = 0.25;
  if (ageHours < 1) mult = 4;
  else if (ageHours < 24) mult = 2;
  else if (ageHours < 24 * 7) mult = 1;
  return count * mult;
}

// Call when a cwd is actually used to start a session.
export function recordVisit(hostId, path) {
  if (!hostId || !path) return;
  const d = read(hostId);
  const cur = d.recents[path] || { count: 0, last: 0 };
  d.recents[path] = { count: cur.count + 1, last: Date.now() };

  const entries = Object.entries(d.recents);
  if (entries.length > RECENTS_CAP) {
    entries.sort((a, b) => frecency(b[1]) - frecency(a[1]));
    d.recents = Object.fromEntries(entries.slice(0, RECENTS_CAP));
  }
  write(hostId, d);
}

export function getRecents(hostId, { excludeFavorites = true, limit = 12 } = {}) {
  const d = read(hostId);
  const favs = new Set(d.favorites);
  return Object.entries(d.recents)
    .filter(([p]) => !(excludeFavorites && favs.has(p)))
    .sort((a, b) => frecency(b[1]) - frecency(a[1]))
    .slice(0, limit)
    .map(([path, meta]) => ({ path, score: frecency(meta), last: meta.last }));
}

export function getFavorites(hostId) {
  return read(hostId).favorites.map((path) => ({ path }));
}

export function isFavorite(hostId, path) {
  return read(hostId).favorites.includes(path);
}

// Toggle pin state; returns the new state (true = now pinned).
export function toggleFavorite(hostId, path) {
  if (!hostId || !path) return false;
  const d = read(hostId);
  const i = d.favorites.indexOf(path);
  let nowFav;
  if (i >= 0) {
    d.favorites.splice(i, 1);
    nowFav = false;
  } else {
    d.favorites.unshift(path);
    nowFav = true;
  }
  write(hostId, d);
  return nowFav;
}
