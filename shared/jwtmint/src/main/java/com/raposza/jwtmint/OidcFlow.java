// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwtmint;

import com.raposza.jwt.TokenException;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The OpenID Connect authorization code flow, behind the login page and the
 * token endpoint.
 *
 * <h2>What a code is bound to</h2>
 *
 * A code is issued to one signed-in user, for one client, one `redirect_uri`
 * and, when the request carried one, one PKCE `code_challenge`. The exchange
 * must present the same client and the same `redirect_uri`, and the verifier
 * whose S256 hash is the challenge - OpenID Connect Core 1.0 section 3.1.3.2 and
 * RFC 7636 section 4.6. A code lives sixty seconds and is spent by the first
 * attempt to exchange it, successful or not, so a replay finds nothing.
 *
 * <h2>What comes back</h2>
 *
 * An RS256 access token whose `sub` is the user and whose `aud` is the
 * `audience` the authorization request asked for - the parameter the Splice
 * web UIs send - or the client when it asked for none. An ID token for the
 * client, carrying the nonce and `at_hash`, when the scope holds `openid`. A
 * refresh token, which renews the access token for as long as this process
 * runs.
 *
 * Everything is held in memory. A restart signs everybody out, which on a test
 * system is a feature.
 *
 * Author Claude/bentzn
 * Generated 2026-09-11T18:30:00Z
 */
@Service
public class OidcFlow {

    public static final String STR_SCOPE_OPENID = "openid";

    public static final String STR_METHOD_S256 = "S256";

    public static final String STR_GRANT_CODE = "authorization_code";

    public static final String STR_GRANT_REFRESH = "refresh_token";

    private static final Duration DUR_CODE = Duration.ofSeconds(60);

    /** The lifetime a login-issued token gets when the mint's own default writes no `exp`. */
    private static final long N_TTL_SECONDS_FLOOR = 3600L;

    /** RFC 7636 section 4.1: 43 to 128 unreserved characters. */
    private static final Pattern PAT_VERIFIER = Pattern.compile("[A-Za-z0-9._~-]{43,128}");

    private static final int N_BYTES_HANDLE = 32;

    private final SecureRandom random = new SecureRandom();

    private final Map<String, CodeGrant> mapCode = new ConcurrentHashMap<>();

    private final Map<String, Grant> mapRefresh = new ConcurrentHashMap<>();

    private final MintService minter;

    private final MintKeyStore store;

    private final IssuerResolver resolver;

    private final MintSettings settings;


    /**
     * What a sign-in grants: who, to which client, for what.
     *
     * @param strUser the signed-in user, the `sub`
     * @param idClient the client it was granted to
     * @param strScope the scope asked for, possibly null
     * @param strAudience the `audience` asked for, possibly null
     * @param instAuth when the user signed in
     */
    record Grant(String strUser, String idClient, String strScope, String strAudience,
            Instant instAuth) {
    }


    /**
     * A code and what it is bound to.
     *
     * @param grant what the code stands for
     * @param strRedirect the `redirect_uri` of the authorization request
     * @param strChallenge the PKCE challenge, or null when none was sent
     * @param strNonce the nonce, or null when none was sent
     * @param instExpires when the code stops being accepted
     */
    record CodeGrant(Grant grant, String strRedirect, String strChallenge, String strNonce,
            Instant instExpires) {
    }


    public OidcFlow(MintService minter, MintKeyStore store, IssuerResolver resolver,
            MintSettings settings) {
        this.minter = minter;
        this.store = store;
        this.resolver = resolver;
        this.settings = settings;
    }


    /**
     * @param strUser the signed-in user
     * @param idClient the client the code is for
     * @param strRedirect the `redirect_uri` it will be delivered to
     * @param strScope the scope asked for
     * @param strAudience the `audience` asked for
     * @param strChallenge the S256 PKCE challenge, or null
     * @param strNonce the nonce, or null
     * @return the code
     */
    public String strIssueCode(String strUser, String idClient, String strRedirect,
            String strScope, String strAudience, String strChallenge, String strNonce) {
        purge();
        String strCode = strHandle();
        Instant instNow = Instant.now();
        mapCode.put(strCode, new CodeGrant(
                new Grant(strUser, idClient, blankToNull(strScope), blankToNull(strAudience), instNow),
                strRedirect, blankToNull(strChallenge), blankToNull(strNonce), instNow.plus(DUR_CODE)));
        return strCode;
    }


