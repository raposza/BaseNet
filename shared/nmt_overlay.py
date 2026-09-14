#!/usr/bin/env python3
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
# Generated 2026-09-12T18:00:00Z
#
# nmt_overlay.py - one install overlay: an absolute heap, a pod limit derived
# from it, and Native Memory Tracking.
#
#   python3 nmt_overlay.py [--out <dir>] [--member] [--no-nmt]
#                          [--heap <component>=<Mi>[:<reserve Mi>]]...
#                          [--mem <component>=<Mi>]...
#
# THE POD LIMIT IS CALCULATED, NOT GUESSED: limit = heap + reserve, rounded up
# to 16 Mi, written to requests AND limits so the pod is Guaranteed QoS.
# -Xms and -Xmx are both set to the heap, so committed heap is exactly the heap
# and the arithmetic is deterministic.
#
# WHY NOT MaxRAMPercentage. Measured 2026-09-12 at 75 %, 40 % and with absolute
# sizing: committed heap came out EQUAL TO MaxHeapSize every time, because the
# charts set InitialRAMPercentage to the same value. The percentage is
# therefore arithmetic on the limit - and the limit is the thing being derived,
# so it is the wrong end to hold. Worse, NON-HEAP DOES NOT SHRINK WITH THE
# HEAP: it was 2973, 2927 and 2780 Mi across those three runs while the sum of
# MaxHeapSize went 45056, 24032 and 1490. Both RAMPercentage flags are
# therefore STRIPPED when a heap is given.
#
# THE RESERVE IS EVERYTHING ELSE RESIDENT - metaspace, symbols, code cache, GC
# structures, thread stacks, direct buffers, and the mappings NMT does not
# account for. It is measured as RSS MINUS COMMITTED HEAP, not as an NMT
# subtotal: on validator-app RSS exceeded the whole NMT committed total by
# 156 Mi, so an NMT-based reserve would under-size the pod.
#
# EACH RELEASE'S JVM STRING IS READ FROM ITS OWN CHART - helm show values - and
# only edited. Retyping the vendor's flags would pin numThreads,
# ActiveProcessorCount and HeapDumpPath into this repository; they differ per
# chart already - validator is numThreads=8 - and they move per release.
#
# A component not named keeps the memory in values/ and the chart's own sizing.
#
# --mem SIZES A COMPONENT THAT RUNS NO JVM - the four splice-postgres releases.
# It writes requests and limits and nothing else, so there is no heap and no
# reserve to derive from: the number is the pod limit as given. Until
# 2026-09-12 this script skipped every PG release outright, which is how the
# four databases kept 4096 Mi of limit EACH - 16384 Mi, three quarters of the
# stack - while the six JVMs were sized to 5312 Mi in total. D-727.
#
# A PostgreSQL limit is not the same instrument as a JVM limit. Most of what a
# PG container charges to its cgroup is PAGE CACHE, which is reclaimable, so a
# tight limit trades IO for memory instead of being OOMKilled. What is NOT
# reclaimable is per-connection: all four values files set maxConnections: 300,
# and that is the number to look at before blaming shared_buffers.
#
# --member SIZES A BaseNet Member INSTEAD OF THE BaseNet Validator. The two
# have different components under the SAME release names - a Member's
# `participant` and `validator` are not the Validator's - so they are separate
# tables rather than extra rows, and their overlays must not share a
# directory. The default --out differs for that reason alone.
#
# A Member component ships NO default reserve. The Validator's reserves were
# measured on its own six JVMs on an idle founded network; a Member's
# participant was read at 3690 Mi RSS against 720 Mi of committed heap on
# 2026-09-13, which is nothing like the Validator's participant, and the
# residual is unexplained until NMT has been on. So --heap on a Member
# component REFUSES unless the reserve is given with it, rather than
# inheriting a number measured somewhere else.
#
# The overlay is passed to install.sh --overlay <dir>, or to
# memberorg-install.sh --overlay <dir> <name> for a Member, which appends it
# after each release's own values file - D-718. It is written outside the tree
# and is never committed.
#
# Needs helm and a network route to the chart registry. No cluster.
import argparse
import re
import subprocess
import sys
from pathlib import Path

from memsweep import map_settings


