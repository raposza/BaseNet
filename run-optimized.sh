#!/usr/bin/env bash
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
# Generated 2026-09-13T18:00:00Z
#
# The same two stacks, installed with an absolute heap per JVM and a pod limit
# calculated from it, instead of the sizing the charts and values/ ship.
#
#   ./run-optimized.sh validator
#   ./run-optimized.sh member memberorg-a
#
# WHAT IT CHANGES. nmt_overlay.py writes one values file per release into a
# directory outside the tree, setting -Xms and -Xmx to the heap below, the pod
# request and limit to heap + reserve rounded up to 16 Mi, and Native Memory
# Tracking on. install.sh and memberorg-install.sh take that directory with
# --overlay and pass each file after the release's own.
#
# THE NUMBERS BELOW ARE THIS REPOSITORY'S, NOT A UNIVERSAL SIZING. They were
# measured on one workload on one cluster. A component that is OOMKilled comes
# back as a bare exit 137 that names nothing, so if that happens, raise its
# reserve here rather than guessing at the heap.
#
# THE RESERVE IS EVERYTHING RESIDENT THAT IS NOT HEAP - metaspace, symbols,
# code cache, GC structures, thread stacks, direct buffers. It must be measured
# UNDER LOAD: a reserve read off an idle network under-sizes the pod, and the
# participant is the one that shows it.
#
# Reading what a run actually used, so these can be revisited:
#
#   python3 shared/jvm_read.py --ns <namespace> --gc
#   python3 shared/jvm_watch.py --ns <namespace>        peaks, during a run
set -euo pipefail
cd "$(dirname "$0")"
. ./basenet.conf
# basenet.conf.local, when present, overrides it and is never committed.
[ ! -f ./basenet.conf.local ] || . ./basenet.conf.local

# component=heap[:reserve], in Mi.
LST_HEAP_VALIDATOR=(
    participant=1024:736
    scan=1536:480
    sv=512:512
    validator=512:736
    sequencer=256:416
    mediator=256:384
)
# The four PostgreSQL releases run no JVM: the number is the pod limit itself.
LST_MEM_VALIDATOR=(
    participant-pg=1408
    sequencer-pg=1408
    mediator-pg=1408
    apps-pg=1408
)
# A Member's participant and validator are NOT the Validator's releases of the
# same name, which is why the two tables and the two overlay directories are
# kept apart.
LST_HEAP_MEMBER=(
    participant=1024:832
    validator=768:768
)
LST_MEM_MEMBER=(
    postgres=640
)

STR_SIDE="${1:-}"
case "$STR_SIDE" in
    validator)
        DIR_OVERLAY=$(mktemp -d /tmp/bn-overlay-validator-XXXXXX)
        LST_ARG=()
        for STR_PAIR in "${LST_HEAP_VALIDATOR[@]}"; do LST_ARG+=(--heap "$STR_PAIR"); done
        for STR_PAIR in "${LST_MEM_VALIDATOR[@]}"; do LST_ARG+=(--mem "$STR_PAIR"); done
        echo "== writing the overlay to $DIR_OVERLAY"
        python3 shared/nmt_overlay.py --out "$DIR_OVERLAY" "${LST_ARG[@]}"
        echo
        ( cd validator && ./secrets.sh )
        ( cd validator && ./install.sh --overlay "$DIR_OVERLAY" )
        ( cd validator && ./smoke.sh )
        STR_NS="$STR_NAMESPACE"
        ;;
    member)
        STR_NAME="${2:-}"
        [ -n "$STR_NAME" ] || { echo "usage: ./run-optimized.sh member <name>" >&2; exit 2; }
        DIR_OVERLAY=$(mktemp -d /tmp/bn-overlay-member-XXXXXX)
        LST_ARG=()
        for STR_PAIR in "${LST_HEAP_MEMBER[@]}"; do LST_ARG+=(--heap "$STR_PAIR"); done
        for STR_PAIR in "${LST_MEM_MEMBER[@]}"; do LST_ARG+=(--mem "$STR_PAIR"); done
        echo "== writing the overlay to $DIR_OVERLAY"
        python3 shared/nmt_overlay.py --member --out "$DIR_OVERLAY" "${LST_ARG[@]}"
        echo
        ( cd member && ./memberorg-secrets.sh "$STR_NAME" )
        ( cd member && ./memberorg-install.sh --overlay "$DIR_OVERLAY" "$STR_NAME" )
        STR_NS="$STR_NAME"
        ;;
    *)
        echo "usage: ./run-optimized.sh validator | member <name>" >&2
        exit 2 ;;
esac

cat <<EOF

=== $STR_SIDE installed with the overlay in $DIR_OVERLAY

THE OVERLAY IS NOT IN THE TREE. Any later install of the same stack without
--overlay silently restores the shipped sizing, and nothing warns you:

    reinstall it sized   ./run-optimized.sh $*
    what it is using     python3 shared/jvm_read.py --ns $STR_NS --gc
    peaks during a run   python3 shared/jvm_watch.py --ns $STR_NS
    pods                 kubectl -n $STR_NS get pods
    a restart count      kubectl -n $STR_NS get pods -o wide

A pod that keeps restarting with no message in its log was OOMKilled: raise
that component's reserve at the top of this script.
EOF
