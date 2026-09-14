// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwtmint;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The parts of an OpenID Provider a browser meets: the authorization endpoint
 * with its login page, UserInfo, and RP-initiated logout.
 *
 * <h2>The login is the production concept, without the hardening</h2>
 *
 * The application sends the browser here; the person types a name and a
 * password; the browser goes back to the application with a code, which the
 * application exchanges at the token endpoint. The application never sees the
 * password. That is how Keycloak does it, and it is why replacing this mint is a
 * change of settings. What is left out is what makes a provider secure rather
 * than correct: no registered clients or redirect URIs, no session, no consent,
 * no lockout. Any `redirect_uri` that is an absolute http or https URI is
 * accepted.
 *
 * <h2>No session</h2>
 *
 * Every authorization request shows the login page. `prompt=none` therefore
 * always answers `login_required`, which is what OpenID Connect Core 1.0
 * section 3.1.2.6 requires of a provider with no signed-in user, and a client
 * renews with its refresh token instead.
 *
 * Author Claude/bentzn
 */
@RestController
@Tag(name = "OpenID Connect", description = "Sign-in for browser applications:"
        + " the authorization code flow with PKCE, UserInfo and logout.")
public class OidcController {

    private static final String STR_NO_STORE = "no-store";

    private static final MediaType TYPE_HTML = MediaType.parseMediaType("text/html;charset=UTF-8");

    /** What the login form carries back to this endpoint, beside what was typed. */
    private static final Set<String> SET_PARAM_CARRIED = Set.of("response_type", "client_id",
            "redirect_uri", "scope", "state", "nonce", "code_challenge", "code_challenge_method",
            "audience", "response_mode");

    private final OidcFlow flow;

    private final OidcUsers users;

    private final IssuerResolver resolver;


    public OidcController(OidcFlow flow, OidcUsers users, IssuerResolver resolver) {
        this.flow = flow;
        this.users = users;
        this.resolver = resolver;
    }


