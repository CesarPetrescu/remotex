import { TelemetrySidebar } from './TelemetrySidebar';
import { PendingPromptsPanel } from './PendingPromptsPanel';

const TAB_LABELS = {
  prompts: 'Prompts',
  telemetry: 'Telemetry',
};

// Right sidebar with tabs. Only one panel at a time — the user picks
// what to keep visible. Closing fully (`view === 'off'`) reclaims the
// whole right column for the chat; a small "open" handle in the
// dashboard header brings it back.
export function RightSidebar({
  view,
  onView,
  onClose,
  telemetry,
  selectedHost,
  pendingApproval,
  pendingUserInput,
  onResolveApproval,
  onResolveUserInput,
  onCancelUserInput,
}) {
  if (view === 'off') return null;
  const pendingPromptCount = (pendingApproval ? 1 : 0) + (pendingUserInput ? 1 : 0);
  return (
    <aside className="right-sidebar" aria-label="Right sidebar">
      <div className="right-sidebar-tabs">
        <RightTab
          id="prompts"
          active={view === 'prompts'}
          onClick={() => onView('prompts')}
          badge={pendingPromptCount > 0 ? String(pendingPromptCount) : null}
        />
        <RightTab
          id="telemetry"
          active={view === 'telemetry'}
          onClick={() => onView('telemetry')}
        />
        <button
          type="button"
          className="right-sidebar-close"
          onClick={onClose}
          aria-label="Close sidebar"
          title="Hide sidebar"
        >×</button>
      </div>
      <div className="right-sidebar-body">
        {view === 'prompts' ? (
          pendingPromptCount > 0 ? (
            <PendingPromptsPanel
              approval={pendingApproval}
              userInput={pendingUserInput}
              onApprovalDecision={onResolveApproval}
              onUserInputSubmit={onResolveUserInput}
              onUserInputCancel={onCancelUserInput}
            />
          ) : (
            <div className="right-sidebar-empty">No pending prompts.</div>
          )
        ) : (
          <TelemetrySidebar telemetry={telemetry} selectedHost={selectedHost} />
        )}
      </div>
    </aside>
  );
}

function RightTab({ id, active, onClick, badge }) {
  return (
    <button
      type="button"
      className={`right-sidebar-tab${active ? ' active' : ''}`}
      onClick={onClick}
      aria-pressed={active}
    >
      <span>{TAB_LABELS[id]}</span>
      {badge && <span className="right-sidebar-tab-badge">{badge}</span>}
    </button>
  );
}
