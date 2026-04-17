// Systems architecture wireframes — 3 variants
export default function SystemsPanel() {
  return (
    <div>
      <div className="sect">
        <h2>Systems architecture</h2>
        <span className="rule"></span>
        <span className="kicker">how the pieces talk · three framings</span>
      </div>

      <div className="variants cols-3">
        <VA />
        <VB />
        <VC />
      </div>

      <div className="annot">
        <p style={{ margin: 0 }}>
          <b>Recap</b> The daemon is per-machine and makes only outbound WSS — no inbound ports required. The relay is pass-through;
          it never parses Codex payloads. Keycloak handles identity for the client apps; the daemon authenticates separately with a{' '}
          <span className="mono">Bridge API key</span> issued after login.
        </p>
      </div>
    </div>
  );
}

/* ---------------- Variant A — topology (classic boxes + lines) ---------------- */
function VA() {
  return (
    <div className="variant">
      <div className="variant-head">
        <div className="row" style={{ gap: 10 }}>
          <span className="num">A</span>
          <span className="title">Topology</span>
        </div>
        <span className="sub">who lives where</span>
      </div>
      <div className="variant-body">
        <div className="diagram">
          <svg viewBox="0 0 600 460" xmlns="http://www.w3.org/2000/svg" style={{ fontFamily: 'JetBrains Mono, monospace' }}>
            <defs>
              <marker id="arrA" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto">
                <path d="M0,0 L10,5 L0,10 z" fill="#1a1a1a" />
              </marker>
              <marker id="arrAcc" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto">
                <path d="M0,0 L10,5 L0,10 z" fill="var(--accent)" />
              </marker>
            </defs>

            {/* CLIENT ZONE */}
            <g>
              <rect x="20" y="20" width="170" height="400" fill="none" stroke="#9a958a" strokeDasharray="4 3" />
              <text x="30" y="38" fontSize="10" fill="#6b6b66">CLIENTS</text>

              <g>
                <rect x="40" y="60" width="130" height="70" fill="#fff" stroke="#1a1a1a" />
                <text x="105" y="82" fontSize="11" fontWeight="700" textAnchor="middle">Desktop Web</text>
                <text x="105" y="98" fontSize="10" textAnchor="middle" fill="#6b6b66">Next.js PWA</text>
                <text x="105" y="114" fontSize="9" textAnchor="middle" fill="#6b6b66">browser</text>
              </g>
              <g>
                <rect x="40" y="155" width="130" height="70" fill="#fff" stroke="#1a1a1a" />
                <text x="105" y="177" fontSize="11" fontWeight="700" textAnchor="middle">Mobile Web</text>
                <text x="105" y="193" fontSize="10" textAnchor="middle" fill="#6b6b66">same PWA</text>
                <text x="105" y="209" fontSize="9" textAnchor="middle" fill="#6b6b66">iOS / Android safari</text>
              </g>
              <g>
                <rect x="40" y="250" width="130" height="70" fill="#fff" stroke="#1a1a1a" />
                <text x="105" y="272" fontSize="11" fontWeight="700" textAnchor="middle">Android app</text>
                <text x="105" y="288" fontSize="10" textAnchor="middle" fill="#6b6b66">Kotlin / Compose</text>
                <text x="105" y="304" fontSize="9" textAnchor="middle" fill="#6b6b66">push notifs</text>
              </g>
              <g>
                <rect x="40" y="345" width="130" height="55" fill="#fff" stroke="#1a1a1a" strokeDasharray="3 3" />
                <text x="105" y="368" fontSize="11" fontWeight="700" textAnchor="middle">iOS app</text>
                <text x="105" y="384" fontSize="9" textAnchor="middle" fill="#6b6b66">later / SwiftUI</text>
              </g>
            </g>

            {/* RELAY ZONE */}
            <g>
              <rect x="220" y="60" width="190" height="340" fill="#f4f1ea" stroke="#1a1a1a" />
              <text x="230" y="80" fontSize="10" fill="#6b6b66">CENTRAL RELAY — k8s</text>

              <rect x="235" y="95" width="160" height="46" fill="#fff" stroke="#1a1a1a" />
              <text x="315" y="115" fontSize="11" fontWeight="700" textAnchor="middle">Envoy Gateway</text>
              <text x="315" y="130" fontSize="9" textAnchor="middle" fill="#6b6b66">wss / https · TLS</text>

              <rect x="235" y="155" width="160" height="46" fill="#fff" stroke="#1a1a1a" />
              <text x="315" y="175" fontSize="11" fontWeight="700" textAnchor="middle">Keycloak</text>
              <text x="315" y="190" fontSize="9" textAnchor="middle" fill="#6b6b66">OIDC · accounts · orgs</text>

              <rect x="235" y="215" width="160" height="60" fill="#fff" stroke="#1a1a1a" strokeWidth="1.5" />
              <text x="315" y="237" fontSize="11" fontWeight="700" textAnchor="middle">Relay (Go)</text>
              <text x="315" y="252" fontSize="9" textAnchor="middle" fill="#6b6b66">WS hub + pass-through</text>
              <text x="315" y="266" fontSize="9" textAnchor="middle" fill="#6b6b66">never parses Codex</text>

              <rect x="235" y="290" width="160" height="46" fill="#fff" stroke="#1a1a1a" />
              <text x="315" y="310" fontSize="11" fontWeight="700" textAnchor="middle">Postgres</text>
              <text x="315" y="325" fontSize="9" textAnchor="middle" fill="#6b6b66">users · hosts · sessions</text>

              <rect x="235" y="350" width="160" height="40" fill="#fff" stroke="#1a1a1a" strokeDasharray="3 3" />
              <text x="315" y="370" fontSize="10" textAnchor="middle">Redis · session locks</text>
              <text x="315" y="382" fontSize="9" textAnchor="middle" fill="#6b6b66">optional</text>
            </g>

            {/* HOST ZONE */}
            <g>
              <rect x="440" y="20" width="140" height="400" fill="none" stroke="#9a958a" strokeDasharray="4 3" />
              <text x="450" y="38" fontSize="10" fill="#6b6b66">HOST MACHINES</text>

              {[
                { y: 60, name: 'i9-4090', nick: 'home rig' },
                { y: 155, name: 'devbox-01', nick: 'work' },
                { y: 250, name: 'xp7', nick: 'laptop' },
                { y: 345, name: 'r720', nick: 'server' },
              ].map((h, i) => (
                <g key={i}>
                  <rect x="455" y={h.y} width="110" height={i === 3 ? 55 : 70} fill="#fff" stroke="#1a1a1a" />
                  <text x="510" y={h.y + 20} fontSize="10" fontWeight="700" textAnchor="middle">{h.name}</text>
                  <text x="510" y={h.y + 34} fontSize="9" textAnchor="middle" fill="#6b6b66">{h.nick}</text>
                  {i !== 3 && (
                    <>
                      <rect x="465" y={h.y + 40} width="90" height="22" fill="#f4f1ea" stroke="#9a958a" />
                      <text x="510" y={h.y + 55} fontSize="9" textAnchor="middle">daemon · codex</text>
                    </>
                  )}
                  {i === 3 && <text x="510" y={h.y + 48} fontSize="9" textAnchor="middle" fill="#6b6b66">offline</text>}
                </g>
              ))}
            </g>

            {/* LINKS */}
            <path d="M 170 95 C 195 95, 210 118, 235 118" fill="none" stroke="var(--accent)" strokeWidth="1.7" markerEnd="url(#arrAcc)" />
            <path d="M 170 190 C 195 190, 210 135, 235 135" fill="none" stroke="var(--accent)" strokeWidth="1.7" />
            <path d="M 170 285 C 195 285, 210 135, 235 135" fill="none" stroke="var(--accent)" strokeWidth="1.7" />

            <path d="M 455 95 C 430 95, 410 235, 395 235" fill="none" stroke="var(--accent)" strokeWidth="1.7" markerEnd="url(#arrAcc)" />
            <path d="M 455 190 C 430 190, 410 240, 395 240" fill="none" stroke="var(--accent)" strokeWidth="1.7" />
            <path d="M 455 285 C 430 285, 410 245, 395 245" fill="none" stroke="var(--accent)" strokeWidth="1.7" />

            <text x="200" y="86" fontSize="9" fill="var(--accent)">wss · Bearer JWT</text>
            <text x="400" y="85" fontSize="9" fill="var(--accent)" textAnchor="end">wss outbound</text>
            <text x="400" y="97" fontSize="9" fill="#6b6b66" textAnchor="end">Bridge API key</text>

            <text x="300" y="50" fontSize="9" textAnchor="middle" fill="#6b6b66">— trust boundary —</text>
          </svg>
          <div className="legend">
            <span><i className="wss"></i> WSS tunnels (bidirectional JSON-RPC)</span>
            <span><i className="rest"></i> HTTPS REST (auth, metadata)</span>
            <span><i className="dash"></i> planned / optional</span>
          </div>
        </div>

        <div className="annot" style={{ marginTop: 14 }}>
          <b>Key idea</b> Clients and daemons both dial <i>outward</i> to the relay. The relay is just a matchmaker — it pairs{' '}
          <span className="mono">session_id</span> pipes end-to-end.
        </div>
      </div>
    </div>
  );
}

