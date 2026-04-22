import { EventStream } from '../components/EventStream';
import { Composer } from '../components/Composer';
import { STATUS } from '../config';

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

  return (
    <div className="screen session-screen">
      <div className="meta">{meta}</div>
      <EventStream
        events={state.events}
        pending={state.pending}
        placeholder={
          state.status === STATUS.Connected ? 'send a prompt to start…' : 'connecting…'
        }
      />
      <Composer
        connected={state.status === STATUS.Connected}
        pending={state.pending}
        model={state.model}
        effort={state.effort}
        permissions={state.permissions}
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
