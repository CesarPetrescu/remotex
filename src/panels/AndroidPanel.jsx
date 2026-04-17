// Android app wireframes — 3 variants (Material/Compose-leaning)
export default function AndroidPanel() {
  return (
    <div>
      <div className="sect">
        <h2>Android app</h2>
        <span className="rule"></span>
        <span className="kicker">Kotlin + Compose · Material 3 · push notifications</span>
      </div>

      <div className="variants cols-3">
        <AVA />
        <AVB />
        <AVC />
      </div>

      <div className="annot">
        <b>Android-specific</b> push via FCM for approval requests · persistent notification when a turn is running · share-sheet
        integration so you can send a code snippet into a new turn · widgets for quick host status.
      </div>
    </div>
  );
}

const DFrame = ({ children, style, dark = false }) => (
  <div className="android-frame" style={style}>
    <div
      style={{
        position: 'absolute',
        top: 0,
        left: 0,
        right: 0,
        height: 22,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '0 14px',
        fontFamily: 'var(--mono)',
        fontSize: 8,
        color: dark ? '#ddd' : 'var(--ink-2)',
        zIndex: 10,
      }}
    >
      <span>9:41</span>
      <span>◦ ▂▄▆ 100%</span>
    </div>
    <div className="screen">{children}</div>
  </div>
);

/* =========== VA — Material 3 home + nav rail =========== */
function AVA() {
  const hosts = [
    ['i9-4090', 'home rig', 'ok', '2 sessions'],
    ['devbox-01', 'work', 'ok', 'idle'],
    ['xp7', 'laptop', 'warn', 'approval'],
    ['r720', 'server', 'off', 'offline 2d'],
  ];
  const threads = [
    ['refactor/auth', 'live', 7, 'var(--accent)'],
    ['bug #482 retry', 'idle', 4, 'var(--ink-3)'],
    ['explore/ml', 'paused', 2, 'var(--ink-3)'],
  ];

  return (
    <div className="variant">
      <div className="variant-head">
        <div className="row" style={{ gap: 10 }}>
          <span className="num">A</span>
          <span className="title">Material 3 hosts</span>
        </div>
        <span className="sub">bottom nav · cards</span>
      </div>
      <div className="variant-body" style={{ display: 'flex', gap: 14, justifyContent: 'center', flexWrap: 'wrap' }}>
        <DFrame>
          <div style={{ padding: '4px 10px 0' }}>
            <div style={{ fontSize: 16, fontWeight: 700, marginTop: 2 }}>Hosts</div>
            <div className="mono dim" style={{ fontSize: 9 }}>4 paired · 2 active</div>
          </div>
          <div style={{ padding: '10px 8px', display: 'flex', flexDirection: 'column', gap: 7 }}>
            {hosts.map(([n, nick, s, sub], i) => (
              <div
                key={i}
                style={{
                  background: '#fff',
                  border: '1px solid var(--line-soft)',
                  borderRadius: 10,
                  padding: '9px 10px',
                  boxShadow: '0 1px 0 rgba(0,0,0,.03)',
                }}
              >
                <div className="row" style={{ justifyContent: 'space-between' }}>
                  <div className="row" style={{ gap: 7 }}>
                    <span className={'dot ' + s}></span>
                    <div>
                      <div style={{ fontWeight: 700, fontSize: 11 }}>{n}</div>
                      <div className="mono dim" style={{ fontSize: 8 }}>{nick}</div>
                    </div>
                  </div>
                  <div
                    className="mono"
                    style={{ fontSize: 9, color: s === 'warn' ? 'var(--warn)' : 'var(--ink-3)', textAlign: 'right' }}
                  >
                    {sub}
                  </div>
                </div>
              </div>
            ))}
          </div>
          <div
            style={{
              position: 'absolute',
              right: 14,
              bottom: 56,
              width: 46,
              height: 46,
              borderRadius: 14,
              background: 'var(--accent)',
              color: '#fff',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: 22,
              fontWeight: 300,
              boxShadow: '0 2px 0 rgba(0,0,0,.15)',
            }}
          >
            ＋
          </div>
          <div
            style={{
              position: 'absolute',
              left: 0,
              right: 0,
              bottom: 0,
              height: 46,
              background: 'var(--paper-2)',
              borderTop: '1px solid var(--line-soft)',
              display: 'flex',
            }}
          >
            {[
              ['⌂', 'Hosts', true],
              ['◎', 'Activity', false],
              ['⚙', 'Settings', false],
            ].map((t, i) => (
              <div
                key={i}
                style={{
                  flex: 1,
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'center',
                  justifyContent: 'center',
                  color: t[2] ? 'var(--accent)' : 'var(--ink-3)',
                }}
              >
                <div style={{ fontSize: 14 }}>{t[0]}</div>
                <div className="mono" style={{ fontSize: 8, fontWeight: t[2] ? 700 : 400 }}>
                  {t[1]}
                </div>
              </div>
            ))}
          </div>
        </DFrame>

        <DFrame>
          <div style={{ padding: '4px 10px 0' }}>
            <div className="row" style={{ gap: 6 }}>
              <span className="mono" style={{ fontSize: 9, color: 'var(--ink-3)' }}>‹</span>
              <span style={{ fontWeight: 700, fontSize: 13 }}>i9-4090</span>
            </div>
            <div className="mono dim" style={{ fontSize: 9 }}>home rig · linux 6.8 · codex gpt-5</div>
          </div>
          <div style={{ padding: '8px 8px' }}>
            <div
              className="mono"
              style={{ fontSize: 9, color: 'var(--ink-3)', letterSpacing: '.08em', margin: '4px 2px' }}
            >
              THREADS · 3
            </div>
            {threads.map(([t, st, tn, c], i) => (
              <div
                key={i}
                style={{
                  background: '#fff',
                  border: '1px solid var(--line-soft)',
                  borderRadius: 8,
                  padding: '8px 10px',
                  marginBottom: 6,
                }}
              >
                <div className="row" style={{ justifyContent: 'space-between' }}>
                  <div>
                    <div style={{ fontWeight: 700, fontSize: 11 }}>{t}</div>
                    <div className="mono dim" style={{ fontSize: 8 }}>
                      turn {tn} · 3m ago
                    </div>
                  </div>
                  <div style={{ fontSize: 9, color: c, fontFamily: 'var(--mono)', fontWeight: 700 }}>{st}</div>
                </div>
              </div>
            ))}
            <div
              style={{ border: '1px dashed var(--line-soft)', borderRadius: 8, padding: '10px', textAlign: 'center' }}
              className="mono dim"
            >
              ＋ new thread
            </div>
          </div>
        </DFrame>
      </div>

      <div className="annot">
        <b>Home → detail</b> Tap host → see its threads (persisted by codex). FAB starts a new thread on this host.
      </div>
    </div>
  );
}

