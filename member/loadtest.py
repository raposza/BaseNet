#!/usr/bin/env python3
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
#
# loadtest.py - a moderate, paced, concurrent workload on a BaseNet Member's
# own participant, over the JSON Ledger API.
#
#   python3 loadtest.py [--ns <namespace>] [--dar <file>] [--clients <n>]
#                       [--rate <cycles/s>] [--for <s>] [--port <local>]
#                       [--setup-only] [--out <file>]
#
# WHY IT EXISTS. Every memory figure in basenet_memory.md was taken on an IDLE
# network: no DAR beyond the charts' own, no transaction. The sizing of D-731
# is therefore unproven under load - todo.md A-24. Run this with jvm_watch.py
# alongside it and the peak column is the number that sizing has to survive.
#
# WHAT IT DOES NOT DO. It does not measure memory. jvm_watch.py does that, from
# outside, and the two are deliberately separate processes: a driver that also
# probed its target would be one clock away from D-725's probe that OOMKilled
# what it measured.
#
# THE WORKLOAD is the pet shop fixture, four transactions per cycle, chosen
# because each exercises a different ledger path rather than to tell a story:
#
#   1. create Pet                      - a plain create, one signatory
#   2. create AdoptionRequest          - a create by another party, with ensure
#   3. exercise Approve                - the interesting one: a consuming choice
#                                        that FETCHES across templates, exercises
#                                        a second choice and creates two contracts
#   4. exercise BookCheckup            - a NONCONSUMING choice, so the ACS grows
#                                        without the parent being archived
#
# Every cycle allocates nothing and reuses its client's party, so the ACS grows
# monotonically for the length of the run. That is deliberate: a workload whose
# ACS is flat exercises none of the memory this test exists to find.
#
# AUTHENTICATION IS THE PRODUCTION PATH, not a back door. The token is minted
# by jwtmint - the same RS256 provider the participant validates against - for
# the participant's OWN admin user, read from the Kubernetes secret rather than
# assumed, with the audience the participant requires. See docs/auth.md.
#
# IT IS MINTED ONCE AND NEVER REFRESHED, so its lifetime is the run length plus
# an hour, never less than ten hours. A token that expires mid-run turns every
# later submission into a 401 and reads as a participant fault.
#
# EVERY PARTY IS ALLOCATED WITH userId SET to that admin user, which is what
# grants it act_as. Nothing here grants a right by a separate call.
#
# THE API IS JSON LEDGER API v2 on the participant's 7575, reached through a
# kubectl port-forward this script starts, RESTARTS WHEN IT DIES, and kills.
# A forward binds to a pod, so a rollout kills it and every later request is
# refused locally in a millisecond - see Forward. Nothing is installed and
# no cluster object is created beyond the parties and the uploaded DAR. The
# endpoint shapes are from the participant's own /docs/openapi, Canton 3.5.14 -
# not from memory.
#
# THE DAR IS BUILT BY petshop/build.sh, not by this script:
#
#   ./petshop/build.sh 3.4.11
#
# 3.4.11 is a 3.4 SDK against a 3.5.14 participant, which is measured to work -
# three versions with no compiler have run a DAR built
# by 3.4.11, and the participant reports LF 2.1 among its supported versions.
#
# Needs kubectl and a cluster with the namespace up, and jwtmint running.
import argparse
import base64
import json
import random
import socket
import statistics
import string
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "shared"))
from memsweep import map_settings

DIR_FIXTURE = Path.home() / ".raposza" / "fixtures" / "petshop"
STR_SECRET = "splice-app-validator-ledger-api-auth"
STR_KEY_USER = "ledger-api-user"
N_TTL = 36000
N_TTL_MARGIN = 3600
N_SEC_FORWARD = 20
LST_SPECIES = ["Dog", "Cat", "Parrot", "Rabbit", "Hamster"]

lockSeq = threading.Lock()
lockRow = threading.Lock()
lockFwd = threading.Lock()


def say(strMsg):
    print(strMsg, flush=True)


