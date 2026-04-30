import { EventStream } from '../components/EventStream';
import { Composer } from '../components/Composer';
import { ResumingBanner } from '../components/ResumingBanner';
import { STATUS } from '../config';

function formatK(n) {
  if (n < 1000) return String(n);
  if (n < 100000) return (n / 1000).toFixed(1) + 'K';
  if (n < 1000000) return Math.round(n / 1000) + 'K';
  return (n / 1000000).toFixed(1) + 'M';
}

export function SessionScreen({
  state,
  onSend,
  onStop,
  onModelChange,
  onEffortChange,
  onPermissionsChange,
  onAttachImage,
  onRemoveImage,
}) {
  const info = state.session;
  const meta = !info
    ? 'no session'
    : [
        `session ${info.sessionId?.slice(0, 12) ?? '—'}… on ${info.hostId?.slice(0, 12) ?? '—'}…`,
        info.model,
        info.cwd,
      ]
        .filter(Boolean)
        .join(' · ');
  const totalIn = state.tokensInput + state.tokensCached;
  const totalOut = state.tokensOutput + state.tokensReasoning;
  const haveTokens = totalIn > 0 || totalOut > 0;

  return (
    <div className="screen session-screen">
      <div className="meta">
        <span className="meta-text">{meta}</span>
        {haveTokens && (
          <span className="token-chip" title={`input ${state.tokensInput} (+${state.tokensCached} cached) · output ${state.tokensOutput} (+${state.tokensReasoning} reasoning)`}>
            🪙 <span className="token-up">{formatK(totalIn)}↑</span>{' '}
            <span className="token-down">{formatK(totalOut)}↓</span>
          </span>
        )}
      </div>
      {state.resuming && <ResumingBanner sinceMs={state.resumingSinceMs} />}
      <EventStream
        events={state.events}
        pending={state.pending}
        placeholder={
          state.status === STATUS.Connected ? 'send a prompt to start…' : 'connecting…'
        }
      />
      <Composer
        connected={state.status === STATUS.Connected && !state.resuming}
        pending={state.pending}
        model={state.model}
        effort={state.effort}
        permissions={state.permissions}
        models={state.modelOptions}
        planMode={state.planMode}
        pendingImages={state.pendingImages}
        onModelChange={onModelChange}
        onEffortChange={onEffortChange}
        onPermissionsChange={onPermissionsChange}
        onSend={onSend}
        onStop={onStop}
        onAttachImage={onAttachImage}
        onRemoveImage={onRemoveImage}
      />
    </div>
  );
}
