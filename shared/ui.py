#!/usr/bin/env python3
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
#
# ui.py - opens the vendor's web UIs of a running BaseNet in a browser.
# STAYS UP; Ctrl-C ends it and every port-forward it holds.
#
#   python3 ui.py              every namespace: BaseNet Validator, then each MemberOrg
#   python3 ui.py memberorg-a  that namespace only
#
# WHY A PROXY AND NOT A PORT-FORWARD. The web-ui pods serve static files and
# nothing else. Each page calls its backend on its OWN origin -
# `/api/validator`, `/api/sv`, `/api/scan` - and in a production deployment the
# ingress routes those paths to the backends. There is no ingress here, so
# this does what it would: one local port per namespace, routing by host name
# and path, with the pages and the APIs on one origin.
#
#   wallet.localhost:<port>   /api/validator -> validator-app, the rest -> wallet-web-ui
#   ans.localhost:<port>      /api/validator -> validator-app, the rest -> ans-web-ui
#   sv.localhost:<port>       /api/sv        -> sv-app,        the rest -> sv-web-ui
#   scan.localhost:<port>     /api/scan, /registry -> scan-app, the rest -> scan-web-ui
#
# A name under .localhost reaches this machine in Chromium, Brave and Firefox
# with no hosts-file entry, and a page there counts as a secure context, which
# the sign-in needs: PKCE hashes with the browser's Web Crypto API. The names
# are how the vendor's UIs find each other - the name-service UI finds the
# wallet by rewriting `ans` to `wallet` in its own address.
#
# ONE BYTE SEQUENCE IS CHANGED, in each config.js: the images write the API
# address as "https://" + window.location.host, which assumes TLS at an
# ingress. Served over http that address is wrong, so it becomes
# window.location.protocol + "//" + window.location.host. Everything else in
# config.js - the identity provider, the client, the audience - is the chart's,
# from the settings, untouched.
#
# The ports: STR_UI_PORT in basenet.conf for BaseNet Validator, the next ones for
# the MemberOrgs in name order. Backends are reached with kubectl port-forward,
# which a restarted pod ends; a dead forward is started again on the next
# request. Needs kubectl and python3.
import html
import http.client
import os
import re
import signal
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

DIR_HERE = Path(__file__).resolve().parent
DIR_ROOT = DIR_HERE.parent
LST_CONF = [DIR_ROOT / "basenet.conf", DIR_ROOT / "basenet.conf.local"]
PAT_ASSIGN = re.compile(r"^([A-Z_][A-Z0-9_]*)=(.*)$")
PAT_FORWARD = re.compile(r"^Forwarding from 127\.0\.0\.1:(\d+) -> ")
STR_LABEL = "basenet-member=true"
N_TIMEOUT_S = 60
N_FORWARD_WAIT_S = 30

# Hop-by-hop headers, and the ones this proxy sets itself. Accept-Encoding is
# dropped so a config.js arrives uncompressed and can be rewritten.
SET_HEADER_SKIP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
                   "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length",
                   "accept-encoding"}

ARR_HTTPS_HOST = b'"https://" + window.location.host'
ARR_SAME_HOST = b'window.location.protocol + "//" + window.location.host'

# vhost -> (web-ui service, [(path prefix, backend service, backend port)])
MAP_VHOST = {
    "wallet": ("wallet-web-ui", [("/api/validator", "validator-app", 5003)]),
    "ans": ("ans-web-ui", [("/api/validator", "validator-app", 5003)]),
    "sv": ("sv-web-ui", [("/api/sv", "sv-app", 5014)]),
    "scan": ("scan-web-ui", [("/api/scan", "scan-app", 5012), ("/registry", "scan-app", 5012)]),
}
LST_VHOST_ORDER = ["wallet", "ans", "sv", "scan"]
N_WEB_UI_PORT = 80


def say(strMsg):
    print(time.strftime("%H:%M:%S ") + strMsg, flush=True)


def map_settings():
    """the shell assignments of basenet.conf, then .local over them"""
    mapOut = {}
    for fileConf in LST_CONF:
        if not fileConf.is_file():
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


def lst_kubectl(lstArg):
    proc = subprocess.run(["kubectl"] + lstArg, capture_output=True, text=True)
    if proc.returncode != 0:
        raise SystemExit("kubectl " + " ".join(lstArg) + ": " + proc.stderr.strip())
    return proc.stdout.split()


