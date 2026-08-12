import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { MODEL_OPTIONS, PERMISSIONS, effortsFor } from '../config';

// One ⚙ options popover next to the send button: model, reasoning
// effort, and permissions in a single panel. The trigger itself shows
// the current per-chat values, so even a small grid pane displays its
// own settings at a glance without a whole chip row.
export function ComposerOptions({
  model,
  effort,
  permissions,
  models,
  onModelChange,
  onEffortChange,
  onPermissionsChange,
}) {
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState(null);
  const ref = useRef(null);
  const menuRef = useRef(null);

  const modelList = models && models.length > 0 ? models : MODEL_OPTIONS;
  const currentModel = modelList.find((m) => m.id === model) || modelList[0];
  const efforts = effortsFor(model, modelList);
  const effortValue = efforts.includes(effort) ? effort : '';
  const currentPerms = PERMISSIONS.find((p) => p.id === permissions) || PERMISSIONS[0];
  const danger = permissions === 'full';

  useEffect(() => {
    if (!open) return undefined;
    function onDown(e) {
      if (ref.current?.contains(e.target)) return;
      if (menuRef.current?.contains(e.target)) return;
      setOpen(false);
    }
    function onReflow(e) {
      if (e.target === menuRef.current) return;
      setOpen(false);
    }
    function onKey(e) {
      if (e.key === 'Escape') setOpen(false);
    }
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    window.addEventListener('resize', onReflow);
    window.addEventListener('scroll', onReflow, true);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
      window.removeEventListener('resize', onReflow);
      window.removeEventListener('scroll', onReflow, true);
    };
  }, [open]);

  function toggle() {
    if (!open && ref.current) {
      const r = ref.current.getBoundingClientRect();
      const width = Math.max(r.width, 230);
      const left = Math.max(8, Math.min(r.right - width, window.innerWidth - width - 8));
      setPos({ left, width, bottom: window.innerHeight - r.top + 4 });
    }
    setOpen((o) => !o);
  }

  function section(label, items, selectedId, onPick, renderItem) {
    return (
      <>
        <div className="dd-section-label">{label}</div>
        {items.map((it) => {
          const active = it.id === selectedId;
          return (
            <button
              key={it.id || 'default'}
              type="button"
              role="option"
              aria-selected={active}
              className={`dd-item${active ? ' selected' : ''}`}
              onClick={() => onPick(it)}
            >
              <span className="dd-check" aria-hidden="true">{active ? '✓' : ''}</span>
              <span className="dd-item-main">{renderItem(it)}</span>
            </button>
          );
        })}
      </>
    );
  }

  return (
    <div ref={ref} className="composer-options">
      <button
        type="button"
        className={`composer-options-btn ${danger ? 'danger' : ''}`}
        onClick={toggle}
        aria-haspopup="dialog"
        aria-expanded={open}
        title={`model ${currentModel.label} · reasoning ${effortValue || 'default'} · permissions ${currentPerms.label}`}
      >
        <span className="co-model">{currentModel.label}</span>
        <span className="co-sep">·</span>
        <span className="co-effort">{effortValue || 'default'}</span>
        <span className="co-sep">·</span>
        <span className={`co-perms ${danger ? 'danger' : ''}`}>
          {currentPerms.label.toLowerCase()}
        </span>
      </button>
      {open && pos
        && createPortal(
          <>
            <div className="dd-scrim" onClick={() => setOpen(false)} aria-hidden="true" />
            <div
              ref={menuRef}
              className="dd dd-portal dd-options"
              role="dialog"
              aria-label="Chat options"
              style={{ left: pos.left, width: pos.width, bottom: pos.bottom }}
            >
              <div className="dd-head">
                <span className="dd-head-label">chat options</span>
                <button
                  type="button"
                  className="dd-close"
                  onClick={() => setOpen(false)}
                  aria-label="Close"
                >
                  ×
                </button>
              </div>
              {section('model', modelList, currentModel.id,
                (opt) => onModelChange(opt.id),
                (opt) => <div className="dd-line" title={opt.hint || ''}>{opt.label}</div>)}
              {section('reasoning', efforts.map((e) => ({ id: e, label: e || 'default' })),
                effortValue,
                (opt) => onEffortChange(opt.id),
                (opt) => <div className="dd-line">{opt.label}</div>)}
              {section('permissions', PERMISSIONS, currentPerms.id,
                (opt) => onPermissionsChange(opt.id),
                (opt) => (
                  <div>
                    <div className={`dd-line ${opt.id === 'full' ? 'danger' : ''}`}>{opt.label}</div>
                    <div className="dd-hint">{opt.hint}</div>
                  </div>
                ))}
            </div>
          </>,
          document.body,
        )}
    </div>
  );
}
