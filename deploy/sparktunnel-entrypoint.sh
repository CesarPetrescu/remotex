#!/bin/sh
set -eu

server=${SPARK_TUNNEL_SERVER:-https://webhost.photonspark.com}
target=${SPARK_TUNNEL_TARGET:-http://relay:8080}
token=${SPARK_TUNNEL_TOKEN:-}

if [ -z "$token" ]; then
    echo "SPARK_TUNNEL_TOKEN is required; copy deploy/.env.example to deploy/.env and add the one-time connector token" >&2
    exit 64
fi

case "$server" in
    https://*) ;;
    *)
        echo "SPARK_TUNNEL_SERVER must use https:// so the bearer token is encrypted in transit" >&2
        exit 64
        ;;
esac

case "$target" in
    http://*|https://*) ;;
    *)
        echo "SPARK_TUNNEL_TARGET must be an http:// or https:// URL" >&2
        exit 64
        ;;
esac

echo "starting SparkTunnel connector: server=$server target=$target"
exec /usr/local/bin/spark-tunnel run \
    --server "$server" \
    --token "$token" \
    --target "$target"
