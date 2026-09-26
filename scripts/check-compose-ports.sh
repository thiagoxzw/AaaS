#!/usr/bin/env sh
# Fails if any docker compose service publishes a port on an address other than 127.0.0.1 (RNF-SEG-15).
set -eu

violations=$(docker compose config --format json \
  | jq -r '.services | to_entries[] | .key as $svc
           | (.value.ports // [])[]
           | select(.host_ip != "127.0.0.1")
           | "\($svc): published=\(.published) host_ip=\(.host_ip // "0.0.0.0 (all interfaces)")"')

if [ -n "$violations" ]; then
  echo "Ports published outside 127.0.0.1:" >&2
  echo "$violations" >&2
  exit 1
fi
echo "OK: every published port is bound to 127.0.0.1"
