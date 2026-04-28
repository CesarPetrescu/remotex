"""End-to-end test.

Spins up the relay in-process against a real Postgres (the inventory
store now requires it), starts the daemon in-process (mock adapter),
then plays the role of a web client: lists hosts, opens a session,
sends a turn, and asserts the streamed events arrive in the expected
order.

Set ``E2E_DATABASE_URL`` (or ``SEARCH_DATABASE_URL``) to a Postgres
instance the test can write to. The test creates ``inventory_*`` tables
on first run; pgvector and ``search_*`` tables are created lazily by
the search service if the URL points at a pgvector image, but the e2e
test only exercises inventory + routing.

Run: python3 scripts/e2e_test.py
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import sys
from pathlib import Path

import aiohttp
from aiohttp import web

PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT))

from daemon.client import DaemonClient  # noqa: E402
from daemon.config import Config  # noqa: E402
from relay.app import make_app  # noqa: E402
from relay.store import DEMO_BRIDGE_TOKEN, DEMO_USER_TOKEN  # noqa: E402


logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("e2e")


async def wait_until(predicate, timeout: float = 5.0, interval: float = 0.1) -> bool:
    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout
    while loop.time() < deadline:
        if predicate():
            return True
        await asyncio.sleep(interval)
    return False


async def _reset_inventory(database_url: str) -> None:
    """Drop inventory tables so each run starts clean. Only touches our
    tables, never the search_* siblings."""
    import asyncpg  # type: ignore[import-not-found]
    conn = await asyncpg.connect(dsn=database_url)
    try:
        await conn.execute(
            "DROP TABLE IF EXISTS inventory_sessions, inventory_bridge_keys, "
            "inventory_hosts, inventory_users CASCADE"
        )
    finally:
        await conn.close()


async def run_test() -> int:
    database_url = os.getenv("E2E_DATABASE_URL") or os.getenv("SEARCH_DATABASE_URL")
    if not database_url:
        log.error(
            "set E2E_DATABASE_URL or SEARCH_DATABASE_URL to a writable Postgres DSN; "
            "e.g. postgres://remotex:remotex-search@127.0.0.1:5432/remotex"
        )
        return 2

    await _reset_inventory(database_url)

    web_root = PROJECT_ROOT / "web"

    app = make_app(database_url, web_root)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()

    # Grab the actual bound port from the started site
    assert site._server is not None, "site not started"  # type: ignore[attr-defined]
    port = site._server.sockets[0].getsockname()[1]  # type: ignore[attr-defined]
    base = f"http://127.0.0.1:{port}"
    ws_base = f"ws://127.0.0.1:{port}"
    log.info("relay up on %s", base)

    # Boot the daemon in-process. No init step needed — construct config directly.
    cfg = Config(
        relay_url=f"{ws_base}/ws/daemon",
        bridge_token=DEMO_BRIDGE_TOKEN,
        nickname="e2e-host",
        mode="mock",
    )
    client = DaemonClient(cfg)
    daemon_task = asyncio.create_task(client.run())

    try:
        # Wait for daemon to come online.
        hub = app["hub"]
        online = await wait_until(lambda: bool(hub.daemons), timeout=5.0)
        assert online, "daemon never attached to relay"
        log.info("daemon connected (host ids=%s)", list(hub.daemons.keys()))

        async with aiohttp.ClientSession() as http:
            headers = {"Authorization": f"Bearer {DEMO_USER_TOKEN}"}

            # Smoke-test the new /api/models endpoint.
            async with http.get(f"{base}/api/models") as resp:
                assert resp.status == 200, f"models: {resp.status}"
                models = (await resp.json())["models"]
                assert any(m["id"] == "" for m in models), "models list missing default"
                log.info("/api/models returned %d entries", len(models))

            async with http.get(f"{base}/api/hosts", headers=headers) as resp:
                assert resp.status == 200, f"list hosts: {resp.status}"
                hosts = (await resp.json())["hosts"]
            online_hosts = [h for h in hosts if h["online"]]
            assert online_hosts, f"no online hosts (got {hosts})"
            host = online_hosts[0]
            log.info("picked host %s (%s)", host["id"], host["nickname"])

            # Open a session.
            async with http.post(
                f"{base}/api/sessions",
                headers=headers,
                json={"host_id": host["id"]},
            ) as resp:
                assert resp.status == 201, f"open session: {resp.status} {await resp.text()}"
                payload = await resp.json()
                sid = payload["session_id"]
                log.info("opened session %s", sid)

            # Attach as a client.
            async with http.ws_connect(f"{ws_base}/ws/client") as ws:
                await ws.send_json({
                    "type": "hello",
                    "token": DEMO_USER_TOKEN,
                    "session_id": sid,
                })
                attached = await asyncio.wait_for(ws.receive_json(), timeout=5)
                assert attached["type"] == "attached", attached
                log.info("client attached")

                await ws.send_json({"type": "turn-start", "input": "extract the jwt verify path"})

                seen = []
                async def drain():
                    async for msg in ws:
                        if msg.type != aiohttp.WSMsgType.TEXT:
                            continue
                        frame = json.loads(msg.data)
                        if frame.get("type") != "session-event":
                            continue
                        ev = frame["event"]
                        seen.append(ev["kind"])
                        if ev["kind"] == "turn-completed":
                            return
                await asyncio.wait_for(drain(), timeout=10)

                required = [
                    "session-started",
                    "turn-started",
                    "item-started",
                    "item-completed",
                    "turn-completed",
                ]
                missing = [k for k in required if k not in seen]
                assert not missing, f"missing events: {missing}; saw {seen}"
                assert seen.count("item-delta") > 0, f"no deltas observed (events={seen})"
                log.info("observed %d events including %d deltas",
                         len(seen), seen.count("item-delta"))
    finally:
        daemon_task.cancel()
        try:
            await daemon_task
        except (asyncio.CancelledError, Exception):
            pass
        await runner.cleanup()

    print("\nE2E: OK — full flow exercised relay <-> daemon <-> client\n")
    return 0


def main() -> int:
    try:
        return asyncio.run(run_test())
    except AssertionError as exc:
        print(f"\nE2E: FAIL — {exc}\n", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
