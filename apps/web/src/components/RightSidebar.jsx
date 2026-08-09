import { TelemetrySidebar } from './TelemetrySidebar';
import { SheetHandle } from './SheetHandle';
import { PendingPromptsPanel } from './PendingPromptsPanel';

// Right column: telemetry. A pending approval/user-input prompt takes over the
// panel while it's waiting (it has to surface to be actioned) and hands the
// column back to telemetry once resolved. Closing (`view === 'off'`) reclaims
// the column for the chat; the header telemetry button reopens it.
export function RightSidebar({
  view,
  onClose,
  telemetry,
  selectedHost,
  pendingApproval,
  pendingUserInput,
  pendingApprovalCount,
  pendingUserInputCount,
  onResolveApproval,
  onResolveUserInput,
  onCancelUserInput,
}) {
  if (view === 'off') return null;
  const hasPrompt = !!(pendingApproval || pendingUserInput);
  return (
    <aside className="right-sidebar" aria-label="Right sidebar">
      <SheetHandle onDismiss={onClose} label="Close panel" />
      <div className="right-sidebar-body">
        {hasPrompt ? (
          <PendingPromptsPanel
            approval={pendingApproval}
            userInput={pendingUserInput}
            approvalQueueLength={pendingApprovalCount}
            userInputQueueLength={pendingUserInputCount}
            onApprovalDecision={onResolveApproval}
            onUserInputSubmit={onResolveUserInput}
            onUserInputCancel={onCancelUserInput}
          />
        ) : (
          <TelemetrySidebar
            telemetry={telemetry}
            selectedHost={selectedHost}
          />
        )}
      </div>
    </aside>
  );
}
