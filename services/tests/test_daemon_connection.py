"""Daemon-to-relay connection lifecycle tests."""
from __future__ import annotations

import asyncio

import pytest
from aiohttp import web

from daemon import client as client_module
from daemon.adapters.base import SessionEvent
from daemon.client import (
    DaemonClient,
    _RelayAuthenticationError,
    _RelayConnectionError,
    _RelayConnectionReplaced,
)
from daemon.config import Config


def _config(relay_url: str = "ws://relay.invalid/ws/daemon") -> Config:
    return Config(
        relay_url=relay_url,
        bridge_token="brg_test",
        nickname="test-host",
        mode="mock",
    )


@pytest.mark.asyncio
async def test_ping_request_answers_immediately():
    daemon = DaemonClient(_config())
    sent: list[dict] = []

    async def send(frame: dict) -> None:
        sent.append(frame)

    await daemon._dispatch(
        {"type": "ping-request", "request_id": "req_ping"}, send,
    )

    assert sent == [{"type": "ping-response", "request_id": "req_ping"}]


@pytest.mark.asyncio
async def test_session_runner_invalidates_inventory_at_thread_boundaries():
    sent: list[dict] = []
    exited = asyncio.Event()

    class FakeAdapter:
        async def start(self) -> None:
            return None

        async def stop(self) -> None:
            return None

        async def events(self):
            yield SessionEvent("session-started", {"thread_id": "thr_1"})
            yield SessionEvent("item-completed", {"item_id": "item_1"})
            yield SessionEvent("turn-completed", {"status": "completed"})

    async def send(frame: dict) -> None:
        sent.append(frame)

    runner = client_module._SessionRunner(
        "sess_1", FakeAdapter(), send, exited.set,
    )
    await runner.start()
    await asyncio.wait_for(exited.wait(), timeout=1.0)
    assert runner._task is not None
    await runner._task

    assert [frame["type"] for frame in sent] == [
        "session-event",
        "threads-changed",
        "session-event",
        "session-event",
        "threads-changed",
        "session-closed",
    ]
    invalidations = [
        frame for frame in sent if frame["type"] == "threads-changed"
    ]
    assert invalidations == [
        {
            "type": "threads-changed",
            "reason": "session-started",
            "thread_id": "thr_1",
        },
        {"type": "threads-changed", "reason": "turn-completed"},
    ]


@pytest.mark.asyncio
async def test_reconnect_backoff_resets_after_stable_connection(monkeypatch):
    daemon = DaemonClient(_config())
    failures = [
        _RelayConnectionError("first"),
        _RelayConnectionError("second"),
        _RelayConnectionError(
            "stable connection dropped",
            connected_for=client_module._RECONNECT_STABLE_SECONDS,
        ),
    ]
    backoff_ceilings: list[float] = []

    async def fail_then_cancel() -> None:
        if failures:
            raise failures.pop(0)
        raise asyncio.CancelledError

    def no_wait(backoff: float) -> float:
        backoff_ceilings.append(backoff)
        return 0.0

    monkeypatch.setattr(daemon, "_run_once", fail_then_cancel)
    monkeypatch.setattr(client_module, "_retry_delay", no_wait)

    with pytest.raises(asyncio.CancelledError):
        await daemon.run()

    assert backoff_ceilings == [1.0, 2.0, 1.0]


@pytest.mark.asyncio
async def test_authentication_failure_uses_slow_retry(monkeypatch):
    daemon = DaemonClient(_config())
    attempts = 0
    backoff_ceilings: list[float] = []

    async def reject_then_cancel() -> None:
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise _RelayAuthenticationError("invalid bridge token")
        raise asyncio.CancelledError

    def no_wait(backoff: float) -> float:
        backoff_ceilings.append(backoff)
        return 0.0

    monkeypatch.setattr(daemon, "_run_once", reject_then_cancel)
    monkeypatch.setattr(client_module, "_retry_delay", no_wait)

    with pytest.raises(asyncio.CancelledError):
        await daemon.run()

    assert backoff_ceilings == [client_module._RECONNECT_MAX_SECONDS]


@pytest.mark.asyncio
async def test_replaced_daemon_stops_instead_of_fighting_new_connection(monkeypatch):
    daemon = DaemonClient(_config())

    async def replaced() -> None:
        raise _RelayConnectionReplaced

    monkeypatch.setattr(daemon, "_run_once", replaced)

    await daemon.run()


@pytest.mark.asyncio
async def test_reconnects_after_websocket_dies(monkeypatch):
    connections = 0
    reconnected = asyncio.Event()

    async def relay_socket(request: web.Request) -> web.WebSocketResponse:
        nonlocal connections
        ws = web.WebSocketResponse()
        await ws.prepare(request)
        hello = await ws.receive_json()
        assert hello["type"] == "hello"
        assert hello["token"] == "brg_test"
        assert hello["mode"] == "mock"
        connections += 1
        await ws.send_json({"type": "welcome", "host_id": "host_test"})
        if connections == 1:
            await ws.close(code=1012, message=b"relay restart")
        else:
            reconnected.set()
            async for _ in ws:
                pass
        return ws

    app = web.Application()
    app.router.add_get("/ws/daemon", relay_socket)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    assert site._server is not None  # type: ignore[attr-defined]
    port = site._server.sockets[0].getsockname()[1]  # type: ignore[attr-defined]

    async def idle_telemetry(*_args) -> None:
        await asyncio.Future()

    monkeypatch.setattr(client_module, "_retry_delay", lambda _backoff: 0.0)
    monkeypatch.setattr(client_module, "telemetry_loop", idle_telemetry)

    daemon = DaemonClient(_config(f"ws://127.0.0.1:{port}/ws/daemon"))
    daemon_task = asyncio.create_task(daemon.run())
    try:
        await asyncio.wait_for(reconnected.wait(), timeout=2.0)
        assert connections == 2
    finally:
        daemon_task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await daemon_task
        await runner.cleanup()


