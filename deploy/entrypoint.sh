#!/bin/sh
# Fix ownership of the named volume so the unprivileged `relay` user
# can write the SQLite DB. Docker mounts the empty volume over the
# image's `/data` after the Dockerfile's chown has already run, so we
# re-apply it at container start. If we're not root (running as
# `relay` directly) this just no-ops and we run as-is.
set -e

if [ "$(id -u)" = "0" ]; then
    chown relay:relay /data 2>/dev/null || true
    # Drop privileges with setpriv (part of util-linux, already present
    # in python:3.12-slim — avoids pulling in gosu just for this).
    # HOME must be explicitly forwarded to the dropped process: asyncpg
    # probes $HOME/.postgresql/postgresql.key on SSL negotiation and
    # falls over with EACCES on /root otherwise.
    exec setpriv --reuid=10001 --regid=10001 --clear-groups -- \
        env HOME=/app "$@"
fi

exec "$@"
