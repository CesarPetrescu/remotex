// Mobile Web (PWA) wireframes — 3 variants
export default function MobilePanel() {
  return (
    <div>
      <div className="sect">
        <h2>Mobile web UI</h2>
        <span className="rule"></span>
        <span className="kicker">PWA · installable · iOS + Android safari</span>
      </div>

      <div className="variants cols-3">
        <MVA />
        <MVB />
        <MVC />
      </div>

      <div className="annot">
        <b>Shared constraints</b> thumb-reach for approve/deny · bottom sheet for critical moments · 44px+ hit targets · streaming
        feed that auto-scrolls unless the user scrolls up.
      </div>
    </div>
  );
}

const Frame = ({ children, style }) => (
  <div className="phone-frame" style={style}>
    <div
      style={{
        position: 'absolute',
        top: 0,
        left: 0,
        right: 0,
        height: 28,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '0 16px',
        fontFamily: 'var(--mono)',
        fontSize: 8,
        color: 'var(--ink-2)',
        zIndex: 10,
      }}
    >
      <span>9:41</span>
      <span>●●● ▂▄▆</span>
    </div>
    <div className="screen">{children}</div>
  </div>
);

/* =========== VARIANT A — Machine list → session (stacked) =========== */
function MVA() {
  const machines = [
    ['i9-4090', 'home rig', 'ok', '2 sessions live'],
    ['devbox-01', 'work', 'ok', 'idle · 3m ago'],
    ['xp7', 'laptop', 'warn', '⚠ approval needed'],
    ['r720', 'server', 'off', 'offline · 2d'],
  ];

  return (
    <div className="variant">
      <div className="variant-head">
        <div className="row" style={{ gap: 10 }}>
          <span className="num">A</span>
          <span className="title">Clean · iOS-leaning</span>
        </div>
        <span className="sub">list → drill → session</span>
      </div>
      <div className="variant-body" style={{ display: 'flex', gap: 14, justifyContent: 'center', flexWrap: 'wrap' }}>
        <Frame>
          <div style={{ padding: '0 4px' }}>
            <div className="mono" style={{ fontSize: 9, color: 'var(--ink-3)' }}>REMOTEX</div>
            <div style={{ fontSize: 18, fontWeight: 700, margin: '2px 0 10px' }}>Machines</div>

            {machines.map(([n, nick, s, sub], i) => (
              <div key={i} style={{ padding: '9px 8px', borderBottom: '1px solid var(--line-soft)' }}>
                <div className="row" style={{ justifyContent: 'space-between' }}>
                  <div className="row" style={{ gap: 7 }}>
                    <span className={'dot ' + s}></span>
                    <div>
                      <div className="mono" style={{ fontSize: 11, fontWeight: 700 }}>{n}</div>
                      <div className="mono dim" style={{ fontSize: 8 }}>{nick}</div>
                    </div>
                  </div>
                  <div
                    className="mono"
                    style={{ fontSize: 9, color: s === 'warn' ? 'var(--warn)' : 'var(--ink-3)', textAlign: 'right' }}
                  >
                    {sub}
                    <br />
                    <span className="dim">›</span>
                  </div>
                </div>
              </div>
            ))}

            <div
              style={{
                marginTop: 10,
                padding: 8,
                border: '1px dashed var(--line-soft)',
                borderRadius: 4,
                textAlign: 'center',
              }}
              className="mono dim"
            >
              ＋ pair new machine
            </div>
          </div>

          <div
            style={{
              position: 'absolute',
              left: 0,
              right: 0,
              bottom: 0,
              height: 44,
              borderTop: '1px solid var(--line-soft)',
              display: 'flex',
              background: 'var(--paper-2)',
            }}
          >
            {['hosts', 'activity', 'settings'].map((t, i) => (
              <div
                key={i}
                style={{
                  flex: 1,
                  textAlign: 'center',
                  padding: '8px 0',
                  fontFamily: 'var(--mono)',
                  fontSize: 9,
                  color: i === 0 ? 'var(--ink)' : 'var(--ink-3)',
                  fontWeight: i === 0 ? 700 : 400,
                  borderTop: i === 0 ? '2px solid var(--accent)' : '2px solid transparent',
                }}
              >
                {t}
              </div>
            ))}
          </div>
        </Frame>

        <Frame>
          <div style={{ padding: '0 2px' }}>
            <div
              className="row"
              style={{ gap: 5, fontSize: 10, fontFamily: 'var(--mono)', color: 'var(--ink-3)', padding: '0 6px' }}
            >
              <span>‹ i9-4090</span>
              <span style={{ flex: 1 }}></span>
              <span className="dot ok"></span>
              <span style={{ fontSize: 9 }}>live</span>
            </div>
            <div style={{ padding: '4px 6px 0' }}>
              <div className="mono" style={{ fontSize: 11, fontWeight: 700 }}>refactor/auth</div>
              <div className="mono dim" style={{ fontSize: 8 }}>gpt-5 · turn 7</div>
            </div>

            <div style={{ padding: '8px 6px', display: 'flex', flexDirection: 'column', gap: 6 }}>
              <div
                style={{ borderLeft: '2px solid #888', padding: '2px 6px', fontFamily: 'var(--mono)', fontSize: 9 }}
              >
                extract the JWT verify path
              </div>
              <div
                style={{ borderLeft: '2px solid #bbb', padding: '2px 6px', fontStyle: 'italic' }}
                className="mono dim"
              >
                reasoning…
              </div>
              <div style={{ borderLeft: '2px solid var(--accent-2)', padding: '2px 6px' }}>
                <div className="mono" style={{ fontSize: 8, color: 'var(--accent-2)' }}>shell</div>
                <div style={{ fontFamily: 'var(--mono)', fontSize: 8, background: '#f4f1ea', padding: '2px 4px' }}>
                  $ rg 'verifyJwt' src/
                </div>
              </div>
              <div
                style={{
                  borderLeft: '2px solid var(--accent)',
                  padding: '2px 6px',
                  fontFamily: 'var(--mono)',
                  fontSize: 9,
                }}
              >
                extracted into its own module
                <span style={{ display: 'inline-block', width: 4, height: 7, background: 'var(--ink)', marginLeft: 2 }} />
              </div>
            </div>
          </div>

          <div
            style={{
              position: 'absolute',
              left: 0,
              right: 0,
              bottom: 0,
              padding: '8px',
              background: 'var(--paper-2)',
              borderTop: '1px solid var(--line-soft)',
            }}
          >
            <div
              style={{
                background: '#fff',
                border: '1px solid var(--line-soft)',
                borderRadius: 18,
                padding: '6px 10px',
                display: 'flex',
                alignItems: 'center',
                gap: 6,
              }}
              className="mono"
            >
              <span style={{ color: 'var(--accent)' }}>❯</span>
              <span className="dim" style={{ fontSize: 9, flex: 1 }}>steer the turn…</span>
              <span
                style={{
                  background: 'var(--ink)',
                  color: 'var(--paper)',
                  padding: '3px 9px',
                  borderRadius: 12,
                  fontSize: 9,
                }}
              >
                send
              </span>
            </div>
          </div>
        </Frame>
      </div>

      <div className="annot">
        <b>Gestures</b> swipe-left on a machine card → quick actions (pause, revoke). Pull-to-refresh on the list. Tap agent message
        to expand reasoning.
      </div>
    </div>
  );
}

