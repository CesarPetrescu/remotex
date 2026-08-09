// Explicit themes are dark, white/light, and high contrast. With no saved
// choice, follow the OS contrast and color-scheme preferences.
// The resolved theme is stamped on <html data-theme="…"> before first
// paint so nothing flashes, and <meta name="theme-color"> tracks it so
// the mobile browser chrome matches.

const KEY = 'remotex.theme';
// Guarded: unit tests import UI modules under plain Node, where there is
// no window. There the stub pins "dark, never changes".
const colorMedia = typeof window !== 'undefined' && window.matchMedia
  ? window.matchMedia('(prefers-color-scheme: light)')
  : { matches: false, addEventListener() {} };
const contrastMedia = typeof window !== 'undefined' && window.matchMedia
  ? window.matchMedia('(prefers-contrast: more)')
  : { matches: false, addEventListener() {} };
const NEXT_THEME = { dark: 'light', light: 'contrast', contrast: 'dark' };

function stored() {
  try {
    const value = localStorage.getItem(KEY);
    return value in NEXT_THEME ? value : null;
  } catch {
    return null;
  }
}

export function resolvedTheme() {
  return stored() ?? (contrastMedia.matches ? 'contrast' : colorMedia.matches ? 'light' : 'dark');
}

function apply(theme) {
  document.documentElement.dataset.theme = theme;
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) {
    meta.setAttribute('content', {
      dark: '#050910',
      light: '#f8fafc',
      contrast: '#000000',
    }[theme]);
  }
}

/** Call once before render. Also follows OS changes while no explicit choice. */
export function initTheme() {
  apply(resolvedTheme());
  const followSystem = () => {
    if (!stored()) apply(resolvedTheme());
  };
  colorMedia.addEventListener('change', followSystem);
  contrastMedia.addEventListener('change', followSystem);
}

export function nextTheme(theme) {
  return NEXT_THEME[theme] ?? 'dark';
}

/** Cycle and persist. Returns the new theme so callers can update state. */
export function toggleTheme() {
  const next = nextTheme(resolvedTheme());
  try {
    localStorage.setItem(KEY, next);
  } catch {
    // private mode — theme still applies for this page's lifetime
  }
  apply(next);
  return next;
}