    /**
     * The `authorization_code` grant.
     *
     * @param strCode the code
     * @param idClient the client presenting it
     * @param strRedirect the `redirect_uri` it presents
     * @param strVerifier the PKCE verifier, or null
     * @return the token response
     * @throws OidcException invalid_request or invalid_grant
     */
    public Map<String, Object> mapExchangeCode(String strCode, String idClient,
            String strRedirect, String strVerifier) {
        if (isBlank(strCode))
            throw new OidcException("invalid_request", "code is required");
        if (isBlank(idClient))
            throw new OidcException("invalid_request", "client_id is required");

        // REMOVED BEFORE IT IS CHECKED. The attempt spends the code whatever
        // its outcome, so a code that leaked into a log or a history cannot be
        // tried twice.
        CodeGrant code = mapCode.remove(strCode);
        if (code == null || Instant.now().isAfter(code.instExpires()))
            throw new OidcException("invalid_grant", "the code is unknown, expired or already used");
        if (!code.grant().idClient().equals(idClient))
            throw new OidcException("invalid_grant", "the code was issued to another client");
        if (!code.strRedirect().equals(strRedirect))
            throw new OidcException("invalid_grant",
                    "redirect_uri is not the one the authorization request carried");
        if (code.strChallenge() != null) {
            if (isBlank(strVerifier) || !PAT_VERIFIER.matcher(strVerifier).matches())
                throw new OidcException("invalid_grant", "code_verifier is missing or malformed");
            if (!code.strChallenge().equals(strS256(strVerifier)))
                throw new OidcException("invalid_grant", "code_verifier does not match the code_challenge");
        }

        Map<String, Object> map = mapTokens(code.grant());
        String strRefresh = strHandle();
        mapRefresh.put(strRefresh, code.grant());
        map.put("refresh_token", strRefresh);
        if (hasScope(code.grant().strScope(), STR_SCOPE_OPENID))
            map.put("id_token", strIdToken(code.grant(), code.strNonce(), (String) map.get("access_token")));
        return map;
    }


    /**
     * The `refresh_token` grant. The refresh token is not rotated, and no new
     * ID token is issued: OpenID Connect Core 1.0 section 12.2 leaves both to
     * the provider.
     *
     * @param strRefresh the refresh token
     * @param idClient the client presenting it, or null when it did not say
     * @return the token response
     * @throws OidcException invalid_request or invalid_grant
     */
    public Map<String, Object> mapRefresh(String strRefresh, String idClient) {
        if (isBlank(strRefresh))
            throw new OidcException("invalid_request", "refresh_token is required");
        Grant grant = mapRefresh.get(strRefresh);
        if (grant == null)
            throw new OidcException("invalid_grant", "the refresh token is unknown");
        if (!isBlank(idClient) && !grant.idClient().equals(idClient))
            throw new OidcException("invalid_grant", "the refresh token was issued to another client");

        Map<String, Object> map = mapTokens(grant);
        map.put("refresh_token", strRefresh);
        return map;
    }