/* ---------------- Variant B — sequence / flow ---------------- */
function VB() {
  const lanes = [
    { x: 60, label: 'Client' },
    { x: 200, label: 'Relay' },
    { x: 340, label: 'Daemon' },
    { x: 490, label: 'codex' },
  ];
  const messages = [
    { y: 70, from: 60, to: 200, label: 'POST /login (OIDC)', acc: true },
    { y: 100, from: 200, to: 60, label: 'session JWT' },
    { y: 135, from: 340, to: 200, label: 'POST /daemon/register', acc: true, note: 'Bridge API key' },
    { y: 165, from: 200, to: 340, label: 'host_id + challenge' },
    { y: 200, from: 340, to: 490, label: 'spawn codex app-server --listen ws://127.0.0.1' },
    { y: 235, from: 340, to: 200, label: 'WSS · register host', acc: true },
    { y: 270, from: 60, to: 200, label: 'WSS · list machines' },
    { y: 300, from: 200, to: 60, label: '[i9-4090, devbox-01, ...]' },
    { y: 335, from: 60, to: 200, label: 'turn/start {thread,input}', acc: true },
    { y: 365, from: 200, to: 340, label: 'relay → daemon (passthrough)' },
    { y: 395, from: 340, to: 490, label: 'JSON-RPC to app-server' },
    { y: 425, from: 490, to: 340, label: 'item/agentMessage/delta (stream)', acc: true, back: true },
    { y: 452, from: 340, to: 200, label: 'frames pushed' },
    { y: 478, from: 200, to: 60, label: 'render in session view' },
    { y: 505, from: 490, to: 60, label: 'item/fileChange/requestApproval', warn: true, back: true },
  ];

  return (
    <div className="variant">
      <div className="variant-head">
        <div className="row" style={{ gap: 10 }}>
          <span className="num">B</span>
          <span className="title">Sequence flow</span>
        </div>
        <span className="sub">first-run → live turn</span>
      </div>
      <div className="variant-body">
        <div className="diagram">
          <svg viewBox="0 0 600 540" xmlns="http://www.w3.org/2000/svg">
            {lanes.map((l, i) => (
              <g key={i}>
                <rect x={l.x - 50} y="12" width="100" height="26" fill="#1a1a1a" />
                <text x={l.x} y="30" fontSize="11" fontWeight="700" fill="#f4f1ea" textAnchor="middle">{l.label}</text>
                <line x1={l.x} y1="38" x2={l.x} y2="520" stroke="#9a958a" strokeDasharray="3 4" />
              </g>
            ))}

            {messages.map((m, i) => {
              const col = m.warn ? '#c04a2b' : m.acc ? 'var(--accent)' : '#1a1a1a';
              return (
                <g key={i}>
                  <line
                    x1={m.from}
                    y1={m.y}
                    x2={m.to}
                    y2={m.y}
                    stroke={col}
                    strokeWidth={m.acc ? 1.6 : 1.2}
                    markerEnd={m.acc ? 'url(#arrSeq)' : 'url(#arrSeqBlk)'}
                  />
                  <text
                    x={(m.from + m.to) / 2}
                    y={m.y - 5}
                    fontSize="9"
                    fill={col}
                    textAnchor="middle"
                    fontFamily="JetBrains Mono, monospace"
                  >
                    {m.label}
                  </text>
                  {m.note && (
                    <text
                      x={(m.from + m.to) / 2}
                      y={m.y + 12}
                      fontSize="8"
                      fill="#6b6b66"
                      textAnchor="middle"
                      fontFamily="JetBrains Mono, monospace"
                    >
                      ({m.note})
                    </text>
                  )}
                </g>
              );
            })}

            <defs>
              <marker id="arrSeq" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto">
                <path d="M0,0 L10,5 L0,10 z" fill="var(--accent)" />
              </marker>
              <marker id="arrSeqBlk" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto">
                <path d="M0,0 L10,5 L0,10 z" fill="#1a1a1a" />
              </marker>
            </defs>

            <g fontSize="9" fontFamily="JetBrains Mono,monospace" fill="#6b6b66">
              <text x="10" y="85">① auth</text>
              <text x="10" y="150">② pair</text>
              <text x="10" y="285">③ list</text>
              <text x="10" y="410">④ run turn</text>
              <text x="10" y="510" fill="#c04a2b">⑤ approve</text>
            </g>
          </svg>
        </div>

        <div className="annot" style={{ marginTop: 14 }}>
          <b>Critical moment</b> The approval request is initiated <i>by</i> the server (codex) and travels all the way back to the
          user's phone. Design the UI so nothing auto-resolves — explicit user decision only.
        </div>
      </div>
    </div>
  );
}

