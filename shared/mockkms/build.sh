#!/usr/bin/env bash
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
#
# Builds the mock KMS driver against a Canton jar.
#
#   ./build.sh /path/to/canton-open-source-<version>.jar
#
# The jar is the one the participant image runs; take it from the image rather
# than from a local install, because the driver binds to internal Canton and
# pureconfig classes that carry no compatibility promise across patch versions.
# Extract it with:
#
#   docker run --rm --entrypoint sh <participant image> \
#       -c "cat /app/lib/canton-open-source-<version>.jar" > canton.jar
#
# Everything the driver needs at runtime is already inside that jar, so the
# dependency is `provided` and the output jar carries only our own classes.
set -euo pipefail
cd "$(dirname "$0")"

STR_JAR="${1:-}"
[ -n "$STR_JAR" ] || { echo "usage: ./build.sh <canton-open-source-*.jar>" >&2; exit 2; }
[ -f "$STR_JAR" ] || { echo "no such file: $STR_JAR" >&2; exit 2; }

# The version is read off the file name, so the pom and the jar cannot disagree.
STR_VER="$(basename "$STR_JAR" .jar | sed -n 's/^canton-open-source-//p')"
[ -n "$STR_VER" ] || { echo "cannot read a version out of $(basename "$STR_JAR")" >&2; exit 2; }

echo "installing canton $STR_VER into the local repository"
mvn -q install:install-file \
    -Dfile="$STR_JAR" \
    -DgroupId=com.digitalasset.canton \
    -DartifactId=canton-open-source \
    -Dversion="$STR_VER" \
    -Dpackaging=jar

echo "building the driver"
mvn -q -Dcanton.version="$STR_VER" clean package

ls -l target/raposza-mockkms-*.jar
