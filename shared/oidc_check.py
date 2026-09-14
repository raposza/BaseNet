#!/usr/bin/env python3
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
# Generated 2026-09-11T17:40:00Z
#
# oidc_check.py - tests an OpenID Provider against OpenID Connect Discovery 1.0
# and Core 1.0 (both incorporating errata set 2), RFC 7636 PKCE, OpenID Connect
# RP-Initiated Logout 1.0, and what the Splice web UIs and backends need from
# it. Stdlib only.
#
#   python3 oidc_check.py <issuer>        e.g. http://<host>:32002
#                                         or   https://kc.example.com/realms/canton
#
# <issuer> is exactly the provider's issuer string; a missing scheme is taken
# as http. Discovery is read from <issuer>/.well-known/openid-configuration.
#
# THE SAME RUN MUST PASS AGAINST THE BUNDLED PROVIDER AND AGAINST THE ONE THAT
# REPLACES IT. That is what makes the switch a matter of settings.
#
# Environment:
#   OIDC_CLIENT_ID        default oidc-check
#   OIDC_CLIENT_SECRET    default none - a public client, as the web UIs are;
#                         when set, sent with HTTP Basic
#   OIDC_REDIRECT_URI     default http://127.0.0.1:8765/callback (never contacted)
#   OIDC_USERNAME         a user to log in as; without it every check that
#   OIDC_PASSWORD         needs a code is SKIP
#   OIDC_AUDIENCE         sent as `audience` on the authorization request, as
#                         the Splice UIs send it, and required in the access
#                         token's aud
#   OIDC_SCOPE            extra scopes beside openid
#   OIDC_ORIGIN           the browser origin for the CORS checks; default the
#                         redirect URI's origin
#   OIDC_ALLOW_HTTP=1     an http issuer is WARN instead of FAIL
#   OIDC_INSECURE=1       do not verify TLS certificates
#
# The login is driven through the provider's own HTML form: the first form
# holding a password field, every hidden input kept, cookies carried. That is
# how both a bundled provider and Keycloak present it.
#
# Every result names the clause it tests. Exit code 1 when anything FAILs.
import base64
import hashlib
import html.parser
from http.cookiejar import CookieJar
import json
import os
import secrets
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

N_TIMEOUT_S = 10
N_HOPS_MAX = 10
LST_RESULT = []
CTX_SSL = ssl._create_unverified_context() if os.environ.get("OIDC_INSECURE") == "1" else None
IS_ALLOW_HTTP = os.environ.get("OIDC_ALLOW_HTTP") == "1"

# DigestInfo prefixes for RSASSA-PKCS1-v1_5, RFC 8017 section 9.2 note 1
MAP_RSA_PREFIX = {
    "RS256": (hashlib.sha256, bytes.fromhex("3031300d060960864801650304020105000420")),
    "RS384": (hashlib.sha384, bytes.fromhex("3041300d060960864801650304020205000430")),
    "RS512": (hashlib.sha512, bytes.fromhex("3051300d060960864801650304020305000440")),
}
MAP_HASH_FOR_ALG = {"256": hashlib.sha256, "384": hashlib.sha384, "512": hashlib.sha512}
SET_PRIVATE_JWK_MEMBERS = {"d", "p", "q", "dp", "dq", "qi", "oth", "k"}
SET_PROMPT_NONE_ERROR = {"login_required", "interaction_required", "consent_required",
                         "account_selection_required"}
SET_REDIRECT = {301, 302, 303, 307, 308}
SET_USERNAME_NAME = {"username", "user", "login", "email"}


def record(strLevel, strSection, strMsg, strRef=""):
    LST_RESULT.append(strLevel)
    strRefOut = ("  (" + strRef + ")") if strRef else ""
    print("[%-4s] %-11s %s%s" % (strLevel, strSection, strMsg, strRefOut))


class NoRedirect(urllib.request.HTTPRedirectHandler):

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def opener_for(jar=None, isFollow=True):
    lstHandler = [urllib.request.HTTPSHandler(context=CTX_SSL)] if CTX_SSL else []
    if not isFollow:
        lstHandler.append(NoRedirect())
    if jar is not None:
        lstHandler.append(urllib.request.HTTPCookieProcessor(jar))
    return urllib.request.build_opener(*lstHandler)


def http(strMethod, strUrl, mapForm=None, mapHeader=None, isFollow=True, jar=None):
    """one request; never raises on an HTTP status

    @return (status, headers, body text), status 0 with the reason as body on
            a transport failure
    """
    data = urllib.parse.urlencode(mapForm).encode() if mapForm is not None else None
    req = urllib.request.Request(strUrl, data=data, method=strMethod, headers=mapHeader or {})
    try:
        with opener_for(jar, isFollow).open(req, timeout=N_TIMEOUT_S) as resp:
            return resp.status, resp.headers, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.headers, exc.read().decode("utf-8", "replace")
    except (urllib.error.URLError, OSError) as exc:
        return 0, {}, str(exc)


def json_or_none(strBody):
    try:
        return json.loads(strBody)
    except ValueError:
        return None


def b64u_decode(strPart):
    return base64.urlsafe_b64decode(strPart + "=" * (-len(strPart) % 4))


def b64u_encode(arrBytes):
    return base64.urlsafe_b64encode(arrBytes).rstrip(b"=").decode()


def str_origin(strUrl):
    parts = urllib.parse.urlsplit(strUrl)
    return parts.scheme + "://" + parts.netloc