/* ---------------- Variant C — trust & credential map ---------------- */
function VC() {
  return (
    <div className="variant">
      <div className="variant-head">
        <div className="row" style={{ gap: 10 }}>
          <span className="num">C</span>
          <span className="title">Trust & credentials</span>
        </div>
        <span className="sub">two keys, two purposes</span>
      </div>
      <div className="variant-body">
        <div className="diagram">
          <svg viewBox="0 0 600 480" xmlns="http://www.w3.org/2000/svg">
            <rect x="10" y="10" width="580" height="460" fill="none" stroke="#9a958a" strokeDasharray="2 4" />
            <text x="22" y="28" fontSize="10" fill="#6b6b66" fontFamily="JetBrains Mono,monospace">PUBLIC INTERNET</text>

            <rect x="50" y="50" width="500" height="380" fill="#fff" stroke="#1a1a1a" />
            <text x="62" y="68" fontSize="10" fill="#6b6b66" fontFamily="JetBrains Mono,monospace">
              REMOTEX CONTROL PLANE (your k8s)
            </text>

            <rect x="80" y="90" width="200" height="320" fill="#f4f1ea" stroke="#1a1a1a" />
            <text x="92" y="110" fontSize="10" fontFamily="JetBrains Mono,monospace">USER TRUST ZONE</text>

            <rect x="100" y="130" width="160" height="60" fill="#fff" stroke="#1a1a1a" />
            <text x="180" y="152" fontSize="11" fontWeight="700" textAnchor="middle">Keycloak account</text>
            <text x="180" y="167" fontSize="9" textAnchor="middle" fill="#6b6b66" fontFamily="JetBrains Mono,monospace">email + password + MFA</text>
            <text x="180" y="181" fontSize="9" textAnchor="middle" fill="#6b6b66" fontFamily="JetBrains Mono,monospace">→ OIDC session JWT</text>

            <rect x="100" y="210" width="160" height="70" fill="#fff" stroke="var(--accent)" strokeWidth="1.5" />
            <text x="180" y="232" fontSize="11" fontWeight="700" textAnchor="middle" fill="var(--accent)">Bridge API key</text>
            <text x="180" y="247" fontSize="9" textAnchor="middle" fill="#6b6b66" fontFamily="JetBrains Mono,monospace">issued per machine</text>
            <text x="180" y="261" fontSize="9" textAnchor="middle" fill="#6b6b66" fontFamily="JetBrains Mono,monospace">revocable anytime</text>
            <text x="180" y="275" fontSize="9" textAnchor="middle" fill="#6b6b66" fontFamily="JetBrains Mono,monospace">scoped to user × host</text>

            <rect x="320" y="90" width="240" height="320" fill="#f4f1ea" stroke="#1a1a1a" />
            <text x="332" y="110" fontSize="10" fontFamily="JetBrains Mono,monospace">YOUR MACHINE (off-platform)</text>

            <rect x="340" y="130" width="200" height="80" fill="#fff" stroke="#1a1a1a" />
            <text x="440" y="150" fontSize="11" fontWeight="700" textAnchor="middle">Daemon (Python)</text>
            <text x="440" y="168" fontSize="9" textAnchor="middle" fill="#6b6b66" fontFamily="JetBrains Mono,monospace">~/.remotex/config.toml</text>
            <text x="440" y="182" fontSize="9" textAnchor="middle" fill="#6b6b66" fontFamily="JetBrains Mono,monospace">stores Bridge API key</text>
            <text x="440" y="197" fontSize="9" textAnchor="middle" fill="#6b6b66" fontFamily="JetBrains Mono,monospace">hostname + nickname</text>

            <rect x="340" y="230" width="200" height="60" fill="#fff" stroke="#1a1a1a" />
            <text x="440" y="252" fontSize="11" fontWeight="700" textAnchor="middle">codex app-server</text>
            <text x="440" y="267" fontSize="9" textAnchor="middle" fill="#6b6b66" fontFamily="JetBrains Mono,monospace">localhost only</text>
            <text x="440" y="281" fontSize="9" textAnchor="middle" fill="#6b6b66" fontFamily="JetBrains Mono,monospace">spawned by daemon</text>

            <rect x="340" y="310" width="200" height="70" fill="#fff" stroke="#3a6b5c" strokeWidth="1.5" />
            <text x="440" y="332" fontSize="11" fontWeight="700" textAnchor="middle" fill="#3a6b5c">OpenAI auth</text>
            <text x="440" y="347" fontSize="9" textAnchor="middle" fill="#6b6b66" fontFamily="JetBrains Mono,monospace">~/.codex/auth.json</text>
            <text x="440" y="361" fontSize="9" textAnchor="middle" fill="#6b6b66" fontFamily="JetBrains Mono,monospace">ChatGPT OAuth or API key</text>
            <text x="440" y="375" fontSize="9" textAnchor="middle" fill="#6b6b66" fontFamily="JetBrains Mono,monospace">daemon NEVER reads this</text>

            <path d="M 260 245 L 340 170" fill="none" stroke="var(--accent)" strokeWidth="1.5" markerEnd="url(#arrC)" />
            <text x="300" y="200" fontSize="9" fontFamily="JetBrains Mono,monospace" fill="var(--accent)" textAnchor="middle">pasted once</text>

            <defs>
              <marker id="arrC" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto">
                <path d="M0,0 L10,5 L0,10 z" fill="var(--accent)" />
              </marker>
            </defs>

            <text
              x="300"
              y="452"
              fontSize="14"
              fontFamily="Caveat, cursive"
              fill="#c04a2b"
              textAnchor="middle"
              fontWeight="600"
            >
              never merge these two credentials
            </text>
          </svg>
        </div>

        <div className="annot" style={{ marginTop: 14 }}>
          <b>Two keys</b> The <span className="mono">Bridge API key</span> logs the <i>daemon</i> into Remotex. The{' '}
          <span className="mono">OpenAI auth</span> logs <i>codex</i> into OpenAI — that's the user's business, the daemon doesn't
          touch it. Blurring the two is the #1 way this goes wrong.
        </div>
      </div>
    </div>
  );
}
