"""Shared Codex socket multiplexing and hot-resume ordering."""

from __future__ import annotations

import asyncio
import json
from pathlib import Path

import pytest

from daemon.adapters import stdio as stdio_module
from daemon.adapters.shared import SharedCodexConnection
from daemon.adapters.stdio import StdioCodexAdapter


class _FakeWebSocket:
    def __init__(self) -> None:
        self.closed = False
        self.sent: list[dict] = []

    async def send_str(self, payload: str) -> None:
        self.sent.append(json.loads(payload))


class _FakeReaderTask:
    @staticmethod
    def done() -> bool:
        return False


class _ClosingWebSocket(_FakeWebSocket):
    def __init__(self) -> None:
        super().__init__()
        self.close_code = 1006
        self.release = asyncio.Event()

    def __aiter__(self):
        return self

    async def __anext__(self):
        await self.release.wait()
        raise StopAsyncIteration


def _connection() -> tuple[SharedCodexConnection, _FakeWebSocket]:
    connection = SharedCodexConnection(Path("/tmp/codex-shared-test.sock"))
    socket = _FakeWebSocket()
    connection._ws = socket  # type: ignore[assignment]
    connection._reader_task = _FakeReaderTask()  # type: ignore[assignment]
    return connection, socket


async def _wait_for_sent(socket: _FakeWebSocket, count: int) -> list[dict]:
    async def wait() -> list[dict]:
        while len(socket.sent) < count:
            await asyncio.sleep(0)
        return socket.sent

    return await asyncio.wait_for(wait(), timeout=1.0)


def _drain(adapter: StdioCodexAdapter) -> list:
    events = []
    while not adapter._queue.empty():
        events.append(adapter._queue.get_nowait())
    return events


@pytest.mark.asyncio
async def test_responses_route_by_id_when_they_arrive_out_of_order():
    connection, socket = _connection()
    first = asyncio.create_task(connection.request("first", {}))
    second = asyncio.create_task(connection.request("second", {}))

    sent = await _wait_for_sent(socket, 2)
    ids = {message["method"]: message["id"] for message in sent}
    await connection._route({"id": ids["second"], "result": {"value": 2}})
    await connection._route({"id": ids["first"], "result": {"value": 1}})

    assert await first == {"value": 1}
    assert await second == {"value": 2}
    assert connection._pending == {}


@pytest.mark.asyncio
async def test_socket_death_fails_requests_and_runs_disconnect_handler():
    connection = SharedCodexConnection(Path("/tmp/codex-shared-test.sock"))
    socket = _ClosingWebSocket()
    connection._ws = socket  # type: ignore[assignment]
    disconnected = asyncio.Event()

    async def on_disconnect(_exc: BaseException) -> None:
        disconnected.set()

    connection.set_disconnect_handler(on_disconnect)
    reader = asyncio.create_task(connection._read_loop())
    connection._reader_task = reader
    request = asyncio.create_task(connection.request("thread/list", {}))
    await _wait_for_sent(socket, 1)

    socket.release.set()
    with pytest.raises(RuntimeError, match="websocket closed"):
        await request
    await asyncio.wait_for(disconnected.wait(), timeout=1.0)
    await reader


@pytest.mark.asyncio
async def test_thread_notifications_are_isolated_between_sessions():
    connection, _ = _connection()
    seen_a: list[dict] = []
    seen_b: list[dict] = []

    async def handle_a(message: dict) -> None:
        seen_a.append(message)

    async def handle_b(message: dict) -> None:
        seen_b.append(message)

    connection.register("thread-a", handle_a)
    connection.register("thread-b", handle_b)
    message_a = {
        "method": "item/agentMessage/delta",
        "params": {"threadId": "thread-a", "itemId": "item-a", "delta": "a"},
    }
    request_b = {
        "id": 91,
        "method": "item/tool/requestUserInput",
        "params": {"threadId": "thread-b", "callId": "call-b", "questions": []},
    }

    await connection._route(message_a)
    await connection._route(request_b)
    await connection._route(
        {
            "method": "item/agentMessage/delta",
            "params": {"threadId": "unclaimed", "itemId": "private", "delta": "hidden"},
        }
    )

    assert seen_a == [message_a]
    assert seen_b == [request_b]


@pytest.mark.asyncio
async def test_thread_start_claims_listener_before_started_notification():
    connection, socket = _connection()
    seen: list[dict] = []

    async def handle(message: dict) -> None:
        seen.append(message)

    start = asyncio.create_task(connection.start_thread({"cwd": "/tmp"}, handle))
    (request,) = await _wait_for_sent(socket, 1)
    await connection._route(
        {
            "id": request["id"],
            "result": {"thread": {"id": "thread-new"}},
        }
    )
    started = {
        "method": "thread/started",
        "params": {"thread": {"id": "thread-new", "status": {"type": "idle"}}},
    }
    await connection._route(started)

    assert await start == {"thread": {"id": "thread-new"}}
    assert seen == [started]


@pytest.mark.asyncio
async def test_unclaimed_local_thread_is_unsubscribed_without_forwarding():
    connection, _ = _connection()
    unsubscribed: list[str] = []
    called = asyncio.Event()

    async def unsubscribe(thread_id: str) -> None:
        unsubscribed.append(thread_id)
        called.set()

    connection._unsubscribe = unsubscribe  # type: ignore[method-assign]
    await connection._route(
        {
            "method": "thread/started",
            "params": {"thread": {"id": "local-private"}},
        }
    )

    await asyncio.wait_for(called.wait(), timeout=1.0)
    await asyncio.gather(*list(connection._orphan_tasks))
    assert unsubscribed == ["local-private"]
    assert "local-private" not in connection._listeners


