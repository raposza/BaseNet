#!/usr/bin/env bash
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
#
# Builds the pet shop DAR that loadtest.py uploads, and records its package id
# beside it.
#
#   ./build.sh [<sdk version>]        default 3.4.11
#
# WHERE IT LANDS. ~/.raposza/fixtures/petshop/<sdk>-build/, which is where
# loadtest.py looks. Nothing is written into this tree: a DAR is build output
# and this repository ships source.
#
#     ~/.raposza/fixtures/petshop/
#       3.4.11-build/petshop-0.0.1.dar
#       3.4.11-build/dar.json          the package id, read back out of the DAR
#       .build/3.4.11/                 the staging project, kept for its cache
#
# WHY 3.4.11 AGAINST A 3.5 PARTICIPANT. A DAR built by a 3.4 SDK carries LF
# 2.1, which a 3.5 participant accepts - it lists 2.1 among its supported
# versions. Any SDK your participant accepts works; name it as the argument.
#
# THE TOOLCHAIN is dpm when dpm is on PATH, otherwise the Daml assistant. dpm
# is the 3.x channel and the assistant is retired there, so a 3.x build with
# no dpm will fail in the compiler rather than here.
#
# THE PACKAGE ID IS READ OUT OF THE BUILT DAR - the Main-Dalf attribute of its
# manifest, unfolded, with the 64 hex characters taken off the file name. It is
# never derived from the source and never assumed.
set -euo pipefail
cd "$(dirname "$0")"

STR_SDK="${1:-3.4.11}"
DIR_STORE="$HOME/.raposza/fixtures/petshop"
DIR_PROJ="$DIR_STORE/.build/$STR_SDK"
DIR_OUT="$DIR_STORE/$STR_SDK-build"

if command -v dpm >/dev/null 2>&1; then
    LST_BUILD=(dpm build)
elif command -v daml >/dev/null 2>&1; then
    LST_BUILD=(daml build)
else
    echo "neither dpm nor daml is on PATH - install one and try again" >&2
    exit 1
fi

mkdir -p "$DIR_PROJ/daml" "$DIR_OUT"
cp daml/Main.daml "$DIR_PROJ/daml/Main.daml"
sed "s/SDK_VERSION/$STR_SDK/" daml.yaml.template > "$DIR_PROJ/daml.yaml"
# Whatever a previous build left would otherwise be picked up as this build's
# output when this build produces nothing.
rm -f "$DIR_PROJ/.daml/dist"/*.dar

echo "== ${LST_BUILD[*]} at sdk-version $STR_SDK"
( cd "$DIR_PROJ" && "${LST_BUILD[@]}" )

FILE_DAR=$(ls -1 "$DIR_PROJ/.daml/dist"/*.dar 2>/dev/null | head -1)
[ -n "$FILE_DAR" ] || { echo "the build reported success and produced no DAR" >&2; exit 1; }

# One DAR per directory: all builds of this fixture declare the same package
# name and version with a different package id, and two of them staged
# together are two revisions of one package to a participant.
rm -f "$DIR_OUT"/petshop*.dar
cp "$FILE_DAR" "$DIR_OUT/"
FILE_OUT="$DIR_OUT/$(basename "$FILE_DAR")"

python3 - "$FILE_OUT" "$DIR_OUT/dar.json" "$STR_SDK" <<'PY'
import json, re, sys, zipfile

fileDar, fileJson, strSdk = sys.argv[1], sys.argv[2], sys.argv[3]
STR_ATTR = "Main-Dalf"

# A jar manifest folds at 72 bytes and continues behind a single space.
# Reading the line as it sits truncates the package id, and a truncated id is
# worse than an absent one because it looks like an answer.
with zipfile.ZipFile(fileDar, "r") as zipDar:
    strManifest = zipDar.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
strValue = ""
flagIn = False
for strLine in strManifest.split("\n"):
    strLine = strLine.rstrip("\r")
    if flagIn:
        if strLine.startswith(" "):
            strValue += strLine[1:]
            continue
        break
    if strLine.startswith(STR_ATTR + ":"):
        strValue = strLine[len(STR_ATTR) + 1:].strip()
        flagIn = True

strBase = strValue.split("/")[-1]
match = re.search(r"-([0-9a-f]{64})\.dalf$", strBase)
if match is None:
    raise SystemExit("no package id in the DAR manifest: " + strBase)
with open(fileJson, "w", encoding="utf-8", newline="\n") as hnd:
    json.dump({"sdk": strSdk, "dar": fileDar.split("/")[-1],
               "packageId": match.group(1)}, hnd, indent=2)
    hnd.write("\n")
print("    package id  " + match.group(1))
PY

echo
echo "=== built  $FILE_OUT"
echo "    run the load test with it:  ./run-loadtest.sh memberorg-a"
