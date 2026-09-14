// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwtmint;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The OAuth and OpenID Connect token surface: the discovery document and the
 * token endpoint.
 *
 * <h2>jwtmint is NOT a replacement for Keycloak, and never will be</h2>
 *
 * It stands in for a production identity provider on a test system. It speaks
 * the same protocol - OpenID Connect Discovery and Core, RFC 7636 PKCE,
 * RP-Initiated Logout - so that a relying party configured against it moves to
 * Keycloak by changing settings and nothing else. `oidc_check.py` at the root
 * of this repository is the contract both are held to.
 *
 * <h2>Two grants for people, one for machines</h2>
 *
 * `authorization_code` is what a browser application uses: the person signs in
 * at {@link OidcController}'s login page and the application exchanges the code
 * here, with its PKCE verifier. `refresh_token` renews what that produced. Both
 * are checked as OpenID Connect Core and RFC 7636 require - {@link OidcFlow}.
 *
 * `client_credentials` is the machine grant, and it stays deliberately
 * unchecked: any client id, any secret, and the request decides what the token
 * carries. That is the difference from the local test identity provider in
 * raposza-test-idp, which registers clients in its constructor and IGNORES the
 * scope a request asks for, precisely so it cannot be talked into minting
 * something it was not configured for. That is the right shape for a fixture
 * inside a test. It is the wrong shape here: a developer can ask this service
 * for any token at all without editing and restarting anything, including
 * tokens that should be refused. `client_id` becomes the subject when nothing
 * else says otherwise, and the secret is not checked.
 *
 * <h2>What the discovery document is</h2>
 *
 * An OpenID Provider configuration, OpenID Connect Discovery 1.0 section 3,
 * served unchanged at the RFC 8414 location as well. It advertises what is
 * built and nothing else: the authorization, token, UserInfo, JWKS and
 * end-session endpoints, the code response type, S256 PKCE, and RS256 ID
 * tokens. A member with nothing to say is left out, because Discovery section
 * 4.2 forbids an empty array.
 *
 * <h2>Every token path is served under three names</h2>
 *
 * `/oauth2/token` is the project convention and the one the discovery document
 * advertises. `/oauth/token` and `/token` are kept because configurations
 * written before the convention point at them. A conforming client reads
 * `token_endpoint` and is unaffected by any of this.
 *
 * Author Claude/bentzn
 * Revised for OpenID Connect 2026-09-11T18:30:00Z
 */
@RestController
@Tag(name = "OAuth", description = "The discovery document and the token endpoint:"
        + " authorization_code and refresh_token for people, client_credentials for machines.")
public class OAuthController {

    private static final String STR_TOKEN_TYPE = "Bearer";

    private static final String STR_BASIC = "Basic ";

    private final MintService minter;

    private final IssuerResolver resolver;

    private final OidcFlow flow;


    public OAuthController(MintService minter, IssuerResolver resolver, OidcFlow flow) {
        this.minter = minter;
        this.resolver = resolver;
        this.flow = flow;
    }


