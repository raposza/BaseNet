#!/usr/bin/env python3
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
#
# jvm_watch.py - samples every JVM in a namespace on an interval and reports
# the PEAK, not a reading.
#
#   python3 jvm_watch.py [--ns <namespace>] [--every <s>] [--for <s>]
#                        [--out <file>] [--quiet]
#
# WHY IT EXISTS. jvm_read.py takes ONE reading, and every memory figure in
# basenet_memory.md was taken after the interesting moment had passed - S-27.
# A component can spend an entire run within 40 Mi of its limit and a reading
# afterwards shows nothing. Start this BEFORE the load, stop it after, and the
# peak column is the number the sizing has to survive.
#
# IT NEVER COLLECTS. jvm_read.py --gc runs a full collection to expose the LIVE
# set, which is the right instrument for choosing a heap and the wrong one
# here: collecting on every sample would both hide the peak and add work to the
# thing being measured. What this reports is committed and resident, which is
# what the cgroup kills against.
#
# THE PROBE COSTS SOMETHING AND THE INTERVAL IS WHY THE DEFAULT IS 30 s. Each
# sample is one kubectl exec per container running four jcmd calls, and jcmd is
# a JVM: D-725 records one OOMKilling its own target. The heap cap and the
# cleared JAVA_TOOL_OPTIONS that fixed that are in jvm_read.py's STR_PROBE and
# are inherited here unchanged. Sampling faster than about 10 s puts the probe
# into the measurement.
#
# EVERY SAMPLE IS WRITTEN TO A FILE AS IT IS TAKEN, not held and written at the
# end, so a lost terminal or a killed run keeps everything up to that point.
#
# Ctrl-C stops the run and still prints the summary.
#
# Needs kubectl and a cluster with the namespace up.
import argparse
import csv
import json
import re
import signal
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from jvm_read import lst_targets, map_figures, map_probe, say
from memsweep import map_settings

LST_FIELD = ["rss", "cgroup", "heapCommitted", "heapUsed", "metaCommitted",
             "nmtCommitted"]
N_EVERY_MIN = 10
# <name>-<ReplicaSet template hash>-<pod suffix>. The hash is 5 to 10 base-32
# characters and the suffix 5; a StatefulSet pod, <name>-<ordinal>, does not
# match and keeps its ordinal.
PAT_RS = re.compile(r"^(.*)-[bcdfghjklmnpqrstvwxz2456789]{5,10}-[a-z0-9]{5}$")

flagStop = False


def on_signal(nSig, frame):
    """Ctrl-C stops the loop rather than killing the summary

    @param nSig the signal number
    @param frame the stack frame
    """
    global flagStop
    flagStop = True


def map_limits(strNs):
    """each container's memory limit in Mi

    Read from -o json rather than a jsonpath template: lst_targets already
    parses the same document that way, and a template puts tab and newline
    escaping between this and the answer for nothing.

    @param strNs the namespace
    @return {(pod, container): Mi}, absent where no limit is declared
    """
    lstCmd = ["kubectl", "-n", strNs, "get", "pods", "-o", "json"]
    proc = subprocess.run(lstCmd, capture_output=True, text=True)
    if proc.returncode != 0:
        return {}
    mapOut = {}
    for mapPod in json.loads(proc.stdout).get("items", []):
        strPod = mapPod.get("metadata", {}).get("name", "")
        for mapCont in mapPod.get("spec", {}).get("containers", []):
            nMem = n_mi_quantity(mapCont.get("resources", {})
                                 .get("limits", {}).get("memory"))
            if nMem is not None:
                mapOut[(strPod, mapCont.get("name", ""))] = nMem
    return mapOut


def n_mi_quantity(strQty):
    """a Kubernetes memory quantity in Mi

    @param strQty the quantity, e.g. 1152Mi, 4Gi, 536870912
    @return the value in Mi, or None if it is empty or unparsable
    """
    strQty = (strQty or "").strip()
    if not strQty:
        return None
    mapUnit = {"Ki": 1 / 1024.0, "Mi": 1.0, "Gi": 1024.0,
               "K": 1000 / 1048576.0, "M": 1000000 / 1048576.0,
               "G": 1000000000 / 1048576.0}
    for strUnit, nFactor in mapUnit.items():
        if strQty.endswith(strUnit):
            strNum = strQty[:-len(strUnit)]
            if strNum.isdigit():
                return round(int(strNum) * nFactor)
            return None
    if strQty.isdigit():
        return round(int(strQty) / 1048576.0)
    return None


def str_name(strPod):
    """a pod name with its ReplicaSet suffix cut off, so a rollout does not
    split one component into two rows

    A Deployment's pod is <name>-<template hash>-<pod suffix>; a StatefulSet's
    is <name>-<ordinal> and keeps it, because the ordinal identifies the
    member. Anything else is returned unchanged.

    @param strPod the pod name
    @return the stable component name
    """
    match = PAT_RS.match(strPod)
    return match.group(1) if match else strPod


def sample(strNs, mapPeak, mapLast, writerCsv, nSeq, flagQuiet):
    """one pass over every container, recorded and folded into the peaks

    @param strNs the namespace
    @param mapPeak {component: {field: peak}}, mutated
    @param mapLast {component: {field: value}}, mutated
    @param writerCsv the open CSV writer, one row per container per sample
    @param nSeq this sample's number
    @param flagQuiet whether to stay silent per sample
    @return how many containers answered
    """
    strNow = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    cntRead = 0
    for strPod, strCont in lst_targets(strNs):
        mapSec = map_probe(strNs, strPod, strCont, False)
        if mapSec is None:
            continue
        mapFig = map_figures(mapSec)
        strComp = str_name(strPod)
        cntRead += 1
        mapPeak.setdefault(strComp, {})
        mapLast[strComp] = mapFig
        for strField in LST_FIELD:
            nVal = mapFig.get(strField)
            if nVal is None:
                continue
            if mapPeak[strComp].get(strField) is None \
                    or nVal > mapPeak[strComp][strField]:
                mapPeak[strComp][strField] = nVal
        writerCsv.writerow([nSeq, strNow, strComp, strPod, strCont]
                           + [mapFig.get(strField) for strField in LST_FIELD])
        if not flagQuiet:
            say("  %-22s rss %5s  cgroup %5s  heap %5s/%-5s"
                % (strComp, mapFig.get("rss"), mapFig.get("cgroup"),
                   mapFig.get("heapUsed"), mapFig.get("heapCommitted")))
    return cntRead