def is_rsa_signature_valid(mapJwk, strAlg, arrSigningInput, arrSig):
    fnHash, arrPrefix = MAP_RSA_PREFIX[strAlg]
    nMod = int.from_bytes(b64u_decode(mapJwk["n"]), "big")
    nExp = int.from_bytes(b64u_decode(mapJwk["e"]), "big")
    cntK = (nMod.bit_length() + 7) // 8
    if len(arrSig) != cntK:
        return False
    arrEm = pow(int.from_bytes(arrSig, "big"), nExp, nMod).to_bytes(cntK, "big")
    arrT = arrPrefix + fnHash(arrSigningInput).digest()
    if cntK < len(arrT) + 11:
        return False
    return arrEm == b"\x00\x01" + b"\xff" * (cntK - len(arrT) - 3) + b"\x00" + arrT


def decode_jws(strToken):
    """@return (header, claims, signature, signing input) or None"""
    lstPart = strToken.split(".")
    if len(lstPart) != 3:
        return None
    try:
        return (json.loads(b64u_decode(lstPart[0])), json.loads(b64u_decode(lstPart[1])),
                b64u_decode(lstPart[2]), (lstPart[0] + "." + lstPart[1]).encode())
    except ValueError:
        return None


def check_signature(strSec, lstKey, mapHead, arrSig, arrInput, strRef):
    strAlg = mapHead.get("alg", "")
    if strAlg not in MAP_RSA_PREFIX:
        record("INFO", strSec, "signature not verified - this checker verifies RS256/384/512 only", "")
        return
    lstCand = [k for k in lstKey if k.get("kty") == "RSA"
               and (not mapHead.get("kid") or k.get("kid") == mapHead["kid"])]
    if not lstCand:
        record("FAIL", strSec, "no RSA key with kid %r in the JWKS" % mapHead.get("kid"), strRef)
        return
    isValid = any(is_rsa_signature_valid(k, strAlg, arrInput, arrSig) for k in lstCand)
    record("PASS" if isValid else "FAIL", strSec,
           "signature %s against the JWKS" % ("verifies" if isValid else "does NOT verify"), strRef)


def pkce_pair():
    strVerifier = b64u_encode(secrets.token_bytes(32))
    strChallenge = b64u_encode(hashlib.sha256(strVerifier.encode("ascii")).digest())
    return strVerifier, strChallenge


class FormParser(html.parser.HTMLParser):
    """collects every form with its action, method and inputs"""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.lstForm = []
        self.mapCurrent = None

    def handle_starttag(self, tag, attrs):
        mapAttr = {k: (v if v is not None else "") for k, v in attrs}
        if tag == "form":
            self.mapCurrent = {"action": mapAttr.get("action", ""),
                               "method": mapAttr.get("method", "get").lower(), "lstInput": []}
            self.lstForm.append(self.mapCurrent)
        elif tag == "input" and self.mapCurrent is not None:
            self.mapCurrent["lstInput"].append(mapAttr)

    def handle_endtag(self, tag):
        if tag == "form":
            self.mapCurrent = None


def form_with_password(strBody):
    parser = FormParser()
    parser.feed(strBody)
    for mapForm in parser.lstForm:
        if any(m.get("type", "").lower() == "password" for m in mapForm["lstInput"]):
            return mapForm
    return None


def fill_form(mapFormHtml, strUser, strPass):
    """@return the fields to post: hidden inputs kept, username and password set

    The username field is the text or email input with a conventional name,
    or the first text or email input when none has one.
    """
    lstText = [m for m in mapFormHtml["lstInput"]
               if m.get("name") and m.get("type", "text").lower() in ("text", "email")]
    lstNamed = [m for m in lstText if m["name"].lower() in SET_USERNAME_NAME]
    strUserField = (lstNamed or lstText or [{}])[0].get("name")
    mapField = {}
    for mapInput in mapFormHtml["lstInput"]:
        strName = mapInput.get("name")
        strType = mapInput.get("type", "text").lower()
        if not strName or strType in ("submit", "button", "image", "reset", "checkbox", "radio"):
            continue
        if strType == "password":
            mapField[strName] = strPass
        elif strName == strUserField:
            mapField[strName] = strUser
        else:
            mapField[strName] = mapInput.get("value", "")
    return mapField


def authorize(mapDisc, strClientId, strRedirect, mapExtra, strUser, strPass, jar):
    """one authorization request, driven through the login form when one appears

    @return (kind, map): kind is code or error with the redirect's query, login
            when a form appeared and no credentials were given, refused when the
            form came back after the credentials, page for anything else
    """
    strUrl = mapDisc["authorization_endpoint"]
    strGet = strUrl + ("&" if "?" in strUrl else "?") + urllib.parse.urlencode(mapExtra)
    strMethod, strNext, mapForm = "GET", strGet, None
    isPosted = False
    for _ in range(N_HOPS_MAX):
        nStatus, hdr, strBody = http(strMethod, strNext, mapForm=mapForm, isFollow=False, jar=jar)
        if nStatus in SET_REDIRECT:
            strLoc = urllib.parse.urljoin(strNext, hdr.get("Location", ""))
            if strLoc.startswith(strRedirect):
                mapQ = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(strLoc).query))
                return ("code" if "code" in mapQ else "error"), mapQ
            strMethod, strNext, mapForm = "GET", strLoc, None
            continue
        if nStatus != 200:
            return "page", {"status": nStatus, "body": strBody[:160]}
        mapFormHtml = form_with_password(strBody)
        if mapFormHtml is None:
            return "page", {"status": nStatus, "body": strBody[:160]}
        if isPosted:
            return "refused", {}
        if strUser is None:
            return "login", {}
        strAction = urllib.parse.urljoin(strNext, mapFormHtml["action"] or strNext)
        mapField = fill_form(mapFormHtml, strUser, strPass or "")
        if mapFormHtml["method"] == "post":
            strMethod, strNext, mapForm = "POST", strAction, mapField
        else:
            strMethod = "GET"
            strNext = strAction + ("&" if "?" in strAction else "?") + urllib.parse.urlencode(mapField)
            mapForm = None
        isPosted = True
    return "page", {"status": "too many hops"}


