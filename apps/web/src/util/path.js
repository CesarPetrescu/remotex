export function joinPath(base, name) {
  const trimmed = base.endsWith('/') ? base.slice(0, -1) : base;
  return `${trimmed}/${name}`;
}

export function parentPath(p) {
  if (!p || p === '/') return '/';
  const trimmed = p.endsWith('/') ? p.slice(0, -1) : p;
  const idx = trimmed.lastIndexOf('/');
  return idx <= 0 ? '/' : trimmed.slice(0, idx);
}

/**
 * Compact long cwd strings for chip / preview display.
 *   /home/user/project   → ~/project  (when HOME is detected)
 *   /very/long/path/that/goes on → …/goes on
 */
export function shortenCwd(cwd) {
  if (!cwd) return '';
  // Browser has no real $HOME; keep this a no-op on the home stripping.
  return cwd.length > 30 ? `…${cwd.slice(-27)}` : cwd;
}
