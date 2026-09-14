#!/usr/bin/env python3
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
#
# memsweep.py - the smallest memory each component of a BaseNet Validator
# still starts and serves on, found by repeated install-and-tear-down.
#
#   python3 memsweep.py            the sweep; hours, one namespace, destructive
#   python3 memsweep.py --dry-run  the plan and the first overlay, no cluster
#
# THE METHOD, and it is the operator's:
#
#   run 0   the values/ files untouched - STD, the baseline. A component that
#           fails here aborts the sweep: that is a broken baseline, not a
#           data point. STD is the LIMIT in values/, not the request: the
#           limit is what the kernel OOMKills against and what
#           MaxRAMPercentage sizes the heap from
#   run 1   every component starts at 1.25x the resident set run 0 measured
#           for it, capped at 3/4 of its STD limit so the first step always
#           descends. Measurement, not a blind halving: a component whose
#           request sits far below what it uses would otherwise be handed a
#           value it cannot start on, and the failure would say nothing
#   run n   every component still searching moves ON ITS OWN. Halve while no
#           failure is known; once a failure and a success bracket it, take
#           the midpoint - which at the first recovery is the operator's
#           150 % of the failing value, the two agree there. Stop when the
#           bracket is under --floor and keep the last value that worked
#
# --seed <runs.jsonl> takes the seeds from an earlier sweep's green run 0 and
# skips run 0 entirely. Attribution is by pod name there, the cluster being
# gone; live it is by the Helm annotation, as everywhere else in this file.
#
# WHAT COUNTS AS A FAILURE, and nothing else does: that component's own
# container was OOMKilled. `validator-app` exits until scan serves and pods
# that merely never turn Ready are collateral - a run that fails with no
# OOMKill anywhere changes no component's value and is run again, and two
# such runs in a row abort the sweep rather than loop.
#
# NO MINING ROUND. The gate is `smoke.sh` without `--round`: every pod Ready,
# then scan answers. The result is therefore a floor for a network that
# starts and serves, NOT one proven to close a round.
#
# Needs kubectl, helm and python3, and a cluster it may destroy repeatedly.
import argparse
import json
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

DIR_HERE = Path(__file__).resolve().parent
# The settings sit at the repository root; the scripts and values this
# sweep drives are the BaseNet Validator's, one directory across.
DIR_ROOT = DIR_HERE.parent
DIR_VALIDATOR = DIR_ROOT / "validator"
LST_CONF = [DIR_ROOT / "basenet.conf", DIR_ROOT / "basenet.conf.local"]
PAT_ASSIGN = re.compile(r"^([A-Z_][A-Z0-9_]*)=(.*)$")
PAT_MEM = re.compile(r"^(\d+)(Mi|Gi|M|G)?$")
N_POLL_S = 10
N_GRACE_S = 90
STR_HELM_RELEASE_ANN = "meta.helm.sh/release-name"


# One component: where its memory lives in a values file, and which release
# and workload it turns into on the cluster.
#
# THIS IS A FOURTH COPY OF PART OF THE RELEASE TABLE - AGENTS.md names the
# three in install.sh, render.sh and images.sh as the repository's one
# duplication and the thing most likely to drift. A chart added there must be
# added here too, or the sweep silently leaves it at its chart default.
class Comp:

    def __init__(self, strName, strRelease, strValues, lstPath, strHint=None):
        self.strName = strName
        self.strRelease = strRelease
        self.strValues = strValues
        self.lstPath = lstPath
        self.strHint = strHint
        self.nStd = 0
        self.nReq = 0
        self.nMem = 0
        self.nRss = None
        self.nGood = None
        self.nFail = None
        self.flagDone = False
        self.flagHold = False


def lst_components():
    return [
        Comp("participant", "participant", "participant.yaml", []),
        Comp("scan", "scan", "scan.yaml", []),
        Comp("sv", "sv", "sv.yaml", []),
        Comp("validator", "validator", "validator.yaml", []),
        Comp("sequencer", "global-domain-0", "global-domain.yaml", ["sequencer"], "sequencer"),
        Comp("mediator", "global-domain-0", "global-domain.yaml", ["mediator"], "mediator"),
        Comp("participant-pg", "participant-pg", "postgres-participant.yaml", []),
        Comp("sequencer-pg", "sequencer-pg", "postgres-sequencer.yaml", []),
        Comp("mediator-pg", "mediator-pg", "postgres-mediator.yaml", []),
        Comp("apps-pg", "apps-pg", "postgres-apps.yaml", []),
    ]