class Forward:
    """one kubectl port-forward to a service, started again when it has died"""

    def __init__(self, strNs, strSvc, nPort):
        self.strNs = strNs
        self.strSvc = strSvc
        self.nPort = nPort
        self.proc = None
        self.nLocal = None
        self.lock = threading.Lock()

    def port(self):
        """@return the local port, starting the forward when there is none"""
        with self.lock:
            if self.proc is None or self.proc.poll() is not None:
                self.start()
            return self.nLocal

    def restart(self):
        with self.lock:
            self.stop()
            self.start()

    def start(self):
        self.proc = subprocess.Popen(
            ["kubectl", "-n", self.strNs, "port-forward", "svc/" + self.strSvc, ":" + str(self.nPort)],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        nDeadline = time.time() + N_FORWARD_WAIT_S
        strLine = ""
        while time.time() < nDeadline:
            strLine = self.proc.stdout.readline()
            match = PAT_FORWARD.match(strLine)
            if match:
                self.nLocal = int(match.group(1))
                # Drain the rest: every connection prints a line, and a full
                # pipe would stall kubectl mid-request.
                threading.Thread(target=lambda proc=self.proc: [None for _ in proc.stdout],
                                 daemon=True).start()
                say("forward   %s/%s:%d -> 127.0.0.1:%d" % (self.strNs, self.strSvc, self.nPort, self.nLocal))
                return
            if self.proc.poll() is not None:
                break
        strLast = strLine.strip() if strLine else "no output"
        self.stop()
        raise ConnectionError("port-forward to %s/%s failed: %s" % (self.strNs, self.strSvc, strLast))

    def stop(self):
        if self.proc is not None and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        self.proc = None


class Site:
    """one namespace behind one local port"""

    def __init__(self, strNs, nPort, setSvc):
        self.strNs = strNs
        self.nPort = nPort
        self.mapForward = {}
        self.mapVhost = {}
        for strVhost in LST_VHOST_ORDER:
            strUi, lstRoute = MAP_VHOST[strVhost]
            if strUi not in setSvc:
                continue
            lstOk = [(strPrefix, self.forward(strSvc, nSvcPort))
                     for strPrefix, strSvc, nSvcPort in lstRoute if strSvc in setSvc]
            self.mapVhost[strVhost] = (self.forward(strUi, N_WEB_UI_PORT), lstOk)

    def forward(self, strSvc, nPort):
        strKey = "%s:%d" % (strSvc, nPort)
        if strKey not in self.mapForward:
            self.mapForward[strKey] = Forward(self.strNs, strSvc, nPort)
        return self.mapForward[strKey]

    def url(self, strVhost):
        return "http://%s.localhost:%d/" % (strVhost, self.nPort)

    def stop(self):
        for fwd in self.mapForward.values():
            fwd.stop()


def handler_for(site, isVerbose):

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, strFormat, *lstArg):
            pass

        def do_GET(self):
            self.serve()

        def do_POST(self):
            self.serve()

        def do_PUT(self):
            self.serve()

        def do_DELETE(self):
            self.serve()

        def do_PATCH(self):
            self.serve()

        def do_HEAD(self):
            self.serve()

        def do_OPTIONS(self):
            self.serve()

        def serve(self):
            strHost = (self.headers.get("Host") or "").split(":")[0].lower()
            strVhost = strHost[:-len(".localhost")] if strHost.endswith(".localhost") else ""
            if strVhost not in site.mapVhost:
                return self.index()
            fwdUi, lstRoute = site.mapVhost[strVhost]
            strPath = self.path.split("?")[0]
            fwd = fwdUi
            for strPrefix, fwdRoute in lstRoute:
                if strPath == strPrefix or strPath.startswith(strPrefix + "/"):
                    fwd = fwdRoute
                    break
            nLen = int(self.headers.get("Content-Length") or 0)
            arrBody = self.rfile.read(nLen) if nLen > 0 else None
            nT0 = time.time()
            try:
                nStatus, lstHeader, arrOut = self.relay(fwd, arrBody)
            except (OSError, http.client.HTTPException) as exc:
                # ONE RETRY on a fresh forward: a restarted pod ends kubectl's
                # forward, and the next request should not be the one to fail.
                try:
                    fwd.restart()
                    nStatus, lstHeader, arrOut = self.relay(fwd, arrBody)
                except (OSError, http.client.HTTPException) as exc2:
                    say("ERROR     %s %s%s -> %s/%s: %s / %s" % (self.command, strHost, self.path,
                                                               fwd.strNs, fwd.strSvc, exc, exc2))
                    return self.reply(502, [("Content-Type", "text/plain")],
                                      ("proxy: %s/%s is not reachable: %s\n" % (fwd.strNs, fwd.strSvc, exc2)).encode())
            if strPath == "/config.js" and fwd is fwdUi and nStatus == 200:
                cntHit = arrOut.count(ARR_HTTPS_HOST)
                arrOut = arrOut.replace(ARR_HTTPS_HOST, ARR_SAME_HOST)
                if cntHit == 0:
                    say("WARN      %s/config.js has no \"https://\" + window.location.host - left as served" % strHost)
            if isVerbose or nStatus >= 400:
                say("%-9d %s %s%s -> %s  %d ms" % (nStatus, self.command, strHost, self.path, fwd.strSvc,
                                                   int((time.time() - nT0) * 1000)))
            self.reply(nStatus, lstHeader, arrOut)

        def relay(self, fwd, arrBody):
            conn = http.client.HTTPConnection("127.0.0.1", fwd.port(), timeout=N_TIMEOUT_S)
            try:
                mapHeader = {k: v for k, v in self.headers.items() if k.lower() not in SET_HEADER_SKIP}
                if arrBody is not None:
                    mapHeader["Content-Length"] = str(len(arrBody))
                conn.request(self.command, self.path, body=arrBody, headers=mapHeader)
                resp = conn.getresponse()
                arrOut = resp.read()
                lstHeader = [(k, v) for k, v in resp.getheaders() if k.lower() not in SET_HEADER_SKIP]
                return resp.status, lstHeader, arrOut
            finally:
                conn.close()

        def reply(self, nStatus, lstHeader, arrOut):
            self.send_response(nStatus)
            for strKey, strValue in lstHeader:
                self.send_header(strKey, strValue)
            self.send_header("Content-Length", str(len(arrOut)))
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(arrOut)

        def index(self):
            strRows = "".join('<li><a href="%s">%s</a></li>' % (html.escape(site.url(v)), html.escape(site.url(v)))
                              for v in site.mapVhost)
            arrOut = ("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>%s - BaseNet UIs</title>"
                      "</head><body style=\"font-family:system-ui,sans-serif\"><h1>%s</h1><ul>%s</ul>"
                      "</body></html>" % (html.escape(site.strNs), html.escape(site.strNs), strRows)).encode()
            self.reply(200, [("Content-Type", "text/html; charset=utf-8")], arrOut)

    return Handler


