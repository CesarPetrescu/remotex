import { useEffect } from 'react';
import { useRemotex } from './hooks/useRemotex';
import { SCREENS } from './config';
import { Header } from './components/Header';
import { Toast } from './components/Toast';
import { ApprovalDialog } from './components/ApprovalDialog';
import { HostsScreen } from './screens/HostsScreen';
import { ThreadsScreen } from './screens/ThreadsScreen';
import { FilesScreen } from './screens/FilesScreen';
import { SessionScreen } from './screens/SessionScreen';

export default function App() {
  const r = useRemotex();
  const { state } = r;

  // Back-button stack: Session → Threads, Threads → Hosts, Hosts → no-op
  function onBack() {
    if (state.screen === SCREENS.Session) {
      r.closeSession();
      r.goToThreads();
    } else if (state.screen === SCREENS.Files) {
      r.goToThreads();
    } else if (state.screen === SCREENS.Threads) {
      r.goToHosts();
    }
  }

  // Browser back button mirrors the top-bar arrow.
  useEffect(() => {
    function onPop() {
      onBack();
    }
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state.screen]);

  return (
    <div className="app">
      <Header state={state} onBack={onBack} />
      <main className="screen-host">
        {state.screen === SCREENS.Hosts && (
          <HostsScreen
            state={state}
            onTokenChange={r.setToken}
            onRefresh={r.refreshHosts}
            onHostTap={r.openHost}
          />
        )}
        {state.screen === SCREENS.Threads && (
          <ThreadsScreen
            state={state}
            onRefresh={r.refreshThreads}
            onNewSession={() => r.goToFiles()}
            onResumeThread={(t) => r.openSession({ threadId: t.id })}
          />
        )}
        {state.screen === SCREENS.Files && (
          <FilesScreen
            state={state}
            onNavigate={r.browseDir}
            onUp={r.browseUp}
            onStartHere={r.startSessionInCurrentPath}
          />
        )}
        {state.screen === SCREENS.Session && (
          <SessionScreen
            state={state}
            onSend={r.sendTurn}
            onStop={r.interruptTurn}
            onModelChange={r.setModel}
            onEffortChange={r.setEffort}
            onPermissionsChange={r.setPermissions}
            onAttachImage={r.attachImage}
            onRemoveImage={r.removeImage}
          />
        )}
      </main>
      <ApprovalDialog prompt={state.pendingApproval} onDecision={r.resolveApproval} />
      <Toast message={state.error} tone="error" onDismiss={r.clearError} />
      <Toast message={state.slashFeedback} tone="info" onDismiss={r.clearFeedback} />
    </div>
  );
}
