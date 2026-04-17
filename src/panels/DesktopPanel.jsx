// Desktop Web wireframes — 3 variants
export default function DesktopPanel() {
  return (
    <div>
      <div className="sect">
        <h2>Desktop web UI</h2>
        <span className="rule"></span>
        <span className="kicker">browser · 1440w · three layouts</span>
      </div>

      <div className="variants">
        <DVA />
        <DVB />
        <DVC />
      </div>

      <div className="annot">
        <b>Screens covered</b> machine list · machine detail + sessions · live turn view · approval prompt · diff viewer ·
        settings/api keys. Each variant shows the live session as the hero screen + supporting surfaces as thumbnails.
      </div>
    </div>
  );
}

/* ---------- shared atoms ---------- */
const Line = ({ w = 80, dark = false }) => <div className={'ln' + (dark ? ' dark' : '')} style={{ width: w + '%' }} />;
const Stack = ({ g = 6, children, style }) => (
  <div style={{ display: 'flex', flexDirection: 'column', gap: g, ...style }}>{children}</div>
);

/* ============================================================== */
/* VARIANT A — Split pane: sidebar + stream + inspector           */
/* ============================================================== */
function DVA() {
  return (
    <div className="variant">
      <div className="variant-head">
        <div className="row" style={{ gap: 10 }}>
          <span className="num">A</span>
          <span className="title">Tri-pane workspace</span>
        </div>
        <span className="sub">Linear/tmux feel · dense, neutral</span>
      </div>
      <div className="variant-body">
        <div className="desktop-frame">
          <div className="chrome">
            <span style={{ marginLeft: 36 }}>remotex.app / session / i9-4090 / repo · feature-branch</span>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '160px 1fr 220px', height: 'calc(100% - 22px)' }}>
            <aside style={{ borderRight: '1px solid var(--line-soft)', padding: '10px 8px', background: 'var(--paper-2)' }}>
              <div className="mono" style={{ fontSize: 9, color: 'var(--ink-3)', letterSpacing: '.1em' }}>MACHINES</div>
              <Stack g={4} style={{ marginTop: 6 }}>
                {[
                  ['i9-4090', 'home rig', 'ok', true],
                  ['devbox-01', 'work', 'ok', false],
                  ['xp7', 'laptop', 'warn', false],
                  ['r720', 'server', 'off', false],
                ].map(([n, nick, s, sel], i) => (
                  <div
                    key={i}
                    style={{
                      padding: '5px 6px',
                      border: sel ? '1px solid var(--ink)' : '1px solid transparent',
                      background: sel ? '#fff' : 'transparent',
                      borderRadius: 3,
                    }}
                  >
                    <div className="row" style={{ gap: 5 }}>
                      <span className={'dot ' + s}></span>
                      <span className="mono" style={{ fontSize: 10, fontWeight: 600 }}>{n}</span>
                    </div>
                    <div className="mono dim" style={{ fontSize: 8, marginLeft: 13 }}>{nick}</div>
                  </div>
                ))}
              </Stack>

              <div className="mono" style={{ fontSize: 9, color: 'var(--ink-3)', letterSpacing: '.1em', marginTop: 14 }}>
                THREADS · i9
              </div>
              <Stack g={3} style={{ marginTop: 6 }}>
                {['refactor/auth', 'bug #482 retry', '+ new thread'].map((t, i) => (
                  <div
                    key={i}
                    className="mono"
                    style={{
                      fontSize: 9,
                      padding: '3px 4px',
                      color: i === 0 ? 'var(--ink)' : 'var(--ink-3)',
                      background: i === 0 ? '#fff' : 'transparent',
                      borderRadius: 2,
                    }}
                  >
                    {t}
                  </div>
                ))}
              </Stack>
            </aside>

            <main style={{ padding: '8px 14px', overflow: 'hidden' }}>
              <div
                className="row"
                style={{ justifyContent: 'space-between', borderBottom: '1px dashed var(--line-soft)', paddingBottom: 6 }}
              >
                <div>
                  <div className="mono" style={{ fontSize: 10, fontWeight: 700 }}>refactor/auth · turn 7</div>
                  <div className="mono dim" style={{ fontSize: 9 }}>gpt-5 · ~/src/remotex · sandbox:workspace-write</div>
                </div>
                <div className="row" style={{ gap: 4 }}>
                  <span className="chip">●<span style={{ color: 'var(--ok)' }}>live</span></span>
                  <span className="chip">pause</span>
                  <span className="chip">fork</span>
                </div>
              </div>

              <Stack g={8} style={{ marginTop: 10 }}>
                <div style={{ borderLeft: '2px solid #6b6b66', padding: '2px 8px' }}>
                  <div className="mono" style={{ fontSize: 9, color: 'var(--ink-3)' }}>USER · 14:02:11</div>
                  <div className="mono" style={{ fontSize: 10 }}>extract the JWT verify path into its own module</div>
                </div>
                <div style={{ borderLeft: '2px solid #c2c0b8', padding: '2px 8px', fontStyle: 'italic' }}>
                  <div className="mono dim" style={{ fontSize: 9 }}>reasoning …</div>
                  <Line w={85} />
                  <div style={{ height: 3 }} />
                  <Line w={70} />
                </div>
                <div style={{ borderLeft: '2px solid var(--accent-2)', padding: '2px 8px' }}>
                  <div className="mono" style={{ fontSize: 9, color: 'var(--accent-2)' }}>TOOL · shell</div>
                  <div
                    className="mono"
                    style={{ fontSize: 10, background: '#f4f1ea', padding: '3px 5px', borderRadius: 2 }}
                  >
                    $ rg -n 'verifyJwt' src/ | head -20
                  </div>
                  <div className="mono dim" style={{ fontSize: 9, marginTop: 3 }}>→ 4 files, 12 matches</div>
                </div>
                <div style={{ borderLeft: '2px solid var(--accent)', padding: '2px 8px' }}>
                  <div className="mono" style={{ fontSize: 9, color: 'var(--accent)' }}>AGENT · 14:02:23</div>
                  <Line w={90} />
                  <div style={{ height: 3 }} />
                  <Line w={80} />
                  <div style={{ height: 3 }} />
                  <Line w={55} />
                </div>
                <div style={{ border: '1.5px solid var(--accent)', padding: '8px', background: 'rgba(217,118,66,.06)' }}>
                  <div className="row" style={{ justifyContent: 'space-between' }}>
                    <span className="mono" style={{ fontSize: 10, fontWeight: 700 }}>⚠ approval needed · write 2 files</span>
                    <div className="row" style={{ gap: 4 }}>
                      <span className="chip" style={{ borderColor: 'var(--warn)', color: 'var(--warn)' }}>deny</span>
                      <span
                        className="chip"
                        style={{ borderColor: 'var(--ok)', color: 'var(--ok)', background: 'rgba(58,107,92,.08)' }}
                      >
                        allow once
                      </span>
                      <span className="chip" style={{ borderColor: 'var(--ink)' }}>always</span>
                    </div>
                  </div>
                  <div className="mono dim" style={{ fontSize: 9, marginTop: 4 }}>
                    src/auth/verify.ts · +84 · src/auth/index.ts · −12 +6
                  </div>
                </div>
                <div style={{ borderLeft: '2px solid var(--accent)', padding: '2px 8px', opacity: 0.75 }}>
                  <div className="mono" style={{ fontSize: 9, color: 'var(--accent)' }}>AGENT · streaming…</div>
                  <div className="mono" style={{ fontSize: 10 }}>
                    here's the extracted module with a narrower surface area
                    <span
                      style={{
                        display: 'inline-block',
                        width: 7,
                        height: 11,
                        background: 'var(--ink)',
                        marginLeft: 2,
                        verticalAlign: 'middle',
                      }}
                    />
                  </div>
                </div>
              </Stack>

              <div
                style={{
                  position: 'absolute',
                  left: 160,
                  right: 220,
                  bottom: 0,
                  padding: '8px 14px',
                  background: 'var(--paper-2)',
                  borderTop: '1px solid var(--line-soft)',
                }}
              >
                <div className="row" style={{ gap: 6, alignItems: 'flex-start' }}>
                  <span className="mono" style={{ color: 'var(--accent)', fontWeight: 700 }}>❯</span>
                  <div
                    style={{
                      flex: 1,
                      minHeight: 18,
                      background: '#fff',
                      border: '1px solid var(--line-soft)',
                      padding: '3px 6px',
                    }}
                    className="mono dim"
                  >
                    steer the turn, or ⌘↵ to send new…
                  </div>
                  <span className="chip">@file</span>
                  <span
                    className="chip"
                    style={{ background: 'var(--ink)', color: 'var(--paper)', borderColor: 'var(--ink)' }}
                  >
                    send
                  </span>
                </div>
              </div>
            </main>

            <aside style={{ borderLeft: '1px solid var(--line-soft)', padding: '10px 10px', overflow: 'hidden' }}>
              <div className="mono" style={{ fontSize: 9, color: 'var(--ink-3)', letterSpacing: '.1em' }}>INSPECTOR · diff</div>
              <div className="mono" style={{ fontSize: 9, marginTop: 4 }}>src/auth/verify.ts</div>
              <div
                style={{ marginTop: 6, border: '1px solid var(--line-soft)', fontFamily: 'var(--mono)', fontSize: 8 }}
              >
                {[
                  '+ export function verify(',
                  '+   token: string,',
                  '+ ): Result<Claims> {',
                  '+   ...',
                  '- const raw = decode(t)',
                  '- if (raw.exp < now) ...',
                ].map((l, i) => (
                  <div
                    key={i}
                    style={{
                      padding: '1px 4px',
                      background: l[0] === '+' ? 'rgba(58,107,92,.08)' : 'rgba(192,74,43,.08)',
                      color: l[0] === '+' ? 'var(--ok)' : 'var(--warn)',
                    }}
                  >
                    {l}
                  </div>
                ))}
              </div>

              <div className="mono" style={{ fontSize: 9, color: 'var(--ink-3)', letterSpacing: '.1em', marginTop: 12 }}>
                TOOL TIMELINE
              </div>
              <Stack g={3} style={{ marginTop: 4 }}>
                {['14:02:11 input', '14:02:14 shell · rg', '14:02:17 read file', '14:02:20 edit · verify.ts', '14:02:22 approval'].map(
                  (t, i) => (
                    <div
                      key={i}
                      className="mono"
                      style={{ fontSize: 8, color: i === 4 ? 'var(--accent)' : 'var(--ink-2)' }}
                    >
                      · {t}
                    </div>
                  )
                )}
              </Stack>
            </aside>
          </div>

          <div className="arrow" style={{ top: 46, left: 10, width: 140 }}>
            machines are top-level · thread list beneath
          </div>
          <div className="pin" style={{ top: 180, left: 640 }}>1</div>
          <div className="pin" style={{ top: 330, left: 640 }}>2</div>
        </div>

        <div className="annot">
          <b>1</b> Approval lives inline in the stream — never breaks flow, but always visible. <b>2</b> Right inspector shows the
          diff for the <i>currently-selected</i> tool call; scrolls independently from the stream.
        </div>
      </div>
    </div>
  );
}