@pytest.mark.asyncio
async def test_hot_resume_snapshot_precedes_buffered_delta_and_sets_active_turn():
    connection, _ = _connection()
    adapter = StdioCodexAdapter(
        default_cwd="/tmp",
        shared_connection=connection,
    )
    adapter._thread_id = "thread-hot"
    connection.register("thread-hot", adapter._dispatch, paused=True)
    await connection._route(
        {
            "method": "item/agentMessage/delta",
            "params": {
                "threadId": "thread-hot",
                "turnId": "turn-hot",
                "itemId": "answer",
                "delta": " world",
            },
        }
    )
    assert _drain(adapter) == []

    await adapter._hydrate_active_turn(
        {
            "id": "thread-hot",
            "turns": [
                {
                    "id": "turn-hot",
                    "status": "inProgress",
                    "items": [{"type": "agentMessage", "id": "answer", "text": "Hello"}],
                }
            ],
        }
    )
    await connection.activate("thread-hot", adapter._dispatch)

    events = _drain(adapter)
    assert adapter._turn_id == "turn-hot"
    assert [event.kind for event in events] == [
        "turn-started",
        "item-started",
        "item-delta",
    ]
    assert events[1].data["text"] == "Hello"
    assert events[2].data["delta"] == " world"


@pytest.mark.asyncio
async def test_shared_idle_resume_emits_turn_state_reconciliation():
    connection, _ = _connection()
    adapter = StdioCodexAdapter(
        default_cwd="/tmp",
        resume_thread_id="thread-idle",
        shared_connection=connection,
    )
    adapter._thread_id = "thread-idle"
    adapter._shared_registered = True
    connection.register("thread-idle", adapter._dispatch, paused=True)

    async def fake_request(method, _params, timeout=60.0):
        if method == "thread/resume":
            return {
                "thread": {"id": "thread-idle", "turns": []},
                "model": "test-model",
                "cwd": "/tmp",
            }
        assert method == "thread/goal/get"
        return {"goal": None}

    adapter._request = fake_request  # type: ignore[method-assign]
    await adapter._finish_resume("test-model")

    resumed = next(
        event for event in _drain(adapter)
        if event.kind == "thread-status" and event.data.get("status") == "resumed"
    )
    assert resumed.data["shared_turn_in_flight"] is False


@pytest.mark.asyncio
async def test_hot_resume_marks_terminal_snapshot_items_completed():
    connection, _ = _connection()
    adapter = StdioCodexAdapter(default_cwd="/tmp", shared_connection=connection)
    adapter._thread_id = "thread-hot"

    await adapter._hydrate_active_turn({
        "id": "thread-hot",
        "turns": [{
            "id": "turn-hot",
            "status": "inProgress",
            "items": [
                {
                    "type": "commandExecution",
                    "id": "finished-tool",
                    "command": "pwd",
                    "status": "completed",
                },
                {
                    "type": "agentMessage",
                    "id": "streaming-answer",
                    "text": "partial",
                },
            ],
        }],
    })

    events = _drain(adapter)
    assert [event.kind for event in events] == [
        "turn-started",
        "item-started",
        "item-completed",
        "item-started",
    ]
    assert events[2].data["item_id"] == "finished-tool"


@pytest.mark.asyncio
async def test_unknown_server_request_gives_peer_a_chance_then_rejects(
    monkeypatch,
):
    shared, socket = _connection()
    shared_adapter = StdioCodexAdapter(
        default_cwd="/tmp",
        shared_connection=shared,
    )
    stdio_adapter = StdioCodexAdapter(default_cwd="/tmp")
    stdio_sent: list[dict] = []

    async def capture_stdio(message: dict) -> None:
        stdio_sent.append(message)

    stdio_adapter._send = capture_stdio  # type: ignore[method-assign]
    request = {
        "id": 72,
        "method": "item/tool/call",
        "params": {"threadId": "thread-1"},
    }

    monkeypatch.setattr(
        stdio_module,
        "_SHARED_UNSUPPORTED_REQUEST_GRACE_SECONDS",
        0.0,
    )
    await shared_adapter._dispatch(request)
    await stdio_adapter._dispatch(request)
    await _wait_for_sent(socket, 1)

    assert socket.sent[0]["id"] == 72
    assert socket.sent[0]["error"]["code"] == -32601
    assert _drain(shared_adapter) == []
    assert stdio_sent[0]["error"]["code"] == -32601
    assert _drain(stdio_adapter) == []


@pytest.mark.asyncio
async def test_peer_resolution_cancels_unknown_request_fallback():
    shared, socket = _connection()
    adapter = StdioCodexAdapter(default_cwd="/tmp", shared_connection=shared)

    await adapter._dispatch({
        "id": "future-request",
        "method": "future/clientFeature",
        "params": {"threadId": "thread-1"},
    })
    assert "future-request" in adapter._unsupported_request_tasks

    await adapter._dispatch({
        "method": "serverRequest/resolved",
        "params": {"threadId": "thread-1", "requestId": "future-request"},
    })
    await asyncio.sleep(0)

    assert socket.sent == []
    assert adapter._unsupported_request_tasks == {}
