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

function compactTokens(value) {
  const n = Number(value || 0);
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(n >= 10_000_000 ? 0 : 1)}m`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(n >= 10_000 ? 0 : 1)}k`;
  return String(Math.max(0, Math.round(n)));
}

function goalStatusLabel(status) {
  return String(status || 'active')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/[-_]/g, ' ')
    .toLowerCase();
}

export function SessionUsage({
  goal,
  tokensInput = 0,
  tokensOutput = 0,
  tokensCached = 0,
  tokensReasoning = 0,
}) {
  const hasTokens = [tokensInput, tokensOutput, tokensCached, tokensReasoning]
    .some((value) => Number(value) > 0);
  if (!goal && !hasTokens) return null;

  const budget = Number(goal?.token_budget);
  const used = Math.max(0, Number(goal?.tokens_used || 0));
  const hasBudget = Number.isFinite(budget) && budget > 0;
  const percent = hasBudget ? Math.round((used / budget) * 100) : null;

  return (
    <div className="session-usage" aria-label="Session usage">
      {goal && (
        <span className="session-goal-usage" title={goal.objective || 'Codex goal'}>
          goal {goalStatusLabel(goal.status)}
          {hasBudget && (
            <>
              <progress
                max={budget}
                value={Math.min(used, budget)}
                aria-label="Goal token budget"
              />
              <span>{compactTokens(used)} / {compactTokens(budget)} ({percent}%)</span>
            </>
          )}
          {!hasBudget && used > 0 && <span> · {compactTokens(used)} used</span>}
        </span>
      )}
      {hasTokens && (
        <span className="session-token-usage">
          tokens {compactTokens(tokensInput)} in · {compactTokens(tokensOutput)} out
          {Number(tokensCached) > 0 && <> · {compactTokens(tokensCached)} cached</>}
          {Number(tokensReasoning) > 0 && <> · {compactTokens(tokensReasoning)} reasoning</>}
        </span>
      )}
    </div>
  );
}

export function SessionScreen({
  state,
  onSend,
  onStop,
  onSteer,
  onQueue,
  onRemoveQueued,
  onLoadOlder,
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
  // Title precedence: real thread title → your latest message → thread
  // preview → a neutral placeholder. Never the session id — nobody can do
  // anything with `session sess_29a6…`.
  const lastUserText = [...state.events].reverse()
    .find((e) => e.role === 'user' && e.text && !e.slash)?.text?.trim();
  const clip = (t) => (t.length > 80 ? `${t.slice(0, 77)}…` : t);
  const chatTitle = thread?.title && !thread.title_is_generic
    ? thread.title
    : (lastUserText ? clip(lastUserText) : (thread?.preview || 'New session'));
  const host = state.hosts.find((h) => h.id === hostId);
  const hostLabel = host
    ? `${host.nickname}${host.os_user ? ' @' + host.os_user : ''}`
    : (hostId ? hostId.slice(0, 12) + '…' : '—');

  const [filesOpen, setFilesOpen] = useState(false);
  // Phone: the static facts row folds while reading history (not at tail).
  const [atBottom, setAtBottom] = useState(true);
  const fileInputRef = useRef(null);

  const onUpload = async (e) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file || !hostId) return;
    // Oversize is rejected by workspaceUploadFile before any bytes leave
    // the browser (contract A) — surface its message like any other
    // upload failure instead of letting the request fail opaquely.
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
      <div className={`session-meta ${atBottom ? '' : 'compact'}`}>
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
        <SessionUsage
          goal={state.goal}
          tokensInput={state.tokensInput}
          tokensOutput={state.tokensOutput}
          tokensCached={state.tokensCached}
          tokensReasoning={state.tokensReasoning}
        />
      </div>
      {state.resuming && <ResumingBanner sinceMs={state.resumingSinceMs} />}
      {/* PLAN moved to the right sidebar tab — see App.jsx → RightSidebar.
          The chat surface stays focused on the conversation. */}
      <EventStream
        events={state.events}
        pending={state.pending}
        pendingSinceMs={state.pendingSinceMs}
        historyHasMore={state.historyHasMore}
        historyLoading={state.historyLoading}
        historyTick={state.historyTick}
        historyPrepend={state.historyPrepend}
        onLoadOlder={onLoadOlder}
        onAtBottomChange={setAtBottom}
        onStop={onStop}
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
        queuedTurns={state.queuedTurns}
        onModelChange={onModelChange}
        onEffortChange={onEffortChange}
        onPermissionsChange={onPermissionsChange}
        onSend={onSend}
        onSteer={onSteer}
        onQueue={onQueue}
        onRemoveQueued={onRemoveQueued}
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