@pytest.mark.asyncio
async def test_shared_codex_socket_death_recycles_relay_and_reconnects(monkeypatch):
    connections = 0
    first_welcome = asyncio.Event()
    reconnected = asyncio.Event()

    async def relay_socket(request: web.Request) -> web.WebSocketResponse:
        nonlocal connections
        ws = web.WebSocketResponse()
        await ws.prepare(request)
        hello = await ws.receive_json()
        assert hello["type"] == "hello"
        assert hello["mode"] == "shared"
        connections += 1
        await ws.send_json({"type": "welcome", "host_id": "host_test"})
        if connections == 1:
            first_welcome.set()
        else:
            reconnected.set()
        async for _ in ws:
            pass
        return ws

    app = web.Application()
    app.router.add_get("/ws/daemon", relay_socket)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    assert site._server is not None  # type: ignore[attr-defined]
    port = site._server.sockets[0].getsockname()[1]  # type: ignore[attr-defined]

    instances = []

    class FakeSharedConnection:
        user_agent = "fake-shared-codex"

        def __init__(self, *_args, **_kwargs) -> None:
            self.disconnect_handler = None
            self.failure_task = None
            instances.append(self)

        async def start(self) -> None:
            return None

        def set_disconnect_handler(self, handler) -> None:
            self.disconnect_handler = handler
            if handler is not None and len(instances) == 1:
                async def fail_after_welcome() -> None:
                    await first_welcome.wait()
                    await handler(RuntimeError("fake Codex socket died"))

                self.failure_task = asyncio.create_task(fail_after_welcome())

        def set_thread_lifecycle_handler(self, handler) -> None:
            self.thread_lifecycle_handler = handler

        async def close(self) -> None:
            task = self.failure_task
            if task is not None and not task.done():
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)

    async def idle_telemetry(*_args) -> None:
        await asyncio.Future()

    monkeypatch.setattr(client_module, "SharedCodexConnection", FakeSharedConnection)
    monkeypatch.setattr(client_module, "_retry_delay", lambda _backoff: 0.0)
    monkeypatch.setattr(client_module, "telemetry_loop", idle_telemetry)

    cfg = _config(f"ws://127.0.0.1:{port}/ws/daemon")
    cfg.mode = "shared"
    cfg.codex_socket_path = "/tmp/fake-codex.sock"
    daemon = DaemonClient(cfg)
    daemon_task = asyncio.create_task(daemon.run())
    try:
        await asyncio.wait_for(reconnected.wait(), timeout=2.0)
        assert connections == 2
        assert len(instances) >= 2
    finally:
        daemon_task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await daemon_task
        await runner.cleanup()


@pytest.mark.asyncio
async def test_real_replacement_close_stops_without_reconnecting(monkeypatch):
    """Use a real aiohttp close frame: ``ws.close_code`` can become 1006
    after receiving code 4000, so the daemon must inspect the frame itself."""
    connections = 0

    async def relay_socket(request: web.Request) -> web.WebSocketResponse:
        nonlocal connections
        ws = web.WebSocketResponse()
        await ws.prepare(request)
        hello = await ws.receive_json()
        assert hello["type"] == "hello"
        connections += 1
        await ws.send_json({"type": "welcome", "host_id": "host_test"})
        await ws.close(code=4000, message=b"daemon-replaced")
        return ws

    app = web.Application()
    app.router.add_get("/ws/daemon", relay_socket)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    assert site._server is not None  # type: ignore[attr-defined]
    port = site._server.sockets[0].getsockname()[1]  # type: ignore[attr-defined]

    async def idle_telemetry(*_args) -> None:
        await asyncio.Future()

    monkeypatch.setattr(client_module, "telemetry_loop", idle_telemetry)
    daemon = DaemonClient(_config(f"ws://127.0.0.1:{port}/ws/daemon"))
    try:
        await asyncio.wait_for(daemon.run(), timeout=2.0)
        assert connections == 1
    finally:
        await runner.cleanup()


@pytest.mark.asyncio
async def test_replacement_before_welcome_also_stops(monkeypatch):
    connections = 0

    async def relay_socket(request: web.Request) -> web.WebSocketResponse:
        nonlocal connections
        ws = web.WebSocketResponse()
        await ws.prepare(request)
        hello = await ws.receive_json()
        assert hello["type"] == "hello"
        connections += 1
        await ws.close(code=4000, message=b"daemon-replaced")
        return ws

    app = web.Application()
    app.router.add_get("/ws/daemon", relay_socket)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    assert site._server is not None  # type: ignore[attr-defined]
    port = site._server.sockets[0].getsockname()[1]  # type: ignore[attr-defined]

    daemon = DaemonClient(_config(f"ws://127.0.0.1:{port}/ws/daemon"))
    try:
        await asyncio.wait_for(daemon.run(), timeout=2.0)
        assert connections == 1
    finally:
        await runner.cleanup()