def say(strMsg):
    print(time.strftime("%H:%M:%S ") + strMsg, flush=True)


def map_settings():
    mapOut = {}
    for fileConf in LST_CONF:
        if not fileConf.exists():
            continue
        for strLine in fileConf.read_text(encoding="utf-8").splitlines():
            match = PAT_ASSIGN.match(strLine.strip())
            if not match:
                continue
            strValue = match.group(2).strip()
            if len(strValue) >= 2 and strValue[0] == strValue[-1] and strValue[0] in "\"'":
                strValue = strValue[1:-1]
            mapOut[match.group(1)] = strValue
    return mapOut


# The values files are nested mappings of scalars and comments and nothing
# else - no lists, no anchors, no block scalars - so this reads them without
# a YAML library. It raises on anything it does not recognise rather than
# guessing, because a silently misread request would set the whole sweep off
# from the wrong baseline.
def map_yaml(fileIn):
    mapRoot = {}
    lstStack = [(-1, mapRoot)]
    for nLine, strRaw in enumerate(fileIn.read_text(encoding="utf-8").splitlines(), 1):
        strLine = strRaw.split(" #")[0].rstrip() if " #" in strRaw else strRaw.rstrip()
        if not strLine.strip() or strLine.lstrip().startswith("#"):
            continue
        nIndent = len(strLine) - len(strLine.lstrip())
        if ":" not in strLine:
            raise SystemExit("%s:%d: not a mapping line: %s" % (fileIn.name, nLine, strRaw))
        strKey, _, strValue = strLine.strip().partition(":")
        strValue = strValue.strip()
        while lstStack and nIndent <= lstStack[-1][0]:
            lstStack.pop()
        if not lstStack:
            raise SystemExit("%s:%d: indentation runs out: %s" % (fileIn.name, nLine, strRaw))
        mapHere = lstStack[-1][1]
        if strValue == "":
            mapNew = {}
            mapHere[strKey] = mapNew
            lstStack.append((nIndent, mapNew))
        else:
            if len(strValue) >= 2 and strValue[0] == strValue[-1] and strValue[0] in "\"'":
                strValue = strValue[1:-1]
            mapHere[strKey] = strValue
    return mapRoot


def n_mebibytes(strMem):
    match = PAT_MEM.match(strMem.strip())
    if not match:
        raise SystemExit("memory value not understood: " + strMem)
    nVal = int(match.group(1))
    strUnit = match.group(2) or "Mi"
    if strUnit in ("Gi", "G"):
        return nVal * 1024
    return nVal