/* ============================================================== */
/* VARIANT B — Command-center cockpit (terminal aesthetic)         */
/* ============================================================== */
function DVB() {
  const sessions = [
    { host: 'i9-4090', thread: 'refactor/auth', status: 'live', live: true, delta: "here's the extracted module" },
    { host: 'devbox-01', thread: 'bug #482 retry', status: 'wait', delta: '⚠ approval · run 3 tests' },
    { host: 'xp7', thread: 'explore/ml-pipeline', status: 'idle', delta: '$ pytest -q passed' },
    { host: 'r720', thread: '—', status: 'off', delta: '' },
  ];

  return (
    <div className="variant">
      <div className="variant-head">
        <div className="row" style={{ gap: 10 }}>
          <span className="num">B</span>
          <span className="title">Cockpit / multi-session</span>
        </div>
        <span className="sub">terminal vibe · watch multiple at once</span>
      </div>
      <div className="variant-body">
        <div className="desktop-frame" style={{ background: '#111' }}>
          <div className="chrome" style={{ background: '#1b1b1b', color: '#888', borderColor: '#222' }}>
            <span style={{ marginLeft: 36 }}>remotex · cockpit · 3 live sessions</span>
          </div>
          <div
            style={{
              padding: 10,
              height: 'calc(100% - 22px)',
              display: 'grid',
              gridTemplateColumns: '1fr 1fr',
              gridTemplateRows: '1fr 1fr',
              gap: 6,
            }}
          >
            {sessions.map((s, i) => (
              <div
                key={i}
                className="terminal"
                style={{
                  border:
                    '1px solid ' +
                    (s.status === 'live' ? '#e8a756' : s.status === 'wait' ? '#c04a2b' : '#333'),
                  borderRadius: 3,
                  overflow: 'hidden',
                }}
              >
                <div
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    borderBottom: '1px dashed #333',
                    paddingBottom: 4,
                    marginBottom: 6,
                  }}
                >
                  <span>
                    <span className="amber">●</span> {s.host} <span className="dim">·</span> {s.thread}
                  </span>
                  <span className={s.status === 'live' ? 'grn' : s.status === 'wait' ? 'amber' : 'dim'}>
                    {s.status === 'live'
                      ? 'STREAMING'
                      : s.status === 'wait'
                      ? 'WAIT · APPROVAL'
                      : s.status === 'off'
                      ? 'OFFLINE'
                      : 'IDLE'}
                  </span>
                </div>
                {s.status === 'off' ? (
                  <div className="dim" style={{ textAlign: 'center', padding: '30px 0' }}>
                    daemon has not checked in
                    <br />
                    since 2d ago
                  </div>
                ) : (
                  <Stack g={4}>
                    <div>
                      <span className="dim">›</span> <span className="hl">extract JWT verify path</span>
                    </div>
                    <div className="dim">› reasoning…</div>
                    <div>
                      <span className="grn">✓</span> shell · rg 'verifyJwt' <span className="dim">(12 matches)</span>
                    </div>
                    <div>
                      <span className="grn">✓</span> read src/auth/*.ts
                    </div>
                    {s.status === 'wait' ? (
                      <div style={{ border: '1px solid #c04a2b', padding: 6, marginTop: 4 }}>
                        <span style={{ color: '#c04a2b' }}>✱</span> {s.delta}{' '}
                        <span className="dim">[d]eny [a]llow</span>
                      </div>
                    ) : (
                      <div>
                        {s.delta}
                        <span style={{ display: 'inline-block', width: 6, height: 10, background: '#e8a756', marginLeft: 2 }} />
                      </div>
                    )}
                  </Stack>
                )}
              </div>
            ))}
          </div>

          <div className="arrow" style={{ top: 46, left: 14, color: '#e8a756' }}>
            quadrants = independent sessions, click to focus
          </div>
        </div>

        <div className="annot">
          <b>Why this</b> Power user running many agents in parallel across machines. One glance shows which is waiting on you.
          Color: green = fine, amber = attention, red = blocked.
        </div>
      </div>
    </div>
  );
}

/* ============================================================== */
/* VARIANT C — Document / settings-forward layout                  */
/* ============================================================== */
function DVC() {
  const machines = [
    ['i9-4090', 'home rig', 'ok', '2 sessions · gpt-5', 'linux 6.8 · 64GB'],
    ['devbox-01', 'work', 'ok', '1 session', 'debian · 32GB'],
    ['xp7', 'laptop', 'warn', 'waiting approval', 'windows 11'],
    ['r720', 'server', 'off', 'last seen 2d', 'linux · paused'],
  ];

  const diffRows = [
    { t: '  17   const decoded = decodeJwt(token)', c: '' },
    { t: '  18 - if (decoded.exp < Date.now())', c: 'del' },
    { t: '  18 + if (isExpired(decoded))', c: 'add' },
    { t: '  19 +   throw new TokenExpiredError()', c: 'add' },
    { t: '  20   return verifySignature(decoded)', c: '' },
    { t: '  21 + // TODO: rotate kid', c: 'add' },
  ];

  const keys = [
    ['i9-4090', 'rx_live_•••••7a92', 'created Apr 2'],
    ['devbox-01', 'rx_live_•••••f103', 'created Mar 18'],
    ['xp7', 'rx_live_•••••c5ee', 'created Jan 9'],
  ];

  return (
    <div className="variant">
      <div className="variant-head">
        <div className="row" style={{ gap: 10 }}>
          <span className="num">C</span>
          <span className="title">Document / machines-first</span>
        </div>
        <span className="sub">calmer · settings, keys, diff viewer inline</span>
      </div>
      <div className="variant-body">
        <div className="desktop-frame">
          <div className="chrome">
            <span style={{ marginLeft: 36 }}>remotex.app / machines</span>
          </div>
          <div
            style={{
              padding: 14,
              display: 'grid',
              gridTemplateColumns: '1fr 1fr',
              gap: 12,
              height: 'calc(100% - 22px)',
            }}
          >
            <div>
              <div className="mono" style={{ fontSize: 10, color: 'var(--ink-3)', letterSpacing: '.1em', marginBottom: 6 }}>
                YOUR MACHINES · 4
              </div>
              <Stack g={7}>
                {machines.map(([n, nick, s, sub, meta], i) => (
                  <div
                    key={i}
                    className="card"
                    style={{
                      borderColor: s === 'warn' ? 'var(--accent)' : s === 'off' ? 'var(--line-soft)' : 'var(--ink)',
                    }}
                  >
                    <div className="row" style={{ justifyContent: 'space-between' }}>
                      <div className="row" style={{ gap: 8 }}>
                        <span className={'dot ' + s}></span>
                        <div>
                          <div className="mono" style={{ fontSize: 11, fontWeight: 700 }}>{n}</div>
                          <div className="mono dim" style={{ fontSize: 9 }}>
                            {nick} · {meta}
                          </div>
                        </div>
                      </div>
                      <div>
                        <div className="mono" style={{ fontSize: 10, textAlign: 'right' }}>{sub}</div>
                        <div className="row" style={{ gap: 4, justifyContent: 'flex-end', marginTop: 3 }}>
                          <span className="chip">open</span>
                          <span className="chip">⋯</span>
                        </div>
                      </div>
                    </div>
                  </div>
                ))}
                <div className="card" style={{ borderStyle: 'dashed', textAlign: 'center' }}>
                  <div className="mono dim" style={{ fontSize: 10 }}>＋ pair a new machine</div>
                </div>
              </Stack>
            </div>

            <div>
              <div className="mono" style={{ fontSize: 10, color: 'var(--ink-3)', letterSpacing: '.1em', marginBottom: 6 }}>
                DIFF · approval pending on xp7
              </div>
              <div className="card" style={{ padding: 0 }}>
                <div
                  className="mono"
                  style={{
                    fontSize: 9,
                    padding: '5px 8px',
                    borderBottom: '1px solid var(--line-soft)',
                    background: 'var(--paper-2)',
                  }}
                >
                  src/auth/verify.ts · +84 −12
                </div>
                <div style={{ fontFamily: 'var(--mono)', fontSize: 8, padding: '3px 0' }}>
                  {diffRows.map((r, i) => (
                    <div
                      key={i}
                      style={{
                        padding: '1px 8px',
                        background:
                          r.c === 'add' ? 'rgba(58,107,92,.08)' : r.c === 'del' ? 'rgba(192,74,43,.08)' : 'transparent',
                        color: r.c === 'add' ? 'var(--ok)' : r.c === 'del' ? 'var(--warn)' : 'var(--ink)',
                      }}
                    >
                      {r.t}
                    </div>
                  ))}
                </div>
                <div
                  className="row"
                  style={{ justifyContent: 'flex-end', gap: 5, padding: 6, borderTop: '1px solid var(--line-soft)' }}
                >
                  <span className="chip" style={{ borderColor: 'var(--warn)', color: 'var(--warn)' }}>deny</span>
                  <span className="chip" style={{ background: 'var(--ok)', color: '#fff', borderColor: 'var(--ok)' }}>
                    allow · once
                  </span>
                </div>
              </div>

              <div className="mono" style={{ fontSize: 10, color: 'var(--ink-3)', letterSpacing: '.1em', margin: '14px 0 6px' }}>
                SETTINGS · API KEYS
              </div>
              <div className="card">
                <div className="mono" style={{ fontSize: 10, fontWeight: 700 }}>
                  Bridge keys <span className="dim" style={{ fontWeight: 400 }}>· 4 active</span>
                </div>
                <Stack g={3} style={{ marginTop: 6 }}>
                  {keys.map(([n, k, c], i) => (
                    <div
                      key={i}
                      className="row"
                      style={{
                        justifyContent: 'space-between',
                        fontFamily: 'var(--mono)',
                        fontSize: 9,
                        padding: '3px 0',
                        borderTop: i ? '1px dashed var(--line-soft)' : 'none',
                      }}
                    >
                      <span>
                        {n} <span className="dim">· {c}</span>
                      </span>
                      <span>
                        {k} <span className="dim">· copy · revoke</span>
                      </span>
                    </div>
                  ))}
                </Stack>
                <div className="row" style={{ justifyContent: 'flex-end', marginTop: 6 }}>
                  <span
                    className="chip"
                    style={{ background: 'var(--ink)', color: 'var(--paper)', borderColor: 'var(--ink)' }}
                  >
                    ＋ issue new key
                  </span>
                </div>
              </div>

              <div className="mono dim" style={{ fontSize: 9, marginTop: 10 }}>
                Your OpenAI auth is <b style={{ color: 'var(--ok)' }}>not stored</b> on Remotex — it lives in{' '}
                <span style={{ color: 'var(--ink)' }}>~/.codex</span> on each machine.
              </div>
            </div>
          </div>
        </div>

        <div className="annot">
          <b>Positioning</b> For the casual user who mostly wants to peek in, manage keys, and review one diff at a time. No
          multi-pane complexity.
        </div>
      </div>
    </div>
  );
}