def str_run(lstCmd, strWhat):
    """a command's stdout, or a clean exit naming what failed

    @param lstCmd the argv
    @param strWhat what it was for, for the error
    @return stdout, stripped
    """
    proc = subprocess.run(lstCmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise SystemExit("%s: rc=%d\n%s" % (strWhat, proc.returncode,
                                            proc.stderr.strip()[:400]))
    return proc.stdout.strip()


def str_setting(mapSet, strKey, strMemberOrg):
    """a per-MemberOrg setting, by memberorg-common.sh's rule

    The setting named with the MemberOrg's name appended - capitals, dashes as
    underscores - wins when it is set; otherwise the general one. %s in the
    value becomes the MemberOrg's name.

    @param mapSet basenet.conf and its .local overlay
    @param strKey the general key
    @param strMemberOrg the MemberOrg name
    @return the resolved value
    """
    strSuffix = strMemberOrg.upper().replace("-", "_")
    strVal = mapSet.get(strKey + "_" + strSuffix, "") or mapSet.get(strKey, "")
    return strVal.replace("%s", strMemberOrg)


def str_admin_user(strNs):
    """the ledger user the participant treats as its administrator

    Read from the secret values/participant.yaml names in
    participantAdminUserNameFrom, so this cannot drift from what was installed.

    @param strNs the namespace
    @return the user id
    """
    strB64 = str_run(["kubectl", "-n", strNs, "get", "secret", STR_SECRET,
                      "-o", "jsonpath={.data.%s}" % STR_KEY_USER],
                     "reading " + STR_SECRET)
    if not strB64:
        raise SystemExit("secret %s has no %s - is the MemberOrg installed?"
                         % (STR_SECRET, STR_KEY_USER))
    return base64.b64decode(strB64).decode("utf-8").strip()


def file_dar(strGiven):
    """the DAR to upload, and its package id

    @param strGiven an explicit path, or "" to take the newest 3.4.11 build
    @return (Path, package id)
    """
    if strGiven:
        fileDar = Path(strGiven)
        dirEntry = fileDar.parent
    else:
        lstDir = sorted(DIR_FIXTURE.glob("3.4.11-*"))
        if not lstDir:
            raise SystemExit(
                "no 3.4.11 build under %s - build it first:\n"
                "    ./petshop/build.sh 3.4.11"
                % DIR_FIXTURE)
        dirEntry = lstDir[0]
        lstDar = sorted(dirEntry.glob("*.dar"))
        if not lstDar:
            raise SystemExit("no .dar in " + str(dirEntry))
        fileDar = lstDar[0]
    if not fileDar.is_file():
        raise SystemExit("no such DAR: " + str(fileDar))
    fileJson = dirEntry / "dar.json"
    if not fileJson.is_file():
        raise SystemExit("no dar.json beside %s - build.sh writes the"
                         " package id and this script will not guess it"
                         % fileDar)
    strPkg = json.loads(fileJson.read_text(encoding="utf-8")).get("packageId", "")
    if not strPkg:
        raise SystemExit("dar.json carries no packageId: " + str(fileJson))
    return fileDar, strPkg


class Forward:
    """the kubectl port-forward, restarted when it dies

    A forward binds to a POD, not to the service's endpoint set, so it dies
    when its target is replaced and every later request arrives as
    `Connection refused` in about a millisecond. Measured 2026-09-13: both
    Members had been rolled two minutes before a run started, both forwards
    died, and the drivers went on firing into closed local ports for the rest
    of the run - 452 successes and then 30 failures every 30 s. The driver
    could not tell that from a ledger refusing work.
    """

    def __init__(self, strNs, nPort):
        self.strNs = strNs
        self.nPort = nPort
        self.proc = None
        self.cntStart = 0

    def start(self):
        """spawn one forward and wait for the local port to accept

        @return True when the port answers within N_SEC_FORWARD
        """
        self.stop()
        self.proc = subprocess.Popen(
            ["kubectl", "-n", self.strNs, "port-forward", "svc/participant",
             "%d:7575" % self.nPort],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.cntStart += 1
        nDeadline = time.time() + N_SEC_FORWARD
        while time.time() < nDeadline:
            if self.proc.poll() is not None:
                return False
            try:
                with socket.create_connection(("127.0.0.1", self.nPort), 1):
                    return True
            except OSError:
                time.sleep(0.25)
        return False

    def heal(self):
        """restart the forward, once, for a caller that saw a refusal

        @return True when a forward is up again
        """
        with lockFwd:
            try:
                with socket.create_connection(("127.0.0.1", self.nPort), 1):
                    return True
            except OSError:
                pass
            return self.start()

    def stop(self):
        if self.proc is None:
            return
        self.proc.terminate()
        try:
            self.proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        self.proc = None


class Api:
    """the JSON Ledger API v2, over a forward that may be restarted"""

    def __init__(self, strBase, strToken, fwd=None):
        self.strBase = strBase
        self.strToken = strToken
        self.fwd = fwd

    def call(self, strPath, dataBody=None, strType="application/json",
             nTimeout=60):
        """one request, retried once when the forward has gone

        @param strPath the path, from /v2 on
        @param dataBody the body as bytes, or None for a GET
        @param strType the content type of the body
        @param nTimeout seconds
        @return the parsed response, or {} when the body is empty
        """
        try:
            return self.once(strPath, dataBody, strType, nTimeout)
        except urllib.error.URLError as err:
            # A REFUSED CONNECTION IS THE TUNNEL, NOT THE LEDGER. Anything the
            # participant itself answers arrives as an HTTPError, which `once`
            # has already turned into a RuntimeError and is not caught here.
            if self.fwd is None or not isinstance(err.reason, OSError):
                raise
            if not self.fwd.heal():
                raise
            return self.once(strPath, dataBody, strType, nTimeout)

    def once(self, strPath, dataBody, strType, nTimeout):
        """the request itself, with Canton's reason kept

        @param strPath the path, from /v2 on
        @param dataBody the body as bytes, or None for a GET
        @param strType the content type of the body
        @param nTimeout seconds
        @return the parsed response, or {} when the body is empty
        """
        req = urllib.request.Request(self.strBase + strPath, data=dataBody)
        req.add_header("Authorization", "Bearer " + self.strToken)
        if dataBody is not None:
            req.add_header("Content-Type", strType)
        # THE BODY IS THE MESSAGE. Canton answers a rejected request with 400
        # and puts the reason in the body; urlopen raises before anything reads
        # it, so an unwrapped HTTPError arrives as a bare "400: Bad Request"
        # and names nothing. Measured 2026-09-13 on a re-allocated party hint.
        try:
            with urllib.request.urlopen(req, timeout=nTimeout) as resp:
                strOut = resp.read().decode("utf-8")
        except urllib.error.HTTPError as err:
            strBody = ""
            try:
                strBody = err.read().decode("utf-8")
            except Exception:
                pass
            raise RuntimeError("HTTP %d on %s: %s"
                               % (err.code, strPath, strBody[:400])) from None
        return json.loads(strOut) if strOut.strip() else {}

    def json(self, strPath, mapBody, nTimeout=60):
        return self.call(strPath,
                         json.dumps(mapBody).encode("utf-8"),
                         nTimeout=nTimeout)


def str_error(err):
    """an HTTPError's body, which is where Canton puts the reason

    @param err the exception
    @return a short description
    """
    if isinstance(err, urllib.error.HTTPError):
        try:
            strBody = err.read().decode("utf-8")[:300]
        except Exception:
            strBody = ""
        return "HTTP %d %s" % (err.code, strBody)
    return type(err).__name__ + " " + str(err)[:200]


def wait_up(api, nSeconds):
    """block until the API answers, or give up

    @param api the client
    @param nSeconds how long to wait
    @return the participant's version string
    """
    nDeadline = time.time() + nSeconds
    errLast = None
    while time.time() < nDeadline:
        try:
            return api.call("/v2/version").get("version", "?")
        except Exception as err:
            errLast = err
            time.sleep(0.5)
    raise SystemExit("the JSON API never answered: " + str_error(errLast))


def str_party(api, strHint, strUser):
    """allocate one party, with act_as granted to the admin user

    THE HINT CARRIES A PER-RUN SUFFIX, so a second run allocates its own
    parties rather than colliding with the first. Canton refuses a hint whose
    party already exists on the participant, which is what a bare "PetShop"
    hit on the second run - and reusing the existing party instead would need
    the user's act_as right checked separately, where allocation grants it in
    the one call.

    @param api the client
    @param strHint the party id hint, already suffixed
    @param strUser the user that gets act_as
    @return the allocated party id
    """
    mapOut = api.json("/v2/parties",
                      {"partyIdHint": strHint, "userId": strUser})
    strParty = mapOut.get("partyDetails", {}).get("party", "")
    if not strParty:
        raise SystemExit("no party in the allocation response: "
                         + json.dumps(mapOut)[:300])
    return strParty


def lst_created(mapTx, strSuffix):
    """the contract ids created by a transaction, for one template

    @param mapTx the submit-and-wait-for-transaction response
    @param strSuffix the template id's tail, e.g. ":Main:Pet"
    @return the contract ids, in event order
    """
    lstOut = []
    for mapEvent in mapTx.get("transaction", {}).get("events", []):
        mapCreated = mapEvent.get("CreatedEvent")
        if mapCreated is None:
            continue
        if mapCreated.get("templateId", "").endswith(strSuffix):
            lstOut.append(mapCreated.get("contractId"))
    return lstOut


class Driver:
    """one run: its parties, its counters and its paced cycles"""

    def __init__(self, api, strPkg, strShop, strVet, nRate, nStart, nFor,
                 writerRow):
        self.api = api
        self.strPkg = strPkg
        self.strShop = strShop
        self.strVet = strVet
        self.nRate = nRate
        self.nStart = nStart
        self.nFor = nFor
        self.writerRow = writerRow
        self.nSeq = 0
        self.lstLatency = []
        self.cntOk = 0
        self.cntFail = 0
        self.mapFail = {}
        self.cntCycle = 0
        self.flagStop = False

    def str_tid(self, strEntity):
        return "%s:Main:%s" % (self.strPkg, strEntity)

    def n_next(self):
        """the next cycle's index, or -1 when the run is over

        @return the index
        """
        with lockSeq:
            if self.flagStop:
                return -1
            nAt = self.nSeq
            self.nSeq += 1
        return nAt

    def submit(self, strKind, lstActAs, lstCommand):
        """one transaction, timed and recorded

        @param strKind which of the four steps this is
        @param lstActAs the acting parties
        @param lstCommand the JsCommands commands array
        @return the response, or None when it failed
        """
        strId = "%s-%s" % (strKind, "".join(
            random.choice(string.ascii_lowercase + string.digits)
            for _ in range(12)))
        mapBody = {"commands": {"commandId": strId,
                                "actAs": lstActAs,
                                "commands": lstCommand}}
        nAt = time.time()
        try:
            mapOut = self.api.json(
                "/v2/commands/submit-and-wait-for-transaction", mapBody)
            nMs = round((time.time() - nAt) * 1000)
            with lockRow:
                self.cntOk += 1
                self.lstLatency.append(nMs)
                self.writerRow(nAt, strKind, nMs, "ok", "")
            return mapOut
        except Exception as err:
            nMs = round((time.time() - nAt) * 1000)
            strWhy = str_error(err)
            with lockRow:
                self.cntFail += 1
                strShort = strWhy.split("\n")[0][:80]
                self.mapFail[strShort] = self.mapFail.get(strShort, 0) + 1
                self.writerRow(nAt, strKind, nMs, "FAIL", strWhy[:200])
            return None

    def cycle(self, strBuyer, nAt):
        """the four transactions, in order, stopping at the first failure

        @param strBuyer this client's party
        @param nAt the cycle index, which names the animal
        """
        strName = "Pet-%06d" % nAt
        mapTx = self.submit("create-pet", [self.strShop], [
            {"CreateCommand": {
                "templateId": self.str_tid("Pet"),
                "createArguments": {"shop": self.strShop,
                                    "owner": self.strShop,
                                    "name": strName,
                                    "species": random.choice(LST_SPECIES)}}}])
        if mapTx is None:
            return
        lstPet = lst_created(mapTx, ":Main:Pet")
        if not lstPet:
            return

        mapTx = self.submit("create-request", [strBuyer], [
            {"CreateCommand": {
                "templateId": self.str_tid("AdoptionRequest"),
                "createArguments": {"shop": self.strShop,
                                    "buyer": strBuyer,
                                    "petName": strName,
                                    "price": "250.00",
                                    "note": ""}}}])
        if mapTx is None:
            return
        lstAsk = lst_created(mapTx, ":Main:AdoptionRequest")
        if not lstAsk:
            return

        mapTx = self.submit("approve", [self.strShop], [
            {"ExerciseCommand": {
                "templateId": self.str_tid("AdoptionRequest"),
                "contractId": lstAsk[0],
                "choice": "Approve",
                "choiceArgument": {"petCid": lstPet[0]}}}])
        if mapTx is None:
            return
        lstOwned = lst_created(mapTx, ":Main:Pet")
        if not lstOwned:
            return

        self.submit("checkup", [strBuyer], [
            {"ExerciseCommand": {
                "templateId": self.str_tid("Pet"),
                "contractId": lstOwned[0],
                "choice": "BookCheckup",
                "choiceArgument": {"vet": self.strVet,
                                   "notes": "Annual vaccination"}}}])

    def work(self, strBuyer):
        """one client thread, holding the run's aggregate rate

        @param strBuyer this client's party
        """
        while True:
            nAt = self.n_next()
            if nAt < 0:
                return
            nDue = self.nStart + nAt / float(self.nRate)
            while True:
                nNow = time.time()
                if nNow - self.nStart >= self.nFor:
                    with lockSeq:
                        self.flagStop = True
                    return
                if nNow >= nDue:
                    break
                time.sleep(min(0.25, nDue - nNow))
            self.cycle(strBuyer, nAt)
            with lockRow:
                self.cntCycle += 1


def main():
    parser = argparse.ArgumentParser(
        description="a paced concurrent workload on a BaseNet Member")
    parser.add_argument("--ns", default="memberorg-a",
                        help="the MemberOrg's namespace (default memberorg-a)")
    parser.add_argument("--dar", default="",
                        help="the DAR (default: the newest 3.4.11 build in"
                             " ~/.raposza/fixtures/petshop)")
    parser.add_argument("--clients", type=int, default=4,
                        help="concurrent buyers, each its own party (default 4)")
    parser.add_argument("--rate", type=float, default=2.0,
                        help="cycles per second across all clients, 4"
                             " transactions each (default 2)")
    parser.add_argument("--for", dest="nFor", type=int, default=600,
                        help="run length in seconds (default 600)")
    parser.add_argument("--port", type=int, default=7575,
                        help="local port for the forward (default 7575)")
    parser.add_argument("--setup-only", action="store_true",
                        help="upload the DAR and allocate the parties, then"
                             " stop - proves the path without load")
    parser.add_argument("--out", default="",
                        help="where the per-transaction CSV goes (default"
                             " /tmp/bn-load-<ns>-<stamp>.csv)")
    args = parser.parse_args()

    if args.clients < 1 or args.rate <= 0:
        raise SystemExit("--clients must be at least 1 and --rate positive")

    mapSet = map_settings()
    strIssuer = mapSet.get("STR_OIDC_BASE_URL", "")
    if not strIssuer or "example.internal" in strIssuer:
        raise SystemExit("STR_OIDC_BASE_URL is unset or still the placeholder"
                         " - set it in basenet.conf.local")
    strAud = str_setting(mapSet, "STR_MO_LEDGER_AUDIENCE", args.ns)
    if not strAud:
        raise SystemExit("no STR_MO_LEDGER_AUDIENCE for " + args.ns)

    fileDar, strPkg = file_dar(args.dar)
    strUser = str_admin_user(args.ns)
    say("namespace %s, admin user %s" % (args.ns, strUser))
    say("dar       %s" % fileDar)
    say("package   %s" % strPkg)

    strUrl = ("%s/mint.txt?sub=%s&aud=%s&alg=RS256&ttlSeconds=%d"
              % (strIssuer.rstrip("/"),
                 urllib.parse.quote(strUser), urllib.parse.quote(strAud),
                 max(N_TTL, args.nFor + N_TTL_MARGIN)))
    try:
        with urllib.request.urlopen(strUrl, timeout=15) as resp:
            strToken = resp.read().decode("utf-8").strip()
    except Exception as err:
        raise SystemExit("jwtmint did not mint a token at %s: %s"
                         % (strIssuer, str_error(err)))
    if strToken.count(".") != 2:
        raise SystemExit("that is not a JWT: " + strToken[:80])
    say("token     minted for %s, aud %s" % (strUser, strAud))

    fwd = Forward(args.ns, args.port)
    if not fwd.start():
        raise SystemExit("the port-forward to svc/participant in %s did not"
                         " come up on %d" % (args.ns, args.port))
    strStamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    strOut = args.out or ("/tmp/bn-load-%s-%s.csv" % (args.ns, strStamp))
    fileCsv = open(strOut, "w", encoding="utf-8")
    fileCsv.write("utc,step,ms,status,detail\n")

    def writerRow(nAt, strKind, nMs, strStatus, strWhy):
        fileCsv.write("%s,%s,%d,%s,%s\n" % (
            datetime.fromtimestamp(nAt, timezone.utc)
            .strftime("%Y-%m-%dT%H:%M:%SZ"),
            strKind, nMs, strStatus, strWhy.replace(",", ";").replace("\n", " ")))

    try:
        api = Api("http://127.0.0.1:%d" % args.port, strToken, fwd)
        say("version   participant %s" % wait_up(api, 30))

        nAt = time.time()
        api.call("/v2/dars?vetAllPackages=true",
                 fileDar.read_bytes(), "application/octet-stream", 300)
        say("uploaded  %s in %ds" % (fileDar.name, round(time.time() - nAt)))

        strRun = "".join(random.choice(string.ascii_lowercase + string.digits)
                         for _ in range(6))
        strShop = str_party(api, "PetShop-" + strRun, strUser)
        strVet = str_party(api, "Vet-" + strRun, strUser)
        lstBuyer = [str_party(api, "Buyer%d-%s" % (nOne + 1, strRun), strUser)
                    for nOne in range(args.clients)]
        say("parties   run %s - shop, vet and %d buyers allocated"
            % (strRun, len(lstBuyer)))

        if args.setup_only:
            say("")
            say("SETUP ONLY - nothing was submitted. The path is proven:"
                " token, upload, allocation.")
            return 0

        say("")
        say("RUN  %d clients, %.2f cycles/s, %d s - 4 transactions per cycle,"
            " so about %.1f tx/s" % (args.clients, args.rate, args.nFor,
                                     args.rate * 4))
        say("     start jvm_watch.py in another terminal if it is not already"
            " running")
        nStart = time.time()
        driver = Driver(api, strPkg, strShop, strVet, args.rate, nStart,
                        args.nFor, writerRow)
        lstThread = [threading.Thread(target=driver.work, args=(strOne,),
                                      daemon=True)
                     for strOne in lstBuyer]
        for thread in lstThread:
            thread.start()
        try:
            while any(thread.is_alive() for thread in lstThread):
                time.sleep(1)
                if round(time.time() - nStart) % 30 == 0:
                    say("  %4ds  %d cycles, %d ok, %d failed"
                        % (round(time.time() - nStart), driver.cntCycle,
                           driver.cntOk, driver.cntFail))
                    time.sleep(1)
        except KeyboardInterrupt:
            driver.flagStop = True
            say("  stopping")
        for thread in lstThread:
            thread.join(timeout=90)

        nRan = time.time() - nStart
        say("")
        say("| measure | value |")
        say("| --- | --- |")
        say("| cycles completed | %d |" % driver.cntCycle)
        say("| transactions ok | %d |" % driver.cntOk)
        say("| transactions FAILED | %d |" % driver.cntFail)
        say("| elapsed | %ds |" % round(nRan))
        say("| achieved | %.2f tx/s |" % (driver.cntOk / nRan if nRan else 0))
        if driver.lstLatency:
            lstSorted = sorted(driver.lstLatency)
            say("| latency median | %d ms |" % statistics.median(lstSorted))
            say("| latency p95 | %d ms |"
                % lstSorted[min(len(lstSorted) - 1,
                                int(len(lstSorted) * 0.95))])
            say("| latency max | %d ms |" % lstSorted[-1])
        for strWhy, cntOne in sorted(driver.mapFail.items(),
                                     key=lambda t: -t[1]):
            say("| FAILED x%d | %s |" % (cntOne, strWhy))
        say("")
        say("THE ACS WAS NEVER PRUNED and nothing was archived beyond what the"
            " choices archive, so the ledger this leaves behind grows with the"
            " run. Tear the MemberOrg down before taking an idle baseline.")
        say("rows      " + strOut)
    finally:
        fileCsv.close()
        fwd.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
