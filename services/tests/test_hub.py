"""Hub attach/detach and session-frame caching."""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest

from relay.hub import Hub


def _ws_mock():
    ws = MagicMock()
    ws.closed = False
    ws.send_json = AsyncMock()
    ws.close = AsyncMock()
    return ws


@pytest.mark.asyncio
async def test_attach_daemon_returns_old_ws_on_replacement():
    hub = Hub()
    first = _ws_mock()
    old = await hub.attach_daemon("host_a", first)
    assert old is None
    second = _ws_mock()
    replaced = await hub.attach_daemon("host_a", second)
    assert replaced is first


@pytest.mark.asyncio
async def test_daemon_mode_is_bound_to_socket_identity():
    hub = Hub()
    shared = _ws_mock()
    stdio = _ws_mock()

    await hub.attach_daemon("host_a", shared, mode="shared")
    replaced = await hub.attach_daemon("host_a", stdio, mode="stdio")

    assert replaced is shared
    assert hub.daemon_mode_for(shared) == "shared"
    assert hub.daemon_mode_for(stdio) == "stdio"
    assert await hub.detach_daemon("host_a", shared) is False
    assert hub.daemon_mode_for(stdio) == "stdio"


@pytest.mark.asyncio
async def test_detach_daemon_only_removes_matching_ws():
    hub = Hub()
    a = _ws_mock()
    b = _ws_mock()
    await hub.attach_daemon("host", a)
    # Trying to detach with a stale reference must not blow away the live one.
    detached = await hub.detach_daemon("host", b)
    assert detached is False
    assert hub.daemon_for("host") is a
    detached = await hub.detach_daemon("host", a)
    assert detached is True
    assert hub.daemon_for("host") is None


@pytest.mark.asyncio
async def test_invalidate_host_prompts_preserves_turn_and_other_hosts():
    hub = Hub()
    await hub.attach_client("sess_a", "host_a", "web", _ws_mock())
    await hub.attach_client("sess_b", "host_b", "web", _ws_mock())
    hub.mark_turn_started("sess_a")
    await hub.note_approval_request("sess_a", "appr_a")
    await hub.note_user_input_request("sess_a", "input_a")
    await hub.note_approval_request("sess_b", "appr_b")

    affected = await hub.invalidate_host_prompts("host_a")

    assert affected == ["sess_a"]
    assert hub.turn_in_flight["sess_a"] is True
    assert (await hub.pending_prompt_snapshot("sess_a"))["approvals"] == []
    assert (await hub.pending_prompt_snapshot("sess_a"))["user_inputs"] == []
    assert len((await hub.pending_prompt_snapshot("sess_b"))["approvals"]) == 1


@pytest.mark.asyncio
async def test_session_open_frame_replay_for_host():
    hub = Hub()
    await hub.ensure_session_open_frame("sess_1", "host_x")
    await hub.ensure_session_open_frame("sess_2", "host_x")
    await hub.ensure_session_open_frame("sess_3", "host_y")
    frames = await hub.session_open_frames_for_host("host_x")
    sids = sorted(f["session_id"] for f in frames)
    assert sids == ["sess_1", "sess_2"]


@pytest.mark.asyncio
async def test_update_session_resume_modifies_cached_frame():
    hub = Hub()
    await hub.ensure_session_open_frame("sess_1", "host_x")
    await hub.update_session_resume("sess_1", thread_id="thr_42", cwd="/work")
    frame = (await hub.session_open_frames_for_host("host_x"))[0]
    assert frame["resume_thread_id"] == "thr_42"
    assert frame["cwd"] == "/work"


@pytest.mark.asyncio
async def test_multiple_clients_attach_to_same_session_without_replacement():
    hub = Hub()
    web = _ws_mock()
    android = _ws_mock()

    assert await hub.attach_client("sess_1", "host_x", "web", web) == 1
    assert await hub.attach_client("sess_1", "host_x", "android", android) == 2

    web.close.assert_not_awaited()
    android.close.assert_not_awaited()

    assert await hub.detach_client("sess_1", "web", web) is False
    assert hub.client_for("sess_1") is android
    assert await hub.detach_client("sess_1", "android", android) is True
    assert hub.client_for("sess_1") is None


