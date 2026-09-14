#!/usr/bin/env bash
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
# Generated 2026-09-14T09:00:00Z
#
# SOURCED, NOT RUN. Per-image overrides, for a registry that does not hold the
# images under the vendor's own names.
#
# WHY ONE PREFIX IS NOT ENOUGH. Every Splice chart composes a reference as
# <imageRepo>/<imageName>:<tag>, and STR_IMAGE_REPO sets imageRepo for all of
# them at once. That covers a mirror that copied the images under their own
# names into one place. It cannot say "this image is somewhere else, under
# another name" - and a registry with per-team projects, or one that renames on
# push, needs exactly that.
#
# THE FORMAT. STR_IMAGE_OVERRIDE is a whitespace-separated list of
#
#     <release>:<chart key>=<value>
#
# for example
#
#     STR_IMAGE_OVERRIDE="participant:imageName=platform/canton-participant
#                         scan:ui.imageName=web/scan-web-ui
#                         *:persistence.initImageName=infra/postgres:14"
#
# THE RELEASE IS THE INSTALL NAME, NOT THE CHART - `participant`, `scan`,
# `global-domain-0`, `sequencer-pg`. A BaseNet Member's releases are its own:
# `postgres`, `participant`, `validator`, in its own namespace. `*` matches
# every release, which is what a key common to all the charts wants.
#
# NO VALUE MAY CONTAIN WHITESPACE. An image reference never does, and the list
# is split on it.
#
# A KEY THE CHART DOES NOT DECLARE FAILS THE RENDER rather than being ignored:
# the Splice charts set additionalProperties false. That is a feature here -
# render.sh will tell you a key is wrong before anything is installed. The keys
# each chart publishes are in docs/images.md.

function check_image_override() {
    local strEntry
    for strEntry in ${STR_IMAGE_OVERRIDE:-}; do
        case "$strEntry" in
            ?*:?*=?*) ;;
            *) echo "STR_IMAGE_OVERRIDE is <release>:<key>=<value>, and this is"\
                    " not: $strEntry" >&2
               exit 2 ;;
        esac
    done
}

# Prints `--set <key>=<value>` for every override naming this release, space
# separated, for a caller to splice in UNQUOTED. Nothing is printed when the
# list is empty or nothing matches.
function lst_image_override() {
    local strEntry
    for strEntry in ${STR_IMAGE_OVERRIDE:-}; do
        case "$strEntry" in
            "$1":*|"*":*) printf -- '--set %s ' "${strEntry#*:}" ;;
        esac
    done
}
