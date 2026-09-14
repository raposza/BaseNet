#!/usr/bin/env python3
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
# Generated 2026-09-12T16:40:00Z
#
# jvm_read.py - what every JVM in the namespace actually holds: RSS, committed
# and used heap, metaspace, and - when Native Memory Tracking is on - the
# non-heap breakdown by category.
#
#   python3 jvm_read.py [--ns <namespace>] [--gc] [--raw]
#
# It reads a network that is already up and changes nothing. --gc runs a full
# collection first, so "heap used" is the LIVE set rather than a point between
# collections; without it the used column is whatever the last cycle left.
#
# THE JVM IS NOT PID 1. pid 1 is `/usr/bin/tini -- /app/entrypoint.sh` and
# `jcmd 1` fails with AttachNotSupportedException - basenet_memory.md section 9,
# where two reads were lost to it. The pid is found by argv[0]'s BASENAME being
# exactly `java`, which is also why this does not grep /proc for the string
# "java": a pattern like that matches the probing shell's own command line and
# the third void read of that session came from exactly there.
#
# WITHOUT NMT the non-heap columns are absent and the residual is a
# subtraction, which is all basenet_memory.md section 3 could do. Turn it on
# with nmt_overlay.py and reinstall; the flag cannot be set on a running JVM.
#
# One kubectl exec per container, which carries every question for that
# container - a round trip costs more than the measurement it holds.
#
# Needs kubectl and a cluster with the namespace up.
import argparse
import json
import re
import subprocess
import sys

from memsweep import map_settings

PAT_HEAP = re.compile(r"total\s+(\d+)K,\s+used\s+(\d+)K")
PAT_META = re.compile(r"^\s*Metaspace\s+used\s+(\d+)K,.*?committed\s+(\d+)K",
                      re.MULTILINE)
PAT_NMT_TOTAL = re.compile(r"^Total:\s+reserved=(\d+)KB,\s+committed=(\d+)KB",
                           re.MULTILINE)
PAT_NMT_CAT = re.compile(r"^-\s+(.+?)\s+\(reserved=(\d+)KB,\s+committed=(\d+)KB\)",
                         re.MULTILINE)
PAT_RSS = re.compile(r"^Rss:\s+(\d+)\s+kB", re.MULTILINE)
PAT_MAXHEAP = re.compile(r"-XX:MaxHeapSize=(\d+)")
LST_SKIP = ["-web-ui"]

# One shell, one round trip. $STR_GC is substituted, not interpolated by the
# shell, and nothing here is passed through a shell on this side: the whole
# script is a single argv element, so its quotes are literal.
STR_PROBE = r'''
pid=
for d in /proc/[0-9]*; do
  c=$(tr '\0' '\n' < "$d/cmdline" 2>/dev/null | head -1)
  case "${c##*/}" in
    java) pid=${d#/proc/}; break ;;
  esac
done
if [ -z "$pid" ]; then echo "@@@ nojvm"; exit 0; fi
# jcmd IS A JVM AND IT INHERITS JAVA_TOOL_OPTIONS FROM THE CONTAINER. With
# -Xms in that variable the probe pre-commits a SECOND full heap inside the
# cgroup, and on 2026-09-12 that OOMKilled the sequencer at a 480 Mi limit -
# exit 137 on the exec, restart 1 on the container. Cleared, and the heap
# capped, so the measurement does not move what it measures.
STR_JCMD='env JAVA_TOOL_OPTIONS= jcmd -J-Xms8m -J-Xmx48m'
echo "@@@ pid $pid"
__GC__
echo "@@@ heap";   $STR_JCMD "$pid" GC.heap_info 2>&1
echo "@@@ nmt";    $STR_JCMD "$pid" VM.native_memory summary 2>&1
echo "@@@ flags";  $STR_JCMD "$pid" VM.flags 2>&1
echo "@@@ smaps";  cat "/proc/$pid/smaps_rollup" 2>&1
echo "@@@ cgroup"; cat /sys/fs/cgroup/memory.current 2>/dev/null \
  || cat /sys/fs/cgroup/memory/memory.usage_in_bytes 2>&1
'''


def say(strMsg):
    print(strMsg, flush=True)


def n_mi_kb(strKb):
    return round(int(strKb) / 1024.0)


def map_section(strOut):
    """the probe's output split on its own markers

    @param strOut the raw stdout of one probe
    @return {section name: text}
    """
    mapOut = {}
    strAt = None
    for strLine in strOut.splitlines():
        if strLine.startswith("@@@ "):
            strAt = strLine[4:].split()[0]
            mapOut[strAt] = mapOut.get(strAt, "")
            if strAt == "pid":
                mapOut["pid"] = strLine[4:].split()[1]
                strAt = None
            continue
        if strAt is not None:
            mapOut[strAt] = mapOut[strAt] + strLine + "\n"
    return mapOut