def check_discovery(strIssuer):
    strSec = "discovery"
    strUrl = strIssuer + "/.well-known/openid-configuration"
    nStatus, hdr, strBody = http("GET", strUrl)
    if nStatus != 200:
        record("FAIL", strSec, "GET %s -> %s %s" % (strUrl, nStatus, strBody[:120]), "Discovery 4, 4.2")
        return None
    record("PASS", strSec, "GET %s -> 200" % strUrl, "Discovery 4")
    strType = (hdr.get("Content-Type") or "").split(";")[0].strip()
    record("PASS" if strType == "application/json" else "FAIL", strSec,
           "Content-Type is %r" % strType, "Discovery 4, 4.2: application/json")
    mapDisc = json_or_none(strBody)
    if not isinstance(mapDisc, dict):
        record("FAIL", strSec, "body is not a JSON object", "Discovery 4.2")
        return None
    strGot = mapDisc.get("issuer")
    if strGot == strIssuer:
        record("PASS", strSec, "issuer is identical to the URL it was fetched under", "Discovery 4.3")
    else:
        record("FAIL", strSec, "issuer %r is not identical to %r" % (strGot, strIssuer), "Discovery 4.3")
    lstResp = mapDisc.get("response_types_supported") or []
    isImplicitOnly = bool(lstResp) and set(lstResp) <= {"id_token", "id_token token"}
    lstRequired = ["issuer", "authorization_endpoint", "jwks_uri", "response_types_supported",
                   "subject_types_supported", "id_token_signing_alg_values_supported"]
    if not isImplicitOnly:
        lstRequired.append("token_endpoint")
    lstMissing = [k for k in lstRequired if k not in mapDisc]
    record("FAIL" if lstMissing else "PASS", strSec,
           ("REQUIRED members missing: " + ", ".join(lstMissing)) if lstMissing
           else "all REQUIRED members present", "Discovery 3")
    lstAlg = mapDisc.get("id_token_signing_alg_values_supported") or []
    record("PASS" if "RS256" in lstAlg else "FAIL", strSec,
           "id_token_signing_alg_values_supported %s %s RS256"
           % (lstAlg, "includes" if "RS256" in lstAlg else "lacks"),
           "Discovery 3: RS256 MUST be included")
    lstHttp = [k for k in ("issuer", "authorization_endpoint", "token_endpoint", "userinfo_endpoint",
                           "jwks_uri", "registration_endpoint", "end_session_endpoint")
               if isinstance(mapDisc.get(k), str) and not mapDisc[k].startswith("https://")]
    if not lstHttp:
        record("PASS", strSec, "every advertised endpoint is https", "Discovery 3")
    elif IS_ALLOW_HTTP:
        record("WARN", strSec, "not https, allowed by OIDC_ALLOW_HTTP: " + ", ".join(lstHttp),
               "Discovery 3: MUST use the https scheme")
    else:
        record("FAIL", strSec, "not https: " + ", ".join(lstHttp), "Discovery 3: MUST use the https scheme")
    lstEmpty = [k for k, v in mapDisc.items() if isinstance(v, list) and not v]
    record("FAIL" if lstEmpty else "PASS", strSec,
           ("empty arrays present: " + ", ".join(lstEmpty)) if lstEmpty else "no empty arrays",
           "Discovery 4.2: zero-element claims MUST be omitted")
    if "scopes_supported" in mapDisc:
        isOpenid = "openid" in mapDisc["scopes_supported"]
        record("PASS" if isOpenid else "FAIL", strSec,
               "scopes_supported %s openid" % ("includes" if isOpenid else "lacks"),
               "Discovery 3: MUST support the openid scope")
    else:
        record("WARN", strSec, "scopes_supported absent", "Discovery 3: RECOMMENDED")
    for strKey in ("userinfo_endpoint", "claims_supported"):
        if strKey not in mapDisc:
            record("WARN", strSec, strKey + " absent", "Discovery 3: RECOMMENDED")
    record("PASS" if "code" in lstResp else "FAIL", strSec,
           "response_types_supported %s code" % ("includes" if "code" in lstResp else "lacks"),
           "Splice UIs: authorization code flow")
    lstGrant = mapDisc.get("grant_types_supported")
    if lstGrant is not None:
        isCode = "authorization_code" in lstGrant
        record("PASS" if isCode else "FAIL", strSec,
               "grant_types_supported %s authorization_code" % ("includes" if isCode else "lacks"),
               "Discovery 3; Splice UIs")
    lstPkce = mapDisc.get("code_challenge_methods_supported") or []
    record("PASS" if "S256" in lstPkce else "FAIL", strSec,
           "code_challenge_methods_supported %s S256" % ("includes" if "S256" in lstPkce else "lacks"),
           "RFC 8414 2; Splice UIs send S256 PKCE")
    isLogout = isinstance(mapDisc.get("end_session_endpoint"), str)
    record("PASS" if isLogout else "FAIL", strSec,
           "end_session_endpoint %s" % ("present" if isLogout else "absent"),
           "RP-Initiated Logout 1.0 2.1; the Splice UIs throw on logout without it")
    return mapDisc