    /**
     * The subject of a token this mint signed, for the UserInfo endpoint.
     *
     * @param strToken a compact JWS
     * @return its `sub` when it verifies against this mint's keys, carries this
     *         issuer and has not expired; null otherwise
     */
    public String strSubjectOf(String strToken) {
        try {
            SignedJWT jwt = SignedJWT.parse(strToken);
            String strKid = jwt.getHeader().getKeyID();
            if (isBlank(strKid))
                return null;
            if (!jwt.verify(verifier(store.keys().jwkOfKid(strKid))))
                return null;
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!resolver.strIssuer().equals(claims.getIssuer()))
                return null;
            Date dateExp = claims.getExpirationTime();
            if (dateExp != null && dateExp.toInstant().isBefore(Instant.now()))
                return null;
            return claims.getSubject();
        }
        catch (ParseException | JOSEException | TokenException | ClassCastException ex) {
            return null;
        }
    }


    /**
     * @param strVerifier a PKCE verifier
     * @return BASE64URL(SHA256(ASCII(verifier))), RFC 7636 section 4.2
     */
    static String strS256(String strVerifier) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(arrSha256(strVerifier.getBytes(StandardCharsets.US_ASCII)));
    }


    /**
     * @param strAccess an access token
     * @return its `at_hash`: the left half of its SHA-256, base64url,
     *         OpenID Connect Core 1.0 section 3.1.3.6
     */
    static String strHalfHash(String strAccess) {
        byte[] arrHash = arrSha256(strAccess.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Arrays.copyOf(arrHash, arrHash.length / 2));
    }


    /**
     * @param strScope a space-separated scope, or null
     * @param strWant one scope value
     * @return true when strScope holds strWant
     */
    static boolean hasScope(String strScope, String strWant) {
        if (strScope == null)
            return false;
        for (String strPart : strScope.trim().split("\\s+")) {
            if (strPart.equals(strWant))
                return true;
        }
        return false;
    }


    private Map<String, Object> mapTokens(Grant grant) {
        long nTtl = nTtl();
        String strAud = grant.strAudience() == null ? grant.idClient() : grant.strAudience();
        Map<String, Object> mapClaim = new LinkedHashMap<>();
        mapClaim.put("azp", grant.idClient());
        MintResponse resp = minter.mint(new MintRequest("RS256", null, grant.strUser(), null,
                List.of(strAud), grant.strScope(), Long.valueOf(nTtl), null, null, null, null,
                null, null, null, mapClaim, null), resolver.strIssuer());

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("access_token", resp.token());
        map.put("token_type", "Bearer");
        map.put("expires_in", Long.valueOf(nTtl));
        if (grant.strScope() != null)
            map.put("scope", grant.strScope());
        return map;
    }


    private String strIdToken(Grant grant, String strNonce, String strAccess) {
        Map<String, Object> mapClaim = new LinkedHashMap<>();
        mapClaim.put("azp", grant.idClient());
        mapClaim.put("auth_time", Long.valueOf(grant.instAuth().getEpochSecond()));
        mapClaim.put("at_hash", strHalfHash(strAccess));
        mapClaim.put("preferred_username", grant.strUser());
        if (strNonce != null)
            mapClaim.put("nonce", strNonce);
        return minter.mint(new MintRequest("RS256", null, grant.strUser(), null,
                List.of(grant.idClient()), null, Long.valueOf(nTtl()), null, null, null, null,
                null, null, null, mapClaim, null), resolver.strIssuer()).token();
    }


    /**
     * @return the mint's default lifetime, or an hour when that default is to
     *         write no `exp` - a signed-in user's token always carries one
     */
    private long nTtl() {
        long nTtl = settings.nTtlSecondsDefault();
        return nTtl > 0 ? nTtl : N_TTL_SECONDS_FLOOR;
    }


    private void purge() {
        Instant instNow = Instant.now();
        mapCode.values().removeIf(code -> instNow.isAfter(code.instExpires()));
    }


    private String strHandle() {
        byte[] arr = new byte[N_BYTES_HANDLE];
        random.nextBytes(arr);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(arr);
    }


    private static JWSVerifier verifier(JWK jwk) throws JOSEException {
        KeyType kty = jwk.getKeyType();
        if (KeyType.RSA.equals(kty))
            return new RSASSAVerifier(((RSAKey) jwk).toPublicJWK());
        if (KeyType.EC.equals(kty))
            return new ECDSAVerifier(((ECKey) jwk).toPublicJWK());
        if (KeyType.OCT.equals(kty))
            return new MACVerifier((OctetSequenceKey) jwk);
        throw new JOSEException("key type " + kty + " cannot verify");
    }


    private static byte[] arrSha256(byte[] arrIn) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(arrIn);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", ex);
        }
    }


    private static String blankToNull(String str) {
        return isBlank(str) ? null : str;
    }


    private static boolean isBlank(String str) {
        return str == null || str.isBlank();
    }

}
