import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { MODEL_OPTIONS, PERMISSIONS, effortsFor } from '../config';

// Three compact dropdown chips shown in the composer row. Each uses
// the same ChipDropdown primitive so the popup styling stays
// consistent with the rest of the boxy UI.

export function ModelPicker({ value, onChange, models }) {
  const list = models && models.length > 0 ? models : MODEL_OPTIONS;
  const current = list.find((m) => m.id === value) || list[0];
  return (
    <ChipDropdown
      label="model"
      value={current.label}
      items={list}
      // Name only. The model list is long and fetched from codex, so a
      // description on every row turned picking a model into reading a page.
      // Kept as the title so the blurb is still there on hover.
      renderItem={(opt) => (
        <div className="dd-line" title={opt.hint || ''}>{opt.label}</div>
      )}
      selectedId={current.id}
      onPick={(opt) => onChange(opt.id)}
    />
  );
}

export function EffortPicker({ model, value, onChange, models }) {
  const options = effortsFor(model, models && models.length > 0 ? models : MODEL_OPTIONS);
  const display = options.includes(value) ? value : '';
  return (
    <ChipDropdown
      label="effort"
      value={display || 'default'}
      items={options.map((e) => ({ id: e, label: e || 'default' }))}
      renderItem={(opt) => <div className="dd-line">{opt.label}</div>}
      selectedId={display}
      onPick={(opt) => onChange(opt.id)}
    />
  );
}

export function PermissionsPicker({ value, onChange }) {
  const current = PERMISSIONS.find((p) => p.id === value) || PERMISSIONS[0];
  const danger = value === 'full';
  return (
    <ChipDropdown
      label="perms"
      value={current.label.toLowerCase()}
      chipClass={danger ? 'danger' : ''}
      items={PERMISSIONS}
      renderItem={(opt) => (
        <div>
          <div className={`dd-line ${opt.id === 'full' ? 'danger' : ''}`}>{opt.label}</div>
          <div className="dd-hint">{opt.hint}</div>
        </div>
      )}
      selectedId={current.id}
      onPick={(opt) => onChange(opt.id)}
    />
  );
}

// --- internal primitive ---

function ChipDropdown({
  label, value, chipClass = '', items, renderItem, onPick, selectedId,
}) {
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState(null);
  const ref = useRef(null);
  const menuRef = useRef(null);

  // The menu is portalled to <body> with position:fixed so it can't be
  // clipped by an ancestor's overflow — the composer's `.chip-row` is
  // `overflow-x:auto` on mobile, which used to swallow the whole dropdown.
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
      const width = Math.max(r.width, 168);
      const left = Math.max(8, Math.min(r.left, window.innerWidth - width - 8));
      // Open upward above the chip (the composer sits at the bottom).
      setPos({ left, width, bottom: window.innerHeight - r.top + 4 });
    }
    setOpen((o) => !o);
  }

  return (
    <div ref={ref} className={`chip ${chipClass}`}>
      <button
        type="button"
        className="chip-button"
        onClick={toggle}
        aria-haspopup="listbox"
        aria-expanded={open}
      >
        <span className="chip-label">{label}</span>
        <span className="chip-value">{value}</span>
      </button>
      {open && pos
        && createPortal(
          <>
            {/* Phone-only: the menu becomes a bottom sheet, so it needs a
                scrim to read as modal and to catch taps outside it. Hidden
                on desktop, where the mousedown listener already suffices. */}
            <div className="dd-scrim" onClick={() => setOpen(false)} aria-hidden="true" />
            <div
              ref={menuRef}
              className="dd dd-portal"
              role="listbox"
              aria-label={label}
              /* Inline coords are the desktop anchor; the phone media query
                 overrides them with !important to dock this to the bottom. */
              style={{ left: pos.left, width: pos.width, bottom: pos.bottom }}
            >
              <div className="dd-head">
                <span className="dd-head-label">{label}</span>
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
                    key={i}
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
