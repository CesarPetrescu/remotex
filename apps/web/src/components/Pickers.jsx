import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { MODEL_OPTIONS, PERMISSIONS, effortsFor } from '../config';

// Composer footer selectors, Claude-style: quiet text buttons that open
// a small menu — permissions bottom-left, model + reasoning
// bottom-right. Each shows its live value, so every pane displays its
// own settings without chrome.

export function ModelSelect({ value, onChange, models }) {
  const list = models && models.length > 0 ? models : MODEL_OPTIONS;
  const current = list.find((m) => m.id === value) || list[0];
  return (
    <MiniSelect
      ariaLabel="Model"
      value={current.label}
      valueClass="model"
      items={list}
      selectedId={current.id}
      onPick={(opt) => onChange(opt.id)}
      renderItem={(opt) => (
        <div className="dd-line" title={opt.hint || ''}>{opt.label}</div>
      )}
    />
  );
}

export function ReasoningSelect({ model, value, onChange, models }) {
  const options = effortsFor(model, models && models.length > 0 ? models : MODEL_OPTIONS);
  const display = options.includes(value) ? value : '';
  return (
    <MiniSelect
      ariaLabel="Reasoning effort"
      value={display || 'default'}
      items={options.map((e) => ({ id: e, label: e || 'default' }))}
      selectedId={display}
      onPick={(opt) => onChange(opt.id)}
      renderItem={(opt) => <div className="dd-line">{opt.label}</div>}
    />
  );
}

export function PermissionsSelect({ value, onChange }) {
  const current = PERMISSIONS.find((p) => p.id === value) || PERMISSIONS[0];
  const danger = value === 'full';
  return (
    <MiniSelect
      ariaLabel="Permissions"
      value={current.label.toLowerCase()}
      valueClass={danger ? 'danger' : ''}
      items={PERMISSIONS}
      selectedId={current.id}
      onPick={(opt) => onChange(opt.id)}
      renderItem={(opt) => (
        <div>
          <div className={`dd-line ${opt.id === 'full' ? 'danger' : ''}`}>{opt.label}</div>
          <div className="dd-hint">{opt.hint}</div>
        </div>
      )}
    />
  );
}

// --- internal primitive ---

function MiniSelect({ ariaLabel, value, valueClass = '', items, renderItem, onPick, selectedId }) {
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState(null);
  const ref = useRef(null);
  const menuRef = useRef(null);

  // Portalled to <body> with position:fixed so ancestor overflow can't
  // clip it; the phone media query redocks it as a bottom sheet.
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
      const width = Math.max(r.width, 190);
      const left = Math.max(8, Math.min(r.left, window.innerWidth - width - 8));
      setPos({ left, width, bottom: window.innerHeight - r.top + 4 });
    }
    setOpen((o) => !o);
  }

  return (
    <div ref={ref} className="mini-select-wrap">
      <button
        type="button"
        className={`mini-select ${valueClass}`}
        onClick={toggle}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel}
        title={ariaLabel}
      >
        {value}
        <span className="mini-select-caret" aria-hidden="true">▾</span>
      </button>
      {open && pos
        && createPortal(
          <>
            <div className="dd-scrim" onClick={() => setOpen(false)} aria-hidden="true" />
            <div
              ref={menuRef}
              className="dd dd-portal"
              role="listbox"
              aria-label={ariaLabel}
              style={{ left: pos.left, width: pos.width, bottom: pos.bottom }}
            >
              <div className="dd-head">
                <span className="dd-head-label">{ariaLabel.toLowerCase()}</span>
                <button
                  type="button"
                  className="dd-close"
                  onClick={() => setOpen(false)}
                  aria-label="Close"
                >
                  ×
                </button>
              </div>
              {items.map((it, i) => {
                const active = selectedId !== undefined && it.id === selectedId;
                return (
                  <button
                    key={it.id || i}
                    type="button"
                    role="option"
                    aria-selected={active}
                    className={`dd-item${active ? ' selected' : ''}`}
                    onClick={() => {
                      onPick(it);
                      setOpen(false);
                    }}
                  >
                    <span className="dd-check" aria-hidden="true">{active ? '✓' : ''}</span>
                    <span className="dd-item-main">{renderItem(it)}</span>
                  </button>
                );
              })}
            </div>
          </>,
          document.body,
        )}
    </div>
  );
}