def lst_targets(strNs):
    """every container worth probing, as (pod, container)

    @param strNs the namespace
    @return the list, pods in name order
    """
    lstCmd = ["kubectl", "-n", strNs, "get", "pods", "-o", "json"]
    proc = subprocess.run(lstCmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise SystemExit("kubectl get pods rc=%d\n%s"
                         % (proc.returncode, proc.stderr.strip()))
    lstOut = []
    for mapPod in json.loads(proc.stdout).get("items", []):
        strPod = mapPod.get("metadata", {}).get("name", "")
        if mapPod.get("status", {}).get("phase") != "Running":
            continue
        if any(strOne in strPod for strOne in LST_SKIP):
            continue
        for mapCont in mapPod.get("spec", {}).get("containers", []):
            lstOut.append((strPod, mapCont.get("name", "")))
    return sorted(lstOut)


def map_probe(strNs, strPod, strCont, flagGc):
    """probe one container

    @param strNs the namespace
    @param strPod the pod
    @param strCont the container
    @param flagGc whether to collect before reading the heap
    @return the parsed sections, or None if that container runs no JVM
    """
    strScript = STR_PROBE.replace(
        "__GC__", '$STR_JCMD "$pid" GC.run >/dev/null 2>&1' if flagGc else ":")
    lstCmd = ["kubectl", "-n", strNs, "exec", strPod, "-c", strCont,
              "--", "sh", "-c", strScript]
    proc = subprocess.run(lstCmd, capture_output=True, text=True)
    if proc.returncode != 0:
        say("  !! %s/%s exec rc=%d: %s"
            % (strPod, strCont, proc.returncode, proc.stderr.strip()[:200]))
        return None
    mapSec = map_section(proc.stdout)
    if "nojvm" in mapSec:
        return None
    return mapSec


def map_figures(mapSec):
    """one container's numbers, all MiB

    @param mapSec the parsed probe sections
    @return the figures, with None for anything the probe could not answer
    """
    mapOut = {"nmt": {}}
    matchRss = PAT_RSS.search(mapSec.get("smaps", ""))
    mapOut["rss"] = n_mi_kb(matchRss.group(1)) if matchRss else None
    strCgroup = mapSec.get("cgroup", "").strip().splitlines()
    mapOut["cgroup"] = (round(int(strCgroup[0]) / 1048576.0)
                        if strCgroup and strCgroup[0].isdigit() else None)
    matchHeap = PAT_HEAP.search(mapSec.get("heap", ""))
    mapOut["heapCommitted"] = n_mi_kb(matchHeap.group(1)) if matchHeap else None
    mapOut["heapUsed"] = n_mi_kb(matchHeap.group(2)) if matchHeap else None
    matchMeta = PAT_META.search(mapSec.get("heap", ""))
    mapOut["metaUsed"] = n_mi_kb(matchMeta.group(1)) if matchMeta else None
    mapOut["metaCommitted"] = n_mi_kb(matchMeta.group(2)) if matchMeta else None
    matchMax = PAT_MAXHEAP.search(mapSec.get("flags", ""))
    mapOut["maxHeap"] = (round(int(matchMax.group(1)) / 1048576.0)
                         if matchMax else None)
    strNmt = mapSec.get("nmt", "")
    matchTotal = PAT_NMT_TOTAL.search(strNmt)
    mapOut["nmtCommitted"] = n_mi_kb(matchTotal.group(2)) if matchTotal else None
    for matchCat in PAT_NMT_CAT.finditer(strNmt):
        mapOut["nmt"][matchCat.group(1)] = n_mi_kb(matchCat.group(3))
    return mapOut


def str_cell(nVal):
    return "-" if nVal is None else str(nVal)


def str_table_main(lstRow):
    """the per-JVM balance sheet

    @param lstRow the (name, figures) pairs, largest RSS first
    @return the markdown table
    """
    lstLine = ["| component | RSS | cgroup | heap committed | heap used |"
               " metaspace committed | NMT committed | RSS - NMT | MaxHeapSize |",
               "| --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    mapSum = {}
    for strName, mapFig in lstRow:
        nGap = None
        if mapFig["rss"] is not None and mapFig["nmtCommitted"] is not None:
            nGap = mapFig["rss"] - mapFig["nmtCommitted"]
        lstLine.append("| `%s` | %s | %s | %s | %s | %s | %s | %s | %s |"
                       % (strName, str_cell(mapFig["rss"]),
                          str_cell(mapFig["cgroup"]),
                          str_cell(mapFig["heapCommitted"]),
                          str_cell(mapFig["heapUsed"]),
                          str_cell(mapFig["metaCommitted"]),
                          str_cell(mapFig["nmtCommitted"]), str_cell(nGap),
                          str_cell(mapFig["maxHeap"])))
        for strKey in ["rss", "heapCommitted", "heapUsed", "metaCommitted",
                       "nmtCommitted"]:
            if mapFig[strKey] is not None:
                mapSum[strKey] = mapSum.get(strKey, 0) + mapFig[strKey]
    lstLine.append("| **total** | **%s** | | **%s** | **%s** | **%s** | **%s** | | |"
                   % (str_cell(mapSum.get("rss")),
                      str_cell(mapSum.get("heapCommitted")),
                      str_cell(mapSum.get("heapUsed")),
                      str_cell(mapSum.get("metaCommitted")),
                      str_cell(mapSum.get("nmtCommitted"))))
    return "\n".join(lstLine)


def str_table_nmt(lstRow):
    """committed memory by NMT category, one column per JVM

    @param lstRow the (name, figures) pairs
    @return the markdown table, or a note if no JVM had tracking on
    """
    lstName = [strName for strName, mapFig in lstRow if mapFig["nmt"]]
    if not lstName:
        return ("NO JVM HAD NATIVE MEMORY TRACKING ON, so the non-heap"
                " breakdown is unavailable and the residual above is a"
                " subtraction. nmt_overlay.py and a reinstall turn it on;"
                " it cannot be set on a running JVM.")
    mapTotal = {}
    for strName, mapFig in lstRow:
        for strCat, nMi in mapFig["nmt"].items():
            mapTotal[strCat] = mapTotal.get(strCat, 0) + nMi
    lstLine = ["| category (committed Mi) | " + " | ".join("`%s`" % one for one in lstName)
               + " | total |",
               "| --- |" + " --- |" * (len(lstName) + 1)]
    for strCat in sorted(mapTotal, key=lambda one: -mapTotal[one]):
        lstCell = []
        for strName in lstName:
            mapFig = dict(lstRow)[strName]
            lstCell.append(str_cell(mapFig["nmt"].get(strCat)))
        lstLine.append("| %s | %s | **%d** |"
                       % (strCat, " | ".join(lstCell), mapTotal[strCat]))
    return "\n".join(lstLine)


def main():
    parser = argparse.ArgumentParser(
        description="what every JVM in the namespace actually holds")
    parser.add_argument("--ns", default="",
                        help="the namespace (default: STR_NAMESPACE from basenet.conf)")
    parser.add_argument("--gc", action="store_true",
                        help="run a full collection first, so heap used is the live set")
    parser.add_argument("--raw", action="store_true",
                        help="also print every probe's raw output")
    args = parser.parse_args()

    strNs = args.ns or map_settings().get("STR_NAMESPACE", "sv")
    lstTarget = lst_targets(strNs)
    say("namespace %s, %d containers to probe%s"
        % (strNs, len(lstTarget), ", collecting first" if args.gc else ""))

    lstRow = []
    for strPod, strCont in lstTarget:
        mapSec = map_probe(strNs, strPod, strCont, args.gc)
        if mapSec is None:
            say("  no jvm  %s/%s" % (strPod, strCont))
            continue
        mapFig = map_figures(mapSec)
        say("  read    %s/%s pid %s, RSS %s Mi"
            % (strPod, strCont, mapSec.get("pid", "?"), str_cell(mapFig["rss"])))
        if args.raw:
            say("----- raw %s/%s" % (strPod, strCont))
            for strKey in ["heap", "nmt", "flags", "smaps", "cgroup"]:
                say("--- " + strKey)
                say(mapSec.get(strKey, "").rstrip())
        lstRow.append((strPod if strCont in strPod else strPod + "/" + strCont,
                       mapFig))

    if not lstRow:
        say("NO JVM FOUND in namespace " + strNs)
        return 1

    lstRow.sort(key=lambda tpl: -(tpl[1]["rss"] or 0))
    say("")
    say(str_table_main(lstRow))
    say("")
    say(str_table_nmt(lstRow))
    say("")
    say("heap used is %s." % ("the LIVE set - a full collection ran first"
                              if args.gc else "a point between collections,"
                              " NOT the live set; --gc for that"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
