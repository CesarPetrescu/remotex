# Remotex deploy - Docker Compose

Self-host the relay + web client with one command. This is the
"single box, no Kubernetes" path; see the "Known gaps" section of
`services/docs/architecture.md` for what needs to be done before
you point real users at it.

## What's in here

```
deploy/
├── Dockerfile.relay     multi-stage image: builds apps/web with Node,
│                        then bundles the built assets into the relay
│                        container so one image serves both the API
│                        and the web UI.
├── docker-compose.yml   relay, Postgres inventory store + optional Caddy TLS
├── Caddyfile            TLS reverse proxy config (activated with --profile tls)
├── .env.example         TLS profile settings, host port/bind, Postgres
│                        credentials, and the relay's tunables (grace,
│                        reservation TTL, file ceiling, demo seeding)
└── README.md            this file
```

## Quickstart - relay only (no TLS)

```bash
cd deploy
docker compose up -d --build
```

The relay binds to `127.0.0.1:8080` on the host. Inventory (users, hosts,
bridge keys, sessions) lives in the Postgres volume
(`remotex_search-data`), so restarts preserve it. Tokens are stored
hashed — an old install's plaintext rows are rewritten in place the first
time the new relay starts.

Web control UI is at http://127.0.0.1:8080/ - it's the
`apps/web/` React client, compiled into static assets during the
image build and served by the relay itself (no separate web
container).

## Getting a token

A fresh database has **no users**. Compose deliberately ships
`RELAY_SEED_DEMO=0`: the demo credentials (`demo-user-token` /
`demo-bridge-token`) are hardcoded in this public repo, so any relay that
seeds them can be driven by anyone who can reach it — listing your hosts,
opening sessions, and running turns on your machines.

For a throwaway box you are only poking at from loopback, you can opt in:

```bash
RELAY_SEED_DEMO=1 docker compose up -d --build
curl -H "Authorization: Bearer demo-user-token" http://127.0.0.1:8080/api/hosts
```

Never do that on anything published, including behind the `tls` profile.
For a real deployment there is no signup flow yet, so insert your own row
in `inventory_users` — the `token` column holds `sha256(<your token>)`
hex, not the token:

```bash
docker compose exec postgres psql -U remotex -d remotex -c \
  "INSERT INTO inventory_users(token, email, created_at)
   VALUES (encode(sha256('<your token>'::bytea),'hex'), 'you@example.com',
           extract(epoch from now())::bigint);"
```

(Generate the token with something like `openssl rand -base64 32`, and
mind that it lands in your shell history.)

Then register each machine and mint a bridge key for it through the API:

```bash
curl -X POST -H "Authorization: Bearer <your token>" \
     -H 'Content-Type: application/json' -d '{"nickname":"mybox"}' \
     http://127.0.0.1:8080/api/hosts          # → {"id": "host_…"}

curl -X POST -H "Authorization: Bearer <your token>" \
     http://127.0.0.1:8080/api/hosts/<host_id>/api-key
```

The response is the **only** time the key's plaintext exists; the relay
keeps just its hash and a 12-char `key_id`. List the live ones with
`GET /api/hosts/{id}/api-key`, and retire one with:

```bash
curl -X POST -H "Authorization: Bearer <your token>" \
     -H 'Content-Type: application/json' -d '{"key_id":"<key id>"}' \
     http://127.0.0.1:8080/api/hosts/<host_id>/api-key/revoke
```

Revoking also drops that host's live daemon socket (close code 4401) —
the relay doesn't record which key a socket authenticated with, so it
disconnects and lets a daemon holding a still-valid key reconnect.

## Session reconnect grace

`RELAY_CLIENT_RECONNECT_GRACE_SECONDS` controls how long the relay keeps
an **idle** daemon session alive after a web or mobile socket
disappears. The default is 75 seconds, which covers phone sleep,
Wi-Fi/LTE switches, and browser reloads without leaving abandoned Codex
processes around for long.

A session with a turn still in flight is exempt: it stays alive as long
as the daemon keeps emitting events, and is only reaped after
`RELAY_SESSION_STALL_CEILING_SECONDS` (default 7200) of complete
silence. So a long agent run survives you closing the tab.

`RELAY_SESSION_REPLAY_LIMIT` (default 1000) is how many events per
session the relay buffers for reconnecting clients to catch up on. It's
in-memory — a relay restart drops it. When a reconnecting client asks for
frames that already fell out of the buffer, the relay sends it a
`replay-gap` frame first and the client renders an "earlier events
unavailable" marker, so a truncated transcript is never passed off as a
complete one.

`RELAY_SESSION_RESERVATION_TTL_SECONDS` (default 600) is how long a
`POST /api/sessions` reservation survives with no client ever attaching
before a background sweeper closes the row and forgets it.

## File transfer ceiling

`REMOTEX_MAX_FILE_BYTES` (default `26214400` = 25 MiB) is the single knob
for anything that moves as bytes — workspace uploads, file reads, image
attachments. The relay derives its HTTP body cap (`+1 MiB` of framing
slack) and the WebSocket frame cap on **both** `/ws/client` and
`/ws/daemon` (`×4/3` for base64, `+4 MiB` for the JSON envelope) from it,
so raising it raises per-connection memory too.