# One component: its release, its chart, where its resources sit in that
# release's values, and its measured reserve.
#
# THIS IS A FIFTH PARTIAL COPY OF THE RELEASE TABLE - install.sh, render.sh,
# images.sh and memsweep.py hold the others, and AGENTS.md names the
# duplication as the thing most likely to drift. A chart added there must be
# added here too, or its options and memory stay at the chart default and the
# run is silently mixed.
class Comp:

    def __init__(self, strName, strRelease, strChart, lstPath, nReserve):
        self.strName = strName
        self.strRelease = strRelease
        self.strChart = strChart
        self.lstPath = lstPath
        self.nReserve = nReserve
        self.nHeap = None
        self.nMem = None


# The default reserves are MEASURED, not chosen: RSS minus committed heap on
# 2026-09-12, Splice 0.7.4, an idle founded network after a full collection,
# rounded up to the next 32 Mi. Re-measure with jvm_read.py after a version
# bump or a workload change; a round-close has never been run - S-23.
#
#   participant 675   validator 696   sv 450   scan 446
#   mediator 343      sequencer 329
def lst_components(flagMember=False):
    if flagMember:
        # THE BaseNet Member's THREE RELEASES - memberorg-install.sh holds the
        # same table and AGENTS.md names the duplication. Reserve 0 means
        # read_heap refuses a --heap without an explicit reserve.
        return [
            Comp("participant", "participant", "splice-participant", [], 0),
            Comp("validator", "validator", "splice-validator", [], 0),
            Comp("postgres", "postgres", "splice-postgres", [], 0),
        ]
    return [
        Comp("participant", "participant", "splice-participant", [], 704),
        Comp("scan", "scan", "splice-scan", [], 480),
        Comp("sv", "sv", "splice-sv-node", [], 480),
        Comp("validator", "validator", "splice-validator", [], 704),
        Comp("sequencer", "global-domain-0", "splice-global-domain", ["sequencer"], 352),
        Comp("mediator", "global-domain-0", "splice-global-domain", ["mediator"], 352),
        Comp("participant-pg", "participant-pg", "splice-postgres", [], 0),
        Comp("sequencer-pg", "sequencer-pg", "splice-postgres", [], 0),
        Comp("mediator-pg", "mediator-pg", "splice-postgres", [], 0),
        Comp("apps-pg", "apps-pg", "splice-postgres", [], 0),
    ]


# Every pod limit carries this on top of heap plus reserve, for whatever
# enters the container that the steady state does not account for: a jcmd, a
# shell, an OOM heap dump. Measured need for it, 2026-09-12: mediator sat at
# 462 Mi of a 480 Mi limit and the sequencer was OOMKilled by a probe.
N_MARGIN = 96

STR_NMT = "-XX:NativeMemoryTracking=summary"
STR_CHART_PG = "splice-postgres"
PAT_PCT = re.compile(r"\s*-XX:(?:MaxRAMPercentage|InitialRAMPercentage)=\d+(?:\.\d+)?")
PAT_XM = re.compile(r"\s*-X(?:mx|ms)\d+[kKmMgG]?")
PAT_KEY = re.compile(r"^defaultJvmOptions:(.*)$")
PAT_HEAP = re.compile(r"^([A-Za-z0-9-]+)=(\d+)(?::(\d+))?$")
PAT_MEM = re.compile(r"^([A-Za-z0-9-]+)=(\d+)$")


def say(strMsg):
    print(strMsg, flush=True)


