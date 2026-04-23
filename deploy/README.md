# Remotex deploy - Docker Compose

Self-host the relay + web client with one command. This is the
"single box, no Kubernetes" path; see
`services/docs/production_plan.md` for what needs to be done before
you point real users at it.

## What's in here

```
deploy/
├── Dockerfile.relay     multi-stage image: builds apps/web with Node,
│                        then bundles the built assets into the relay
│                        container so one image serves both the API
│                        and the web UI.
├── docker-compose.yml   relay, pgvector search store + optional Caddy TLS
├── Caddyfile            TLS reverse proxy config (activated with --profile tls)
├── .env.example         variables for the tls profile
└── README.md            this file
```

## Quickstart - relay only (no TLS)

```bash
cd deploy
docker compose up -d --build
```

The relay binds to `127.0.0.1:8080` on the host. SQLite lives in a
named volume (`remotex_relay-data`), so restarts preserve hosts,
bridge tokens, and sessions. Semantic search chunks and vectors live
in the Postgres/pgvector volume (`remotex_search-data`).

```bash
curl -H "Authorization: Bearer demo-user-token" \
     http://127.0.0.1:8080/api/hosts
```

Web control UI is at http://127.0.0.1:8080/ - it's the
`apps/web/` React client, compiled into static assets during the
image build and served by the relay itself (no separate web
container).

## Semantic Search

The Compose stack includes Postgres with pgvector. Search is visible
in the clients, but results require an OpenAI-compatible embedding
endpoint. LiteLLM works when it exposes `/v1/embeddings`:

```bash
cd deploy
cp .env.example .env
$EDITOR .env
docker compose up -d --build
```

Set at least:

```dotenv
EMBEDDING_API_BASE_URL=http://your-litellm-host:80/v1
EMBEDDING_API_KEY=your-litellm-master-key
EMBEDDING_MODEL=qwen3-embedding
EMBEDDING_DIMENSIONS=4096
EMBEDDING_MAX_CONTEXT_TOKENS=32768
```

If the same LiteLLM proxy also serves the Qwen LLM, chat
title/description generation can use it too:

```dotenv
MAIN_MODEL_API_BASE_URL=http://your-litellm-host:80/v1
MAIN_MODEL_API_KEY=your-litellm-master-key
MAIN_MODEL=MainModel
MAIN_MODEL_CONTEXT_TOKENS=16000
MAIN_MODEL_DISABLE_THINKING_STYLE=chat_template_kwargs
CHAT_TITLE_ENABLED=1
```

The relay sends `chat_template_kwargs: {enable_thinking: false}` so
Qwen3.6 returns the XML metadata directly instead of streaming a
`<think>` section first.

The relay exposes non-secret effective config at:

```bash
curl -H "Authorization: Bearer demo-user-token" \
     http://127.0.0.1:8080/api/search/config
```

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
docker compose down -v               # -v nukes the SQLite volume too
```

## Pointing a daemon at this relay

On any machine that should expose Codex sessions:

```bash
cd services
pip install -r requirements.txt
python3 -m daemon init \
    --relay-url wss://relay.example.com/ws/daemon \
    --bridge-token <token from the relay admin flow> \
    --nickname mybox \
    --mode mock \
    --config ./demo-config.toml
python3 -m daemon run --config ./demo-config.toml
```

For the local-only run, swap `wss://relay.example.com` for
`ws://127.0.0.1:8080`.

## What's still TODO before "production"

Docker Compose gets you a self-hosted demo, not a hardened service.
Open items tracked against the roadmap:

- Move the primary relay inventory from SQLite to Postgres.
- Keycloak / OIDC auth (currently demo bearer tokens seeded on first run).
- Audit log + metrics.
- Bounded queues / backpressure on session fan-out.
- Real bridge-key lifecycle: revoke, expire, audit.

Item ordering lives in the root `README.md`.
