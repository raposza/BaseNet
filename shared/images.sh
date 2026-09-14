#!/usr/bin/env bash
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
#
# Lists every container image this BaseNet needs, for the version in
# basenet.conf. Contacts no cluster and pulls no image; it renders the
# charts and reads the image references out of the manifests.
#
# THE LIST IS DERIVED, NEVER HAND-KEPT. A hand-written image list is wrong the
# first time a chart adds a sidecar, and it goes wrong silently.
#
#   ./images.sh              the VENDOR references, one per line - the list to
#                            feed a mirror
#   ./images.sh --as-deployed  the same render with STR_IMAGE_REPO applied
#                            exactly as install.sh applies it. With a mirror
#                            configured, this is what the cluster will pull
#   ./images.sh --by-release   each image prefixed with the release that
#                            renders it, with STR_IMAGE_REPO applied. Use this
#                            when a reference is not on your mirror and you
#                            need to know which chart emits it
#   ./images.sh --repo-keys  also: which image keys each chart declares, and
#                            what each one defaults to
#
# Feed the list to whatever mirrors into your own registry:
#
#   ./images.sh | while read -r i; do
#       docker pull "$i"
#       docker tag  "$i" "artifactory.example.com/${i#*/}"
#       docker push "artifactory.example.com/${i#*/}"
#   done
set -uo pipefail
cd "$(dirname "$0")"
# The repository root, and the values the release table names. Both are
# overridable so download_images.sh can render from a throwaway copy of the
# settings at another version without editing the tree.
DIR_ROOT="${DIR_BASENET_ROOT:-..}"
DIR_VALUES="${DIR_BASENET_VALUES:-$DIR_ROOT/validator/values}"
. "$DIR_ROOT/basenet.conf"
# basenet.conf.local, when present, overrides it and is never committed.
[ ! -f "$DIR_ROOT/basenet.conf.local" ] || . "$DIR_ROOT/basenet.conf.local"

FLAG_KEYS=0
FLAG_DEPLOYED=0
FLAG_BY_RELEASE=0
case "${1:-}" in
    --repo-keys)   FLAG_KEYS=1 ;;
    --as-deployed) FLAG_DEPLOYED=1 ;;
    --by-release)  FLAG_DEPLOYED=1; FLAG_BY_RELEASE=1 ;;
esac

# install.sh's own rewrite, reproduced here so the two cannot disagree about
# what the cluster ends up pulling.
LST_REPO=()
LST_REPO_PG=()
LST_INIT=()
LST_INIT_GD=()
if [ "$FLAG_DEPLOYED" = "1" ] && [ -n "$STR_IMAGE_REPO" ]; then
    LST_REPO=(--set imageRepo="$STR_IMAGE_REPO")
    LST_REPO_PG=(--set imageName="$STR_IMAGE_REPO/$STR_PG_IMAGE")
    # EVERY app chart runs a PostgreSQL init container and imageRepo reaches
    # none of them. global-domain has two, one per node.
    LST_INIT=(--set persistence.initImageName="$STR_IMAGE_REPO/$STR_PG_IMAGE")
    LST_INIT_GD=(--set sequencer.persistence.initImageName="$STR_IMAGE_REPO/$STR_PG_IMAGE"
                 --set mediator.persistence.initImageName="$STR_IMAGE_REPO/$STR_PG_IMAGE")
elif [ "$FLAG_DEPLOYED" = "1" ]; then
    echo "STR_IMAGE_REPO is blank - this is the vendor's own list" >&2
fi

# The release table. It also appears in install.sh and render.sh; a chart added
# here must be added there too.
#
# Only splice-global-domain takes the sequencer password, and it is REQUIRED
# there. Passing it to a chart whose schema sets additionalProperties false
# fails the render, so the extra argument is per row and not global.
STR_TABLE="splice-postgres      sequencer-pg    postgres-sequencer.yaml
splice-postgres      mediator-pg     postgres-mediator.yaml
splice-postgres      participant-pg  postgres-participant.yaml
splice-postgres      apps-pg         postgres-apps.yaml
splice-global-domain global-domain-0 global-domain.yaml
splice-participant   participant     participant.yaml
splice-scan          scan            scan.yaml
splice-sv-node       sv              sv.yaml
splice-validator     validator       validator.yaml"

STR_OUT=$(mktemp -d)
trap 'rm -rf "$STR_OUT"' EXIT

# The SV and validator schemas REQUIRE auth.jwksUrl. It has no bearing on which
# images are rendered, but the render fails without it.
STR_JWKS="${STR_OIDC_JWKS_URL:-$STR_OIDC_BASE_URL/oauth2/jwks}"

N_FAIL=0
. ./image-overrides.sh
check_image_override