    /**
     * The authorization endpoint, GET and POST alike - Core 3.1.2.1.
     *
     * A request without a name and password answers the login page. The login
     * page posts back here with the request's parameters and what was typed; a
     * match redirects to the client with a code, a mismatch shows the page
     * again.
     *
     * @param mapParam the request's parameters, query or form
     * @return the login page, an error page, or a redirect to the client
     */
    @Operation(summary = "Authorization endpoint - sign in",
            description = "**Standard: OpenID Connect Core 1.0 section 3.1.2**, the"
                    + " authorization code flow, with **RFC 7636** PKCE (S256).\n\n"
                    + "Open it in a browser with `response_type=code`, `client_id`,"
                    + " `redirect_uri` and `scope=openid` to see the login page.")
    @RequestMapping(value = IssuerResolver.STR_PATH_AUTHORIZE,
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> authorize(@RequestParam Map<String, String> mapParam) {
        String idClient = mapParam.get("client_id");
        String strRedirect = mapParam.get("redirect_uri");
        // NEVER REDIRECTED. Core 3.1.2.6 and RFC 6749 4.1.2.1: without a valid
        // redirect_uri the error cannot be sent anywhere trustworthy, so the
        // person is told here.
        if (isBlank(idClient))
            return page(HttpStatus.BAD_REQUEST, "Sign-in refused", "The request names no client_id.");
        if (!isRedirectUri(strRedirect)) {
            return page(HttpStatus.BAD_REQUEST, "Sign-in refused",
                    "redirect_uri is missing, or is not an absolute http or https URI without a fragment.");
        }

        String strState = mapParam.get("state");
        if (!"code".equals(mapParam.get("response_type"))) {
            return redirect(strRedirect, mapError("unsupported_response_type",
                    "only response_type=code is supported", strState));
        }
        String strChallenge = mapParam.get("code_challenge");
        if (!isBlank(strChallenge) && !OidcFlow.STR_METHOD_S256.equals(mapParam.get("code_challenge_method"))) {
            return redirect(strRedirect, mapError("invalid_request",
                    "code_challenge_method must be S256", strState));
        }

        if (mapParam.containsKey("username")) {
            String strUser = mapParam.get("username");
            if (!users.isValid(strUser, mapParam.get("password")))
                return loginPage(mapParam, true);
            String strCode = flow.strIssueCode(strUser, idClient, strRedirect,
                    mapParam.get("scope"), mapParam.get("audience"), strChallenge,
                    mapParam.get("nonce"));
            Map<String, String> mapQ = new LinkedHashMap<>();
            mapQ.put("code", strCode);
            if (strState != null)
                mapQ.put("state", strState);
            mapQ.put("iss", resolver.strIssuer());
            return redirect(strRedirect, mapQ);
        }

        if (hasPrompt(mapParam.get("prompt"), "none")) {
            return redirect(strRedirect, mapError("login_required",
                    "this provider keeps no session; the user has to sign in", strState));
        }
        return loginPage(mapParam, false);
    }


    /**
     * @param strAuthorization the `Authorization` header
     * @return the signed-in user's claims, or 401 with a Bearer challenge
     */
    @Operation(summary = "UserInfo endpoint",
            description = "**Standard: OpenID Connect Core 1.0 section 5.3**, GET and"
                    + " POST, with an access token this mint issued.")
    @RequestMapping(value = IssuerResolver.STR_PATH_USERINFO,
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> userinfo(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String strAuthorization) {
        String strToken = strBearer(strAuthorization);
        String strSub = strToken == null ? null : flow.strSubjectOf(strToken);
        if (strSub == null) {
            // RFC 6750 section 3: no token gets the bare challenge, a bad one
            // gets invalid_token.
            String strChallenge = "Bearer realm=\"" + resolver.strIssuer() + "\""
                    + (strToken == null ? "" : ", error=\"invalid_token\"");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, strChallenge).build();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sub", strSub);
        map.put("preferred_username", strSub);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, STR_NO_STORE).body(map);
    }


    /**
     * RP-initiated logout. There is no session to end, so this only sends the
     * browser back where the client asked.
     *
     * @param mapParam `post_logout_redirect_uri`, `state`, `id_token_hint`,
     *        `client_id`
     * @return a redirect to post_logout_redirect_uri, or a signed-out page
     */
    @Operation(summary = "End-session endpoint - sign out",
            description = "**Standard: OpenID Connect RP-Initiated Logout 1.0.**"
                    + " Redirects to `post_logout_redirect_uri` with `state`.")
    @RequestMapping(value = IssuerResolver.STR_PATH_LOGOUT,
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> logout(@RequestParam Map<String, String> mapParam) {
        String strPost = mapParam.get("post_logout_redirect_uri");
        if (isRedirectUri(strPost)) {
            Map<String, String> mapQ = new LinkedHashMap<>();
            if (mapParam.get("state") != null)
                mapQ.put("state", mapParam.get("state"));
            return redirect(strPost, mapQ);
        }
        return page(HttpStatus.OK, "Signed out", "This provider keeps no session; there is nothing more to end.");
    }


    private ResponseEntity<String> loginPage(Map<String, String> mapParam, boolean flagFailed) {
        StringBuilder sb = new StringBuilder();
        sb.append(strHead("Sign in"));
        sb.append("<h1>Sign in</h1>\n<p class=\"who\">to <b>").append(esc(mapParam.get("client_id")))
                .append("</b></p>\n");
        if (flagFailed)
            sb.append("<p class=\"err\">Invalid name or password.</p>\n");
        sb.append("<form method=\"post\" action=\"").append(IssuerResolver.STR_PATH_AUTHORIZE).append("\">\n");
        for (Map.Entry<String, String> entParam : mapParam.entrySet()) {
            if (!SET_PARAM_CARRIED.contains(entParam.getKey()))
                continue;
            sb.append("<input type=\"hidden\" name=\"").append(esc(entParam.getKey()))
                    .append("\" value=\"").append(esc(entParam.getValue())).append("\">\n");
        }
        String strUser = flagFailed ? mapParam.get("username") : "";
        sb.append("<label for=\"username\">Name</label>\n");
        sb.append("<input id=\"username\" name=\"username\" type=\"text\" autocomplete=\"username\""
                + " autofocus value=\"").append(esc(strUser)).append("\">\n");
        sb.append("<label for=\"password\">Password</label>\n");
        sb.append("<input id=\"password\" name=\"password\" type=\"password\""
                + " autocomplete=\"current-password\">\n");
        sb.append("<input type=\"submit\" value=\"Sign in\">\n</form>\n");
        sb.append("<p class=\"note\">jwtmint - a test identity provider. Not for production.</p>\n");
        sb.append("</body></html>\n");
        return ResponseEntity.ok().contentType(TYPE_HTML)
                .header(HttpHeaders.CACHE_CONTROL, STR_NO_STORE).body(sb.toString());
    }


    private static ResponseEntity<String> page(HttpStatus status, String strTitle, String strText) {
        String strBody = strHead(strTitle) + "<h1>" + esc(strTitle) + "</h1>\n<p>" + esc(strText)
                + "</p>\n</body></html>\n";
        return ResponseEntity.status(status).contentType(TYPE_HTML)
                .header(HttpHeaders.CACHE_CONTROL, STR_NO_STORE).body(strBody);
    }


    private static String strHead(String strTitle) {
        return "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + esc(strTitle) + " - jwtmint</title>\n<style>"
                + "body{font-family:system-ui,sans-serif;max-width:22rem;margin:4rem auto;padding:0 1rem}"
                + "label,input{display:block;width:100%;box-sizing:border-box;margin:.35rem 0}"
                + "input{padding:.45rem}input[type=submit]{margin-top:1rem;cursor:pointer}"
                + ".err{color:#b00020}.who,.note{color:#555}.note{font-size:.8rem;margin-top:2rem}"
                + "</style></head><body>\n";
    }


    /**
     * @param strBase the client's URI, possibly carrying a query already
     * @param mapQ the parameters to add, form-encoded
     * @return a 302 to it
     */
    private static ResponseEntity<String> redirect(String strBase, Map<String, String> mapQ) {
        StringBuilder sb = new StringBuilder(strBase);
        char chSep = strBase.indexOf('?') < 0 ? '?' : '&';
        for (Map.Entry<String, String> entQ : mapQ.entrySet()) {
            sb.append(chSep).append(enc(entQ.getKey())).append('=').append(enc(entQ.getValue()));
            chSep = '&';
        }
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, sb.toString())
                .header(HttpHeaders.CACHE_CONTROL, STR_NO_STORE).build();
    }


    private Map<String, String> mapError(String strError, String strDescription, String strState) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("error", strError);
        map.put("error_description", strDescription);
        if (strState != null)
            map.put("state", strState);
        map.put("iss", resolver.strIssuer());
        return map;
    }


