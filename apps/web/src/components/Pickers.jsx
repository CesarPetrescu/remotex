import { useEffect, useRef, useState } from 'react';
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
      renderItem={(opt) => (
        <div>
          <div className="dd-line">{opt.label}</div>
          <div className="dd-hint">{opt.hint}</div>
        </div>
      )}
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
      onPick={(opt) => onChange(opt.id)}
    />
  );
}

// 4th chip: which kind of session the next "+ New session" opens.
// `coder`        → today's flow, single codex agent.
// `orchestrator` → opens the launcher modal so the user can supply a
//                  task; the brain plans + fans out to child codexes.
// When inside an active session the chip shows the SESSION's actual
// kind and is read-only — you can't switch a running session in place.
export const SESSION_KINDS = [
  { id: 'coder', label: 'coder', hint: 'one Codex agent works directly in this thread' },
  { id: 'orchestrator', label: 'orchestrator', hint: 'brain uses MCP tools to delegate to child agents' },
];

export function KindPicker({ value, onChange, locked = false, lockedReason = '' }) {
  const current = SESSION_KINDS.find((k) => k.id === value) || SESSION_KINDS[0];
  if (locked) {
    return (
      <span
        className="chip-static"
        title={lockedReason || 'Active session — kind is fixed'}
      >
        <span className="chip-label">kind</span>
        <span className="chip-value">{current.label}</span>
      </span>
    );
  }
  return (
    <ChipDropdown
      label="kind"
      value={current.label}
      items={SESSION_KINDS}
      renderItem={(opt) => (
        <div>
          <div className="dd-line">{opt.label}</div>
          <div className="dd-hint">{opt.hint}</div>
        </div>
      )}
      onPick={(opt) => onChange(opt.id)}
    />
  );
}

// --- internal primitive ---

function ChipDropdown({ label, value, chipClass = '', items, renderItem, onPick }) {
  const [open, setOpen] = useState(false);
  const ref = useRef(null);

  // Outside-click dismiss — same behaviour as Android's DropdownMenu
  // which closes when you tap anywhere outside its bounds.
  useEffect(() => {
    if (!open) return undefined;
    function onDown(e) {
      if (ref.current && !ref.current.contains(e.target)) setOpen(false);
    }
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open]);

  return (
    <div ref={ref} className={`chip ${chipClass}`}>
      <button type="button" className="chip-button" onClick={() => setOpen((o) => !o)}>
        <span className="chip-label">{label}</span>
        <span className="chip-value">{value}</span>
      </button>
      {open && (
        <div className="dd">
          {items.map((it, i) => (
            <button
              key={i}
              type="button"
              className="dd-item"
              onClick={() => {
                onPick(it);
                setOpen(false);
              }}
            >
              {renderItem(it)}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