def n_round16(nMem):
    return max(64, int(nMem) // 16 * 16)


# STD is the LIMIT. The request is read too, and only reported: nothing is
# OOMKilled for exceeding a request, and MaxRAMPercentage is a percentage of
# the limit. A values file that sets no memory limit raises here rather than
# being guessed at - there would be no ceiling to descend from.
def read_std(lstComp):
    for comp in lstComp:
        mapVals = map_yaml(DIR_VALIDATOR / "values" / comp.strValues)
        mapAt = mapVals
        for strKey in comp.lstPath:
            mapAt = mapAt[strKey]
        mapRes = mapAt["resources"]
        if "limits" not in mapRes or "memory" not in mapRes["limits"]:
            raise SystemExit("%s sets no memory limit for %s" % (comp.strValues, comp.strName))
        comp.nStd = n_mebibytes(mapRes["limits"]["memory"])
        comp.nReq = n_mebibytes(mapRes["requests"]["memory"])
        comp.nMem = comp.nStd


# One overlay file per RELEASE, so the two components of global-domain-0 land
# in one file. Emitted by hand: the shape is three levels of mapping and a
# YAML library is not a dependency this repository has.
def write_overlays(dirOut, lstComp):
    mapRelease = {}
    for comp in lstComp:
        mapRelease.setdefault(comp.strRelease, []).append(comp)
    for strRelease, lstOne in mapRelease.items():
        lstLine = ["# Generated by memsweep.py - one run's memory, never committed."]
        for comp in lstOne:
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
        (dirOut / (strRelease + ".yaml")).write_text("\n".join(lstLine) + "\n", encoding="utf-8")


def run(lstArg, flagCheck=True):
    proc = subprocess.run(lstArg, capture_output=True, text=True)
    if flagCheck and proc.returncode != 0:
        raise SystemExit(" ".join(lstArg) + ": " + (proc.stderr.strip() or proc.stdout.strip()))
    return proc


def map_json(strNs, strKind):
    proc = run(["kubectl", "-n", strNs, "get", strKind, "-o", "json"], flagCheck=False)
    if proc.returncode != 0:
        return {"items": []}
    return json.loads(proc.stdout)


# Pod -> release, through the workload that owns it and the annotation Helm 3
# writes on everything it installs. Not through pod names: those are the
# charts' to change.
def map_owners(strNs):
    mapOut = {}
    for strKind in ("replicasets", "statefulsets", "deployments", "jobs"):
        for mapItem in map_json(strNs, strKind).get("items", []):
            mapMeta = mapItem.get("metadata", {})
            mapOut[(mapItem.get("kind", ""), mapMeta.get("name", ""))] = mapMeta
    return mapOut


def str_release_of(mapPod, mapOwners):
    lstRef = mapPod.get("metadata", {}).get("ownerReferences", [])
    strName = ""
    for mapRef in lstRef:
        mapMeta = mapOwners.get((mapRef.get("kind", ""), mapRef.get("name", "")))
        if mapMeta is None:
            continue
        strName = mapMeta.get("name", "")
        strRelease = mapMeta.get("annotations", {}).get(STR_HELM_RELEASE_ANN, "")
        if strRelease:
            return strRelease, strName
        # A ReplicaSet carries the Deployment's annotations only sometimes;
        # walk one more step when it does not.
        for mapUp in mapMeta.get("ownerReferences", []):
            mapMetaUp = mapOwners.get((mapUp.get("kind", ""), mapUp.get("name", "")))
            if mapMetaUp is None:
                continue
            strRelease = mapMetaUp.get("annotations", {}).get(STR_HELM_RELEASE_ANN, "")
            if strRelease:
                return strRelease, mapMetaUp.get("name", strName)
    return "", strName


# One release plus one owner name -> one component, or None. The OOM path and
# the RSS path must agree, so they ask the same question here.
def comp_of(lstComp, strRelease, strOwner):
    lstHit = [comp for comp in lstComp if comp.strRelease == strRelease and
              (comp.strHint is None or comp.strHint in strOwner)]
    if len(lstHit) == 1:
        return lstHit[0]
    return None


def lst_oomkilled(strNs, mapOwners):
    lstOut = []
    for mapPod in map_json(strNs, "pods").get("items", []):
        mapStatus = mapPod.get("status", {})
        lstStat = (mapStatus.get("containerStatuses", []) or []) + \
                  (mapStatus.get("initContainerStatuses", []) or [])
        flagOom = False
        for mapStat in lstStat:
            for mapWhere in (mapStat.get("state", {}), mapStat.get("lastState", {})):
                if mapWhere.get("terminated", {}).get("reason") == "OOMKilled":
                    flagOom = True
        if not flagOom:
            continue
        strRelease, strOwner = str_release_of(mapPod, mapOwners)
        lstOut.append((mapPod["metadata"]["name"], strRelease, strOwner))
    return lstOut


def n_expected_pods(strNs):
    nOut = 0
    for strKind in ("deployments", "statefulsets"):
        for mapItem in map_json(strNs, strKind).get("items", []):
            nOut += int(mapItem.get("spec", {}).get("replicas", 0) or 0)
    return nOut


def n_ready_pods(strNs):
    nOut = 0
    for mapPod in map_json(strNs, "pods").get("items", []):
        mapStatus = mapPod.get("status", {})
        if mapStatus.get("phase") == "Succeeded":
            continue
        for mapCond in mapStatus.get("conditions", []) or []:
            if mapCond.get("type") == "Ready" and mapCond.get("status") == "True":
                nOut += 1
    return nOut


# One install, watched. Returns (flagReady, lstOom, strNote). The watch runs
# THROUGH the install, so a PostgreSQL that OOM-loops does not cost the ten
# minutes install.sh's --wait would otherwise spend on it.
def one_run(strNs, dirOverlay, nInstallS, nReadyS):
    lstCmd = ["./install.sh"]
    if dirOverlay is not None:
        lstCmd += ["--overlay", str(dirOverlay)]
    say("install   " + " ".join(lstCmd))
    procInst = subprocess.Popen(lstCmd, cwd=str(DIR_VALIDATOR),
                                stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    nStart = time.time()
    nInstallEnd = None
    nOomAt = None
    lstOom = []
    mapOwners = {}
    while True:
        time.sleep(N_POLL_S)
        if procInst.poll() is not None and nInstallEnd is None:
            nInstallEnd = time.time()
            say("install   returned rc=%d after %ds" % (procInst.returncode, nInstallEnd - nStart))
        mapOwners = map_owners(strNs) or mapOwners
        lstSeen = lst_oomkilled(strNs, mapOwners)
        if lstSeen:
            lstOom = lstSeen
            if nOomAt is None:
                nOomAt = time.time()
                say("OOMKilled " + ", ".join(str_oom(one) for one in lstSeen))
        nExpect = n_expected_pods(strNs)
        nReady = n_ready_pods(strNs)
        if nInstallEnd is not None and nExpect > 0 and nReady >= nExpect and not lstOom:
            say("ready     %d/%d pods, %ds" % (nReady, nExpect, time.time() - nStart))
            return True, [], ""
        if nOomAt is not None and time.time() - nOomAt > N_GRACE_S:
            end_install(procInst)
            return False, lstOom, "OOMKilled"
        if nInstallEnd is None and time.time() - nStart > nInstallS:
            end_install(procInst)
            return False, lstOom, "install did not return within %ds" % nInstallS
        if nInstallEnd is not None and time.time() - nInstallEnd > nReadyS:
            return False, lstOom, "only %d of %d pods Ready within %ds" % (nReady, nExpect, nReadyS)


def str_oom(tplOom):
    return "%s (release %s, %s)" % (tplOom[0], tplOom[1] or "?", tplOom[2] or "?")


def end_install(procInst):
    if procInst.poll() is None:
        procInst.terminate()
        try:
            procInst.wait(timeout=60)
        except subprocess.TimeoutExpired:
            procInst.kill()


def flag_smoke():
    proc = subprocess.run(["./smoke.sh"], cwd=str(DIR_VALIDATOR), capture_output=True, text=True)
    for strLine in proc.stdout.splitlines()[-3:]:
        say("smoke     " + strLine.strip())
    return proc.returncode == 0


def lst_top(strNs):
    proc = run(["kubectl", "-n", strNs, "top", "pods", "--no-headers"], flagCheck=False)
    if proc.returncode != 0:
        return []
    return [strLine.split() for strLine in proc.stdout.splitlines() if strLine.strip()]


# What each component's pod actually had resident, attributed through the Helm
# annotation and not through pod names. Web UIs are skipped: the sweep does not
# size those. Returns {component name: Mi} for whatever it could attribute.
def map_rss(strNs, lstComp, mapOwners):
    mapPodRel = {}
    for mapPod in map_json(strNs, "pods").get("items", []):
        strName = mapPod.get("metadata", {}).get("name", "")
        mapPodRel[strName] = str_release_of(mapPod, mapOwners)
    mapOut = {}
    for lstRow in lst_top(strNs):
        if len(lstRow) < 3 or "-web-ui" in lstRow[0]:
            continue
        strRelease, strOwner = mapPodRel.get(lstRow[0], ("", ""))
        comp = comp_of(lstComp, strRelease, strOwner)
        if comp is None:
            continue
        nMem = n_mebibytes(lstRow[2])
        if nMem > mapOut.get(comp.strName, 0):
            mapOut[comp.strName] = nMem
    return mapOut


# The same figures out of an earlier sweep's runs.jsonl, where the cluster is
# gone and only pod names survive. Longest matching prefix wins, so
# participant-pg-0 goes to participant-pg and not to participant. The prefix is
# derived from the release and the hint the component already carries - this
# file is not gaining a second name table.
def map_rss_file(fileSeed, lstComp):
    mapRow = None
    for strLine in fileSeed.read_text(encoding="utf-8").splitlines():
        if not strLine.strip():
            continue
        mapOne = json.loads(strLine)
        if mapOne.get("std") and mapOne.get("green") and mapOne.get("top"):
            mapRow = mapOne
    if mapRow is None:
        raise SystemExit("no green run 0 with a top snapshot in " + str(fileSeed))
    lstPrefix = []
    for comp in lstComp:
        strPrefix = comp.strRelease + "-" + (comp.strHint + "-" if comp.strHint else "")
        lstPrefix.append((strPrefix, comp))
    mapOut = {}
    for lstRow in mapRow["top"]:
        if len(lstRow) < 3 or "-web-ui" in lstRow[0]:
            continue
        compBest = None
        nBest = -1
        for strPrefix, comp in lstPrefix:
            if lstRow[0].startswith(strPrefix) and len(strPrefix) > nBest:
                compBest = comp
                nBest = len(strPrefix)
        if compBest is None:
            say("seed      no component owns pod %s - ignored" % lstRow[0])
            continue
        nMem = n_mebibytes(lstRow[2])
        if nMem > mapOut.get(compBest.strName, 0):
            mapOut[compBest.strName] = nMem
    return mapOut


# 1.25x what it used, and never more than 3/4 of STD so run 1 always descends.
def seed(lstComp, mapRss, flagHold):
    for comp in lstComp:
        nRss = mapRss.get(comp.strName)
        if nRss is None:
            say("seed      %s was not measured - halving from STD instead" % comp.strName)
            continue
        comp.nRss = nRss
        comp.nMem = max(64, min(n_round16(nRss * 1.25), n_round16(comp.nStd * 0.75)))
        # On the run-0 path a step() stands between this and the run that uses
        # the seed, and the hold is what carries the seed past it. On the
        # --seed path there is no such step(), and setting the hold here would
        # swallow the component's first real move instead - the failing
        # component would repeat its value for one whole cycle, and every other
        # component would repeat with it.
        comp.flagHold = flagHold
        say("seed      %s %d Mi resident at STD -> %d Mi" % (comp.strName, nRss, comp.nMem))


def reset():
    say("teardown")
    subprocess.run(["./teardown.sh"], cwd=str(DIR_VALIDATOR),
                   stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    subprocess.run(["./secrets.sh"], cwd=str(DIR_VALIDATOR),
                   stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)


def step(lstComp, nFloor):
    for comp in lstComp:
        if comp.flagDone:
            comp.nMem = comp.nGood
            continue
        # Collateral, or a run nothing could be blamed for: the same value is
        # tried again rather than descending on evidence that is not there.
        if comp.flagHold:
            comp.flagHold = False
            continue
        if comp.nFail is None:
            comp.nMem = n_round16(comp.nMem * 0.5)
            continue
        if comp.nGood is None:
            comp.nMem = n_round16(comp.nMem * 1.5)
            continue
        if comp.nGood - comp.nFail <= nFloor:
            comp.flagDone = True
            comp.nMem = comp.nGood
            continue
        # STD is a ceiling, not a near miss, and nGood starts there. Bisecting
        # against it after a failure spends runs far above anything that will
        # be kept, so the first recovery is the operator's 150 % of the failing
        # value - which is what the header has always said and what an
        # unconditional midpoint made unreachable.
        if comp.nGood > comp.nFail * 2:
            comp.nMem = n_round16(comp.nFail * 1.5)
            continue
        comp.nMem = n_round16((comp.nGood + comp.nFail) / 2)


def str_table(lstComp):
    lstLine = ["| component | STD limit Mi | request Mi | resident Mi | floor Mi | of STD | last fail Mi |",
               "| --- | --- | --- | --- | --- | --- | --- |"]
    nStd = 0
    nReq = 0
    nFloor = 0
    for comp in lstComp:
        nStd += comp.nStd
        nReq += comp.nReq
        nBest = comp.nGood if comp.nGood is not None else comp.nStd
        nFloor += nBest
        lstLine.append("| `%s` | %d | %d | %s | %d | %d %% | %s |" %
                       (comp.strName, comp.nStd, comp.nReq,
                        comp.nRss if comp.nRss is not None else "-",
                        nBest, round(100.0 * nBest / comp.nStd),
                        comp.nFail if comp.nFail is not None else "-"))
    lstLine.append("| **total** | **%d** | **%d** | | **%d** | **%d %%** | |" %
                   (nStd, nReq, nFloor, round(100.0 * nFloor / nStd)))
    return "\n".join(lstLine)


def main():
    parser = argparse.ArgumentParser(description="the smallest memory BaseNet Validator starts on")
    parser.add_argument("--deadline", type=int, default=600,
                        help="seconds from install.sh returning to every pod Ready (default 600)")
    parser.add_argument("--install-deadline", type=int, default=900,
                        help="seconds install.sh itself may take (default 900)")
    parser.add_argument("--floor", type=int, default=128,
                        help="stop bisecting a component under this many Mi (default 128)")
    parser.add_argument("--max-runs", type=int, default=30, help="give up after this many runs")
    parser.add_argument("--out", default="", help="where the log goes (default /tmp/memsweep-<stamp>)")
    parser.add_argument("--seed", default="",
                        help="an earlier sweep's runs.jsonl: take the seeds from its green "
                             "run 0 and skip run 0 (default: measure it)")
    parser.add_argument("--dry-run", action="store_true",
                        help="print the plan and the first overlay set, touch no cluster")
    args = parser.parse_args()

    mapSet = map_settings()
    strNs = mapSet.get("STR_NAMESPACE", "sv")
    lstComp = lst_components()
    read_std(lstComp)

    strStamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    dirOut = Path(args.out) if args.out else Path("/tmp/memsweep-" + strStamp)
    dirOut.mkdir(parents=True, exist_ok=True)
    dirOverlay = dirOut / "overlay"
    dirOverlay.mkdir(exist_ok=True)
    fileLog = dirOut / "runs.jsonl"

    say("namespace %s, %d components, STD %d Mi of limit (%d Mi requested), log %s" %
        (strNs, len(lstComp), sum(comp.nStd for comp in lstComp),
         sum(comp.nReq for comp in lstComp), dirOut))

    if args.seed:
        for comp in lstComp:
            comp.nGood = comp.nStd
        seed(lstComp, map_rss_file(Path(args.seed), lstComp), False)

    if args.dry_run:
        write_overlays(dirOverlay, lstComp)
        for fileOne in sorted(dirOverlay.iterdir()):
            print("===== " + fileOne.name)
            print(fileOne.read_text(encoding="utf-8"), end="")
        print(str_table(lstComp))
        return 0

    nRun = 1 if args.seed else 0
    cntBlind = 0
    while nRun < args.max_runs:
        flagStd = nRun == 0
        if flagStd:
            for comp in lstComp:
                comp.nMem = comp.nStd
        else:
            write_overlays(dirOverlay, lstComp)
        say("=== run %d: %s" % (nRun, ", ".join("%s=%d" % (comp.strName, comp.nMem) for comp in lstComp)))
        reset()
        flagReady, lstOom, strNote = one_run(strNs, None if flagStd else dirOverlay,
                                             args.install_deadline, args.deadline)
        flagGreen = flagReady and flag_smoke()
        lstTop = lst_top(strNs) if flagGreen else []
        mapRss = map_rss(strNs, lstComp, map_owners(strNs)) if flagGreen else {}

        setFail = set()
        for tplOom in lstOom:
            if "-web-ui" in tplOom[2]:
                say("ignored   a web UI was OOMKilled, and the sweep does not size those: " + str_oom(tplOom))
                continue
            compHit = comp_of(lstComp, tplOom[1], tplOom[2])
            if compHit is None:
                say("!!! cannot attribute an OOMKill to one component: " + str_oom(tplOom))
                return 2
            setFail.add(compHit.strName)

        with fileLog.open("a", encoding="utf-8") as fileOne:
            fileOne.write(json.dumps({
                "run": nRun, "std": flagStd, "green": flagGreen, "note": strNote,
                "mem": {comp.strName: comp.nMem for comp in lstComp},
                "failed": sorted(setFail),
                "oom": [str_oom(one) for one in lstOom],
                "top": lstTop,
                "rss": mapRss,
            }) + "\n")

        if flagStd:
            if not flagGreen:
                say("!!! the baseline is not green: " + (strNote or "smoke failed") +
                    ". Nothing was measured; fix that first.")
                return 1
            for comp in lstComp:
                comp.nGood = comp.nStd
            say("baseline  green at STD, %d Mi of limit" % sum(comp.nStd for comp in lstComp))
            seed(lstComp, mapRss, True)
        elif setFail:
            cntBlind = 0
            for comp in lstComp:
                if comp.strName in setFail:
                    comp.nFail = comp.nMem
                elif not comp.flagDone:
                    comp.flagHold = True
                    say("collateral %s stays at %d Mi" % (comp.strName, comp.nMem))
        elif flagGreen:
            cntBlind = 0
            for comp in lstComp:
                if not comp.flagDone:
                    comp.nGood = comp.nMem
        else:
            cntBlind += 1
            for comp in lstComp:
                comp.flagHold = not comp.flagDone
            say("blind     %s, and no OOMKill to blame - repeating" % (strNote or "smoke failed"))
            if cntBlind >= 2:
                say("!!! two runs failed with no OOMKill. Stopping; read " + str(fileLog))
                return 1

        step(lstComp, args.floor)
        nRun += 1
        if all(comp.flagDone for comp in lstComp):
            break

    print()
    print(str_table(lstComp))
    (dirOut / "result.md").write_text(str_table(lstComp) + "\n", encoding="utf-8")
    say("done in %d runs; %s" % (nRun, dirOut))
    return 0


if __name__ == "__main__":
    sys.exit(main())
