import { createPortal } from 'react-dom';

// Server-initiated approval modal — fires when codex asks to run a
// command or edit files under the Default / Read Only permissions
// modes. Three buttons: decline, accept, always (acceptForSession).
// Matches the Android AlertDialog shape 1:1.
//
// Rendered through a portal directly into document.body so the
// dashboard's grid layout (`.dashboard-layout > * { position: relative }`)
// can't kick the scrim out of viewport-fixed positioning into a
// stray grid cell at the bottom-left of the screen.
export function ApprovalDialog({ prompt, onDecision }) {
  if (!prompt) return null;
  const title =
    prompt.kind === 'command' ? 'COMMAND APPROVAL' : 'FILE CHANGE APPROVAL';
  const node = (
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
  return typeof document !== 'undefined' ? createPortal(node, document.body) : node;
}
