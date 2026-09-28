#!/usr/bin/env sh
# ADR-011: only docker-socket-proxy may mount the Docker socket, the proxy may not publish ports, it may not
# allow POST globally, it may turn on nothing beyond reading containers and logs and restarting them (slice 8),
# and no service may keep stdin open (the proxy cannot block websocket attach, see docs/06-threat-model.md).
set -eu

config=$(docker compose config --format json)
failed=0

mounts=$(echo "$config" | jq -r '.services | to_entries[] | .key as $svc
          | (.value.volumes // [])[] | select((.source // "") | test("docker\\.sock$")) | $svc' | sort -u)
if [ "$mounts" != "docker-socket-proxy" ]; then
  echo "The Docker socket must be mounted by docker-socket-proxy only; mounted by: ${mounts:-nobody}" >&2
  failed=1
fi

writable=$(echo "$config" | jq -r '.services["docker-socket-proxy"].volumes[]
          | select((.source // "") | test("docker\\.sock$")) | select(.read_only != true) | .target')
if [ -n "$writable" ]; then
  echo "docker-socket-proxy must mount the socket read-only" >&2
  failed=1
fi

ports=$(echo "$config" | jq -r '.services["docker-socket-proxy"].ports // [] | length')
if [ "$ports" != "0" ]; then
  echo "docker-socket-proxy must not publish ports" >&2
  failed=1
fi

post=$(echo "$config" | jq -r '.services["docker-socket-proxy"].environment.POST // "0"')
if [ "$post" != "0" ]; then
  echo "docker-socket-proxy must keep POST=0 (it would allow creating and starting containers)" >&2
  failed=1
fi

# Allowlist: every proxy setting turned on must be one of these. Anything else (EXEC, IMAGES, ALLOW_START,
# VOLUMES, BUILD...) fails the check, whatever its name.
allowed="CONTAINERS ALLOW_LOGS ALLOW_RESTARTS PING VERSION DISABLE_IPV6"
enabled=$(echo "$config" | jq -r '.services["docker-socket-proxy"].environment // {} | to_entries[]
          | select((.value | tostring) == "1") | .key')
for setting in $enabled; do
  case " $allowed " in
    *" $setting "*) ;;
    *) echo "docker-socket-proxy turns on $setting, which is not in the allowlist ($allowed)" >&2; failed=1 ;;
  esac
done

internal=$(echo "$config" | jq -r '.networks["docker-proxy"].internal // false')
if [ "$internal" != "true" ]; then
  echo "The docker-proxy network must be internal" >&2
  failed=1
fi

stdin=$(echo "$config" | jq -r '.services | to_entries[] | select(.value.stdin_open == true) | .key')
if [ -n "$stdin" ]; then
  echo "Services must not keep stdin open: $stdin" >&2
  failed=1
fi

[ "$failed" -eq 0 ] && echo "OK: only docker-socket-proxy mounts the socket (read-only), without ports, POST=0, only allowlisted settings, internal network"
exit "$failed"
