from __future__ import annotations

import pytest

from relay.search.config import SearchConfig
from relay.search.service import SearchService
from relay.search.titles import _title_turn_ready


class FakeAcquire:
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, _exc_type, _exc, _tb):
        return False


class FakePool:
    def __init__(self, conn):
        self.conn = conn

    def acquire(self):
        return FakeAcquire(self.conn)


class FakeConn:
    def __init__(self) -> None:
        self.executes: list[tuple[str, tuple]] = []

    async def execute(self, sql: str, *params):
        self.executes.append((sql, params))


def _config(**overrides) -> SearchConfig:
    values = {
        "database_url": "postgresql://test",
        "embedding_api_base_url": None,
        "embedding_api_key": None,
        "embedding_model": "embed",
        "embedding_dimensions": 3,
        "embedding_max_context_tokens": 8192,
        "embedding_batch_size": 16,
        "chunk_max_tokens": 512,
        "chunk_min_tokens": 8,
        "reranker_api_base_url": None,
        "reranker_api_key": None,
        "reranker_model": "rerank",
        "reranker_top_n": 20,
        "reranker_candidate_pool": 50,
        "main_model_api_base_url": "http://main.test/v1",
        "main_model_api_key": "key",
        "main_model": "TitleModel",
        "main_model_context_tokens": 16000,
        "main_model_disable_thinking_style": "chat_template_kwargs",
        "title_generation_enabled": True,
        "title_max_input_tokens": 12000,
        "title_max_attempts": 3,
    }
    values.update(overrides)
    return SearchConfig(**values)


def test_title_turn_ready_requires_user_and_agent_message():
    assert not _title_turn_ready({
        "user_text": "Build the dashboard",
        "items": [],
    })
    assert not _title_turn_ready({
        "user_text": None,
        "items": [{"item_type": "agent_message", "text": "Done"}],
    })
    assert _title_turn_ready({
        "user_text": "Build the dashboard",
        "items": [{"item_type": "agent_message", "text": "Done"}],
    })


@pytest.mark.asyncio
async def test_title_enqueue_waits_for_ready_first_turn():
    service = SearchService(_config())
    service._pool = FakePool(FakeConn())
    service._title_task = object()
    enqueued: list[str] = []
    contexts = [
        ({"title_generated_at": None, "title_attempts": 0}, []),
        ({"title_generated_at": None, "title_attempts": 0}, [{"turn_id": "t1"}]),
    ]

    async def fake_context(_session_id: str):
        return contexts.pop(0)

    service._title_context = fake_context
    service._enqueue_title = enqueued.append

    await service._maybe_enqueue_title("s1")
    await service._maybe_enqueue_title("s1")

    assert enqueued == ["s1"]


@pytest.mark.asyncio
async def test_title_generation_uses_first_ready_turn_once():
    conn = FakeConn()
    service = SearchService(_config())
    service._pool = FakePool(conn)
    service._http = object()
    transcripts: list[str] = []

    async def fake_context(_session_id: str):
        return (
            {"title_generated_at": None, "title_attempts": 0, "cwd": "/work", "model": "gpt", "thread_id": "th"},
            [
                {
                    "turn_id": "first",
                    "user_text": "Fix the flaky relay reconnect",
                    "items": [{"item_type": "agent_message", "text": "I fixed the reconnect bug."}],
                },
                {
                    "turn_id": "second",
                    "user_text": "Now redesign the app",
                    "items": [{"item_type": "agent_message", "text": "I redesigned the app."}],
                },
            ],
        )

    async def fake_call(_session, transcript: str):
        transcripts.append(transcript)
        return {
            "title": "Fix Relay Reconnect",
            "description": "The chat started with a relay reconnect fix.",
            "is_generic": False,
        }

    service._title_context = fake_context
    service._call_title_model = fake_call

    await service._generate_title_for_session("s1")

    assert len(transcripts) == 1
    assert "Fix the flaky relay reconnect" in transcripts[0]
    assert "Now redesign the app" not in transcripts[0]
    assert conn.executes
    _sql, params = conn.executes[0]
    assert params[1:4] == (
        "Fix Relay Reconnect",
        "The chat started with a relay reconnect fix.",
        False,
    )


@pytest.mark.asyncio
async def test_title_generation_skips_after_success():
    service = SearchService(_config())
    service._http = object()
    called = False

    async def fake_context(_session_id: str):
        return (
            {"title_generated_at": 123, "title_attempts": 1, "cwd": "/work", "model": "gpt", "thread_id": "th"},
            [{"turn_id": "first", "user_text": "Task", "items": []}],
        )

    async def fake_call(_session, _transcript: str):
        nonlocal called
        called = True
        return {"title": "Bad", "description": "", "is_generic": True}

    service._title_context = fake_context
    service._call_title_model = fake_call

    await service._generate_title_for_session("s1")

    assert called is False


@pytest.mark.asyncio
async def test_title_failure_increments_attempts():
    conn = FakeConn()
    service = SearchService(_config())
    service._pool = FakePool(conn)

    await service._mark_title_failed("s1", "model failed")

    assert conn.executes
    sql, params = conn.executes[0]
    assert "title_attempts = title_attempts + 1" in sql
    assert params[0] == "s1"
    assert params[1] == "model failed"
