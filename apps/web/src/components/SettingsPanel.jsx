import { createPortal } from 'react-dom';

export function SettingsPanel({ open, remember, onSignOut, onClose }) {
  if (!open) return null;

  const node = (
    <div className="jp-scrim" onClick={onClose}>
      <div
        className="jp-modal settings-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="settings-title"
        onClick={(event) => event.stopPropagation()}
        onKeyDown={(event) => {
          if (event.key === 'Escape') onClose();
        }}
      >
        <div className="jp-input-row">
          <span className="jp-glyph" aria-hidden="true">⚙</span>
          <span id="settings-title" className="settings-title">SETTINGS</span>
          <button type="button" className="jp-x" onClick={onClose} aria-label="Close">
            ×
          </button>
        </div>

        <div className="settings-body">
          <span className="settings-label">Account</span>
          <strong className="settings-status">Access token verified</strong>
          <p className="settings-hint">
            {remember
              ? 'Signed in on this device. The token stays in this browser until you sign out.'
              : 'Signed in for this tab only. Closing the tab removes the token.'}
          </p>
        </div>

        <div className="jp-selectbar">
          <button type="button" className="btn-sm" onClick={onClose} autoFocus>
            Close
          </button>
          <button type="button" className="btn-sm settings-signout" onClick={onSignOut}>
            Sign out
          </button>
        </div>
      </div>
    </div>
  );

  return typeof document !== 'undefined' ? createPortal(node, document.body) : node;
}
