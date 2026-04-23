import { relativeAge } from '../util/time';
import { shortenCwd } from '../util/path';

export function HostsSidebar({
  state,
  selectedHost,
  onClose,
  onRefreshHosts,
  onSelectHost,
  onNewSession,
  onResumeThread,
  onAddHost,
  onOpenSettings,
}) {
  return (
    <aside className="hosts-sidebar" aria-label="Hosts and sessions">
      <div className="sidebar-head">
        <div>
          <div className="brand">REMOTEX</div>
          <div className="sidebar-subtitle">workspace · codex</div>
        </div>
        {onClose && (
          <button type="button" className="sidebar-close" onClick={onClose} aria-label="Close">
            ×
          </button>
        )}
      </div>

      <div className="sidebar-scroll">
        <section className="sidebar-section">
          <div className="sidebar-section-head">
            <span>Hosts</span>
            <button
              type="button"
              className="icon-button"
              onClick={onAddHost || onRefreshHosts}
              title={onAddHost ? 'Add host' : 'Refresh'}
            >
              <span aria-hidden="true">+</span>
              <span className="icon-button-label">{onAddHost ? 'Add host' : 'Refresh'}</span>
            </button>
          </div>
          {state.hosts.length === 0 ? (
            <div className="sidebar-empty">
              {state.hostsLoading ? 'loading hosts…' : 'no hosts registered'}
            </div>
          ) : (
            <div className="host-list">
              {state.hosts.map((host) => (
                <HostCard
                  key={host.id}
                  host={host}
                  active={host.id === state.selectedHostId}
                  onClick={() => onSelectHost(host)}
                />
              ))}
            </div>
          )}
        </section>

        <section className="sidebar-section">
          <div className="sidebar-section-head">
            <span>Sessions</span>
            {selectedHost?.online && (
              <span className="sidebar-hint">{state.threads.length}</span>
            )}
          </div>

          <button
            type="button"
            className="session-new"
            onClick={onNewSession}
            disabled={!selectedHost?.online}
          >
            <span className="plus-badge">+</span>
            <span className="session-new-body">
              <span className="session-new-title">New session</span>
              <span className="session-new-sub">Start a fresh codex thread</span>
            </span>
          </button>

          {state.threadsLoading && state.threads.length === 0 ? (
            <div className="sidebar-empty">loading sessions…</div>
          ) : state.threads.length === 0 ? (
            <div className="sidebar-empty">
              {selectedHost?.online ? 'no previous sessions' : 'select an online host'}
            </div>
          ) : (
            <div className="session-list">
              {state.threads.map((t) => (
                <SessionRow key={t.id} thread={t} onClick={() => onResumeThread(t)} />
              ))}
            </div>
          )}
        </section>
      </div>

      <div className="sidebar-foot">
        <div className="user-card">
          <div className="user-avatar" aria-hidden="true">
            {initials(state.userToken)}
          </div>
          <div className="user-body">
            <div className="user-name">{displayName(state.userToken)}</div>
            <div className="user-token">{state.userToken}</div>
          </div>
          {onOpenSettings && (
            <button
              type="button"
              className="icon-button gear"
              onClick={onOpenSettings}
              aria-label="Settings"
            >
              <span aria-hidden="true">⚙</span>
            </button>
          )}
        </div>
      </div>
    </aside>
  );
}

function HostCard({ host, active, onClick }) {
  return (
    <button
      type="button"
      className={`host-card ${host.online ? 'online' : 'offline'} ${active ? 'active' : ''}`}
      onClick={onClick}
      disabled={!host.online}
    >
      <span className="host-card-row">
        <span className={`dot ${host.online ? 'ok' : ''}`} />
        <span className="host-card-nick">{host.nickname}</span>
        <span className="host-card-status">{host.online ? 'online' : 'offline'}</span>
      </span>
      <span className="host-card-sub">
        {host.hostname || host.id?.slice(0, 16) || '—'}
      </span>
    </button>
  );
}

function SessionRow({ thread, onClick }) {
  const hasSpecificTitle = thread.title && thread.title_is_generic === false;
  const title = hasSpecificTitle ? thread.title : thread.preview || '(no preview)';
  const age = relativeAge(thread.updated_at ?? thread.created_at);
  return (
    <button type="button" className="session-row" onClick={onClick}>
      <span className="session-row-icon" aria-hidden="true">
        ◎
      </span>
      <span className="session-row-body">
        <span className="session-row-title">{title}</span>
        <span className="session-row-meta">
          <span>{age}</span>
          {thread.cwd && <span>· {shortenCwd(thread.cwd)}</span>}
        </span>
      </span>
    </button>
  );
}

function initials(token) {
  if (!token) return '··';
  const cleaned = token.replace(/^demo-/, '').replace(/-token$/, '');
  const parts = cleaned.split(/[-_ ]/).filter(Boolean);
  if (parts.length >= 2) {
    return (parts[0][0] + parts[1][0]).toUpperCase();
  }
  return cleaned.slice(0, 2).toUpperCase() || '··';
}

function displayName(token) {
  if (!token) return 'anonymous';
  const cleaned = token.replace(/^demo-/, '').replace(/-token$/, '');
  return cleaned
    .split(/[-_]/)
    .filter(Boolean)
    .map((p) => p.charAt(0).toUpperCase() + p.slice(1))
    .join(' ') || token;
}
