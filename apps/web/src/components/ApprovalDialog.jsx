// Server-initiated approval modal — fires when codex asks to run a
// command or edit files under the Default / Read Only permissions
// modes. Three buttons: decline, accept, always (acceptForSession).
// Matches the Android AlertDialog shape 1:1.
export function ApprovalDialog({ prompt, onDecision }) {
  if (!prompt) return null;
  const title =
    prompt.kind === 'command' ? 'COMMAND APPROVAL' : 'FILE CHANGE APPROVAL';
  return (
    <div className="modal-scrim" onClick={() => onDecision('cancel')}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-title">{title}</div>
        {prompt.reason && <div className="modal-reason">{prompt.reason}</div>}
        {prompt.command && <pre className="item-code">{prompt.command}</pre>}
        {prompt.cwd && <div className="modal-cwd">cwd: {prompt.cwd}</div>}
        <div className="modal-actions">
          <button type="button" className="btn-decline" onClick={() => onDecision('decline')}>
            decline
          </button>
          {prompt.decisions.includes('acceptForSession') && (
            <button type="button" className="btn-always" onClick={() => onDecision('acceptForSession')}>
              always
            </button>
          )}
          <button type="button" className="btn-accept" onClick={() => onDecision('accept')}>
            accept
          </button>
        </div>
      </div>
    </div>
  );
}
