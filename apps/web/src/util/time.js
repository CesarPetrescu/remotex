// "3m ago", "2d ago" — same rules as Android's relativeAge()
// so thread rows read identically on both surfaces.
export function relativeAge(epochSeconds) {
  if (!epochSeconds) return '—';
  const diff = Math.floor(Date.now() / 1000) - epochSeconds;
  if (diff < 60) return 'just now';
  if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`;
  if (diff < 604800) return `${Math.floor(diff / 86400)}d ago`;
  return `${Math.floor(diff / 604800)}w ago`;
}
