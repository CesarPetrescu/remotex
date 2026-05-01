import { relativeAge } from '../util/time';
import { shortenCwd } from '../util/path';

/**
 * Left sidebar: hosts at the top, sessions below, user identity at the
 * bottom. Sharp corners + monospace + left-edge accent stripes — the
 * boxed/rounded look from the previous version felt bolted-on against
 * the rest of the terminal-native chrome.
 */
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
  const hostUserChip = osUserChipFor(selectedHost);
  return (
    <aside className="hosts-sidebar" aria-label="Hosts and sessions">
      {onClose && (
        <button
          type="button"
          className="sidebar-close-floating"
          onClick={onClose}
          aria-label="Close sidebar"
          title="Close"
        >×</button>
      )}

      <div className="sidebar-scroll">
        <SidebarSection
          label="HOSTS"
          right={state.hosts.length > 0 ? `${state.hosts.length}` : null}
          action={(onAddHost || onRefreshHosts) && (
            <button
              type="button"
              className="sidebar-action"
              onClick={onAddHost || onRefreshHosts}
              title={onAddHost ? 'Add host' : 'Refresh'}
            >
              {onAddHost ? '+ add' : '↻'}
            </button>
          )}
        >
          {state.hosts.length === 0 ? (
            <SidebarEmpty>{state.hostsLoading ? 'loading hosts…' : 'no hosts registered'}</SidebarEmpty>
          ) : (
            state.hosts.map((host) => (
              <HostRow
                key={host.id}
                host={host}
                active={host.id === state.selectedHostId}
                onClick={() => onSelectHost(host)}
              />
            ))
          )}
        </SidebarSection>

        <SidebarSection
          label="SESSIONS"
          right={selectedHost?.online ? `${state.threads.length}` : null}
        >
          <button
            type="button"
            className="sidebar-new-session"
            onClick={onNewSession}
            disabled={!selectedHost?.online}
          >
            <span className="sidebar-new-session-marker">+</span>
            <span>
              <span className="sidebar-new-session-title">new session</span>
              <span className="sidebar-new-session-sub">
                {selectedHost?.online ? `start a fresh codex thread on ${selectedHost.nickname}` : 'select an online host first'}
              </span>
            </span>
          </button>

          {state.threadsLoading && state.threads.length === 0 ? (
            <SidebarEmpty>loading sessions…</SidebarEmpty>
          ) : state.threads.length === 0 ? (
            <SidebarEmpty>
              {selectedHost?.online ? 'no previous sessions' : 'select an online host'}
            </SidebarEmpty>
          ) : (
            state.threads.map((t) => (
              <SessionRow
                key={t.id}
                thread={t}
                active={state.session?.threadId === t.id || state.session?.thread_id === t.id}
                onClick={() => onResumeThread(t)}
              />
            ))
          )}
        </SidebarSection>
      </div>

      <div className="sidebar-foot">
        <span className="sidebar-foot-token">{state.userToken}</span>
        {hostUserChip && <span className="sidebar-foot-host">{hostUserChip}</span>}
        {onOpenSettings && (
          <button
            type="button"
            className="sidebar-action"
            onClick={onOpenSettings}
            aria-label="Settings"
            title="Settings"
          >⚙</button>
        )}
      </div>
    </aside>
  );
}

function SidebarSection({ label, right, action, children }) {
  return (
    <section className="sidebar-section">
      <div className="sidebar-section-head">
        <span className="sidebar-section-label">{label}</span>
        {right && <span className="sidebar-section-right">{right}</span>}
        {action}
      </div>
      <div className="sidebar-section-body">{children}</div>
    </section>
  );
}

function SidebarEmpty({ children }) {
  return <div className="sidebar-empty">{children}</div>;
}

function HostRow({ host, active, onClick }) {
  return (
    <button
      type="button"
      className={`sidebar-host ${host.online ? 'online' : 'offline'} ${active ? 'active' : ''}`}
      onClick={onClick}
      disabled={!host.online}
    >
      <span className={`sidebar-host-stripe ${active ? 'active' : ''}`} />
      <span className="sidebar-host-body">
        <span className="sidebar-host-row1">
          <span className={`sidebar-host-dot ${host.online ? 'on' : 'off'}`} />
          <span className="sidebar-host-nick">{host.nickname}</span>
          {host.os_user && <span className="sidebar-host-user">@{host.os_user}</span>}
        </span>
        <span className="sidebar-host-row2">
          {host.hostname || (host.id ? host.id.slice(0, 14) + '…' : '—')}
          <span className="sidebar-host-state">{host.online ? 'online' : 'offline'}</span>
        </span>
      </span>
    </button>
  );
}

function SessionRow({ thread, active, onClick }) {
  const hasSpecificTitle = thread.title && thread.title_is_generic === false;
  const title = hasSpecificTitle ? thread.title : (thread.preview || '(no preview)');
  const age = relativeAge(thread.updated_at ?? thread.created_at);
  return (
    <button
      type="button"
      className={`sidebar-session ${active ? 'active' : ''}`}
      onClick={onClick}
    >
      <span className={`sidebar-session-stripe ${active ? 'active' : ''}`} />
      <span className="sidebar-session-body">
        <span className="sidebar-session-title">{title}</span>
        <span className="sidebar-session-meta">
          <span>{age}</span>
          {thread.cwd && <span>· {shortenCwd(thread.cwd)}</span>}
        </span>
      </span>
    </button>
  );
}

function osUserChipFor(host) {
  if (!host) return null;
  if (host.os_user) return `${host.nickname} @${host.os_user}`;
  return host.nickname;
}
