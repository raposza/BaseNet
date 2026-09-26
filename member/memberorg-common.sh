#!/usr/bin/env bash
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
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

# ONBOARDING.JSON, beside run-member.sh, points a MemberOrg at a network
# outside this cluster and onboards it there with a secret. Raposza SV hands
# it out, one per secret:
#
#   {"sponsorUrl": "https://...", "scanUrl": "https://...",
#    "sequencerUrl": "https://...", "secret": "...", "spliceVersion": "0.8.3"}
#
# memberorg-install.sh reads it; the addresses above are then not used.
FILE_ONBOARDING="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/onboarding.json"

# The Kubernetes secret the onboarding secret is kept in, in the MemberOrg's
# namespace - the validator chart takes it only from a secret.
STR_ONBOARDING_SECRET_NAME="splice-app-validator-onboarding"


# One value from onboarding.json, checked, with no newline. spliceVersion may
# be absent and prints nothing; every other key is required.
function onboarding_value() {
    python3 - "$FILE_ONBOARDING" "$1" <<'PY'
import json, re, sys
strFile, strKey = sys.argv[1], sys.argv[2]
try:
    with open(strFile, encoding="utf-8") as handle:
        mapIn = json.load(handle)
except (OSError, ValueError) as ex:
    sys.exit("%s: %s" % (strFile, ex))
mapPattern = {
    "sponsorUrl": r"https?://[^\s\"'\\]+",
    "scanUrl": r"https?://[^\s\"'\\]+",
    "sequencerUrl": r"https?://[^\s\"'\\]+",
    "secret": r"[A-Za-z0-9+/=]+",
    "spliceVersion": r"[0-9]+\.[0-9]+\.[0-9]+",
}
strValue = mapIn.get(strKey) if isinstance(mapIn, dict) else None
if strValue is None and strKey == "spliceVersion":
    sys.exit(0)
if not isinstance(strValue, str) or not re.fullmatch(mapPattern[strKey], strValue):
    sys.exit("%s: %s is missing or not valid" % (strFile, strKey))
sys.stdout.write(strValue)
PY
}

# FLAG_MO_KMS_FORCE in the environment wins over FLAG_MO_KMS from the
# settings, so a harness can run one MemberOrg without the KMS without
# editing basenet.conf.local.
[ -z "${FLAG_MO_KMS_FORCE:-}" ] || FLAG_MO_KMS="$FLAG_MO_KMS_FORCE"

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