    /**
     * @return the OpenID Provider configuration; scribe takes
     *         `--pipeline-oauth-issuer` beside its endpoint and this is what
     *         answers it
     */
    @Operation(summary = "OpenID Provider configuration",
            description = "**Standard: OpenID Connect Discovery 1.0**, section 3,"
                    + " and the same document at the **RFC 8414** location.\n\n"
                    + "Metadata ONLY - this is NOT the key set. It carries a"
                    + " `jwks_uri` field, and THAT is what a participant's"
                    + " `url` must be set to.\n\n"
                    + "`issuer` here and the `iss` claim of every token this"
                    + " service mints are the same string, resolved once at"
                    + " startup. Pin it with `raposza.jwtmint.issuer` when"
                    + " the verifier reaches this machine by some other"
                    + " address.\n\n"
                    + "```\n"
                    + "curl http://localhost:32002/.well-known/openid-configuration\n"
                    + "```")
    @GetMapping({IssuerResolver.STR_PATH_DISCOVERY_OAUTH,
            IssuerResolver.STR_PATH_DISCOVERY_OIDC})
    public Map<String, Object> mapDiscovery() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("issuer", resolver.strIssuer());
        map.put("authorization_endpoint", resolver.strUrl(IssuerResolver.STR_PATH_AUTHORIZE));
        map.put("token_endpoint", resolver.strUrl(IssuerResolver.STR_PATH_TOKEN));
        map.put("userinfo_endpoint", resolver.strUrl(IssuerResolver.STR_PATH_USERINFO));
        map.put("jwks_uri", resolver.strUrl(IssuerResolver.STR_PATH_JWKS));
        map.put("end_session_endpoint", resolver.strUrl(IssuerResolver.STR_PATH_LOGOUT));
        map.put("response_types_supported", List.of("code"));
        map.put("response_modes_supported", List.of("query"));
        map.put("grant_types_supported", List.of(OidcFlow.STR_GRANT_CODE,
                OidcFlow.STR_GRANT_REFRESH, "client_credentials"));
        map.put("subject_types_supported", List.of("public"));
        map.put("id_token_signing_alg_values_supported", List.of("RS256"));
        map.put("code_challenge_methods_supported", List.of(OidcFlow.STR_METHOD_S256));
        map.put("token_endpoint_auth_methods_supported",
                List.of("none", "client_secret_basic", "client_secret_post"));
        map.put("scopes_supported", List.of(OidcFlow.STR_SCOPE_OPENID, MintService.STR_SCOPE_DEFAULT));
        map.put("claims_supported", List.of("iss", "sub", "aud", "exp", "iat", "nbf", "jti",
                "auth_time", "nonce", "at_hash", "azp", "scope", "preferred_username"));
        // RFC 9207: every authorization response carries iss, so a client can
        // tell which provider answered it.
        map.put("authorization_response_iss_parameter_supported", Boolean.TRUE);
        map.put("service_documentation", resolver.strUrl("/swagger-ui.html"));
        return map;
    }


    /**
     * The form-encoded grant.
     *
     * @param strAuthorization an RFC 6749 section 2.3.1 Basic credential, read
     *        for its client id only
     * @param strGrantType accepted and recorded, never enforced
     * @param strClientId becomes the subject when no `sub` is given
     * @param strClientSecret accepted and NOT checked
     * @param strScope the scope to put in the token
     * @param strAudience the audience to put in the token
     * @param strSub an explicit subject, winning over client_id
     * @param strAlg which algorithm to sign with
     * @param strKid which key to sign with
     * @param strShape AUDIENCE, SCOPE, CUSTOM or RAW
     * @param idParticipant used to build the audience for shape AUDIENCE
     * @param nTtlSeconds lifetime
     * @param strSecret an HS* shared secret to sign with instead of ours
     * @param strCode authorization_code: the code
     * @param strRedirectUri authorization_code: the redirect_uri of the
     *        authorization request
     * @param strCodeVerifier authorization_code: the PKCE verifier
     * @param strRefreshToken refresh_token: the refresh token
     * @return an OAuth token response, never cached
     */
    @PostMapping(value = {IssuerResolver.STR_PATH_TOKEN, "/oauth/token", "/token"},
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @Operation(summary = "authorization_code, refresh_token, client_credentials - form encoded",
            description = "`authorization_code` and `refresh_token`: **OpenID"
                    + " Connect Core 1.0** sections 3.1.3 and 12, with **RFC 7636**"
                    + " PKCE. The code comes from signing in at the authorization"
                    + " endpoint; it is single use and bound to its client,"
                    + " redirect_uri and code_challenge.\n\n"
                    + "Anything else, `client_credentials` included: **RFC 6749**,"
                    + " section 4.4 - the OAuth 2.0"
                    + " client credentials grant. The request is form encoded"
                    + " per section 3.2 and the response follows section 5.1;"
                    + " `kid`, `alg` and `grant_type` are extra members this"
                    + " service adds. The path is NOT fixed by the standard -"
                    + " a client takes it from the discovery document, and"
                    + " `/oauth/token` and `/token` serve the same thing.\n\n"
                    + "Credentials are read from an `Authorization: Basic`"
                    + " header as well as from the body, per RFC 6749 section"
                    + " 2.3.1. Only the client id is used, and only as a"
                    + " fallback subject; `sub` and a body `client_id` both"
                    + " win over it.\n\n"
                    + "**UNSAFE. For development use only.** The secret is NOT"
                    + " checked and no client is registered: the request"
                    + " decides what the token carries.\n\n"
                    + "```\n"
                    + "curl -X POST http://localhost:32002/oauth2/token"
                    + " -d grant_type=client_credentials -d client_id=alice"
                    + " -d client_secret=anything -d shape=SCOPE\n"
                    + "```")
    public ResponseEntity<Map<String, Object>> mapTokenForm(
            @Parameter(hidden = true)
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
            String strAuthorization,
            @Parameter(description = "The grant. Recorded and echoed back, never enforced.",
                    example = "client_credentials")
            @RequestParam(name = "grant_type", required = false) String strGrantType,
            @Parameter(description = "Becomes the `sub` claim when no `sub` is sent.",
                    example = "participant_admin")
            @RequestParam(name = "client_id", required = false) String strClientId,
            @Parameter(description = "Accepted and NOT checked. Anything at all.",
                    example = "anything")
            @RequestParam(name = "client_secret", required = false) String strClientSecret,
            @Parameter(description = "The `scope` claim. What a SCOPE-shaped participant checks.",
                    example = "daml_ledger_api")
            @RequestParam(name = "scope", required = false) String strScope,
            @Parameter(description = "The `aud` claim. What an AUDIENCE-shaped participant checks.",
                    example = "https://daml.com/jwt/aud/participant/sandbox")
            @RequestParam(name = "audience", required = false) String strAudience,
            @Parameter(description = "An explicit subject, winning over client_id.",
                    example = "participant_admin")
            @RequestParam(name = "sub", required = false) String strSub,
            @Parameter(description = "Signing algorithm. RS256 is what a JWKS participant reads.",
                    example = "RS256")
            @RequestParam(name = "alg", required = false) String strAlg,
            @Parameter(description = "A specific key, overriding the one `alg` picks."
                    + " The kid is the algorithm in lower case.",
                    example = "rs256")
            @RequestParam(name = "kid", required = false) String strKid,
            @Parameter(description = "AUDIENCE, SCOPE, CUSTOM or RAW.", example = "SCOPE")
            @RequestParam(name = "shape", required = false) String strShape,
            @Parameter(description = "The participant the AUDIENCE shape builds its audience from.",
                    example = "sandbox")
            @RequestParam(name = "participant_id", required = false) String idParticipant,
            @Parameter(description = "Lifetime in seconds. 0 or less writes no `exp` at all.",
                    example = "3600")
            @RequestParam(name = "ttl_seconds", required = false) Long nTtlSeconds,
            @Parameter(description = "The HS* shared secret to sign with, INSTEAD of"
                    + " this service's own key. The UTF-8 bytes of it are the HMAC key,"
                    + " which is the reading Canton takes of the `secret` in an"
                    + " `unsafe-jwt-hmac-256` auth-service, so a participant configured"
                    + " with one can be handed a token it will verify. Ignored for the"
                    + " asymmetric algorithms. At least 32 bytes for HS256, per RFC 7518"
                    + " section 3.2.",
                    example = "raposza-sandbox-unsafe-shared-secret")
            @RequestParam(name = "secret", required = false) String strSecret,
            @Parameter(description = "authorization_code: the code the authorization endpoint issued.")
            @RequestParam(name = "code", required = false) String strCode,
            @Parameter(description = "authorization_code: the redirect_uri the authorization request carried.")
            @RequestParam(name = "redirect_uri", required = false) String strRedirectUri,
            @Parameter(description = "authorization_code: the PKCE code_verifier.")
            @RequestParam(name = "code_verifier", required = false) String strCodeVerifier,
            @Parameter(description = "refresh_token: the refresh token.")
            @RequestParam(name = "refresh_token", required = false) String strRefreshToken) {
        // A PARAMETER OF ITS OWN rather than a second meaning for
        // `client_secret`. That one is documented as accepted and never
        // checked, and every existing caller sends something arbitrary in it;
        // reading it as signing material would sign their tokens with a string
        // chosen to be ignored, and most of those are under the 32 bytes HS256
        // needs, so the failure would arrive as a refusal from the mint rather
        // than as anything to do with what they changed.
        String strClient = isBlank(strClientId)
                ? strClientIdOfBasic(strAuthorization) : strClientId;

        // THE PEOPLE'S GRANTS ARE CHECKED; everything else keeps the unchecked
        // mint below, which is what existing callers rely on.
        if (OidcFlow.STR_GRANT_CODE.equals(strGrantType)
                || OidcFlow.STR_GRANT_REFRESH.equals(strGrantType)) {
            try {
                Map<String, Object> map = OidcFlow.STR_GRANT_CODE.equals(strGrantType)
                        ? flow.mapExchangeCode(strCode, strClient, strRedirectUri, strCodeVerifier)
                        : flow.mapRefresh(strRefreshToken, strClient);
                return noStore(ResponseEntity.ok()).body(map);
            }
            catch (OidcException ex) {
                Map<String, Object> mapErr = new LinkedHashMap<>();
                mapErr.put("error", ex.strError());
                mapErr.put("error_description", ex.getMessage());
                return noStore(ResponseEntity.status(ex.status())).body(mapErr);
            }
        }

        MintRequest req = new MintRequest(strAlg, strKid,
                isBlank(strSub) ? strClient : strSub,
                null,
                isBlank(strAudience) ? null : List.of(strAudience),
                strScope, nTtlSeconds, strShape, null, null, null, null, null,
                idParticipant, null, strSecret);
        return noStore(ResponseEntity.ok()).body(mapOf(minter.mint(req, resolver.strIssuer()),
                strGrantType));
    }


    /**
     * The same grant with a JSON body, for clients that post JSON.
     *
     * @param req the request, in {@link MintRequest} shape
     * @return an OAuth token response, never cached
     */
    @PostMapping(value = {IssuerResolver.STR_PATH_TOKEN, "/oauth/token", "/token"},
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "client_credentials, JSON body",
            description = "**Not a standard.** RFC 6749 section 3.2 specifies form"
                    + " encoding for the token endpoint; this variant exists for"
                    + " clients that post JSON, and the response is still the"
                    + " section 5.1 shape.\n\n"
                    + "**UNSAFE. For development use only.** The body is a"
                    + " `MintRequest`, so anything `/mint` accepts works here"
                    + " too.\n\n"
                    + "```\ncurl -X POST http://localhost:32002/oauth2/token \\\n"
                    + "  -H 'content-type: application/json' \\\n"
                    + "  -d '{\"sub\":\"participant_admin\",\"shape\":\"SCOPE\","
                    + "\"ttlSeconds\":3600}'\n```")
    public ResponseEntity<Map<String, Object>> mapTokenJson(
            @RequestBody(required = false) MintRequest req) {
        return noStore(ResponseEntity.ok()).body(mapOf(minter.mint(req, resolver.strIssuer()),
                "client_credentials"));
    }


    /**
     * RFC 6749 section 5.1 and OpenID Connect Core 1.0 section 3.1.3.3: a
     * response carrying tokens is never cached.
     *
     * @param bld the response being built
     * @return it, with Cache-Control no-store and Pragma no-cache
     */
    private static ResponseEntity.BodyBuilder noStore(ResponseEntity.BodyBuilder bld) {
        return bld.header(HttpHeaders.CACHE_CONTROL, "no-store").header(HttpHeaders.PRAGMA, "no-cache");
    }


    /**
     * The client id out of an RFC 6749 section 2.3.1 Basic credential.
     *
     * Some clients transmit their credentials this way rather than as body
     * parameters, so before this the client id arrived as null and the subject
     * fell back to the service default. Sending `sub` in the endpoint query
     * still wins; this makes the header work for callers that do not.
     *
     * The secret half is discarded rather than checked, which is the whole
     * posture of this service.
     *
     * @param strAuthorization the header value, or null
     * @return the client id, or null when there is not one to be had
     */
    private static String strClientIdOfBasic(String strAuthorization) {
        if (strAuthorization == null || !strAuthorization.startsWith(STR_BASIC))
            return null;

        try {
            byte[] arrRaw = Base64.getDecoder()
                    .decode(strAuthorization.substring(STR_BASIC.length()).trim());
            String strPair = new String(arrRaw, StandardCharsets.UTF_8);
            int idxColon = strPair.indexOf(':');
            String strId = idxColon < 0 ? strPair : strPair.substring(0, idxColon);
            // section 2.3.1 encodes both halves as application/x-www-form-urlencoded
            // before base64, so a client id with a reserved character arrives
            // percent-encoded and has to come back out.
            String strOut = URLDecoder.decode(strId, StandardCharsets.UTF_8);
            return strOut.isBlank() ? null : strOut;
        }
        catch (IllegalArgumentException ex) {
            // not base64, or not a decodable pair. A malformed credential is
            // not an error here: nothing is being authenticated.
            return null;
        }
    }


    private static boolean isBlank(String strValue) {
        return strValue == null || strValue.isBlank();
    }


    /**
     * @param resp what was minted
     * @param strGrantType echoed back so a caller can see what was understood
     * @return the OAuth response body
     */
    private static Map<String, Object> mapOf(MintResponse resp, String strGrantType) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("access_token", resp.token());
        map.put("token_type", STR_TOKEN_TYPE);
        if (resp.expiresIn() > 0)
            map.put("expires_in", Long.valueOf(resp.expiresIn()));
        Object objScope = resp.claims().get("scope");
        if (objScope != null)
            map.put("scope", objScope);
        // NOT part of OAuth. It is here because the first question about a
        // refused token is always which key signed it.
        map.put("kid", resp.kid());
        map.put("alg", resp.alg());
        map.put("grant_type", strGrantType);
        return map;
    }

}
