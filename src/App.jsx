import { useEffect, useState } from 'react';
import SystemsPanel from './panels/SystemsPanel.jsx';
import DesktopPanel from './panels/DesktopPanel.jsx';
import MobilePanel from './panels/MobilePanel.jsx';
import AndroidPanel from './panels/AndroidPanel.jsx';

const TABS = [
  { key: 'sys', label: 'Systems', count: 3, Panel: SystemsPanel },
  { key: 'desk', label: 'Desktop Web', count: 3, Panel: DesktopPanel },
  { key: 'mob', label: 'Mobile Web', count: 3, Panel: MobilePanel },
  { key: 'droid', label: 'Android', count: 3, Panel: AndroidPanel },
];

const PERSIST_TAB = 'remotex.tab';
const DEFAULT_TWEAKS = {
  grain: true,
  accent: '#d97642',
  hand: true,
  pins: true,
  density: 'cozy',
};

export default function App() {
  const [active, setActive] = useState(() => {
    try {
      const saved = localStorage.getItem(PERSIST_TAB);
      if (saved && TABS.some((t) => t.key === saved)) return saved;
    } catch (e) {}
    return 'sys';
  });

  const [tweaks, setTweaks] = useState(DEFAULT_TWEAKS);
  const [tweaksOpen, setTweaksOpen] = useState(false);

  useEffect(() => {
    try { localStorage.setItem(PERSIST_TAB, active); } catch (e) {}
  }, [active]);

  useEffect(() => {
    document.documentElement.style.setProperty('--accent', tweaks.accent);
    document.body.style.backgroundImage = tweaks.grain
      ? 'radial-gradient(circle at 20% 10%, rgba(0,0,0,.025), transparent 40%), radial-gradient(circle at 80% 90%, rgba(0,0,0,.03), transparent 45%), repeating-linear-gradient(0deg, rgba(0,0,0,0.018) 0 1px, transparent 1px 28px)'
      : 'none';
    document.body.classList.toggle('no-hand', !tweaks.hand);
    document.body.classList.toggle('no-pins', !tweaks.pins);
    document.body.dataset.density = tweaks.density;
  }, [tweaks]);

  const update = (k, v) => setTweaks((t) => ({ ...t, [k]: v }));

  return (
    <>
      <div className="wrap">
        <header className="masthead">
          <div>
            <h1>
              Remotex <span className="swish">wireframes</span>
            </h1>
            <div className="hand" style={{ fontSize: 20, color: 'var(--ink-3)', marginTop: 4 }}>
              exploring the shape of a remote-control client for your Codex daemons
            </div>
          </div>
          <div className="meta">
            <b>v0.1 · low-fi exploration</b>
            <br />
            4 surfaces · 3 variants each
            <br />
            not final · everything movable
          </div>
        </header>

        <nav className="tabs" role="tablist">
          {TABS.map((t) => (
            <button
              key={t.key}
              className="tab"
              role="tab"
              aria-selected={active === t.key}
              onClick={() => setActive(t.key)}
            >
              {t.label} <span className="tab-count">×{t.count}</span>
            </button>
          ))}
        </nav>

        {TABS.map((t) => (
          <section
            key={t.key}
            className="panel"
            {...(active === t.key ? { 'data-active': '' } : {})}
          >
            <t.Panel />
          </section>
        ))}
      </div>

      <button className="tweaks-fab" onClick={() => setTweaksOpen((v) => !v)}>
        TWEAKS
      </button>
      {tweaksOpen && (
        <div className="tweaks-panel">
          <h4>Tweaks</h4>
          <label>
            Paper grain
            <input type="checkbox" checked={tweaks.grain} onChange={(e) => update('grain', e.target.checked)} />
          </label>
          <label>
            Accent color
            <select value={tweaks.accent} onChange={(e) => update('accent', e.target.value)}>
              <option value="#d97642">orange</option>
              <option value="#3a6b5c">teal</option>
              <option value="#c2a94a">mustard</option>
              <option value="#6b4e8e">plum</option>
            </select>
          </label>
          <label>
            Hand-font annotations
            <input type="checkbox" checked={tweaks.hand} onChange={(e) => update('hand', e.target.checked)} />
          </label>
          <label>
            Show pins/callouts
            <input type="checkbox" checked={tweaks.pins} onChange={(e) => update('pins', e.target.checked)} />
          </label>
          <label>
            Density
            <select value={tweaks.density} onChange={(e) => update('density', e.target.value)}>
              <option value="compact">compact</option>
              <option value="cozy">cozy</option>
              <option value="roomy">roomy</option>
            </select>
          </label>
        </div>
      )}
    </>
  );
}