while read -r STR_CHART STR_RELEASE STR_VALUES; do
    # The same rule as LST_REPO: the vendor's own list is the default, and a
    # user's layout applies only to --as-deployed.
    STR_OVR=
    [ "$FLAG_DEPLOYED" != "1" ] || STR_OVR=$(lst_image_override "$STR_RELEASE")
    [ -z "$STR_CHART" ] && continue
    case "$STR_CHART" in
        splice-postgres)
            helm template "$STR_RELEASE" "$STR_CHART_REPO/$STR_CHART" \
                --version "$STR_SPLICE_VERSION" -n "$STR_NAMESPACE" \
                -f "$DIR_VALUES/$STR_VALUES" "${LST_REPO_PG[@]}" \
                $STR_OVR > "$STR_OUT/$STR_RELEASE.yaml" ;;
        splice-global-domain)
            helm template "$STR_RELEASE" "$STR_CHART_REPO/$STR_CHART" \
                --version "$STR_SPLICE_VERSION" -n "$STR_NAMESPACE" \
                -f "$DIR_VALUES/$STR_VALUES" "${LST_REPO[@]}" "${LST_INIT_GD[@]}" \
                --set sequencer.driver.password="$STR_PG_PASSWORD" \
                $STR_OVR > "$STR_OUT/$STR_RELEASE.yaml" ;;
        splice-sv-node)
            helm template "$STR_RELEASE" "$STR_CHART_REPO/$STR_CHART" \
                --version "$STR_SPLICE_VERSION" -n "$STR_NAMESPACE" \
                -f "$DIR_VALUES/$STR_VALUES" "${LST_REPO[@]}" "${LST_INIT[@]}" \
                --set auth.jwksUrl="$STR_JWKS" \
                $STR_OVR > "$STR_OUT/$STR_RELEASE.yaml" ;;
        splice-validator)
            helm template "$STR_RELEASE" "$STR_CHART_REPO/$STR_CHART" \
                --version "$STR_SPLICE_VERSION" -n "$STR_NAMESPACE" \
                -f "$DIR_VALUES/$STR_VALUES" "${LST_REPO[@]}" "${LST_INIT[@]}" \
                --set auth.jwksUrl="$STR_JWKS" \
                $STR_OVR > "$STR_OUT/$STR_RELEASE.yaml" ;;
        *)
            helm template "$STR_RELEASE" "$STR_CHART_REPO/$STR_CHART" \
                --version "$STR_SPLICE_VERSION" -n "$STR_NAMESPACE" \
                -f "$DIR_VALUES/$STR_VALUES" "${LST_REPO[@]}" "${LST_INIT[@]}" \
                $STR_OVR > "$STR_OUT/$STR_RELEASE.yaml" ;;
    esac
    if [ $? -ne 0 ]; then
        echo "FAILED TO RENDER  $STR_CHART ($STR_RELEASE) - the list below is incomplete" >&2
        N_FAIL=$((N_FAIL + 1))
    fi
done <<< "$STR_TABLE"

# Both `image:` and `- image:` occur, quoted and unquoted.
function lst_image() {
    grep -hoE '^[[:space:]-]*image:[[:space:]]*"?[^"[:space:]]+' "$1" \
        | sed -E 's/.*image:[[:space:]]*"?//' \
        | sort -u
}

if [ "$FLAG_BY_RELEASE" = "1" ]; then
    while read -r STR_CHART STR_RELEASE STR_VALUES; do
        [ -z "$STR_CHART" ] && continue
        [ -f "$STR_OUT/$STR_RELEASE.yaml" ] || continue
        lst_image "$STR_OUT/$STR_RELEASE.yaml" | sed "s|^|$STR_RELEASE  |"
    done <<< "$STR_TABLE"
else
    cat "$STR_OUT"/*.yaml > "$STR_OUT/all.yaml"
    lst_image "$STR_OUT/all.yaml"
fi

if [ "$FLAG_KEYS" = "1" ]; then
    echo
    echo "=== image keys, from each chart's own default values"
    printf '%s\n' "$STR_TABLE" | awk '{print $1}' | sort -u | while read -r STR_CHART; do
        echo "--- $STR_CHART"
        helm show values "$STR_CHART_REPO/$STR_CHART" \
            --version "$STR_SPLICE_VERSION" > "$STR_OUT/values.yaml"
        if [ $? -ne 0 ]; then
            echo "    COULD NOT READ THE CHART"
            continue
        fi
        grep -nE "imageRepo|imageName|imageTag|imagePullPolicy|imagePullSecret" \
            "$STR_OUT/values.yaml" || echo "    (no image key declared)"
    done
fi

exit "$N_FAIL"