/* =========== VB — Live turn + inline approval sheet =========== */
function AVB() {
  return (
    <div className="variant">
      <div className="variant-head">
        <div className="row" style={{ gap: 10 }}>
          <span className="num">B</span>
          <span className="title">Live turn + push</span>
        </div>
        <span className="sub">notification → approve in 2 taps</span>
      </div>
      <div className="variant-body" style={{ display: 'flex', gap: 14, justifyContent: 'center', flexWrap: 'wrap' }}>
        <DFrame dark style={{ background: '#0d0f13' }}>
          <div style={{ padding: '30px 10px', color: '#eee' }}>
            <div style={{ textAlign: 'center', fontSize: 32, fontWeight: 200, marginBottom: 2 }}>9:41</div>
            <div style={{ textAlign: 'center', fontSize: 10, opacity: 0.6, marginBottom: 20 }} className="mono">
              Friday, Apr 18
            </div>

            <div
              style={{
                background: 'rgba(255,255,255,.1)',
                backdropFilter: 'blur(8px)',
                borderRadius: 14,
                padding: '10px 12px',
              }}
            >
              <div className="row" style={{ justifyContent: 'space-between', fontSize: 9, opacity: 0.7 }}>
                <span>▲ Remotex</span>
                <span>now</span>
              </div>
              <div style={{ fontWeight: 700, fontSize: 11, marginTop: 4 }}>Approval needed · i9-4090</div>
              <div style={{ fontSize: 10, marginTop: 2, opacity: 0.85 }}>
                refactor/auth wants to write 2 files (+84 −12)
              </div>
              <div className="row" style={{ gap: 6, marginTop: 10 }}>
                <div
                  style={{
                    flex: 1,
                    border: '1px solid rgba(255,255,255,.3)',
                    padding: '6px',
                    borderRadius: 6,
                    textAlign: 'center',
                    fontSize: 10,
                    fontWeight: 600,
                  }}
                >
                  Deny
                </div>
                <div
                  style={{
                    flex: 1,
                    background: 'var(--accent)',
                    color: '#fff',
                    padding: '6px',
                    borderRadius: 6,
                    textAlign: 'center',
                    fontSize: 10,
                    fontWeight: 700,
                  }}
                >
                  Allow
                </div>
                <div
                  style={{
                    flex: 1,
                    border: '1px solid rgba(255,255,255,.3)',
                    padding: '6px',
                    borderRadius: 6,
                    textAlign: 'center',
                    fontSize: 10,
                  }}
                >
                  View
                </div>
              </div>
            </div>

            <div className="mono" style={{ fontSize: 8, opacity: 0.5, textAlign: 'center', marginTop: 14 }}>
              swipe up to unlock
            </div>
          </div>
        </DFrame>

        <DFrame>
          <div style={{ padding: '4px 10px', fontSize: 11, fontWeight: 700 }}>
            refactor/auth{' '}
            <span className="mono dim" style={{ fontSize: 8, fontWeight: 400, marginLeft: 6 }}>
              · turn 7
            </span>
          </div>
          <div style={{ padding: '2px 8px', display: 'flex', flexDirection: 'column', gap: 5, opacity: 0.45 }}>
            <div
              style={{ borderLeft: '2px solid #888', padding: '1px 6px', fontFamily: 'var(--mono)', fontSize: 9 }}
            >
              extract the JWT verify path
            </div>
            <div
              className="mono"
              style={{ borderLeft: '2px solid var(--accent-2)', padding: '1px 6px', fontSize: 9 }}
            >
              shell · rg verifyJwt
            </div>
            <div
              style={{
                borderLeft: '2px solid var(--accent)',
                padding: '1px 6px',
                fontFamily: 'var(--mono)',
                fontSize: 9,
              }}
            >
              12 matches in 4 files…
            </div>
          </div>
          <div style={{ position: 'absolute', inset: '22px 0 0 0', background: 'rgba(0,0,0,.35)' }} />

          <div
            style={{
              position: 'absolute',
              left: 0,
              right: 0,
              bottom: 0,
              background: '#fff',
              borderTop: '1px solid var(--ink)',
              borderRadius: '16px 16px 0 0',
              padding: '10px 14px 14px',
            }}
          >
            <div
              style={{ width: 36, height: 4, background: 'var(--line-soft)', borderRadius: 2, margin: '0 auto 10px' }}
            />
            <div className="row" style={{ gap: 7, alignItems: 'flex-start' }}>
              <div
                style={{
                  width: 26,
                  height: 26,
                  borderRadius: 13,
                  background: 'var(--accent)',
                  color: '#fff',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  fontWeight: 700,
                  fontFamily: 'var(--mono)',
                }}
              >
                !
              </div>
              <div>
                <div style={{ fontSize: 12, fontWeight: 700 }}>Write 2 files?</div>
                <div className="mono dim" style={{ fontSize: 9 }}>i9-4090 · refactor/auth</div>
              </div>
            </div>
            <div
              style={{
                background: 'var(--paper-2)',
                padding: '6px 8px',
                marginTop: 8,
                borderRadius: 6,
                fontFamily: 'var(--mono)',
                fontSize: 9,
              }}
            >
              <div>
                ✎ src/auth/verify.ts · <span style={{ color: 'var(--ok)' }}>+84</span>
              </div>
              <div>
                ✎ src/auth/index.ts · <span style={{ color: 'var(--ok)' }}>+6</span>{' '}
                <span style={{ color: 'var(--warn)' }}>−12</span>
              </div>
              <div className="dim" style={{ marginTop: 3 }}>tap to preview diff ›</div>
            </div>
            <div className="col" style={{ gap: 6, marginTop: 10 }}>
              <div
                style={{
                  background: 'var(--ok)',
                  color: '#fff',
                  padding: '10px',
                  borderRadius: 22,
                  textAlign: 'center',
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
                    padding: '8px',
                    borderRadius: 22,
                    textAlign: 'center',
                    fontFamily: 'var(--mono)',
                    fontSize: 10,
                  }}
                >
                  Always
                </div>
                <div
                  style={{
                    flex: 1,
                    border: '1px solid var(--warn)',
                    color: 'var(--warn)',
                    padding: '8px',
                    borderRadius: 22,
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
        </DFrame>
      </div>

      <div className="annot">
        <b>Android advantage</b> FCM + notification actions → you can allow/deny without opening the app. Tap "View" to jump
        straight to the diff.
      </div>
    </div>
  );
}

/* =========== VC — Pairing flow + settings =========== */
function AVC() {
  const keys = [
    ['i9-4090', '•••••7a92', 'Apr 2'],
    ['devbox-01', '•••••f103', 'Mar 18'],
    ['xp7', '•••••c5ee', 'Jan 9'],
  ];
  const prefs = [
    ['Push on approval', true],
    ['Push on turn done', false],
    ['Biometric to approve', true],
  ];

  return (
    <div className="variant">
      <div className="variant-head">
        <div className="row" style={{ gap: 10 }}>
          <span className="num">C</span>
          <span className="title">Pair new machine + settings</span>
        </div>
        <span className="sub">QR + Bridge API key entry</span>
      </div>
      <div className="variant-body" style={{ display: 'flex', gap: 14, justifyContent: 'center', flexWrap: 'wrap' }}>
        <DFrame>
          <div style={{ padding: '4px 10px 0' }}>
            <div className="row" style={{ gap: 6 }}>
              <span className="mono dim" style={{ fontSize: 9 }}>‹ back</span>
            </div>
            <div style={{ fontSize: 15, fontWeight: 700, marginTop: 4 }}>Pair a machine</div>
            <div className="mono dim" style={{ fontSize: 9, marginTop: 2 }}>Run the daemon, then enter its code</div>
          </div>

          <div style={{ padding: '14px 14px' }}>
            <div style={{ background: '#fff', border: '1px solid var(--ink)', borderRadius: 10, padding: 10, textAlign: 'center' }}>
              <div className="mono" style={{ fontSize: 9, color: 'var(--ink-3)', letterSpacing: '.1em' }}>OPTION 1 · SCAN</div>
              <div
                style={{
                  width: 130,
                  height: 130,
                  margin: '10px auto',
                  background: '#fff',
                  border: '2px solid var(--ink)',
                  position: 'relative',
                }}
              >
                <div
                  style={{
                    position: 'absolute',
                    inset: 0,
                    backgroundImage:
                      'repeating-linear-gradient(0deg,#000 0 3px,transparent 3px 7px),repeating-linear-gradient(90deg,#000 0 3px,transparent 3px 7px)',
                    opacity: 0.85,
                    margin: 8,
                  }}
                />
                <div
                  style={{ position: 'absolute', top: 8, left: 8, width: 30, height: 30, border: '4px solid #000', background: '#fff' }}
                />
                <div
                  style={{ position: 'absolute', top: 8, right: 8, width: 30, height: 30, border: '4px solid #000', background: '#fff' }}
                />
                <div
                  style={{ position: 'absolute', bottom: 8, left: 8, width: 30, height: 30, border: '4px solid #000', background: '#fff' }}
                />
              </div>
              <div className="mono dim" style={{ fontSize: 9 }}>
                Point at the terminal where you ran
                <br />
                <span style={{ color: 'var(--ink)' }}>remotex daemon pair</span>
              </div>
            </div>

            <div className="mono" style={{ fontSize: 9, textAlign: 'center', color: 'var(--ink-3)', margin: '10px 0' }}>
              — or —
            </div>

            <div style={{ background: '#fff', border: '1px solid var(--line-soft)', borderRadius: 10, padding: 10 }}>
              <div className="mono" style={{ fontSize: 9, color: 'var(--ink-3)', letterSpacing: '.1em' }}>OPTION 2 · CODE</div>
              <div className="row" style={{ gap: 3, marginTop: 7, justifyContent: 'center' }}>
                {['4', 'K', '2', '—', '9', 'P', 'X'].map((c, i) => (
                  <div
                    key={i}
                    style={{
                      width: 18,
                      height: 26,
                      border: '1px solid var(--line-soft)',
                      borderRadius: 4,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      fontFamily: 'var(--mono)',
                      fontSize: 11,
                      fontWeight: 700,
                      background: i === 0 ? 'rgba(217,118,66,.08)' : '#fff',
                    }}
                  >
                    {c === '—' ? '' : c}
                  </div>
                ))}
              </div>
            </div>
          </div>
        </DFrame>

        <DFrame>
          <div style={{ padding: '4px 10px 0' }}>
            <div style={{ fontSize: 15, fontWeight: 700 }}>Settings</div>
            <div className="mono dim" style={{ fontSize: 9 }}>cesar@remotex.app</div>
          </div>
          <div style={{ padding: '6px 10px' }}>
            <div
              className="mono"
              style={{ fontSize: 9, color: 'var(--ink-3)', letterSpacing: '.08em', margin: '8px 2px' }}
            >
              BRIDGE API KEYS
            </div>
            <div style={{ background: '#fff', border: '1px solid var(--line-soft)', borderRadius: 8, padding: '2px 0' }}>
              {keys.map(([n, k, d], i) => (
                <div key={i} style={{ padding: '7px 10px', borderBottom: i < 2 ? '1px solid var(--line-soft)' : 'none' }}>
                  <div className="row" style={{ justifyContent: 'space-between' }}>
                    <div>
                      <div style={{ fontSize: 10, fontWeight: 600 }}>{n}</div>
                      <div className="mono dim" style={{ fontSize: 8 }}>
                        {k} · {d}
                      </div>
                    </div>
                    <span className="mono" style={{ fontSize: 9, color: 'var(--warn)' }}>revoke</span>
                  </div>
                </div>
              ))}
            </div>
            <div
              style={{
                textAlign: 'center',
                marginTop: 8,
                padding: '8px',
                border: '1px dashed var(--accent)',
                color: 'var(--accent)',
                borderRadius: 8,
                fontFamily: 'var(--mono)',
                fontSize: 10,
                fontWeight: 700,
              }}
            >
              ＋ issue new key
            </div>

            <div
              className="mono"
              style={{ fontSize: 9, color: 'var(--ink-3)', letterSpacing: '.08em', margin: '12px 2px 5px' }}
            >
              PREFERENCES
            </div>
            <div style={{ background: '#fff', border: '1px solid var(--line-soft)', borderRadius: 8, padding: '2px 0' }}>
              {prefs.map(([l, on], i) => (
                <div
                  key={i}
                  className="row"
                  style={{ justifyContent: 'space-between', padding: '8px 10px', borderBottom: i < 2 ? '1px solid var(--line-soft)' : 'none' }}
                >
                  <span style={{ fontSize: 10 }}>{l}</span>
                  <div
                    style={{
                      width: 26,
                      height: 14,
                      background: on ? 'var(--ok)' : 'var(--line-soft)',
                      borderRadius: 7,
                      position: 'relative',
                    }}
                  >
                    <div
                      style={{
                        position: 'absolute',
                        top: 1,
                        left: on ? 13 : 1,
                        width: 12,
                        height: 12,
                        background: '#fff',
                        borderRadius: 6,
                      }}
                    />
                  </div>
                </div>
              ))}
            </div>

            <div className="mono dim" style={{ fontSize: 8, marginTop: 10, textAlign: 'center' }}>
              Codex (OpenAI) auth is not stored here.
              <br />
              It lives on each paired machine.
            </div>
          </div>
        </DFrame>
      </div>

      <div className="annot">
        <b>Pairing</b> QR is the happy path; the 7-char code is the fallback for a machine without a display nearby.{' '}
        <b>Biometric to approve</b> hardens the notification-action flow.
      </div>
    </div>
  );
}
