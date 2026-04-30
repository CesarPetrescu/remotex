import { useRef, useState } from 'react';
import { EventStream } from '../components/EventStream';
import { Composer } from '../components/Composer';
import { ResumingBanner } from '../components/ResumingBanner';
import { WorkspaceFilesDrawer } from '../components/WorkspaceFilesDrawer';
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
  workspaceApi,
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
  const cwd = info?.cwd || '/';
  const hostId = info?.hostId;

  const [filesOpen, setFilesOpen] = useState(false);
  const fileInputRef = useRef(null);

  const onUpload = async (e) => {
    const file = e.target.files?.[0];
    e.target.value = ''; // allow re-selecting the same file later
    if (!file || !hostId) return;
    try {
      await workspaceApi.upload(hostId, cwd, file);
      // Re-open the drawer so the user sees the new file land.
      setFilesOpen(true);
    } catch (err) {
      alert(`Upload failed: ${err.message || err}`);
    }
  };

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
      <div className="ws-toolbar">
        <button
          type="button"
          className="ws-toolbar-btn"
          onClick={() => setFilesOpen(true)}
          disabled={!hostId}
          title="Browse files in this chat's workspace"
        >📁 Files</button>
        <button
          type="button"
          className="ws-toolbar-btn add"
          onClick={() => fileInputRef.current?.click()}
          disabled={!hostId}
          title="Add a file to the workspace (NOT image attach)"
        >+ Add</button>
        <span className="ws-toolbar-cwd">{cwd}</span>
        <input
          ref={fileInputRef}
          type="file"
          style={{ display: 'none' }}
          onChange={onUpload}
        />
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
      <WorkspaceFilesDrawer
        open={filesOpen}
        initialPath={cwd}
        hostId={hostId}
        apiRef={workspaceApi.apiRef}
        onClose={() => setFilesOpen(false)}
      />
    </div>
  );
}
