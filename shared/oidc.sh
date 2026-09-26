#!/usr/bin/env bash
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
#
# Starts Raposza OIDC, the identity provider this BaseNet validates against.
#
#   ./oidc.sh            start it; STAYS UP
#   ./oidc.sh <arg>...   any Spring argument is passed through
#
# THE JAR COMES FROM THE LOCAL MAVEN REPOSITORY: the newest
# raposza-oidc-server-*-app.jar under com/raposza/oidc/raposza-oidc-server,
# the same rule Raposza's own launcher and Sandbox window apply. Nothing is
# built here. Install it from the Raposza OIDC tree with its ./build.sh, or
# take the release from Maven Central into the local repository.
#
# ITS OWN STORE. Keys, users and clients live in STR_OIDC_DIR from
# basenet.conf, not in the provider's default directory, so a BaseNet never
# shares a key set or a client registry with anything else on the machine.
# The users are seeded from STR_OIDC_USERS the first time that directory
# is used; after that the store wins.
#
# NO CLIENT IS REGISTERED, so the provider checks no client_id and no secret -
# the same behaviour the provider bundled here before it had. See
# docs/auth.md.
#
# It listens on every interface, because the cluster has to reach the JWKS and
# a loopback bind fails in a way that looks like a key problem.
#
# THE ISSUER IS PINNED to STR_OIDC_BASE_URL from basenet.conf. Discovery and
# every minted token must carry the SAME issuer string - RFC 8414 section 3.3
# compares them literally - and Raposza OIDC refuses to start without one.
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

if [ "${1:-}" = "--build" ]; then
    echo "--build is gone: the provider is Raposza OIDC, installed into the local" >&2
    echo "Maven repository from its own tree. Nothing is built here." >&2
    exit 2
fi

DIR_REPO="${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}"
DIR_ART="$DIR_REPO/com/raposza/oidc/raposza-oidc-server"
FILE_JAR="$(find "$DIR_ART" -mindepth 2 -maxdepth 2 \
    -name 'raposza-oidc-server-*-app.jar' -printf '%T@ %p\n' 2>/dev/null |
    sort -rn | head -1 | cut -d' ' -f2-)"
if [ -z "$FILE_JAR" ]; then
    echo "no raposza-oidc-server-*-app.jar under $DIR_ART" >&2
    echo "install Raposza OIDC into the local Maven repository first" >&2
    exit 1
fi

DIR_STORE="${STR_OIDC_DIR/#\~/$HOME}"
mkdir -p "$DIR_STORE"
N_PORT="${STR_OIDC_BASE_URL##*:}"
echo "starting Raposza OIDC from $FILE_JAR"
echo "  issuer   $STR_OIDC_BASE_URL"
echo "  jwks     $STR_OIDC_BASE_URL/oauth2/jwks"
echo "  store    $DIR_STORE"
# NAMES ONLY on the console; the passwords stay in the conf.
echo "  users    $(printf '%s' "$STR_OIDC_USERS" | sed 's/:[^,]*//g')"
exec java -jar "$FILE_JAR" \
    --server.port="$N_PORT" \
    --raposza.jwtmint.issuer="$STR_OIDC_BASE_URL" \
    --raposza.jwtmint.dir-keys="$DIR_STORE" \
    --raposza.jwtmint.users="$STR_OIDC_USERS" "$@"
