import { useEffect, useRef } from 'react';
import { useRemotex } from '../hooks/useRemotex';
import { SessionScreen } from '../screens/SessionScreen';
import { PendingPromptsPanel } from './PendingPromptsPanel';
import { STATUS } from '../config';

// One extra chat column for the desktop multi-session grid. Runs its own
// useRemotex instance (own session socket, own approval queues) — the
// daemon fans a session out to any number of clients, so this is just
// one more client on the same relay token. Prompts render inline at the
// top of the pane because the app-level right sidebar belongs to the
// primary session.
export function SessionPane({ token, remember, hostId, threadId, cwd, title, onClose }) {
  const r = useRemotex({ token, remember });
  const { state } = r;
  const openedRef = useRef(false);
  useEffect(() => {
    if (openedRef.current) return;
    openedRef.current = true;
    r.openSession({ threadId, cwd, hostId });
    // r is stable per mount; the pane opens its thread exactly once.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const hasPrompt = !!(state.pendingApproval || state.pendingUserInput);
  const connected = state.status === STATUS.Connected;

  return (
    <section className="session-pane" aria-label={`Session ${title}`}>
      <header className="session-pane-head">
        <span className="session-pane-title" title={title}>{title}</span>
        <span className={`session-pane-status ${connected ? 'ok' : ''}`}>
          {state.status}
        </span>
        <button
          type="button"
          className="session-pane-close"
          onClick={onClose}
          aria-label={`Close session tab ${title}`}
        >
          ✕
        </button>
      </header>
      {hasPrompt && (
        <div className="session-pane-prompts">
          <PendingPromptsPanel
            approval={state.pendingApproval}
            userInput={state.pendingUserInput}
            approvalQueueLength={state.pendingApprovals.length}
            userInputQueueLength={state.pendingUserInputs.length}
            onApprovalDecision={r.resolveApproval}
            onUserInputSubmit={r.resolveUserInput}
            onUserInputCancel={r.cancelUserInput}
          />
        </div>
      )}
      <div className="session-pane-body">
        <SessionScreen
          state={state}
          onSend={r.sendTurn}
          onStop={r.interruptTurn}
          onSteer={r.steerTurn}
          onQueue={r.queueTurn}
          onRemoveQueued={r.removeQueuedTurn}
          onLoadOlder={r.loadOlderHistory}
          onModelChange={r.setModel}
          onEffortChange={r.setEffort}
          onPermissionsChange={r.setPermissions}
          onAttachImage={r.attachImage}
          onRemoveImage={r.removeImage}
          workspaceApi={{
            apiRef: r.apiRef,
            upload: r.workspaceUploadFile,
            sendSlash: r.sendSlash,
          }}
        />
      </div>
    </section>
  );
}