def check_rfc8414(strIssuer):
    strSec = "rfc8414"
    parts = urllib.parse.urlsplit(strIssuer)
    strInserted = "%s://%s/.well-known/oauth-authorization-server%s" % (parts.scheme, parts.netloc, parts.path)
    nStatus, _, strBody = http("GET", strInserted)
    if nStatus == 200:
        mapMeta = json_or_none(strBody) or {}
        isSame = mapMeta.get("issuer") == strIssuer
        record("PASS" if isSame else "FAIL", strSec,
               "%s -> 200, issuer %r" % (strInserted, mapMeta.get("issuer")), "RFC 8414 3, 3.3")
        return
    if parts.path:
        strAppended = strIssuer + "/.well-known/oauth-authorization-server"
        nStatus2, _, _ = http("GET", strAppended)
        if nStatus2 == 200:
            record("WARN", strSec,
                   "served at %s but not at %s (%s)" % (strAppended, strInserted, nStatus),
                   "RFC 8414 3: the well-known segment goes between host and path")
            return
    record("INFO", strSec, "no RFC 8414 metadata at %s (%s); optional for OIDC" % (strInserted, nStatus), "")


def check_cors(strSec, strMethod, strUrl, strOrigin, mapForm=None, strLevelMiss="FAIL",
               strRef="Fetch standard CORS; the Splice UIs call it from the browser"):
    """a browser request from strOrigin; the page reads the response only when allowed"""
    nStatus, hdr, _ = http(strMethod, strUrl, mapForm=mapForm, mapHeader={"Origin": strOrigin})
    strAllow = hdr.get("Access-Control-Allow-Origin") if hdr else None
    isAllowed = strAllow in (strOrigin, "*")
    record("PASS" if isAllowed else strLevelMiss, strSec,
           "%s %s from %s -> %s, Access-Control-Allow-Origin %r" % (strMethod, strUrl, strOrigin, nStatus, strAllow),
           strRef)


def check_jwks(mapDisc, strOrigin):
    strSec = "jwks"
    strUrl = mapDisc.get("jwks_uri")
    if not strUrl:
        record("SKIP", strSec, "no jwks_uri", "")
        return []
    nStatus, _, strBody = http("GET", strUrl)
    mapSet = json_or_none(strBody)
    if nStatus != 200 or not isinstance(mapSet, dict) or not isinstance(mapSet.get("keys"), list):
        record("FAIL", strSec, "GET %s -> %s, not a JWK Set" % (strUrl, nStatus), "RFC 7517 5")
        return []
    lstKey = mapSet["keys"]
    record("PASS" if lstKey else "FAIL", strSec, "%d key(s) at %s" % (len(lstKey), strUrl), "RFC 7517 5")
    lstLeak = [str(k.get("kid")) for k in lstKey if SET_PRIVATE_JWK_MEMBERS & set(k)]
    record("FAIL" if lstLeak else "PASS", strSec,
           ("private or symmetric key material in kid(s): " + ", ".join(lstLeak)) if lstLeak
           else "no private or symmetric key material",
           "Discovery 3: MUST NOT contain private or symmetric key values")
    if any(k.get("use") == "enc" for k in lstKey) and any("use" not in k for k in lstKey):
        record("FAIL", strSec, "signing and encryption keys mixed, and some keys have no use", "Discovery 3")
    if not any(k.get("kty") == "RSA" for k in lstKey):
        record("WARN", strSec, "no RSA key, so RS256 tokens cannot be verified", "Discovery 3: RS256")
    check_cors(strSec, "GET", strUrl, strOrigin, strLevelMiss="WARN",
               strRef="Fetch standard CORS; needed only by a browser client that verifies signatures")
    return lstKey


def params_authorize(strClientId, strRedirect, strChallenge, strPrompt=None):
    """the request the Splice UIs make: code, openid plus any scope, the
    audience as an extra parameter, PKCE S256, state and nonce

    @return (params, state, nonce)
    """
    strState = secrets.token_urlsafe(16)
    strNonce = secrets.token_urlsafe(16)
    strScope = " ".join(["openid"] + os.environ.get("OIDC_SCOPE", "").split())
    mapParam = {"response_type": "code", "scope": strScope, "client_id": strClientId,
                "redirect_uri": strRedirect, "state": strState, "nonce": strNonce}
    if strChallenge:
        mapParam["code_challenge"] = strChallenge
        mapParam["code_challenge_method"] = "S256"
    if os.environ.get("OIDC_AUDIENCE"):
        mapParam["audience"] = os.environ["OIDC_AUDIENCE"]
    if strPrompt:
        mapParam["prompt"] = strPrompt
    return mapParam, strState, strNonce


