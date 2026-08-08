"""Store helpers that don't need Postgres: seeding gate + token hashing."""
from __future__ import annotations

import pytest

from relay.store import (
    DEMO_BRIDGE_TOKEN,
    DEMO_USER_TOKEN,
    hash_token,
    key_id,
    seed_demo_enabled,
)


@pytest.mark.parametrize("value", ["1", "true", "TRUE", "yes", " Yes "])
def test_seed_demo_opt_in(monkeypatch, value):
    monkeypatch.setenv("RELAY_SEED_DEMO", value)
    assert seed_demo_enabled() is True


@pytest.mark.parametrize("value", ["", "0", "false", "no", "maybe"])
def test_seed_demo_off_for_anything_else(monkeypatch, value):
    monkeypatch.setenv("RELAY_SEED_DEMO", value)
    assert seed_demo_enabled() is False


def test_seed_demo_defaults_off(monkeypatch):
    """The demo tokens are published in this repo; a relay that seeds them
    unasked is a relay anyone owns."""
    monkeypatch.delenv("RELAY_SEED_DEMO", raising=False)
    assert seed_demo_enabled() is False


def test_hash_token_is_stable_64_hex_and_not_the_token():
    hashed = hash_token(DEMO_USER_TOKEN)
    assert len(hashed) == 64
    assert set(hashed) <= set("0123456789abcdef")
    assert hashed == hash_token(DEMO_USER_TOKEN)
    assert DEMO_USER_TOKEN not in hashed
    assert hash_token(DEMO_BRIDGE_TOKEN) != hashed


def test_key_id_is_a_prefix_of_the_stored_hash():
    token = "brg_live_example"
    assert key_id(token) == hash_token(token)[:12]
    assert len(key_id(token)) == 12
