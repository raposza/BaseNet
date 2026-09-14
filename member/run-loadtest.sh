#!/usr/bin/env bash
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
#
# The moderate-load run: a paced concurrent workload against one BaseNet
# Member, with every JVM in its namespace sampled from outside while it runs.
#
#   ./run-loadtest.sh <name> [--rate <cycles/s>] [--for <s>] [--clients <n>]
#                     [--every <s>]
#
# --every is the memory sampler's interval, 30 s by default. On a run of hours
# raise it: each sample is one kubectl exec per container running jcmd, and a
# probe that samples too hard becomes part of what it measures.
#
# TWO PROCESSES ON PURPOSE. loadtest.py drives the ledger and measures
# latency; jvm_watch.py samples memory and reports the peak. A driver that
# also probed its target would compete with the thing it is measuring.
#
# THE RATE IS CYCLES PER SECOND AND A CYCLE IS FOUR TRANSACTIONS. The
# synchronizer caps a member at 300 transactions per 60 s, so 1.25 cycles/s
# per member is the ceiling on a stock network; the default of 1 sits under it.
#
# The DAR must be built first - ./petshop/build.sh - and the identity provider
# must be running, because the token comes from it the production way.
set -euo pipefail
cd "$(dirname "$0")"

STR_NS="${1:-}"
if [ -z "$STR_NS" ] || [ "${STR_NS#-}" != "$STR_NS" ]; then
    echo "usage: ./run-loadtest.sh <name> [--rate <cycles/s>] [--for <s>] [--clients <n>]" >&2
    exit 2
fi
shift

N_FOR=600
N_EVERY=30
LST_LOAD=()
while [ $# -gt 0 ]; do
    case "$1" in
        --for)     N_FOR="${2:-}"; LST_LOAD+=(--for "${2:-}"); shift 2 ;;
        --every)   N_EVERY="${2:-}"; shift 2 ;;
        --rate|--clients|--dar|--port|--out)
                   LST_LOAD+=("$1" "${2:-}"); shift 2 ;;
        *)         echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done
case " ${LST_LOAD[*]-} " in
    *" --rate "*) ;;
    *) LST_LOAD+=(--rate 1) ;;
esac

STR_STAMP=$(date -u +%Y%m%dT%H%M%SZ)
FILE_WATCH="/tmp/bn-watch-$STR_NS-$STR_STAMP.csv"
FILE_LOAD="/tmp/bn-load-$STR_NS-$STR_STAMP.csv"

echo "=== load run on $STR_NS"
echo "    memory samples   $FILE_WATCH"
echo "    per-transaction  $FILE_LOAD"
echo "    while it runs:   kubectl -n $STR_NS get pods -w"
echo "                     kubectl -n $STR_NS top pods"
echo

# Started FIRST and given the run length plus a minute, so the peak covers the
# whole load and the sampler ends on its own if this shell is interrupted.
python3 ../shared/jvm_watch.py --ns "$STR_NS" --every "$N_EVERY" \
    --for $(( N_FOR + 60 )) --out "$FILE_WATCH" --quiet &
PID_WATCH=$!
trap 'kill "$PID_WATCH" 2>/dev/null || true' EXIT

python3 loadtest.py --ns "$STR_NS" --out "$FILE_LOAD" "${LST_LOAD[@]}"

echo
echo "=== waiting for the memory sampler to report its peak"
wait "$PID_WATCH" || true
trap - EXIT

echo
echo "=== done"
echo "    latency and errors  $FILE_LOAD"
echo "    memory peaks        $FILE_WATCH"
echo "    a JVM in detail     python3 ../shared/jvm_read.py --ns $STR_NS --gc"
