#!/usr/bin/env bash
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
# Generated 2026-09-07T00:00:00Z
#
# Starts the JWT mint, the identity provider this BaseNet validates against.
#
#   ./jwtmint.sh            start from the jar that is there, building if absent
#   ./jwtmint.sh --build    rebuild first
#
# It listens on every interface, because the cluster has to reach the JWKS and
# a loopback bind fails in a way that looks like a key problem.
#
# THE ISSUER IS PINNED to STR_OIDC_BASE_URL from basenet.conf. Discovery and
# every minted token must carry the SAME issuer string - RFC 8414 section 3.3
# compares them literally - and letting the service pick an address on a
# multi-homed host is how they end up disagreeing.
#
# It publishes its own private keys on request. It belongs on a development
# machine and nowhere else.
set -euo pipefail
cd "$(dirname "$0")"
. ../basenet.conf
# basenet.conf.local, when present, overrides it and is never committed.
[ ! -f ../basenet.conf.local ] || . ../basenet.conf.local

case "$STR_OIDC_BASE_URL" in
    *example.internal*)
        echo "set STR_OIDC_BASE_URL in basenet.conf.local - it is still the placeholder"
        exit 1 ;;
esac

FLAG_BUILD=0
if [ "${1:-}" = "--build" ]; then
    FLAG_BUILD=1
    shift
fi

function file_jar() {
    ls -1t jwtmint/target/jwtmint-*-app.jar 2>/dev/null | head -1
}

FILE_JAR="$(file_jar || true)"
if [ -z "$FILE_JAR" ] || [ "$FLAG_BUILD" = "1" ]; then
    echo "building the mint..."
    ( cd jwtmint && mvn -B package )
    FILE_JAR="$(file_jar || true)"
fi

if [ -z "$FILE_JAR" ]; then
    echo "no jar under jwtmint/target/ - run: cd jwtmint && mvn package" >&2
    exit 1
fi

N_PORT="${STR_OIDC_BASE_URL##*:}"
echo "starting the mint from $FILE_JAR"
echo "  issuer   $STR_OIDC_BASE_URL"
echo "  jwks     $STR_OIDC_BASE_URL/oauth2/jwks"
# NAMES ONLY on the console; the passwords stay in the conf.
echo "  users    $(printf '%s' "$STR_JWTMINT_USERS" | sed 's/:[^,]*//g')"
exec java -jar "$FILE_JAR" \
    --server.port="$N_PORT" \
    --raposza.jwtmint.issuer="$STR_OIDC_BASE_URL" \
    --raposza.jwtmint.users="$STR_JWTMINT_USERS" "$@"