@pytest.mark.asyncio
async def test_same_client_id_replaces_stale_socket_only():
    hub = Hub()
    old = _ws_mock()
    new = _ws_mock()

    await hub.attach_client("sess_1", "host_x", "web", old)
    assert await hub.attach_client("sess_1", "host_x", "web", new) == 1

    old.close.assert_awaited_once()
    new.close.assert_not_awaited()
    assert hub.client_for("sess_1") is new


@pytest.mark.asyncio
async def test_broadcast_records_sequence_and_replays_to_late_client():
    hub = Hub()
    web = _ws_mock()
    android = _ws_mock()
    await hub.attach_client("sess_1", "host_x", "web", web)
    await hub.attach_client("sess_1", "host_x", "android", android)

    delivered = await hub.broadcast_to_session(
        "sess_1",
        {"type": "session-event", "event": {"kind": "turn-started", "data": {}}},
    )

    assert delivered is True
    sent = web.send_json.await_args.args[0]
    assert sent["seq"] == 1
    assert sent["session_id"] == "sess_1"
    android.send_json.assert_awaited_once()

    replay = await hub.replay_since("sess_1", 0)
    assert len(replay) == 1
    assert replay[0]["seq"] == 1
    assert await hub.replay_since("sess_1", 1) == []


@pytest.mark.asyncio
async def test_replay_gap_reports_evicted_frames(monkeypatch):
    """A client whose cursor predates the oldest retained frame must be
    told, not silently handed a truncated transcript."""
    import relay.hub as hub_mod

    monkeypatch.setattr(hub_mod, "_SESSION_REPLAY_LIMIT", 3)
    hub = Hub()

    for _ in range(5):
        await hub.record_session_frame("sess_1", {"type": "session-event"})

    # Buffer holds seq 3,4,5; 1 and 2 were evicted.
    # A client asking for everything from the start still only gets the
    # tail, so it is owed a marker too (contract C).
    assert await hub.replay_gap("sess_1", 0) == (1, 2)
    assert await hub.replay_gap("sess_1", 1) == (2, 2)
    assert await hub.replay_gap("sess_1", 2) is None      # has everything still buffered
    assert await hub.replay_gap("sess_1", 4) is None
    assert [f["seq"] for f in await hub.replay_since("sess_1", 1)] == [3, 4, 5]


@pytest.mark.asyncio
async def test_replay_gap_cleared_with_session():
    hub = Hub()
    hub.session_replay_evicted["sess_1"] = 10
    assert await hub.replay_gap("sess_1", 1) == (2, 10)
    await hub.forget_session("sess_1")
    assert await hub.replay_gap("sess_1", 1) is None


@pytest.mark.asyncio
async def test_broadcast_to_host_clients_is_bounded_and_host_scoped():
    hub = Hub()
    mine = _ws_mock()
    other = _ws_mock()
    await hub.attach_client("sess_1", "host_x", "web", mine)
    await hub.attach_client("sess_2", "host_y", "web", other)

    assert await hub.broadcast_to_host_clients("host_x", {"type": "host-telemetry"}) is True

    mine.send_json.assert_awaited_once()
    other.send_json.assert_not_awaited()


@pytest.mark.asyncio
async def test_admin_requests_are_bound_to_the_host_they_were_sent_to():
    hub = Hub()
    fut = hub.register_admin_request("host_x", "req_1")

    # A daemon authenticated for another host cannot answer it.
    assert hub.resolve_admin_request("host_y", "req_1", {"threads": []}) is False
    assert not fut.done()
    assert hub.resolve_admin_request("host_x", "req_1", {"threads": []}) is True
    assert (await fut) == {"threads": []}

    hub.discard_admin_request("host_x", "req_1")
    assert hub.pending_admin == {}


@pytest.mark.asyncio
async def test_turn_slot_is_single_writer_until_completed():
    hub = Hub()

    assert await hub.try_begin_turn("sess_1") is True
    assert await hub.try_begin_turn("sess_1") is False
    hub.mark_turn_completed("sess_1")
    assert await hub.try_begin_turn("sess_1") is True


