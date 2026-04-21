import { useEffect, useMemo, useState } from 'react';
import { RelayApi } from './api.js';
import { useHosts } from './hooks/useHosts.js';
import { useSession } from './hooks/useSession.js';
import Header from './components/Header.jsx';
import HostList from './components/HostList.jsx';
import EventStream from './components/EventStream.jsx';
import Composer from './components/Composer.jsx';
import Toast from './components/Toast.jsx';

const TOKEN_KEY = 'remotex.userToken';

export default function App() {
  const [token, setToken] = useState(() => {
    try {
      return localStorage.getItem(TOKEN_KEY) || 'demo-user-token';
    } catch {
      return 'demo-user-token';
    }
  });
  const [selectedHostId, setSelectedHostId] = useState(null);
  const [toast, setToast] = useState(null);

  const api = useMemo(() => new RelayApi(token), [token]);
  const { hosts, loading, error: hostsError, refresh } = useHosts(api);
  const session = useSession({ api, token, hostId: selectedHostId });

  useEffect(() => {
    try {
      localStorage.setItem(TOKEN_KEY, token);
    } catch {
      // ignore storage failures
    }
  }, [token]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  useEffect(() => {
    if (hostsError) setToast(hostsError);
  }, [hostsError]);

  useEffect(() => {
    if (session.error) setToast(session.error);
  }, [session.error]);

  const selectedHost = hosts.find((h) => h.id === selectedHostId);
  const canOpen = !!selectedHost && selectedHost.online && session.status === 'idle';

  function handleSelect(host) {
    setSelectedHostId(host.id);
    if (session.status !== 'idle') session.close();
  }

  return (
    <div className="app">
      <Header status={session.status} />
      <div className="main">
        <aside className="sidebar">
          <div className="auth">
            <label htmlFor="token">User token</label>
            <input
              id="token"
              value={token}
              onChange={(e) => setToken(e.target.value)}
              spellCheck={false}
            />
            <button onClick={refresh}>Load hosts</button>
          </div>
          <div>
            <h3>Hosts</h3>
            <HostList
              hosts={hosts}
              selected={selectedHostId}
              onSelect={handleSelect}
              loading={loading}
            />
          </div>
          <div>
            <h3>Session</h3>
            <button disabled={!canOpen} onClick={session.open}>
              Open session
            </button>
            {session.status !== 'idle' && (
              <button style={{ marginTop: 6 }} onClick={session.close}>
                Close
              </button>
            )}
          </div>
        </aside>

        <section className="session">
          <div className="meta">
            {session.sessionInfo
              ? `session ${session.sessionInfo.sessionId} on ${session.sessionInfo.hostId}` +
                (session.sessionInfo.model ? ` · ${session.sessionInfo.model}` : '') +
                (session.sessionInfo.cwd ? ` · ${session.sessionInfo.cwd}` : '')
              : 'no session'}
          </div>
          <div className="stream">
            <EventStream events={session.events} pending={session.pending} />
          </div>
          <Composer
            disabled={session.status !== 'connected' || session.pending}
            onSend={session.sendTurn}
          />
        </section>
      </div>
      <Toast message={toast} onDismiss={() => setToast(null)} />
    </div>
  );
}
