#!/usr/bin/env bash
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
#
# Proves the driver end to end without a cluster:
#   1 Canton's own ServiceLoader lookup finds it by name
#   2 its ConfigReader parses a crypto.kms.config block
#   3 it generates a signing key, signs, and the signature verifies
#   4 a SECOND driver over the SAME key file still has the key
#
# Step 4 is the whole point of this driver and the thing the vendor's in-memory
# mock cannot do. Run build.sh first.
#
#   ./selftest.sh /path/to/canton-open-source-<version>.jar
set -euo pipefail
cd "$(dirname "$0")"

STR_JAR="${1:-}"
[ -n "$STR_JAR" ] || { echo "usage: ./selftest.sh <canton-open-source-*.jar>" >&2; exit 2; }
[ -f "$STR_JAR" ] || { echo "no such file: $STR_JAR" >&2; exit 2; }

STR_OWN="$(ls target/raposza-mockkms-*.jar 2>/dev/null | head -1)"
[ -n "$STR_OWN" ] || { echo "no driver jar - run ./build.sh first" >&2; exit 2; }

# Unprivileged, out of a temporary directory, removed on the way out.
DIR_TMP="$(mktemp -d)"
trap 'rm -rf "$DIR_TMP"' EXIT

javac -proc:none -d "$DIR_TMP" -cp "$STR_OWN:$STR_JAR" src/selftest/java/SelfTest.java
java -cp "$DIR_TMP:$STR_OWN:$STR_JAR" SelfTest "$DIR_TMP/keys.txt"
