"""SessionEvent envelope and the SessionAdapter abstract base."""
from __future__ import annotations

import time
from dataclasses import dataclass
from typing import AsyncIterator


@dataclass
class SessionEvent:
    """Envelope kind wrapped by the daemon before shipping to the relay."""
    kind: str
    data: dict

    def to_frame(self, session_id: str) -> dict:
        return {
            "type": "session-event",
            "session_id": session_id,
            "event": {"kind": self.kind, "data": self.data, "ts": time.time()},
        }


class SessionAdapter:
    async def start(self) -> None: ...
    async def stop(self) -> None: ...
    async def handle(self, frame: dict) -> None: ...
    def events(self) -> AsyncIterator[SessionEvent]: ...