/* =========== VARIANT B — Approval bottom sheet =========== */
function MVB() {
  const diff = [
    ['  17', ' const decoded = decodeJwt(t)', ''],
    ['  18', '-if (decoded.exp < Date.now())', 'del'],
    ['  18', '+if (isExpired(decoded))', 'add'],
    ['  19', '+  throw new TokenExpired()', 'add'],
    ['  20', ' return verifySig(decoded)', ''],
    ['  21', '+// rotate kid later', 'add'],
    ['  22', '+export { verify }', 'add'],
  ];

  return (
    <div className="variant">
      <div className="variant-head">
        <div className="row" style={{ gap: 10 }}>
          <span className="num">B</span>
          <span className="title">Approval moment</span>
        </div>
        <span className="sub">the critical screen</span>
      </div>
      <div className="variant-body" style={{ display: 'flex', gap: 14, justifyContent: 'center', flexWrap: 'wrap' }}>
        <Frame>
          <div style={{ position: 'absolute', inset: '28px 0 0 0', padding: '4px 6px', opacity: 0.35 }}>
            <div className="mono" style={{ fontSize: 10, fontWeight: 700 }}>refactor/auth · turn 7</div>
            <div className="col" style={{ gap: 6, marginTop: 8 }}>
              <div
                style={{ borderLeft: '2px solid #888', padding: '2px 6px', fontFamily: 'var(--mono)', fontSize: 9 }}
              >
                extract the JWT verify path
              </div>
              <div
                style={{ borderLeft: '2px solid var(--accent-2)', padding: '2px 6px' }}
                className="mono"
              >
                shell · rg
              </div>
              <div
                style={{
                  borderLeft: '2px solid var(--accent)',
                  padding: '2px 6px',
                  fontFamily: 'var(--mono)',
                  fontSize: 9,
                }}
              >
                found 12 matches across 4 files…
              </div>
            </div>
          </div>
          <div style={{ position: 'absolute', inset: '28px 0 0 0', background: 'rgba(0,0,0,.4)' }} />

          <div
            style={{
              position: 'absolute',
              left: 0,
              right: 0,
              bottom: 0,
              background: 'var(--paper)',
              borderTop: '2px solid var(--ink)',
              borderRadius: '18px 18px 0 0',
              padding: '10px 14px 14px',
              boxShadow: '0 -4px 0 rgba(0,0,0,.08)',
            }}
          >
            <div
              style={{ width: 40, height: 4, background: 'var(--line-soft)', borderRadius: 2, margin: '0 auto 10px' }}
            />
            <div className="row" style={{ gap: 7, alignItems: 'flex-start' }}>
              <div
                style={{
                  width: 28,
                  height: 28,
                  borderRadius: 14,
                  background: 'var(--accent)',
                  color: '#fff',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  fontFamily: 'var(--mono)',
                  fontWeight: 700,
                }}
              >
                !
              </div>
              <div>
                <div style={{ fontSize: 12, fontWeight: 700 }}>Approval needed</div>
                <div className="mono dim" style={{ fontSize: 9 }}>i9-4090 · refactor/auth</div>
              </div>
            </div>

            <div className="mono" style={{ fontSize: 10, marginTop: 10, lineHeight: 1.45 }}>
              Write 2 files:
              <div
                style={{
                  background: '#fff',
                  border: '1px solid var(--line-soft)',
                  padding: '5px 7px',
                  marginTop: 4,
                  fontSize: 9,
                }}
              >
                <div>
                  ✎ <b>src/auth/verify.ts</b> <span style={{ color: 'var(--ok)' }}>+84</span>
                </div>
                <div>
                  ✎ <b>src/auth/index.ts</b> <span style={{ color: 'var(--ok)' }}>+6</span>{' '}
                  <span style={{ color: 'var(--warn)' }}>−12</span>
                </div>
              </div>
            </div>

            <div style={{ marginTop: 8, fontFamily: 'var(--mono)', fontSize: 9, color: 'var(--ink-3)' }}>
              Preview diff ›
            </div>

            <div className="col" style={{ gap: 6, marginTop: 12 }}>
              <div
                style={{
                  background: 'var(--ok)',
                  color: '#fff',
                  padding: '10px',
                  borderRadius: 6,
                  textAlign: 'center',
                  fontFamily: 'var(--mono)',
                  fontWeight: 700,
                  fontSize: 11,
                }}
              >
                Allow once
              </div>
              <div className="row" style={{ gap: 6 }}>
                <div
                  style={{
                    flex: 1,
                    background: 'var(--paper-2)',
                    padding: '9px',
                    border: '1px solid var(--line-soft)',
                    borderRadius: 6,
                    textAlign: 'center',
                    fontFamily: 'var(--mono)',
                    fontSize: 10,
                  }}
                >
                  Always allow
                </div>
                <div
                  style={{
                    flex: 1,
                    border: '1px solid var(--warn)',
                    color: 'var(--warn)',
                    padding: '9px',
                    borderRadius: 6,
                    textAlign: 'center',
                    fontFamily: 'var(--mono)',
                    fontSize: 10,
                    fontWeight: 700,
                  }}
                >
                  Deny
                </div>
              </div>
            </div>
          </div>
        </Frame>

        <Frame>
          <div style={{ padding: '0 4px' }}>
            <div
              className="row"
              style={{ gap: 5, fontSize: 10, fontFamily: 'var(--mono)', color: 'var(--ink-3)', padding: '2px 6px' }}
            >
              <span>✕ close</span>
              <span style={{ flex: 1, textAlign: 'center', fontWeight: 700, color: 'var(--ink)' }}>diff · verify.ts</span>
              <span>⋯</span>
            </div>
            <div
              style={{
                background: '#fff',
                border: '1px solid var(--line-soft)',
                margin: '6px 4px',
                fontFamily: 'var(--mono)',
                fontSize: 8,
              }}
            >
              {diff.map((r, i) => (
                <div
                  key={i}
                  style={{
                    display: 'flex',
                    padding: '2px 6px',
                    background: r[2] === 'add' ? 'rgba(58,107,92,.08)' : r[2] === 'del' ? 'rgba(192,74,43,.08)' : 'transparent',
                  }}
                >
                  <span className="dim" style={{ width: 24 }}>{r[0]}</span>
                  <span
                    style={{
                      color: r[2] === 'add' ? 'var(--ok)' : r[2] === 'del' ? 'var(--warn)' : 'var(--ink)',
                    }}
                  >
                    {r[1]}
                  </span>
                </div>
              ))}
            </div>
            <div className="mono dim" style={{ fontSize: 9, textAlign: 'center' }}>
              swipe → src/auth/index.ts
            </div>
            <div className="row" style={{ justifyContent: 'center', gap: 4, marginTop: 4 }}>
              <span style={{ width: 14, height: 3, background: 'var(--accent)' }} />
              <span style={{ width: 14, height: 3, background: 'var(--line-soft)' }} />
            </div>
          </div>

          <div
            style={{
              position: 'absolute',
              left: 0,
              right: 0,
              bottom: 0,
              padding: 10,
              display: 'flex',
              gap: 6,
              background: 'var(--paper-2)',
              borderTop: '1px solid var(--line-soft)',
            }}
          >
            <div
              style={{
                flex: 1,
                border: '1px solid var(--warn)',
                color: 'var(--warn)',
                padding: '9px',
                borderRadius: 6,
                textAlign: 'center',
                fontFamily: 'var(--mono)',
                fontSize: 10,
                fontWeight: 700,
              }}
            >
              Deny
            </div>
            <div
              style={{
                flex: 1.3,
                background: 'var(--ok)',
                color: '#fff',
                padding: '9px',
                borderRadius: 6,
                textAlign: 'center',
                fontFamily: 'var(--mono)',
                fontSize: 10,
                fontWeight: 700,
              }}
            >
              Allow
            </div>
          </div>
        </Frame>
      </div>

      <div className="annot">
        <b>Safety</b> Primary action (Allow) is large and thumb-reachable. Deny is same size but outlined, not buried. Nothing
        auto-resolves on timeout — codex waits.
      </div>
    </div>
  );
}

