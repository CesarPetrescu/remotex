import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';

/**
 * Settings modal — currently just the bearer token and where it is kept.
 *
 * "Remember on this device" ON (the default) stores the token in
 * localStorage, so it survives a browser restart. OFF stores it in
 * sessionStorage, so it dies with the tab — the right choice on a shared
 * machine. Either way it is a long-lived bearer token readable by any
 * script on this origin; this narrows the window, it does not close it.
 */
export function SettingsPanel({ open, token, remember, onSave, onClose }) {
  const [draft, setDraft] = useState(token || '');
  const [keep, setKeep] = useState(remember !== false);
  const [reveal, setReveal] = useState(false);

  useEffect(() => {
    if (!open) return;
    setDraft(token || '');
    setKeep(remember !== false);
    setReveal(false);
  }, [open, token, remember]);

  if (!open) return null;

  const save = () => {
    onSave(draft.trim(), keep);
    onClose();
  };

  const node = (
    <div className="jp-scrim" onClick={onClose}>
      <div
        className="jp-modal settings-modal"
        role="dialog"
        aria-modal="true"
        aria-label="Settings"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="jp-input-row">
          <span className="jp-glyph">⚙</span>
          <span className="settings-title">SETTINGS</span>
          <button type="button" className="jp-x" onClick={onClose} aria-label="Close">
            ×
          </button>
        </div>

        <div className="settings-body">
          <label className="settings-label" htmlFor="settings-token">
            Access token
          </label>
          <div className="settings-token-row">
            <input
              id="settings-token"
              className="jp-input"
              type={reveal ? 'text' : 'password'}
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') save();
                if (e.key === 'Escape') onClose();
              }}
              spellCheck={false}
              autoComplete="off"
            />
            <button
              type="button"
              className="btn-sm"
              onClick={() => setReveal((v) => !v)}
            >
              {reveal ? 'hide' : 'show'}
            </button>
          </div>

          <label className="settings-check">
            <input
              type="checkbox"
              checked={keep}
              onChange={(e) => setKeep(e.target.checked)}
            />
            <span>Remember on this device</span>
          </label>
          <p className="settings-hint">
            {keep
              ? 'Stored in this browser until you clear it. Leave it off on a shared machine.'
              : 'Kept for this tab only — closing the tab signs you out.'}
          </p>
        </div>

        <div className="jp-selectbar">
          <button type="button" className="btn-sm" onClick={onClose}>
            Cancel
          </button>
          <button type="button" className="btn-primary jp-use" onClick={save}>
            Save
          </button>
        </div>
      </div>
    </div>
  );

  return typeof document !== 'undefined' ? createPortal(node, document.body) : node;
}
