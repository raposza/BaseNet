#!/usr/bin/env bash
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
# Generated 2026-09-10T19:00:00Z
#
# Downloads every container image a Splice release needs, verifies each one
# against the digest the vendor's chart pins, and saves it into a folder named
# after the release, one docker-save tar per image:
#
#   ./download_images.sh 0.8.0     ->   ./0.8.0/sv-app_0.8.0.tar, ...
#                                       ./0.8.0/digests.txt
#                                       ./0.8.0/SHA256SUMS
#
# The folder is created in the directory you run it from. The list is
# images.sh's own, rendered for the requested version, so nothing is
# hand-kept. If a chart at that version rejects this repository's values, the
# render names the chart and nothing is downloaded.
#
# THE VERIFICATION. The charts pin every Splice image as name:tag@sha256:...
# docker pulls by that digest and refuses content that does not hash to it;
# this script then confirms the pulled image carries exactly that digest before
# saving it, and refuses to save one that does not. An image the chart names
# by tag alone has no published hash to check against: it is saved, marked
# UNPINNED, and the digest it arrived with is recorded.
#
# digests.txt   per tar: VERIFIED or UNPINNED, the digest, the vendor reference
# SHA256SUMS    the tars' own hashes. Carried to another machine, the folder is
#               checked with: sha256sum -c SHA256SUMS
#
# Both files are rewritten after EVERY image, so a run that stops partway
# keeps the record of everything it finished. A second run re-hashes every tar
# already there against SHA256SUMS: a match is kept, anything else is fetched
# again. A run that stops says where and with what exit code. `docker load -i <tar>` brings an
# image back. Needs docker and helm.
set -Eeuo pipefail