/* =========== VARIANT C — Terminal-style (cockpit) =========== */
function MVC() {
  const hosts = [
    ['●', 'i9-4090', 'home rig', '2 live', 'grn'],
    ['●', 'devbox-01', 'work', 'idle', 'grn'],
    ['✱', 'xp7', 'laptop', 'APPROVAL', 'amber'],
    ['○', 'r720', 'server', '2d offline', 'dim'],
  ];

  return (
    <div className="variant">
      <div className="variant-head">
        <div className="row" style={{ gap: 10 }}>
          <span className="num">C</span>
          <span className="title">Terminal / hacker cockpit</span>
        </div>
        <span className="sub">amber on black · monospace</span>
      </div>
      <div className="variant-body" style={{ display: 'flex', gap: 14, justifyContent: 'center', flexWrap: 'wrap' }}>
        <Frame style={{ background: '#111', borderColor: '#000' }}>
          <div className="terminal" style={{ padding: '30px 10px 10px', height: '100%', overflow: 'hidden' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between' }}>
              <span className="amber">remotex/</span>
              <span className="grn">● wss</span>
            </div>
            <div className="dim" style={{ marginTop: 4 }}>~/hosts/i9-4090/refactor-auth</div>
            <div style={{ borderTop: '1px dashed #333', margin: '6px 0' }} />
            <div>
              › <span className="hl">extract jwt verify path</span>
            </div>
            <div className="dim">  reasoning…</div>
            <div>
              <span className="grn">✓</span> shell · rg
            </div>
            <div className="dim">  → 12 matches</div>
            <div>
              <span className="grn">✓</span> read × 4
            </div>
            <div className="amber">✱ approval: write 2 files</div>
            <div style={{ marginTop: 2, marginLeft: 8 }}>
              <span style={{ border: '1px solid #c04a2b', color: '#c04a2b', padding: '1px 5px', marginRight: 4 }}>[d]eny</span>
              <span style={{ border: '1px solid #7dc87d', color: '#7dc87d', padding: '1px 5px' }}>[a]llow</span>
            </div>
            <div style={{ marginTop: 6 }}>
              here's the extracted module with
              <br />a narrower surface area
              <span style={{ display: 'inline-block', width: 5, height: 9, background: '#e8a756', marginLeft: 2 }} />
            </div>
          </div>
          <div
            style={{
              position: 'absolute',
              left: 0,
              right: 0,
              bottom: 0,
              padding: 8,
              background: '#0a0a0a',
              borderTop: '1px solid #222',
            }}
          >
            <div style={{ fontFamily: 'var(--mono)', fontSize: 9, color: '#e8a756' }}>
              ❯ <span style={{ color: '#6a6660' }}>type to steer…</span>
            </div>
          </div>
        </Frame>

        <Frame style={{ background: '#111', borderColor: '#000' }}>
          <div className="terminal" style={{ padding: '30px 10px 10px', height: '100%', overflow: 'hidden' }}>
            <div>
              <span className="amber">$</span> hosts
            </div>
            <div style={{ marginTop: 6 }} className="col">
              {hosts.map((r, i) => (
                <div
                  key={i}
                  style={{
                    padding: '5px 0',
                    borderBottom: '1px dashed #222',
                    display: 'flex',
                    gap: 8,
                  }}
                >
                  <span className={r[4]}>{r[0]}</span>
                  <div style={{ flex: 1 }}>
                    <div>{r[1]}</div>
                    <div className="dim" style={{ fontSize: 9 }}>
                      {r[2]} · {r[3]}
                    </div>
                  </div>
                  <span className="dim">›</span>
                </div>
              ))}
            </div>
            <div className="dim" style={{ marginTop: 8, textAlign: 'center', fontSize: 9 }}>
              ＋ pair new host
            </div>
          </div>
          <div
            style={{
              position: 'absolute',
              left: 0,
              right: 0,
              bottom: 0,
              height: 36,
              background: '#0a0a0a',
              borderTop: '1px solid #222',
              display: 'flex',
            }}
          >
            {['hosts', 'feed', 'keys'].map((t, i) => (
              <div
                key={i}
                style={{
                  flex: 1,
                  textAlign: 'center',
                  padding: '8px 0',
                  fontFamily: 'var(--mono)',
                  fontSize: 9,
                  color: i === 0 ? '#e8a756' : '#6a6660',
                  borderTop: i === 0 ? '1.5px solid #e8a756' : 'none',
                }}
              >
                {t}
              </div>
            ))}
          </div>
        </Frame>
      </div>

      <div className="annot">
        <b>For whom</b> the CTO-type user who lives in tmux. Dense, fast to scan, single hand. Same data, different vocabulary.
      </div>
    </div>
  );
}