def say_summary(mapPeak, mapLast, mapLimit, mapPodOf, nSample, strOut):
    """the peak table, which is the point of the run

    @param mapPeak {component: {field: peak}}
    @param mapLast {component: {field: value}} from the final sample
    @param mapLimit {(pod, container): Mi}
    @param mapPodOf {component: (pod, container)} as last seen
    @param nSample how many passes completed
    @param strOut where the CSV went
    """
    say("")
    say("| component | limit | PEAK rss | last rss | PEAK cgroup | headroom |"
        " PEAK heap committed | PEAK metaspace | PEAK NMT |")
    say("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for strComp in sorted(mapPeak):
        mapOne = mapPeak[strComp]
        nLimit = mapLimit.get(mapPodOf.get(strComp))
        nPeakCg = mapOne.get("cgroup")
        strHead = "-"
        if nLimit is not None and nPeakCg is not None:
            strHead = str(nLimit - nPeakCg)
        say("| `%s` | %s | **%s** | %s | %s | %s | %s | %s | %s |"
            % (strComp,
               "-" if nLimit is None else nLimit,
               mapOne.get("rss", "-"),
               mapLast.get(strComp, {}).get("rss", "-"),
               "-" if nPeakCg is None else nPeakCg,
               strHead,
               mapOne.get("heapCommitted", "-"),
               mapOne.get("metaCommitted", "-"),
               mapOne.get("nmtCommitted", "-")))
    say("")
    say("%d samples. HEADROOM IS AGAINST PEAK CGROUP, which is what the kernel"
        " OOMKills against - basenet_memory.md section 9." % nSample)
    say("No collection was ever run, so heap committed is committed and not a"
        " live set; use jvm_read.py --gc for that.")
    say("samples  " + strOut)


def main():
    parser = argparse.ArgumentParser(
        description="sample every JVM in a namespace and report the peak")
    parser.add_argument("--ns", default="",
                        help="the namespace (default STR_NAMESPACE from"
                             " basenet.conf)")
    parser.add_argument("--every", type=int, default=30,
                        help="seconds between samples (default 30, minimum 10)")
    parser.add_argument("--for", dest="nFor", type=int, default=0,
                        help="stop after this many seconds (default: until"
                             " Ctrl-C)")
    parser.add_argument("--out", default="",
                        help="where the CSV goes (default"
                             " /tmp/bn-watch-<ns>-<stamp>.csv)")
    parser.add_argument("--quiet", action="store_true",
                        help="no per-sample lines, only the summary")
    args = parser.parse_args()

    if args.every < N_EVERY_MIN:
        raise SystemExit("--every below %d s puts the probe into the"
                         " measurement - D-725" % N_EVERY_MIN)
    strNs = args.ns or map_settings().get("STR_NAMESPACE", "")
    if not strNs:
        raise SystemExit("no namespace: pass --ns or set STR_NAMESPACE")

    strStamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    strOut = args.out or ("/tmp/bn-watch-%s-%s.csv" % (strNs, strStamp))
    Path(strOut).parent.mkdir(parents=True, exist_ok=True)

    signal.signal(signal.SIGINT, on_signal)
    signal.signal(signal.SIGTERM, on_signal)

    mapLimit = map_limits(strNs)
    mapPeak = {}
    mapLast = {}
    mapPodOf = {}
    nSample = 0
    nStart = time.time()

    say("namespace %s, every %d s, %s" % (
        strNs, args.every,
        "for %d s" % args.nFor if args.nFor else "until Ctrl-C"))
    say("writing  " + strOut)

    fileCsv = open(strOut, "w", newline="", encoding="utf-8")
    writerCsv = csv.writer(fileCsv)
    writerCsv.writerow(["sample", "utc", "component", "pod", "container"]
                       + LST_FIELD)

    try:
        # THE DEADLINE IS A WALL and it is tested at the TOP: the sleep is cut
        # short by it and no further sample is taken, so --for 600 is ten
        # minutes and not ten minutes plus one interval. The first sample is
        # always taken, whatever --for says.
        while not flagStop:
            if nSample and args.nFor and time.time() - nStart >= args.nFor:
                break
            nSample += 1
            if not args.quiet:
                say("== sample %d, %ds" % (nSample, round(time.time() - nStart)))
            for strPod, strCont in lst_targets(strNs):
                mapPodOf[str_name(strPod)] = (strPod, strCont)
            sample(strNs, mapPeak, mapLast, writerCsv, nSample, args.quiet)
            fileCsv.flush()
            nWake = time.time() + args.every
            while not flagStop and time.time() < nWake:
                if args.nFor and time.time() - nStart >= args.nFor:
                    break
                time.sleep(0.5)
    finally:
        fileCsv.close()

    if nSample == 0:
        raise SystemExit("no sample was taken")
    mapLimit.update(map_limits(strNs))
    say_summary(mapPeak, mapLast, mapLimit, mapPodOf, nSample, strOut)
    return 0


if __name__ == "__main__":
    sys.exit(main())