def on_signal(nSig, frame):
    raise KeyboardInterrupt


def main():
    lstArg = [s for s in sys.argv[1:] if s != "--verbose"]
    isVerbose = "--verbose" in sys.argv[1:]
    mapConf = map_settings()
    strExt = mapConf.get("STR_NAMESPACE", "sv")
    nPortBase = int(mapConf.get("STR_UI_PORT", "4400"))

    lstNs = [strExt] + sorted(s.split("/", 1)[1] for s in lst_kubectl(["get", "ns", "-l", STR_LABEL, "-o", "name"]))
    lstExisting = [s.split("/", 1)[1] for s in lst_kubectl(["get", "ns", "-o", "name"])]
    lstPick = [(strNs, nPortBase + idx) for idx, strNs in enumerate(lstNs) if strNs in lstExisting]
    if lstArg:
        lstPick = [(strNs, nPort) for strNs, nPort in lstPick if strNs in lstArg]
        if not lstPick:
            raise SystemExit("no such BaseNet namespace: %s - known: %s" % (" ".join(lstArg), " ".join(lstNs)))

    lstSite = []
    lstServer = []
    for strNs, nPort in lstPick:
        setSvc = {s.split("/", 1)[1] for s in lst_kubectl(["-n", strNs, "get", "svc", "-o", "name"])}
        site = Site(strNs, nPort, setSvc)
        if not site.mapVhost:
            say("skip      %s serves no web UI" % strNs)
            continue
        server = ThreadingHTTPServer(("127.0.0.1", nPort), handler_for(site, isVerbose))
        server.daemon_threads = True
        threading.Thread(target=server.serve_forever, daemon=True).start()
        lstSite.append(site)
        lstServer.append(server)

    print(flush=True)
    for site in lstSite:
        for strVhost in site.mapVhost:
            print("  %-12s %-7s %s" % (site.strNs, strVhost, site.url(strVhost)), flush=True)
    # KeyboardInterrupt on SIGTERM as well as SIGINT, and SIGINT restored for
    # a shell that started this in the background: either way the forwards
    # are ended rather than orphaned.
    signal.signal(signal.SIGINT, on_signal)
    signal.signal(signal.SIGTERM, on_signal)
    print("\nSign in with a user of the identity provider - STR_OIDC_USERS for the"
          "\nbundled one. Ctrl-C ends this and every port-forward.\n", flush=True)
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        pass
    finally:
        for server in lstServer:
            server.shutdown()
        for site in lstSite:
            site.stop()
        say("stopped")


if __name__ == "__main__":
    main()
