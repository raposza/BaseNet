#!/usr/bin/env bash
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
# Generated 2026-09-13T18:00:00Z
#
# Brings up one BaseNet Member at the SHIPPED sizing - the memory in
# member/values/, no overlay - pointed at the BaseNet Validator named in
# basenet.conf. run-optimized.sh is the other one.
#
#   ./run-member.sh memberorg-a
#
# The name is the namespace, the validator's party hint and its node
# identifier. A second Member is the same command with another name.
set -euo pipefail
cd "$(dirname "$0")"
. ./basenet.conf
# basenet.conf.local, when present, overrides it and is never committed.
[ ! -f ./basenet.conf.local ] || . ./basenet.conf.local

STR_NAME="${1:-}"
[ -n "$STR_NAME" ] || { echo "usage: ./run-member.sh <name>, e.g. memberorg-a" >&2; exit 2; }

( cd member && ./memberorg-secrets.sh "$STR_NAME" )
( cd member && ./memberorg-install.sh "$STR_NAME" )

cat <<EOF

=== BaseNet Member $STR_NAME is installed

It is ONBOARDED when validator-app turns Ready, which is a few minutes later:

    kubectl -n $STR_NAME get pods -w

    web UIs           python3 shared/ui.py $STR_NAME     stays up, prints its addresses
                        the wallet, and the name service
    one pod's log     kubectl -n $STR_NAME logs -f <pod>
    what is installed helm -n $STR_NAME list
    memory now        kubectl -n $STR_NAME top pods
    memory in detail  python3 shared/jvm_read.py --ns $STR_NAME
    a load test       ( cd member && ./petshop/build.sh && ./run-loadtest.sh $STR_NAME )
    destroy it        ( cd member && ./memberorg-teardown.sh $STR_NAME )

Sign in to the wallet as $STR_NAME's own user at the identity provider's login
page - the bundled provider's users are STR_JWTMINT_USERS in basenet.conf.
EOF