def check_authorize(mapDisc, strClientId, strRedirect, strUser, strPass):
    """@return (code, state, nonce, verifier, cookie jar) when a code was issued"""
    strSec = "authorize"
    strUrl = mapDisc.get("authorization_endpoint")
    if not strUrl:
        record("SKIP", strSec, "no authorization_endpoint", "")
        return None

    mapParam, strState, _ = params_authorize(strClientId, strRedirect, pkce_pair()[1])
    strKindGet, _ = authorize(mapDisc, strClientId, strRedirect, mapParam, None, None,
                              CookieJar())
    nStatusPost, hdrPost, _ = http("POST", strUrl, mapForm=dict(mapParam, state=strState + "p"), isFollow=False)
    strLocPost = hdrPost.get("Location", "") if hdrPost else ""
    strKindPost = "redirect" if nStatusPost in SET_REDIRECT else ("login" if nStatusPost == 200 else str(nStatusPost))
    isBoth = strKindGet in ("login", "code", "error") and strKindPost in ("login", "redirect")
    record("PASS" if isBoth else "FAIL", strSec,
           "GET -> %s, POST -> %s %s" % (strKindGet, nStatusPost, strLocPost[:80]),
           "Core 3.1.2.1: MUST support GET and POST")

    mapNone, strStateNone, _ = params_authorize(strClientId, strRedirect, pkce_pair()[1], "none")
    strKind, mapQ = authorize(mapDisc, strClientId, strRedirect, mapNone, None, None,
                              CookieJar())
    isNone = strKind == "error" and mapQ.get("error") in SET_PROMPT_NONE_ERROR \
        and mapQ.get("state") == strStateNone
    record("PASS" if isNone else "FAIL", strSec,
           "prompt=none with no session -> %s %s" % (strKind, mapQ.get("error", "")),
           "Core 3.1.2.6: MUST NOT show a page, MUST redirect with login_required, state echoed")

    if strUser is None:
        record("SKIP", strSec, "no OIDC_USERNAME, so no login and no code", "")
        return None

    kindBad, _ = authorize(mapDisc, strClientId, strRedirect,
                           params_authorize(strClientId, strRedirect, pkce_pair()[1])[0],
                           strUser, (strPass or "") + "-wrong", CookieJar())
    record("PASS" if kindBad != "code" else "FAIL", strSec,
           "a wrong password -> %s" % kindBad, "the password is checked")

    jar = CookieJar()
    strVerifier, strChallenge = pkce_pair()
    mapParam, strState, strNonce = params_authorize(strClientId, strRedirect, strChallenge)
    strKind, mapQ = authorize(mapDisc, strClientId, strRedirect, mapParam, strUser, strPass, jar)
    if strKind != "code":
        record("FAIL", strSec, "logging in as %r -> %s %s" % (strUser, strKind, mapQ), "Core 3.1.2.5")
        return None
    isState = mapQ.get("state") == strState
    record("PASS" if isState else "FAIL", strSec,
           "logged in as %r, redirected with a code, state %s" % (strUser, "echoed" if isState else "NOT echoed"),
           "Core 3.1.2.5, RFC 6749 4.1.2")
    if mapDisc.get("authorization_response_iss_parameter_supported"):
        isIss = mapQ.get("iss") == mapDisc.get("issuer")
        record("PASS" if isIss else "FAIL", strSec, "iss %r on the redirect" % mapQ.get("iss"), "RFC 9207 2")
    return mapQ["code"], strState, strNonce, strVerifier, jar


def token_request(mapDisc, mapForm, strClientId, strOrigin=None):
    mapHeader = {}
    strSecret = os.environ.get("OIDC_CLIENT_SECRET")
    if strSecret:
        mapHeader["Authorization"] = "Basic " + base64.b64encode(
            (urllib.parse.quote(strClientId, safe="") + ":"
             + urllib.parse.quote(strSecret, safe="")).encode()).decode()
    else:
        mapForm = dict(mapForm, client_id=strClientId)
    if strOrigin:
        mapHeader["Origin"] = strOrigin
    return http("POST", mapDisc["token_endpoint"], mapForm=mapForm, mapHeader=mapHeader)


def is_error_json(nStatus, strBody, setStatus=(400,), strError=None):
    mapErr = json_or_none(strBody)
    if nStatus not in setStatus or not isinstance(mapErr, dict) or "error" not in mapErr:
        return False
    return strError is None or mapErr["error"] == strError


def fresh_code(mapDisc, strClientId, strRedirect, strUser, strPass, jar):
    """another code for a negative test; the session in jar may skip the form

    @return (code, verifier) or None
    """
    strVerifier, strChallenge = pkce_pair()
    mapParam = params_authorize(strClientId, strRedirect, strChallenge)[0]
    strKind, mapQ = authorize(mapDisc, strClientId, strRedirect, mapParam, strUser, strPass, jar)
    return (mapQ["code"], strVerifier) if strKind == "code" else None


