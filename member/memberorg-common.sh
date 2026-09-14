#!/usr/bin/env bash
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
# Generated 2026-09-11T09:46:00Z
#
# Sourced by memberorg-secrets.sh, memberorg-install.sh and
# memberorg-teardown.sh, after basenet.conf and its .local. Not run on its
# own.
#
# It holds what all three must agree on: where a MemberOrg is pointed, what a
# MemberOrg name may be, and how a per-MemberOrg setting is read.

# Blank addresses in basenet.conf mean this BaseNet Validator, reached from another
# namespace on the same cluster by its services' full names.
STR_EXT_SCAN_URL="${STR_EXT_SCAN_URL:-http://scan-app.$STR_NAMESPACE.svc.cluster.local:5012}"
STR_EXT_SPONSOR_URL="${STR_EXT_SPONSOR_URL:-http://sv-app.$STR_NAMESPACE.svc.cluster.local:5014}"
STR_EXT_SEQUENCER_URL="${STR_EXT_SEQUENCER_URL:-http://global-domain-0-sequencer.$STR_NAMESPACE.svc.cluster.local:5008}"

# The label memberorg-secrets.sh puts on a MemberOrg's namespace.
# memberorg-teardown.sh deletes a namespace only when it carries it.
STR_MEMBERORG_LABEL="basenet-member"


# A MemberOrg name is its namespace, its validator's party hint and its node
# identifier at once, so it must be all three: a DNS label, which is the
# narrowest of them. The characters are listed rather than ranged so that no
# locale can widen them.
function check_memberorg() {
    local strName="$1"
    if [ -z "$strName" ]; then
        echo "a MemberOrg name is required, e.g. memberorg-a" >&2
        return 1
    fi
    if [ -n "$(printf '%s' "$strName" | tr -d 'abcdefghijklmnopqrstuvwxyz0123456789-')" ]; then
        echo "MemberOrg name '$strName': lower-case letters, digits and '-' only" >&2
        return 1
    fi
    case "$strName" in
        -*|*-)
            echo "MemberOrg name '$strName': must not start or end with '-'" >&2
            return 1 ;;
    esac
    if [ "${#strName}" -gt 40 ]; then
        echo "MemberOrg name '$strName': 40 characters at most" >&2
        return 1
    fi
    if [ "$strName" = "$STR_NAMESPACE" ]; then
        echo "MemberOrg name '$strName' is this network's own namespace" >&2
        return 1
    fi
}


# The value of a per-MemberOrg setting for $STR_MEMBERORG. The setting named
# with the MemberOrg's name appended - capitals, dashes as underscores - wins
# when it is set; otherwise the general one. %s in the value becomes the
# MemberOrg's name, so one default serves every MemberOrg and one override
# serves the MemberOrg that differs:
#
#   STR_MO_WALLET_USER=%s-user                  memberorg-a-user, memberorg-b-user
#   STR_MO_WALLET_USER_MEMBERORG_A=4f1c...      a Keycloak user's sub, for one
function memberorg_setting() {
    local strKey="$1" strSuffix strOver strValue
    strSuffix=$(printf '%s' "$STR_MEMBERORG" | tr 'abcdefghijklmnopqrstuvwxyz-' 'ABCDEFGHIJKLMNOPQRSTUVWXYZ_')
    strOver="${strKey}_${strSuffix}"
    strValue="${!strOver:-${!strKey:-}}"
    printf '%s' "${strValue//"%s"/$STR_MEMBERORG}"
}
