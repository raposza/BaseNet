#!/usr/bin/env bash
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
#
# Asserts that the BaseNet Validator is up, and prints how long each step took. Exit 0
# means every step held; anything else names the one that did not. Straight
# after an install it times a cold start:
#
#   ./install.sh && ./smoke.sh
#
#   1  every pod in the namespace is Ready. sv-app turns Ready when it answers
#      its readiness endpoint, which it does once the DSO is founded
#   2  scan answers GET /api/scan/v0/dso with 200 and a JSON document
#
# That is the whole default. A closed mining round is not needed to use the
# network: a faucet tap and a transfer between two validators have gone
# through in round 1, minutes after every pod was Ready.
#
#   ./smoke.sh --round
#
#   3  also waits until scan reports at least one CLOSED mining round - the
#      round timer has advanced. On a new network the first round has closed
#      between half an hour and an hour after every pod was Ready, so this is
#      the slow step and it runs only when asked for
#
# Scan is reached through a kubectl port-forward to svc/scan-app on a random
# local port; the service and its port are the ones values/sv.yaml points the
# SV at. Needs kubectl and python3 on the host. Changes nothing.
set -euo pipefail
cd "$(dirname "$0")"
. ../basenet.conf
# basenet.conf.local, when present, overrides it and is never committed.
[ ! -f ../basenet.conf.local ] || . ../basenet.conf.local

FLAG_ROUND=0
case "${1:-}" in
    "")      ;;
    --round) FLAG_ROUND=1 ;;
    *)       echo "usage: ./smoke.sh [--round]" >&2; exit 2 ;;
esac

N_WAIT_PODS_S=1200
N_WAIT_ROUND_S=3600
N_POLL_S=15
N_START=$(date +%s)
DIR_TMP=$(mktemp -d)
PID_FWD=


function cleanup() {
    [ -z "$PID_FWD" ] || kill "$PID_FWD" 2>/dev/null || true
    rm -rf "$DIR_TMP"
}
trap cleanup EXIT


function elapsed() {
    local nSec
    nSec=$(( $(date +%s) - N_START ))
    printf '%dm%02ds' $(( nSec / 60 )) $(( nSec % 60 ))
}


function die() {
    echo "!!! SMOKE FAILED at $(elapsed): $1" >&2
    exit 1
}


# GET one scan path. Prints the body on HTTP 200 when it parses as JSON;
# anything else exits non-zero with the reason on stderr.
function scan_get() {
    python3 - "$STR_SCAN$1" <<'PY'
import json, sys, urllib.request
try:
    with urllib.request.urlopen(sys.argv[1], timeout=10) as resp:
        strBody = resp.read().decode("utf-8")
    json.loads(strBody)
    print(strBody)
except Exception as exc:
    print(sys.argv[1] + ": " + str(exc), file=sys.stderr)
    sys.exit(1)
PY
}


echo "== 1  every pod Ready, up to ${N_WAIT_PODS_S}s"
kubectl get ns "$STR_NAMESPACE" >/dev/null 2>&1 || die "no namespace $STR_NAMESPACE - run install.sh first"
kubectl -n "$STR_NAMESPACE" wait --for=condition=Ready pod --all --timeout="${N_WAIT_PODS_S}s" \
    || die "not every pod became Ready: kubectl -n $STR_NAMESPACE get pods"
cntPod=$(kubectl -n "$STR_NAMESPACE" get pods --no-headers | wc -l)
echo "    ok  $cntPod pods Ready, $(elapsed)"

echo "== 2  scan answers"
kubectl -n "$STR_NAMESPACE" port-forward svc/scan-app :5012 > "$DIR_TMP/fwd.log" 2>&1 &
PID_FWD=$!
N_PORT=
for cntLoop in $(seq 1 30); do
    N_PORT=$(sed -n 's/^Forwarding from 127\.0\.0\.1:\([0-9]*\) -> 5012$/\1/p' "$DIR_TMP/fwd.log" | head -1)
    [ -z "$N_PORT" ] || break
    kill -0 "$PID_FWD" 2>/dev/null || die "port-forward to svc/scan-app exited: $(cat "$DIR_TMP/fwd.log")"
    sleep 1
done
[ -n "$N_PORT" ] || die "port-forward to svc/scan-app named no local port in 30s"
STR_SCAN="http://127.0.0.1:$N_PORT"
scan_get /api/scan/v0/dso > "$DIR_TMP/dso.json" || die "GET /api/scan/v0/dso"
echo "    ok  /api/scan/v0/dso, $(wc -c < "$DIR_TMP/dso.json") bytes, $(elapsed)"

if [ "$FLAG_ROUND" = "0" ]; then
    echo
    echo "=== SMOKE GREEN in $(elapsed)"
    echo "    no closed round waited for - ./smoke.sh --round adds that step"
    exit 0
fi

echo "== 3  a closed mining round, up to ${N_WAIT_ROUND_S}s"
N_DEADLINE=$(( $(date +%s) + N_WAIT_ROUND_S ))
cntRound=0
while true; do
    if scan_get /api/scan/v0/closed-rounds > "$DIR_TMP/rounds.json" 2>"$DIR_TMP/rounds.err"; then
        cntRound=$(python3 -c 'import json, sys; print(len(json.load(open(sys.argv[1])).get("rounds", [])))' \
                       "$DIR_TMP/rounds.json")
        [ "$cntRound" -eq 0 ] || break
    fi
    kill -0 "$PID_FWD" 2>/dev/null || die "the port-forward to scan died: $(cat "$DIR_TMP/fwd.log")"
    [ "$(date +%s)" -lt "$N_DEADLINE" ] || die "no closed round; last answer: $(cat "$DIR_TMP/rounds.json" "$DIR_TMP/rounds.err")"
    sleep "$N_POLL_S"
done
echo "    ok  $cntRound closed round(s), $(elapsed)"

echo
echo "=== SMOKE GREEN in $(elapsed)"