def check_token(mapDisc, lstKey, tupAuth, strClientId, strRedirect, strOrigin, strUser, strPass):
    """@return (token response, id token claims) or None"""
    strSec = "token"
    strCode, _, strNonce, strVerifier, jar = tupAuth
    mapForm = {"grant_type": "authorization_code", "code": strCode, "redirect_uri": strRedirect,
               "code_verifier": strVerifier}
    nStatus, hdr, strBody = token_request(mapDisc, mapForm, strClientId, strOrigin)
    mapResp = json_or_none(strBody)
    if nStatus != 200 or not isinstance(mapResp, dict):
        record("FAIL", strSec, "code exchange -> %s %s" % (nStatus, strBody[:160]), "Core 3.1.3.3")
        return None
    record("PASS", strSec, "code exchange with the PKCE verifier -> 200", "Core 3.1.3.3, RFC 7636 4.5")
    strAllow = hdr.get("Access-Control-Allow-Origin")
    record("PASS" if strAllow in (strOrigin, "*") else "FAIL", strSec,
           "from %s, Access-Control-Allow-Origin %r" % (strOrigin, strAllow),
           "Fetch standard CORS; the Splice UIs exchange the code from the browser")
    strCache = (hdr.get("Cache-Control") or "").lower()
    record("PASS" if "no-store" in strCache else "FAIL", strSec,
           "Cache-Control %r" % strCache, "Core 3.1.3.3: MUST be no-store")
    strPragma = (hdr.get("Pragma") or "").lower()
    record("PASS" if "no-cache" in strPragma else "WARN", strSec,
           "Pragma %r" % strPragma, "RFC 6749 5.1: no-cache; dropped by OAuth 2.1")
    strTokenType = str(mapResp.get("token_type", ""))
    record("PASS" if strTokenType.lower() == "bearer" else "FAIL", strSec,
           "token_type %r" % strTokenType, "Core 3.1.3.3: MUST be Bearer")
    record("PASS" if mapResp.get("access_token") else "FAIL", strSec, "access_token present", "RFC 6749 5.1")
    strIdToken = mapResp.get("id_token")
    if not strIdToken:
        record("FAIL", strSec, "no id_token in the response", "Core 3.1.3.3: MUST be included")
        return None
    record("PASS", strSec, "id_token present", "Core 3.1.3.3")

    nStatus2, _, strBody2 = token_request(mapDisc, mapForm, strClientId)
    record("PASS" if is_error_json(nStatus2, strBody2, strError="invalid_grant") else "FAIL", strSec,
           "the same code a second time -> %s %s" % (nStatus2, strBody2[:80]),
           "Core 3.1.3.2: MUST verify the code was not previously used; RFC 6749 5.2 invalid_grant")
    nStatus3, _, strBody3 = token_request(mapDisc, dict(mapForm, code="not-a-code-" + secrets.token_hex(4)),
                                          strClientId)
    record("PASS" if is_error_json(nStatus3, strBody3) else "FAIL", strSec,
           "an invalid code -> %s %s" % (nStatus3, strBody3[:80]), "Core 3.1.3.4: 400 with a JSON error")

    check_bindings(mapDisc, strClientId, strRedirect, strUser, strPass, jar)
    mapClaims = check_id_token(mapDisc, lstKey, strIdToken, mapResp.get("access_token", ""), strNonce, strClientId)
    return mapResp, mapClaims


def check_bindings(mapDisc, strClientId, strRedirect, strUser, strPass, jar):
    """a code is bound to its verifier, its redirect_uri and its client"""
    lstCase = [
        ("a wrong code_verifier", lambda v: {"code_verifier": b64u_encode(secrets.token_bytes(32))},
         "RFC 7636 4.6: invalid_grant", "pkce"),
        ("no code_verifier after a challenge", lambda v: {"code_verifier": None},
         "RFC 7636 4.6: invalid_grant", "pkce"),
        ("a different redirect_uri", lambda v: {"redirect_uri": strRedirect + "x"},
         "Core 3.1.3.2, RFC 6749 4.1.3: MUST be identical", "binding"),
        ("a different client_id", lambda v: {"client_id": strClientId + "-other"},
         "Core 3.1.3.2, RFC 6749 4.1.3: issued to that client", "binding"),
    ]
    for strWhat, fnChange, strRef, strSecCase in lstCase:
        tupCode = fresh_code(mapDisc, strClientId, strRedirect, strUser, strPass, jar)
        if tupCode is None:
            record("SKIP", strSecCase, strWhat + ": no second code could be obtained", "")
            continue
        mapForm = {"grant_type": "authorization_code", "code": tupCode[0], "redirect_uri": strRedirect,
                   "code_verifier": tupCode[1]}
        mapChange = fnChange(tupCode[1])
        strClient = mapChange.pop("client_id", strClientId)
        mapForm.update(mapChange)
        mapForm = {k: v for k, v in mapForm.items() if v is not None}
        nStatus, _, strBody = token_request(mapDisc, mapForm, strClient)
        isRefused = is_error_json(nStatus, strBody, setStatus=(400, 401))
        record("PASS" if isRefused else "FAIL", strSecCase,
               "exchange with %s -> %s %s" % (strWhat, nStatus, strBody[:80]), strRef)