STR_VERSION=${1:-}
if [[ ! "$STR_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "usage: ./download_images.sh <splice version>, e.g. 0.8.0" >&2
    exit 2
fi
DIR_OUT="$PWD/$STR_VERSION"
cd "$(dirname "$0")"
command -v docker >/dev/null 2>&1 || { echo "docker is not on PATH" >&2; exit 1; }

# images.sh reads the version from basenet.conf. Rather than touch that file,
# render with DIR_BASENET_ROOT and DIR_BASENET_VALUES pointed at a throwaway
# copy of the settings and the values, with the version replaced in it.
DIR_TMP=$(mktemp -d)
trap 'rm -rf "$DIR_TMP"' EXIT
trap 'echo "!!! STOPPED - exit $? at line $LINENO: $BASH_COMMAND" >&2' ERR
trap 'echo "!!! INTERRUPTED" >&2; exit 130' INT TERM
cp ../basenet.conf "$DIR_TMP/"
[ ! -f ../basenet.conf.local ] || cp ../basenet.conf.local "$DIR_TMP/"
cp -r ../validator/values "$DIR_TMP/"
sed -i "s/^STR_SPLICE_VERSION=.*/STR_SPLICE_VERSION=$STR_VERSION/" "$DIR_TMP"/basenet.conf*

echo "== the image list for Splice $STR_VERSION"
if ! DIR_BASENET_ROOT="$DIR_TMP" DIR_BASENET_VALUES="$DIR_TMP/values" \
        ./images.sh > "$DIR_TMP/images.txt" </dev/null; then
    echo "images.sh could not render every chart at $STR_VERSION - nothing downloaded" >&2
    exit 1
fi
[ -s "$DIR_TMP/images.txt" ] || { echo "images.sh listed no image" >&2; exit 1; }
echo "    $(wc -l < "$DIR_TMP/images.txt") images"

mkdir -p "$DIR_OUT"
FILE_SUMS="$DIR_OUT/SHA256SUMS"
FILE_DIGESTS="$DIR_OUT/digests.txt"

# What an earlier run recorded, keyed by tar name.
declare -A MAP_SUM=()
declare -A MAP_REC=()
if [ -f "$FILE_SUMS" ]; then
    while read -r strSum strFile; do
        MAP_SUM["$strFile"]=$strSum
    done < "$FILE_SUMS"
fi
if [ -f "$FILE_DIGESTS" ]; then
    while read -r strFile strRest; do
        MAP_REC["$strFile"]=$strRest
    done < "$FILE_DIGESTS"
fi

# Every tar with a record whose file is present, sorted, each file written
# through a temporary name and renamed into place.
function write_records() {
    local strTar
    : > "$FILE_SUMS.part"
    : > "$FILE_DIGESTS.part"
    while read -r strTar; do
        [ -n "$strTar" ] || continue
        [ -f "$DIR_OUT/$strTar" ] || continue
        [ -n "${MAP_REC[$strTar]:-}" ] || continue
        echo "${MAP_SUM[$strTar]}  $strTar" >> "$FILE_SUMS.part"
        echo "$strTar ${MAP_REC[$strTar]}" >> "$FILE_DIGESTS.part"
    done < <(printf '%s\n' "${!MAP_SUM[@]}" | LC_ALL=C sort)
    mv "$FILE_SUMS.part" "$FILE_SUMS"
    mv "$FILE_DIGESTS.part" "$FILE_DIGESTS"
}


cntVerified=0
cntUnpinned=0
cntKept=0
cntFail=0
while read -r strRef; do
    # name:tag@sha256:... - docker pulls by the digest; the tar keeps name:tag
    strNoDigest=${strRef%@*}
    strLast=${strNoDigest##*/}
    if [[ "$strLast" == *:* ]]; then
        strRepo=${strNoDigest%:*}
        strTag=${strLast##*:}
    else
        strRepo=$strNoDigest
        strTag=latest
    fi
    strKeep="$strRepo:$strTag"
    strDigest=
    strPull=$strKeep
    if [[ "$strRef" == *@* ]]; then
        strDigest=${strRef#*@}
        strPull="$strRepo@$strDigest"
    fi
    strTar="${strRepo##*/}_$strTag.tar"
    fileTar="$DIR_OUT/$strTar"

    if [ -f "$fileTar" ] && [ -n "${MAP_SUM[$strTar]:-}" ] && [ -n "${MAP_REC[$strTar]:-}" ]; then
        if [ "$(sha256sum "$fileTar" | cut -d' ' -f1)" = "${MAP_SUM[$strTar]}" ]; then
            strStatus=${MAP_REC[$strTar]%% *}
            echo "--- kept   $strTar, $strStatus, sha256 matches the record"
            cntKept=$((cntKept + 1))
            if [ "$strStatus" = "VERIFIED" ]; then
                cntVerified=$((cntVerified + 1))
            else
                cntUnpinned=$((cntUnpinned + 1))
            fi
            continue
        fi
        echo "--- DAMAGED $strTar - its sha256 does not match the record, fetching again"
    fi

    echo "--- pull   $strPull"
    docker pull "$strPull" </dev/null
    [ "$strPull" = "$strKeep" ] || docker tag "$strPull" "$strKeep" </dev/null
    if [ -n "$strDigest" ]; then
        if ! docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$strKeep" </dev/null \
                | grep -qFx "$strRepo@$strDigest"; then
            echo "!!! $strKeep does not carry the pinned $strDigest - NOT saved" >&2
            cntFail=$((cntFail + 1))
            continue
        fi
        strStatus=VERIFIED
        strGot=$strDigest
    else
        strStatus=UNPINNED
        strGot=$(docker image inspect --format '{{index .RepoDigests 0}}' "$strKeep" </dev/null 2>/dev/null || true)
        strGot=${strGot#*@}
        [ -n "$strGot" ] || strGot=-
    fi
    docker save -o "$fileTar.part" "$strKeep" </dev/null
    mv "$fileTar.part" "$fileTar"
    MAP_SUM["$strTar"]=$(sha256sum "$fileTar" | cut -d' ' -f1)
    MAP_REC["$strTar"]="$strStatus $strGot $strRef"
    write_records
    echo "    $strStatus  $strTar"
    if [ "$strStatus" = "VERIFIED" ]; then
        cntVerified=$((cntVerified + 1))
    else
        cntUnpinned=$((cntUnpinned + 1))
    fi
done < "$DIR_TMP/images.txt"

write_records

echo
echo "=== Splice $STR_VERSION: $cntVerified verified, $cntUnpinned unpinned (no published hash), $cntFail refused; $cntKept were already there"
echo "    $(du -s --si "$DIR_OUT" | cut -f1) in $DIR_OUT"
echo "    elsewhere, check the folder with: sha256sum -c SHA256SUMS"
[ "$cntFail" -eq 0 ] || exit 1
