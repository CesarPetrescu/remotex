import { useRef, useState } from 'react';
import { EventStream } from '../components/EventStream';
import { Composer } from '../components/Composer';
import { ResumingBanner } from '../components/ResumingBanner';
import { WorkspaceFilesDrawer } from '../components/WorkspaceFilesDrawer';
import { STATUS } from '../config';

function shortenCwdLeft(cwd, max = 36) {
  if (!cwd) return '';
  if (cwd.length <= max) return cwd;
  // A5/W4 mirror: keep the leaf folder visible.
  const tail = cwd.slice(-(max - 1));
  const slash = tail.indexOf('/');
  return '…' + (slash > 0 ? tail.slice(slash) : tail);
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
  const hostId = info?.hostId;
  const cwd = info?.cwd || '/';
  // W4: derive a real chat title from the threads list when we have one,
  // so the meta block shows something humans can scan instead of a
  // session UUID prefix.
  const threadId = info?.threadId || info?.thread_id;
  const thread = threadId ? state.threads.find((t) => t.id === threadId) : null;
  const chatTitle = thread?.title && !thread.title_is_generic
    ? thread.title
    : (thread?.preview || (info?.sessionId ? `session ${info.sessionId.slice(0, 12)}…` : 'no session'));
  const host = state.hosts.find((h) => h.id === hostId);
  const hostLabel = host
    ? `${host.nickname}${host.os_user ? ' @' + host.os_user : ''}`
    : (hostId ? hostId.slice(0, 12) + '…' : '—');

  const [filesOpen, setFilesOpen] = useState(false);
  const fileInputRef = useRef(null);

  const onUpload = async (e) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file || !hostId) return;
    try {
      await workspaceApi.upload(hostId, cwd, file);
      setFilesOpen(true);
    } catch (err) {
      alert(`Upload failed: ${err.message || err}`);
    }
  };

  return (
    <div className="screen session-screen">
      {/* W4: two-line meta — title up top, host/model/cwd below.
          W6: workspace + add buttons live INSIDE the meta block as
          icon-only controls, so they're discoverable without claiming
          a whole row of vertical space between meta and chat. */}
      <div className="session-meta">
        <div className="session-meta-row1">
          <span className="session-meta-title" title={chatTitle}>{chatTitle}</span>
          <button
            type="button"
            className="meta-icon-btn"
            onClick={() => setFilesOpen(true)}
            disabled={!hostId}
            title="Workspace files (rename / delete / download)"
          >📁</button>
          <button
            type="button"
            className="meta-icon-btn add"
            onClick={() => fileInputRef.current?.click()}
            disabled={!hostId}
            title="Upload a file into the workspace cwd"
          >＋</button>
          <input
            ref={fileInputRef}
            type="file"
            style={{ display: 'none' }}
            onChange={onUpload}
          />
        </div>
        <div className="session-meta-row2">
          <span className="session-meta-host">{hostLabel}</span>
          {info?.model && <span className="session-meta-sep">·</span>}
          {info?.model && <span className="session-meta-model">{info.model}</span>}
          <span className="session-meta-sep">·</span>
          <span className="session-meta-cwd" title={cwd}>{shortenCwdLeft(cwd)}</span>
        </div>
      </div>
      {state.resuming && <ResumingBanner sinceMs={state.resumingSinceMs} />}
      {/* PLAN moved to the right sidebar tab — see App.jsx → RightSidebar.
          The chat surface stays focused on the conversation. */}
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
        goal={state.goal}
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
        onSlashCommand={workspaceApi?.sendSlash}
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
