#!/usr/bin/env bash
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
# Generated 2026-09-07T00:00:00Z
#
# Destroys the BaseNet Validator and leaves Kubernetes and the image cache alone, so
# the next install pulls nothing.
#
# Deleting the namespace takes the releases, the pods, the PVCs and Helm's own
# release secrets with it. `helm uninstall` alone would leave the StatefulSet
# PVCs and sv-app's migration PVC, which carries helm.sh/resource-policy: keep.
#
# THIS DESTROYS THE LEDGER. There is no state to keep and nothing to restore.
set -euo pipefail
cd "$(dirname "$0")"
. ../basenet.conf
# basenet.conf.local, when present, overrides it and is never committed.
[ ! -f ../basenet.conf.local ] || . ../basenet.conf.local

kubectl delete ns "$STR_NAMESPACE" --wait --timeout=300s 2>/dev/null || true
echo "=== namespace gone; images are still cached"
