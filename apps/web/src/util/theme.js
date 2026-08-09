// Light/dark theme state. Three-way: 'light' | 'dark' | follow the OS.
// The resolved theme is stamped on <html data-theme="…"> before first
// paint so nothing flashes, and <meta name="theme-color"> tracks it so
// the mobile browser chrome matches.

const KEY = 'remotex.theme';
// Guarded: unit tests import UI modules under plain Node, where there is
// no window. There the stub pins "dark, never changes".
const media = typeof window !== 'undefined' && window.matchMedia
  ? window.matchMedia('(prefers-color-scheme: light)')
  : { matches: false, addEventListener() {} };

function stored() {
  try {
    const value = localStorage.getItem(KEY);
    return value === 'light' || value === 'dark' ? value : null;
  } catch {
    return null;
  }
}

export function resolvedTheme() {
  return stored() ?? (media.matches ? 'light' : 'dark');
}

function apply(theme) {
  document.documentElement.dataset.theme = theme;
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute('content', theme === 'light' ? '#f6f8fb' : '#050910');
}

/** Call once before render. Also follows OS changes while no explicit choice. */
export function initTheme() {
  apply(resolvedTheme());
  media.addEventListener('change', () => {
    if (!stored()) apply(resolvedTheme());
  });
}

/** Flip and persist. Returns the new theme so callers can update state. */
export function toggleTheme() {
  const next = resolvedTheme() === 'light' ? 'dark' : 'light';
  try {
    localStorage.setItem(KEY, next);
  } catch {
    // private mode — theme still applies for this page's lifetime
  }
  apply(next);
  return next;
}
