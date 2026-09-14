#!/usr/bin/env bash
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
# Generated 2026-09-11T09:46:00Z
#
# Destroys one MemberOrg: its namespace, and with it the releases, the pods, the
# PVCs and Helm's own release secrets. The network it was pointed at is not
# touched, and it keeps the MemberOrg's party and onboarding in its own state.
#
#   ./memberorg-teardown.sh memberorg-a
#
# It deletes ONLY a namespace memberorg-secrets.sh created, which it
# recognises by the label memberorg-secrets.sh puts on it. A mistyped name
# that happens to match some other namespace is refused, not deleted.
set -euo pipefail
cd "$(dirname "$0")"
. ../basenet.conf
# basenet.conf.local, when present, overrides it and is never committed.
[ ! -f ../basenet.conf.local ] || . ../basenet.conf.local
. ./memberorg-common.sh

STR_MEMBERORG="${1:-}"
check_memberorg "$STR_MEMBERORG" || exit 2

if ! kubectl get ns "$STR_MEMBERORG" >/dev/null 2>&1; then
    echo "=== no namespace $STR_MEMBERORG - nothing to delete"
    exit 0
fi
# Read the list first: grep -q stops at its first match, and under pipefail the
# writer it leaves behind would fail the test that just succeeded.
STR_LABELLED=$(kubectl get ns -l "$STR_MEMBERORG_LABEL=true" -o name)
if ! grep -qx "namespace/$STR_MEMBERORG" <<< "$STR_LABELLED"; then
    echo "namespace $STR_MEMBERORG does not carry $STR_MEMBERORG_LABEL=true - not a MemberOrg, refusing" >&2
    exit 1
fi

kubectl delete ns "$STR_MEMBERORG" --wait --timeout=300s
echo "=== MemberOrg $STR_MEMBERORG gone; images are still cached"
