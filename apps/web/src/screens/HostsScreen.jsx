import { HostRow } from '../components/HostRow';

export function HostsScreen({ state, onTokenChange, onRefresh, onHostTap }) {
  return (
    <div className="screen hosts-screen">
      <section className="token-field">
        <label htmlFor="token">USER TOKEN</label>
        <input
          id="token"
          type="text"
          value={state.userToken}
          onChange={(e) => onTokenChange(e.target.value)}
          spellCheck={false}
        />
      </section>
      <button type="button" className="btn-surface" onClick={onRefresh}>
        {state.hostsLoading ? 'Loading…' : 'Load hosts'}
      </button>
      <h3>HOSTS</h3>
      {state.hosts.length === 0 ? (
        <div className="empty">
          {state.hostsLoading ? 'loading…' : 'no hosts yet'}
        </div>
      ) : (
        <div className="host-list">
          {state.hosts.map((h) => (
            <HostRow key={h.id} host={h} onClick={onHostTap} />
          ))}
        </div>
      )}
    </div>
  );
}
