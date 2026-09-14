#!/usr/bin/env bash
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
#
# Brings up a BaseNet Validator at the SHIPPED sizing - the memory in
# validator/values/, no overlay. This is the shape that is exercised; use it
# unless you have a reason not to. run-optimized.sh is the other one.
#
#   ./run-validator.sh            secrets, the nine releases, then the smoke test
#   ./run-validator.sh --round    also wait for the first closed mining round
#
# The identity provider must be running - ./shared/jwtmint.sh in another
# terminal - because the SV and validator apps validate against it at start.
set -euo pipefail
cd "$(dirname "$0")"
. ./basenet.conf
# basenet.conf.local, when present, overrides it and is never committed.
[ ! -f ./basenet.conf.local ] || . ./basenet.conf.local

LST_SMOKE=()
case "${1:-}" in
    "")      ;;
    --round) LST_SMOKE=(--round) ;;
    *)       echo "usage: ./run-validator.sh [--round]" >&2; exit 2 ;;
esac

echo "== the identity provider at $STR_OIDC_BASE_URL"
python3 - "$STR_OIDC_BASE_URL/.well-known/openid-configuration" <<'PY' || true
import sys, urllib.request
strUrl = sys.argv[1]
try:
    urllib.request.urlopen(strUrl, timeout=5).read()
    print("    ok  " + strUrl)
except Exception as exc:
    print("    NOT REACHABLE FROM HERE: %s (%s)" % (strUrl, exc))
    print("    the cluster may still reach it; if it does not, start it with")
    print("        ./shared/jwtmint.sh")
PY
echo

( cd validator && ./secrets.sh )
( cd validator && ./install.sh )
( cd validator && ./smoke.sh "${LST_SMOKE[@]+"${LST_SMOKE[@]}"}" )

cat <<EOF

=== the BaseNet Validator is up, in namespace $STR_NAMESPACE

    web UIs           python3 shared/ui.py $STR_NAMESPACE     stays up, Ctrl-C ends it
                        http://sv.localhost:$STR_UI_PORT/       the super validator
                        http://scan.localhost:$STR_UI_PORT/     the network's scan
                        http://wallet.localhost:$STR_UI_PORT/   its own wallet
    pods              kubectl -n $STR_NAMESPACE get pods
    one pod's log     kubectl -n $STR_NAMESPACE logs -f <pod>
    what is installed helm -n $STR_NAMESPACE list
    memory now        kubectl -n $STR_NAMESPACE top pods
    memory in detail  python3 shared/jvm_read.py --ns $STR_NAMESPACE
    the images used   ./shared/images.sh
    add a member      ./run-member.sh memberorg-a
    destroy it        ( cd validator && ./teardown.sh )
EOF