@pytest.mark.asyncio
async def test_approval_resolution_is_first_writer_wins():
    hub = Hub()

    await hub.note_approval_request("sess_1", "appr_1", {"reason": "approve"})
    claim = await hub.resolve_approval("sess_1", "appr_1")
    assert claim is not None
    assert claim.data == {"reason": "approve"}
    assert await hub.resolve_approval("sess_1", "appr_1") is None


@pytest.mark.asyncio
async def test_user_input_resolution_is_first_writer_wins():
    hub = Hub()

    await hub.note_user_input_request("sess_1", "call_1")
    assert await hub.resolve_user_input("other_sess", "call_1") is None
    assert await hub.resolve_user_input("sess_1", "call_1") is not None
    assert await hub.resolve_user_input("sess_1", "call_1") is None


@pytest.mark.asyncio
async def test_restored_approval_claim_can_be_answered_again():
    """A response that never reached the daemon must leave the prompt
    answerable — codex is still holding the request."""
    hub = Hub()

    await hub.note_approval_request("sess_1", "appr_1", {"reason": "approve"})
    claim = await hub.resolve_approval("sess_1", "appr_1")
    assert claim is not None
    assert await hub.resolve_approval("sess_1", "appr_1") is None

    await hub.restore_approval(claim)

    snapshot = await hub.pending_prompt_snapshot("sess_1")
    assert [a["approval_id"] for a in snapshot["approvals"]] == ["appr_1"]
    again = await hub.resolve_approval("sess_1", "appr_1")
    assert again is not None
    assert again.data == {"reason": "approve"}


@pytest.mark.asyncio
async def test_restored_user_input_keeps_its_queue_position():
    """Contract (F): restoring a claim must not push it behind a prompt
    that arrived later."""
    hub = Hub()

    await hub.note_user_input_request("sess_1", "call_1", {"call_id": "call_1"})
    await hub.note_user_input_request("sess_1", "call_2", {"call_id": "call_2"})

    claim = await hub.resolve_user_input("sess_1", "call_1")
    assert claim is not None
    await hub.restore_user_input(claim)

    snapshot = await hub.pending_prompt_snapshot("sess_1")
    assert [u["call_id"] for u in snapshot["user_inputs"]] == ["call_1", "call_2"]


@pytest.mark.asyncio
async def test_prompt_ids_are_scoped_to_their_session():
    """Prompt ids come off a daemon frame, so a flat namespace would let
    one host's daemon overwrite — and permanently wedge — a prompt on a
    session it has nothing to do with."""
    hub = Hub()

    await hub.note_approval_request("sess_a", "appr_1", {"reason": "mine"})
    await hub.note_approval_request("sess_b", "appr_1", {"reason": "theirs"})

    snapshot = await hub.pending_prompt_snapshot("sess_a")
    assert [a["reason"] for a in snapshot["approvals"]] == ["mine"]

    claim = await hub.resolve_approval("sess_a", "appr_1")
    assert claim is not None and claim.data["reason"] == "mine"
    # Closing sess_b must not take sess_a's prompt with it.
    await hub.forget_session("sess_b")
    assert await hub.resolve_approval("sess_b", "appr_1") is None


@pytest.mark.asyncio
async def test_restore_is_dropped_once_the_prompt_was_invalidated():
    """turn-completed / session-closed mean codex has discarded the
    request; a restore racing them would leave a prompt nobody can
    ever answer."""
    hub = Hub()

    await hub.note_approval_request("sess_1", "appr_1", {"approval_id": "appr_1"})
    claim = await hub.resolve_approval("sess_1", "appr_1")
    assert claim is not None

    await hub.clear_session_prompts("sess_1")
    await hub.restore_approval(claim)

    assert (await hub.pending_prompt_snapshot("sess_1"))["approvals"] == []