def check_id_token(mapDisc, lstKey, strIdToken, strAccess, strNonce, strClientId):
    strSec = "id_token"
    tupJws = decode_jws(strIdToken)
    if tupJws is None:
        record("FAIL", strSec, "not a decodable JWS compact serialization", "Core 2: MUST be signed")
        return None
    mapHead, mapClaims, arrSig, arrInput = tupJws
    strAlg = mapHead.get("alg", "")
    record("FAIL" if strAlg == "none" else "PASS", strSec, "alg %r" % strAlg,
           "Core 2: MUST NOT use none unless requested")
    if strAlg not in (mapDisc.get("id_token_signing_alg_values_supported") or []):
        record("WARN", strSec, "alg %r is not in id_token_signing_alg_values_supported" % strAlg, "Discovery 3")
    check_signature(strSec, lstKey, mapHead, arrSig, arrInput, "Core 3.1.3.7 item 6")
    isIss = mapClaims.get("iss") == mapDisc.get("issuer")
    record("PASS" if isIss else "FAIL", strSec, "iss %r" % mapClaims.get("iss"),
           "Discovery 4.3 / Core 3.1.3.7 item 2: identical to issuer")
    strSub = mapClaims.get("sub")
    isSub = isinstance(strSub, str) and 0 < len(strSub) <= 255
    record("PASS" if isSub else "FAIL", strSec, "sub %r" % strSub, "Core 2: REQUIRED, at most 255 ASCII")
    objAud = mapClaims.get("aud")
    lstAud = objAud if isinstance(objAud, list) else [objAud]
    record("PASS" if strClientId in lstAud else "FAIL", strSec, "aud %r" % objAud,
           "Core 2: MUST contain the client_id")
    if len(lstAud) > 1:
        record("PASS" if mapClaims.get("azp") == strClientId else "WARN", strSec,
               "azp %r beside several audiences" % mapClaims.get("azp"), "Core 2: SHOULD be present")
    nNow = time.time()
    objExp = mapClaims.get("exp")
    isExp = isinstance(objExp, (int, float)) and objExp > nNow
    record("PASS" if isExp else "FAIL", strSec, "exp %r (now %d)" % (objExp, nNow), "Core 2: REQUIRED, in the future")
    objIat = mapClaims.get("iat")
    record("PASS" if isinstance(objIat, (int, float)) else "FAIL", strSec, "iat %r" % objIat, "Core 2: REQUIRED")
    isNonce = mapClaims.get("nonce") == strNonce
    record("PASS" if isNonce else "FAIL", strSec,
           "nonce %s" % ("echoed" if isNonce else "NOT echoed: %r" % mapClaims.get("nonce")),
           "Core 2: MUST be included when sent")
    if "at_hash" in mapClaims and strAlg[-3:] in MAP_HASH_FOR_ALG:
        arrHash = MAP_HASH_FOR_ALG[strAlg[-3:]](strAccess.encode()).digest()
        strWant = b64u_encode(arrHash[:len(arrHash) // 2])
        record("PASS" if mapClaims["at_hash"] == strWant else "FAIL", strSec, "at_hash", "Core 3.1.3.6")
    return mapClaims


def check_access_token(mapDisc, lstKey, strAccess, mapClaims):
    """what validator-app and sv-app verify: the ACCESS token, as an RS256 JWT
    against jwks_uri, with the audience they are configured with"""
    strSec = "splice"
    tupJws = decode_jws(strAccess or "")
    if tupJws is None:
        record("FAIL", strSec, "the access token is not a JWT", "the Splice backends verify it against jwks_uri")
        return
    mapHead, mapAccess, arrSig, arrInput = tupJws
    record("PASS" if mapHead.get("alg") == "RS256" else "FAIL", strSec, "access token alg %r" % mapHead.get("alg"),
           "the Splice backends verify RS256")
    check_signature(strSec, lstKey, mapHead, arrSig, arrInput, "the Splice backends verify it against jwks_uri")
    record("PASS" if mapAccess.get("iss") == mapDisc.get("issuer") else "FAIL", strSec,
           "access token iss %r" % mapAccess.get("iss"), "identical to issuer")
    isSub = mapClaims is not None and mapAccess.get("sub") == mapClaims.get("sub")
    record("PASS" if isSub else "FAIL", strSec, "access token sub %r, the ID token's" % mapAccess.get("sub"),
           "the user the UI logged in as is the ledger user")
    objExp = mapAccess.get("exp")
    record("PASS" if isinstance(objExp, (int, float)) and objExp > time.time() else "FAIL", strSec,
           "access token exp %r" % objExp, "RFC 9068 2.2")
    strAud = os.environ.get("OIDC_AUDIENCE")
    objAud = mapAccess.get("aud")
    lstAud = objAud if isinstance(objAud, list) else [objAud]
    if strAud:
        record("PASS" if strAud in lstAud else "FAIL", strSec, "access token aud %r" % objAud,
               "must contain OIDC_AUDIENCE, the backends' auth.audience")
    else:
        record("INFO", strSec, "access token aud %r; set OIDC_AUDIENCE to check it" % objAud, "")


def check_refresh(mapDisc, mapResp, strClientId):
    strSec = "renew"
    strRefresh = mapResp.get("refresh_token")
    if not strRefresh:
        record("INFO", strSec, "no refresh_token; silent renewal goes through prompt=none", "")
        return
    nStatus, hdr, strBody = token_request(mapDisc, {"grant_type": "refresh_token", "refresh_token": strRefresh},
                                          strClientId)
    mapNew = json_or_none(strBody)
    isOk = nStatus == 200 and isinstance(mapNew, dict) and mapNew.get("access_token")
    record("PASS" if isOk else "FAIL", strSec, "refresh_token grant -> %s %s" % (nStatus, strBody[:80]),
           "RFC 6749 6, Core 12")
    if isOk:
        record("PASS" if "no-store" in (hdr.get("Cache-Control") or "").lower() else "FAIL", strSec,
               "Cache-Control on the refresh response", "Core 12.2: MUST be no-store")


def check_silent(mapDisc, strClientId, strRedirect, jar):
    strSec = "renew"
    mapParam, strState, _ = params_authorize(strClientId, strRedirect, pkce_pair()[1], "none")
    strKind, mapQ = authorize(mapDisc, strClientId, strRedirect, mapParam, None, None, jar)
    if strKind == "code" or (strKind == "error" and mapQ.get("error") in SET_PROMPT_NONE_ERROR):
        record("PASS" if mapQ.get("state") == strState else "FAIL", strSec,
               "prompt=none after the login -> %s %s" % (strKind, mapQ.get("error", "")),
               "Core 3.1.2.6: a code with a session, login_required without one, never a page")
    else:
        record("FAIL", strSec, "prompt=none after the login -> %s" % strKind,
               "Core 3.1.2.6: MUST NOT display a page")


def check_userinfo(mapDisc, strAccess, mapClaims):
    strSec = "userinfo"
    strUrl = mapDisc.get("userinfo_endpoint")
    if not strUrl:
        record("SKIP", strSec, "no userinfo_endpoint advertised", "")
        return
    nStatus, hdr, _ = http("GET", strUrl)
    isChallenge = nStatus == 401 and "bearer" in ((hdr.get("WWW-Authenticate") if hdr else "") or "").lower()
    record("PASS" if isChallenge else "FAIL", strSec,
           "no token -> %s, WWW-Authenticate %r" % (nStatus, hdr.get("WWW-Authenticate") if hdr else None),
           "RFC 6750 3: 401 with a Bearer challenge")
    if not strAccess:
        record("SKIP", strSec, "no access token, the authenticated checks need the code flow", "")
        return
    for strMethod in ("GET", "POST"):
        nStatus, _, strBody = http(strMethod, strUrl, mapForm={} if strMethod == "POST" else None,
                                   mapHeader={"Authorization": "Bearer " + strAccess})
        mapInfo = json_or_none(strBody)
        if nStatus != 200 or not isinstance(mapInfo, dict):
            record("FAIL", strSec, "%s with the token -> %s %s" % (strMethod, nStatus, strBody[:100]),
                   "Core 5.3.1: MUST support GET and POST")
            continue
        isSame = mapClaims is not None and mapInfo.get("sub") == mapClaims.get("sub")
        record("PASS" if isSame else "FAIL", strSec,
               "%s with the token -> 200, sub %r" % (strMethod, mapInfo.get("sub")),
               "Core 5.3.2: sub MUST be returned and match the ID token")


def check_logout(mapDisc, strIdToken, strClientId, strRedirect):
    strSec = "logout"
    strUrl = mapDisc.get("end_session_endpoint")
    if not strUrl:
        record("SKIP", strSec, "no end_session_endpoint", "")
        return
    strPost = str_origin(strRedirect) + "/"
    strState = secrets.token_urlsafe(8)
    mapParam = {"post_logout_redirect_uri": strPost, "client_id": strClientId, "state": strState}
    if strIdToken:
        mapParam["id_token_hint"] = strIdToken
    nStatus, hdr, strBody = http("GET", strUrl + ("&" if "?" in strUrl else "?") + urllib.parse.urlencode(mapParam),
                                 isFollow=False)
    strLoc = hdr.get("Location", "") if hdr else ""
    if nStatus in SET_REDIRECT and strLoc.startswith(strPost):
        isState = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(strLoc).query)).get("state") == strState
        record("PASS" if isState else "FAIL", strSec,
               "-> %s back to %s, state %s" % (nStatus, strPost, "echoed" if isState else "NOT echoed"),
               "RP-Initiated Logout 1.0 3: MUST include state")
    elif nStatus == 200:
        record("WARN", strSec, "-> 200, a page; the user is asked to confirm",
               "RP-Initiated Logout 1.0 2: permitted; register %s as a post-logout URI" % strPost)
    else:
        record("FAIL", strSec, "-> %s %s %s" % (nStatus, strLoc[:80], strBody[:80]), "RP-Initiated Logout 1.0 2")


