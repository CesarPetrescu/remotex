import { relativeAge } from '../util/time';
import { SheetHandle } from './SheetHandle';
import { usePrefetchIntent } from '../hooks/usePrefetchIntent';
import { shortenCwd } from '../util/path';
import { hostHomePath } from '../util/host';
import { SCREENS } from '../config';

const SCREEN_LABELS = {
  [SCREENS.Files]: 'FILES',
};

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
  onOpenThreadInTab,
  onPrefetchThread,
  onAddHost,
  onOpenSettings,
}) {
  const hostUserChip = osUserChipFor(selectedHost);
  // W2: surface "you are here" for screens that aren't covered by the
  // hosts/sessions highlights — Files lives outside that hierarchy and
  // was invisible to anyone scanning the sidebar.
  const screenLabel = SCREEN_LABELS[state.screen];
  return (
    <aside className="hosts-sidebar" aria-label="Hosts and sessions">
      <SheetHandle onDismiss={onClose} label="Close hosts" />
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
        {screenLabel && (
          <div
            className="sidebar-screen-pill"
            title={`Currently viewing the ${screenLabel.toLowerCase()} screen`}
            aria-label={`Currently on ${screenLabel}`}
          >
            <span className="arrow">▶</span>
            <span className="label">{screenLabel}</span>
          </div>
        )}
        <SidebarSection
          label="HOST TABS"
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
                {selectedHost?.online ? `choose a folder on ${selectedHost.nickname}` : 'select an online host first'}
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
            state.threads.map((t) => {
              const active = state.session?.threadId === t.id || state.session?.thread_id === t.id;
              return (
                <SessionRow
                  key={t.id}
                  thread={t}
                  onPrefetch={onPrefetchThread}
                  active={active}
                  onClick={() => onResumeThread(t)}
                  // The active chat is already on screen — no ⊞, a second
                  // copy of the same session is never useful.
                  onOpenInTab={onOpenThreadInTab && !active ? () => onOpenThreadInTab(t) : null}
                />
              );
            })
          )}
        </SidebarSection>
      </div>

      <div className="sidebar-foot">
        {/* Masked: the footer is on screen during every screen-share and
            screenshot, and this is a bearer credential. The full value is
            editable behind the settings gear. */}
        <span className="sidebar-foot-token" title="Access token (settings to change)">
          {maskToken(state.userToken)}
          {!state.rememberToken && <span className="sidebar-foot-ephemeral"> · this tab only</span>}
        </span>
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
  const home = hostHomePath(host);
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
        <span className="sidebar-host-row3" title={home}>
          <span className="sidebar-host-home-label">home</span>
          <span className="sidebar-host-home">{home}</span>
        </span>
      </span>
    </button>
  );
}

function SessionRow({ thread, active, onClick, onPrefetch, onOpenInTab }) {
  const intent = usePrefetchIntent(() => onPrefetch?.(thread));
  const hasSpecificTitle = thread.title && thread.title_is_generic === false;
  const title = hasSpecificTitle ? thread.title : (thread.preview || '(no preview)');
  const age = relativeAge(thread.updated_at ?? thread.created_at);
  return (
    <button
      type="button"
      className={`sidebar-session ${active ? 'active' : ''}`}
      onClick={onClick}
      {...intent}
    >
      <span className={`sidebar-session-stripe ${active ? 'active' : ''}`} />
      <span className="sidebar-session-body">
        <span className="sidebar-session-title">{title}</span>
        <span className="sidebar-session-meta">
          <span>{age}</span>
          {thread.cwd && <span>· {shortenCwd(thread.cwd)}</span>}
        </span>
      </span>
      {onOpenInTab && (
        <span
          className="sidebar-session-tab-btn"
          role="button"
          tabIndex={0}
          aria-label={`Open ${title} in a new session tab`}
          title="Open in new session tab"
          onClick={(e) => {
            e.stopPropagation();
            onOpenInTab();
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.stopPropagation();
              onOpenInTab();
            }
          }}
        >
          ⊞
        </span>
      )}
    </button>
  );
}

function maskToken(token) {
  const t = String(token || '');
  if (!t) return 'no token';
  return t.length <= 8 ? '••••' : `${t.slice(0, 4)}••••${t.slice(-2)}`;
}

function osUserChipFor(host) {
  if (!host) return null;
  if (host.os_user) return `${host.nickname} @${host.os_user}`;
  return host.nickname;
}