def n_round16(nMem):
    return int((nMem + 15) // 16 * 16)


def str_chart_jvm(strRepo, strChart, strVersion, mapCache):
    """the chart's OWN defaultJvmOptions, read from the chart once

    @param strRepo the OCI chart repository
    @param strChart the chart name
    @param strVersion the chart version
    @param mapCache the per-chart cache, mutated
    @return the option string, unquoted
    """
    if strChart in mapCache:
        return mapCache[strChart]
    lstCmd = ["helm", "show", "values", strRepo + "/" + strChart,
              "--version", strVersion]
    proc = subprocess.run(lstCmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise SystemExit("helm show values %s rc=%d\n%s"
                         % (strChart, proc.returncode, proc.stderr.strip()))
    for strLine in proc.stdout.splitlines():
        match = PAT_KEY.match(strLine)
        if match is None:
            continue
        strVal = match.group(1).strip()
        if len(strVal) >= 2 and strVal[0] == strVal[-1] and strVal[0] in "\"'":
            strVal = strVal[1:-1]
        mapCache[strChart] = strVal.strip()
        return mapCache[strChart]
    raise SystemExit(strChart + " has no top-level defaultJvmOptions -"
                     " the chart changed, read it before going further")


def str_extend(strJvm, nHeap, flagNmt):
    """the chart's string with an absolute heap and NMT

    Both RAMPercentage flags and any existing -Xms or -Xmx are removed before
    the new pair is appended, so the heap is stated exactly once and there is
    no precedence question to reason about.

    @param strJvm the chart's own option string
    @param nHeap the heap in Mi, or None to leave the sizing alone
    @param flagNmt whether to append the tracking flag
    @return the option string to write
    """
    strOut = strJvm
    if nHeap is not None:
        strOut = PAT_PCT.sub("", strOut)
        strOut = PAT_XM.sub("", strOut)
        strOut = strOut + " -Xms%dm -Xmx%dm" % (nHeap, nHeap)
    if flagNmt and "NativeMemoryTracking" not in strOut:
        strOut = strOut + " " + STR_NMT
    return " ".join(strOut.split())


def read_heap(lstComp, lstHeap):
    """apply every --heap to its component and derive its pod limit

    @param lstComp the components
    @param lstHeap the raw --heap arguments
    """
    mapComp = {comp.strName: comp for comp in lstComp}
    for strOne in lstHeap:
        match = PAT_HEAP.match(strOne)
        if match is None:
            raise SystemExit("--heap wants <component>=<Mi>[:<reserve Mi>],"
                             " got: " + strOne)
        strName = match.group(1)
        if strName not in mapComp:
            raise SystemExit("no such component: %s - one of %s"
                             % (strName, ", ".join(sorted(mapComp))))
        comp = mapComp[strName]
        if comp.strChart == STR_CHART_PG:
            raise SystemExit(strName + " runs no JVM; a heap means nothing there")
        comp.nHeap = int(match.group(2))
        if match.group(3) is not None:
            comp.nReserve = int(match.group(3))
        if comp.nReserve <= 0:
            raise SystemExit(strName + " has no measured reserve; give one as"
                             " <component>=<Mi>:<reserve Mi>")
        comp.nMem = n_round16(comp.nHeap + comp.nReserve + N_MARGIN)


def read_mem(lstComp, lstMem):
    """apply every --mem to its component as an absolute pod limit

    --mem is for a component with NO JVM. On a JVM component the limit is
    derived from the heap and the reserve, so taking a limit directly there
    would let MaxRAMPercentage size a heap against a number this script did not
    compute - two sizings for one pod. It is refused and --heap named instead.

    @param lstComp the components
    @param lstMem the raw --mem arguments
    """
    mapComp = {comp.strName: comp for comp in lstComp}
    for strOne in lstMem:
        match = PAT_MEM.match(strOne)
        if match is None:
            raise SystemExit("--mem wants <component>=<Mi>, got: " + strOne)
        strName = match.group(1)
        if strName not in mapComp:
            raise SystemExit("no such component: %s - one of %s"
                             % (strName, ", ".join(sorted(mapComp))))
        comp = mapComp[strName]
        if comp.strChart != STR_CHART_PG:
            raise SystemExit(strName + " runs a JVM; size it with --heap so the"
                             " limit is derived from the heap and the reserve")
        if comp.nMem is not None:
            raise SystemExit(strName + " is sized twice")
        comp.nMem = n_round16(int(match.group(2)))


def main():
    parser = argparse.ArgumentParser(
        description="an install overlay: an absolute heap, a derived pod limit, and NMT")
    parser.add_argument("--out", default="",
                        help="where the overlay is written (default /tmp/bn-nmt,"
                             " or /tmp/bn-nmt-member under --member)")
    parser.add_argument("--member", action="store_true",
                        help="size a BaseNet Member's three releases instead of"
                             " the BaseNet Validator's ten")
    parser.add_argument("--heap", action="append", default=[],
                        metavar="COMP=MI[:RESERVE]",
                        help="a component's heap in Mi, and optionally the non-heap"
                             " reserve to add to it for the pod limit. Repeatable")
    parser.add_argument("--mem", action="append", default=[],
                        metavar="COMP=MI",
                        help="a component's pod memory in Mi, requests and limits"
                             " both, for a component that runs no JVM. Repeatable")
    parser.add_argument("--no-nmt", action="store_true",
                        help="do not append the tracking flag")
    args = parser.parse_args()

    lstComp = lst_components(args.member)
    read_heap(lstComp, args.heap)
    read_mem(lstComp, args.mem)
    flagNmt = not args.no_nmt
    if args.no_nmt and not args.heap and not args.mem:
        raise SystemExit("nothing to write: --no-nmt with no --heap and no --mem")

    mapSet = map_settings()
    strRepo = mapSet.get("STR_CHART_REPO", "")
    strVersion = mapSet.get("STR_SPLICE_VERSION", "")
    if not strRepo or not strVersion:
        raise SystemExit("basenet.conf gave no STR_CHART_REPO or STR_SPLICE_VERSION")

    dirOut = Path(args.out or ("/tmp/bn-nmt-member" if args.member
                               else "/tmp/bn-nmt"))
    dirOut.mkdir(parents=True, exist_ok=True)
    say("chart repo %s, version %s" % (strRepo, strVersion))

    mapRelease = {}
    for comp in lstComp:
        mapRelease.setdefault(comp.strRelease, []).append(comp)
    mapCache = {}
    cntFile = 0
    nTotal = 0

    for strRelease, lstOne in mapRelease.items():
        flagPg = lstOne[0].strChart == STR_CHART_PG
        lstSet = [comp for comp in lstOne if comp.nHeap is not None]
        lstMem = [comp for comp in lstOne if comp.nMem is not None]
        # A PG release has no defaultJvmOptions to carry, so it earns a file
        # only when --mem named it, and that file is a resources block alone.
        flagJvm = not flagPg and (flagNmt or lstSet)
        if not flagJvm and not lstMem:
            continue

        lstLine = ["# Generated by nmt_overlay.py - one run's sizing,"
                   " never committed."]
        strNew = None
        nHeap = None
        if flagJvm:
            # defaultJvmOptions is ONE top-level key, so the two components of
            # global-domain-0 share it. Their heaps differ, so the LOWER is
            # written and the choice is printed: the higher would let the
            # smaller pod commit a heap its limit cannot hold.
            nHeap = min([comp.nHeap for comp in lstSet], default=None)
            strJvm = str_chart_jvm(strRepo, lstOne[0].strChart, strVersion,
                                   mapCache)
            strNew = str_extend(strJvm, nHeap, flagNmt)
            if "\"" in strNew:
                raise SystemExit("a double quote would break the overlay: "
                                 + strNew)
            lstLine.append("# JVM options read from %s %s and edited."
                           % (lstOne[0].strChart, strVersion))
            lstLine.append("defaultJvmOptions: \"%s\"" % strNew)
        for comp in lstMem:
            nIndent = 0
            for strKey in comp.lstPath:
                lstLine.append("  " * nIndent + strKey + ":")
                nIndent += 1
            strPad = "  " * nIndent
            lstLine.append(strPad + "resources:")
            lstLine.append(strPad + "  requests:")
            lstLine.append(strPad + "    memory: %dMi" % comp.nMem)
            lstLine.append(strPad + "  limits:")
            lstLine.append(strPad + "    memory: %dMi" % comp.nMem)
            nTotal += comp.nMem

        fileOut = dirOut / (strRelease + ".yaml")
        fileOut.write_text("\n".join(lstLine) + "\n", encoding="utf-8")
        cntFile += 1
        say("")
        say("===== " + str(fileOut))
        for comp in lstMem:
            if comp.nHeap is None:
                say("  %-12s no JVM - pod %d Mi as given"
                    % (comp.strName, comp.nMem))
                continue
            say("  %-12s heap %d + reserve %d + margin %d -> pod %d Mi"
                % (comp.strName, comp.nHeap, comp.nReserve, N_MARGIN,
                   comp.nMem))
            if comp.nHeap != nHeap:
                say("  %-12s the release carries -Xmx%dm, the lower of its"
                    " components - this pod keeps %d Mi of limit and will run"
                    " on the smaller heap" % ("", nHeap, comp.nMem))
        if strNew is not None:
            say("  jvm          " + strNew)

    if cntFile == 0:
        raise SystemExit("no release needed a file - nothing was written")
    say("")
    if nTotal:
        say("sized    %d Mi of limit across the components named" % nTotal)
    if args.member:
        say("overlay  %s, %d files - pass it as:"
            " ./memberorg-install.sh --overlay %s <name>"
            % (dirOut, cntFile, dirOut))
    else:
        say("overlay  %s, %d files - pass it as: ./install.sh --overlay %s"
            % (dirOut, cntFile, dirOut))
    return 0


if __name__ == "__main__":
    sys.exit(main())