def main():
    if len(sys.argv) != 2:
        print("usage: python3 oidc_check.py <issuer>")
        return 2
    strIssuer = sys.argv[1].rstrip("/")
    if "://" not in strIssuer:
        strIssuer = "http://" + strIssuer
    strClientId = os.environ.get("OIDC_CLIENT_ID", "oidc-check")
    strRedirect = os.environ.get("OIDC_REDIRECT_URI", "http://127.0.0.1:8765/callback")
    strOrigin = os.environ.get("OIDC_ORIGIN", str_origin(strRedirect))
    strUser = os.environ.get("OIDC_USERNAME")
    strPass = os.environ.get("OIDC_PASSWORD")
    print("issuer %s   client_id %s   redirect_uri %s   origin %s   user %s\n"
          % (strIssuer, strClientId, strRedirect, strOrigin, strUser))

    mapDisc = check_discovery(strIssuer)
    check_rfc8414(strIssuer)
    if mapDisc is None:
        record("SKIP", "all", "nothing further can be tested without a discovery document", "")
    else:
        check_cors("discovery", "GET", strIssuer + "/.well-known/openid-configuration", strOrigin)
        lstKey = check_jwks(mapDisc, strOrigin)
        strAccess, mapClaims, strIdToken = None, None, None
        tupAuth = check_authorize(mapDisc, strClientId, strRedirect, strUser, strPass)
        if tupAuth and mapDisc.get("token_endpoint"):
            tupTok = check_token(mapDisc, lstKey, tupAuth, strClientId, strRedirect, strOrigin, strUser, strPass)
            if tupTok:
                mapResp, mapClaims = tupTok
                strAccess, strIdToken = mapResp.get("access_token"), mapResp.get("id_token")
                check_access_token(mapDisc, lstKey, strAccess, mapClaims)
                check_refresh(mapDisc, mapResp, strClientId)
            check_silent(mapDisc, strClientId, strRedirect, tupAuth[4])
        elif not tupAuth:
            record("SKIP", "token", "no authorization code, so no tokens to test", "")
        check_userinfo(mapDisc, strAccess, mapClaims)
        check_logout(mapDisc, strIdToken, strClientId, strRedirect)

    print("\n%d PASS, %d FAIL, %d WARN, %d SKIP, %d INFO" % tuple(
        LST_RESULT.count(s) for s in ("PASS", "FAIL", "WARN", "SKIP", "INFO")))
    return 1 if "FAIL" in LST_RESULT else 0


if __name__ == "__main__":
    sys.exit(main())
