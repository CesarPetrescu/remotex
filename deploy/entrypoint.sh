#!/bin/sh
# Drop privileges to the unprivileged `relay` user. Inventory state
# now lives in Postgres (shared with the search store), so no /data
# volume ownership dance is required.
set -e

if [ "$(id -u)" = "0" ]; then
    # HOME must be explicitly forwarded to the dropped process: asyncpg
    # probes $HOME/.postgresql/postgresql.key on SSL negotiation and
    # falls over with EACCES on /root otherwise.
    exec setpriv --reuid=10001 --regid=10001 --clear-groups -- \
        env HOME=/app "$@"
fi

exec "$@"
