import { ThreadRow } from '../components/ThreadRow';

export function ThreadsScreen({ state, onRefresh, onNewSession, onResumeThread }) {
  const host = state.hosts.find((h) => h.id === state.selectedHostId);
  return (
    <div className="screen threads-screen">
      <div className="section-title">
        sessions on {host?.nickname || state.selectedHostId || 'host'}
      </div>
      <button type="button" className="new-session-card" onClick={onNewSession}>
        <span className="plus-badge">+</span>
        <div>
          <div className="new-session-title">New session</div>
          <div className="new-session-hint">pick a folder and start a fresh codex thread</div>
        </div>
      </button>
      <div className="row-between">
        <div className="muted">or continue a previous one</div>
        <button type="button" className="icon-button" onClick={onRefresh} aria-label="Refresh">
          ↻
        </button>
      </div>
      {state.threadsLoading && state.threads.length === 0 ? (
        <div className="empty">loading…</div>
      ) : state.threads.length === 0 ? (
        <div className="empty">
          no previous sessions yet — your first "New session" will show up here after it runs
        </div>
      ) : (
        <div className="thread-list">
          {state.threads.map((t) => (
            <ThreadRow key={t.id} thread={t} onClick={(thread) => onResumeThread(thread)} />
          ))}
        </div>
      )}
    </div>
  );
}