The host daemon reads the same variable — **keep the two in sync**. If the
daemon's ceiling is higher than the relay's, an oversized frame gets
dropped by aiohttp, which kills the daemon socket and with it every
session on that host. Over-limit transfers are answered with an explicit
error instead (HTTP 413 with `{error, max_bytes, size}`, or an error
response frame), never a silent disconnect — the daemon measures each
frame before it writes it, because the receiving side cannot report an
oversize frame at all: aiohttp closes the socket before the handler sees
one.

The web and Android clients mirror the default client-side; a deployment
that raises the server limit should rebuild the web bundle with
`VITE_REMOTEX_MAX_FILE_BYTES` set to match and update `MAX_FILE_BYTES` in
`android/.../ui/RemotexViewModel.kt`.

## Quickstart - with TLS (Caddy + Let's Encrypt)

Point a DNS record at the server first, then:

```bash
cp .env.example .env
$EDITOR .env             # set REMOTEX_HOSTNAME and ACME_EMAIL
docker compose --profile tls up -d --build
```

Caddy binds `:80` and `:443`, terminates TLS, and proxies everything
(including the `/ws/daemon` and `/ws/client` WebSocket upgrades) to
the relay container.

**Set `RELAY_TRUST_PROXY=1` in `.env` when you do this.** Behind a proxy
the relay's view of every caller is the proxy's own address, so every
client and every daemon shares a single rate-limit bucket and one noisy
peer can 429 the rest. With the flag set, the relay reads the address from
`X-Forwarded-For` (which Caddy sets) instead. Leave it off when the relay
is exposed directly — the header is caller-supplied and worthless without
something in front to overwrite it.

## Tail logs

```bash
docker compose logs -f relay
docker compose logs -f postgres
docker compose --profile tls logs -f caddy
```

## Upgrade

```bash
git pull
docker compose up -d --build        # or `--profile tls` if you use Caddy
```

The relay volume (`remotex_relay-data`) carries over.

## Reset

```bash
docker compose down -v               # -v nukes the Postgres volume too
```

## Pointing a daemon at this relay

### Linux (systemd user service, recommended)

On any Linux machine that should expose Codex sessions:

```bash
git clone <this repo>
cd remotex
deploy/install-daemon.sh \
    --relay-url    wss://relay.example.com/ws/daemon \
    --bridge-token <token from the relay admin flow> \
    --nickname     mybox
```

Run it with no flags to be prompted for each value. The installer
creates a venv at `~/.local/share/remotex/venv`, writes config to
`~/.remotex/config.toml`, drops a `remotex-daemon.service` unit into
`~/.config/systemd/user/`, and enables it. Re-run anytime — it's
idempotent. `deploy/install-daemon.sh --uninstall` removes the unit
(config and venv stay).

For a relay running on the same box, use `ws://127.0.0.1:8080/ws/daemon`.

### Cleartext relay URLs are refused

`daemon run` exits rather than shipping the bridge token and every prompt
over an unencrypted link: `wss://` is always fine, and so is `ws://` to
loopback, but `ws://<LAN-IP>/…` is not. If you really want a plaintext LAN
relay, add `allow_insecure = true` under `[daemon]` in
`~/.remotex/config.toml` — the installer has no flag for it, so this is a
deliberate hand edit — and expect a warning in the journal on every start.
The fix, not the workaround, is the `tls` profile above.

**Upgrading an existing plaintext-LAN install:** the refusal is an exit,
and the unit is `Restart=on-failure`, so a host that was already pointed
at `ws://<LAN-IP>/ws/daemon` will restart-loop after the upgrade with only
a line on stderr to say why. Check with
`journalctl --user -u remotex-daemon -n 20`, then either move it to
`wss://` or set `allow_insecure = true`.

To survive logout, allow your user to linger:

```bash
sudo loginctl enable-linger $USER
```

### Manual / non-Linux

```bash
cd services
pip install -r requirements.txt
python3 -m daemon init \
    --relay-url wss://relay.example.com/ws/daemon \
    --bridge-token <token from the relay admin flow> \
    --nickname mybox \
    --mode stdio \
    --config ./demo-config.toml
python3 -m daemon run --config ./demo-config.toml
```

## What's still TODO before "production"

Docker Compose gets you a self-hosted demo, not a hardened service.
Open items tracked against the roadmap:

- Keycloak / OIDC auth. Tokens are hashed at rest and demo seeding is off
  by default, but a long-lived bearer string is still a bearer string.
- Audit retention + metrics. Audit lines are emitted on `logger=audit`
  in the relay's JSON logs, but nothing collects them.
- Bridge-key metadata: issue, list, and revoke work; expiry and an
  `issued_by` column don't exist yet.
- Single-replica only — the routing hub is process-local, so you can't
  scale the relay horizontally without sticky routing or a shared bus.

Backpressure is handled: a client that can't accept a frame within 5s is
closed with code 1013 rather than stalling the relay.

Full list and ordering: the "Known gaps" section of
`services/docs/architecture.md`.