    /**
     * @param str a candidate redirect URI
     * @return true when it is absolute, http or https, has a host and no
     *         fragment - RFC 6749 section 3.1.2
     */
    static boolean isRedirectUri(String str) {
        if (isBlank(str))
            return false;
        try {
            URI uri = new URI(str);
            String strScheme = uri.getScheme();
            return uri.isAbsolute() && uri.getHost() != null && uri.getRawFragment() == null
                    && ("http".equalsIgnoreCase(strScheme) || "https".equalsIgnoreCase(strScheme));
        }
        catch (URISyntaxException ex) {
            return false;
        }
    }


    private static boolean hasPrompt(String strPrompt, String strWant) {
        return OidcFlow.hasScope(strPrompt, strWant);
    }


    private static String strBearer(String strAuthorization) {
        if (strAuthorization == null)
            return null;
        String strTrim = strAuthorization.trim();
        if (strTrim.length() <= 7 || !strTrim.regionMatches(true, 0, "Bearer ", 0, 7))
            return null;
        return strTrim.substring(7).trim();
    }


    private static String enc(String str) {
        return URLEncoder.encode(str == null ? "" : str, StandardCharsets.UTF_8);
    }


    private static String esc(String str) {
        return HtmlUtils.htmlEscape(str == null ? "" : str);
    }


    private static boolean isBlank(String str) {
        return str == null || str.isBlank();
    }

}
