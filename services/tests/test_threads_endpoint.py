"""Relay thread-list response normalization."""
from __future__ import annotations

import json
from types import SimpleNamespace

import pytest

from relay.handlers import threads as threads_module


@pytest.mark.asyncio
async def test_thread_summary_keeps_inventory_metadata(monkeypatch):
    async def fake_daemon_request(*_args, **_kwargs) -> dict:
        return {
            "threads": [
                {
                    "id": "thr_named",
                    "name": "  Ship it  ",
                    "preview": "generic preview",
                    "status": {"type": "active"},
                    "createdAt": 10,
                    "updatedAt": 20,
                    "recencyAt": 30,
                    "cwd": "/work",
                    "ephemeral": False,
                },
                {
                    "id": "thr_generic",
                    "preview": "Fallback title",
                    "status": {"type": "idle"},
                    "ephemeral": False,
                },
                {"id": "thr_hidden", "ephemeral": True},
            ],
            "next_cursor": "next",
        }

    monkeypatch.setattr(
        threads_module, "await_daemon_request", fake_daemon_request,
    )
    request = SimpleNamespace(
        match_info={"host_id": "host-a"},
        query={"limit": "20"},
    )

    response = await threads_module.list_host_threads(request)
    payload = json.loads(response.text)

    assert payload["next_cursor"] == "next"
    assert payload["threads"] == [
        {
            "id": "thr_named",
            "name": "Ship it",
            "title": "Ship it",
            "title_is_generic": False,
            "preview": "generic preview",
            "status": {"type": "active"},
            "created_at": 10,
            "updated_at": 20,
            "recency_at": 30,
            "cwd": "/work",
            "ephemeral": False,
        },
        {
            "id": "thr_generic",
            "name": None,
            "title": "Fallback title",
            "title_is_generic": True,
            "preview": "Fallback title",
            "status": {"type": "idle"},
            "created_at": None,
            "updated_at": None,
            "recency_at": None,
            "cwd": None,
            "ephemeral": False,
        },
    ]
