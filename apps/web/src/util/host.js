export function hostHomePath(host) {
  if (!host) return '/';
  if (host.home_dir) return host.home_dir;
  const user = (host.os_user || '').trim();
  if (!user) return '/';
  if (user === 'root') return '/root';
  const platform = String(host.platform || '').toLowerCase();
  if (platform.includes('darwin') || platform.includes('mac')) {
    return `/Users/${user}`;
  }
  return `/home/${user}`;
}

export function hostDisplayName(host) {
  if (!host) return '';
  if (host.os_user) return `${host.nickname} @${host.os_user}`;
  return host.nickname || host.hostname || host.id || '';
}