@pytest.mark.asyncio
async def test_codex_resolved_prompt_cannot_race_back_into_queue():
    """A client claim can be in flight when Codex resolves the same prompt.

    Per-prompt tombstones reject only that claim's later restore; another
    concurrent prompt in the same turn remains answerable.
    """
    hub = Hub()

    await hub.note_approval_request("sess_1", "appr_1", {"approval_id": "appr_1"})
    await hub.note_approval_request("sess_1", "appr_2", {"approval_id": "appr_2"})
    approval = await hub.resolve_approval("sess_1", "appr_1")
    assert approval is not None
    await hub.invalidate_approval("sess_1", "appr_1")
    await hub.restore_approval(approval)

    await hub.note_user_input_request("sess_1", "call_1", {"call_id": "call_1"})
    user_input = await hub.resolve_user_input("sess_1", "call_1")
    assert user_input is not None
    await hub.invalidate_user_input("sess_1", "call_1")
    await hub.restore_user_input(user_input)

    snapshot = await hub.pending_prompt_snapshot("sess_1")
    assert [entry["approval_id"] for entry in snapshot["approvals"]] == ["appr_2"]
    assert snapshot["user_inputs"] == []

    # A genuinely new request may reuse an id and clears the old tombstone.
    await hub.note_user_input_request("sess_1", "call_1", {"call_id": "call_1"})
    snapshot = await hub.pending_prompt_snapshot("sess_1")
    assert [entry["call_id"] for entry in snapshot["user_inputs"]] == ["call_1"]


@pytest.mark.asyncio
async def test_restore_after_forget_session_does_not_leak():
    hub = Hub()

    await hub.note_user_input_request("sess_1", "call_1", {"call_id": "call_1"})
    claim = await hub.resolve_user_input("sess_1", "call_1")
    assert claim is not None

    await hub.forget_session("sess_1")
    await hub.restore_user_input(claim)

    assert hub.pending_user_inputs == {}


@pytest.mark.asyncio
async def test_snapshot_carries_relay_queue_order():
    """Clients re-sort by it so a restored claim lands back in its old
    slot instead of behind a prompt that arrived later (contract F)."""
    hub = Hub()

    await hub.note_approval_request("sess_1", "appr_1", {"approval_id": "appr_1"})
    await hub.note_approval_request("sess_1", "appr_2", {"approval_id": "appr_2"})

    snapshot = await hub.pending_prompt_snapshot("sess_1")
    orders = [a["order"] for a in snapshot["approvals"]]
    assert orders == sorted(orders)


@pytest.mark.asyncio
async def test_forget_session_clears_pending_prompt_claims():
    hub = Hub()

    await hub.note_approval_request("sess_1", "appr_1", {"reason": "approve"})
    await hub.note_user_input_request("sess_1", "call_1", {"questions": []})

    await hub.forget_session("sess_1")

    assert await hub.resolve_approval("sess_1", "appr_1") is None
    assert await hub.resolve_user_input("sess_1", "call_1") is None


@pytest.mark.asyncio
async def test_pending_prompt_snapshot_is_independent_of_seq():
    hub = Hub()

    await hub.note_approval_request("sess_1", "appr_1", {
        "approval_id": "appr_1",
        "kind": "command",
        "reason": "run command",
    })
    await hub.note_user_input_request("sess_1", "call_1", {
        "call_id": "call_1",
        "turn_id": "turn_1",
        "questions": [{"id": "q1", "question": "Pick one"}],
    })

    snapshot = await hub.pending_prompt_snapshot("sess_1")

    assert snapshot["type"] == "pending-prompts"
    assert snapshot["session_id"] == "sess_1"
    assert snapshot["approvals"][0]["replayed"] is True
    assert snapshot["approvals"][0]["approval_id"] == "appr_1"
    assert snapshot["user_inputs"][0]["questions"][0]["id"] == "q1"

    assert await hub.resolve_user_input("sess_1", "call_1") is not None
    snapshot = await hub.pending_prompt_snapshot("sess_1")
    assert len(snapshot["approvals"]) == 1
    assert snapshot["user_inputs"] == []


@pytest.mark.asyncio
async def test_clear_session_prompts_clears_pending_prompt_snapshot():
    hub = Hub()

    await hub.note_approval_request("sess_1", "appr_1", {"approval_id": "appr_1"})
    await hub.note_user_input_request("sess_1", "call_1", {"call_id": "call_1"})

    await hub.clear_session_prompts("sess_1")
    snapshot = await hub.pending_prompt_snapshot("sess_1")

    assert snapshot["approvals"] == []
    assert snapshot["user_inputs"] == []
