// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwtmint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The authorization code flow without a servlet: what a code is bound to, what
 * the tokens carry, and that every binding refuses when it should.
 *
 * Author Claude/bentzn
 * Generated 2026-09-11T18:30:00Z
 */
class OidcFlowTest {

    private static final String STR_ISSUER = "http://127.0.0.1:32002";

    private static final String STR_CLIENT = "wallet-ui";

    private static final String STR_REDIRECT = "http://wallet.localhost:4000";

    private static final String STR_AUDIENCE = "https://validator.example.com";

    /** 43 characters, the RFC 7636 minimum. */
    private static final String STR_VERIFIER = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";

    @TempDir
    static Path dirKeys;

    private static OidcFlow flow;

    private static MintService minter;


    @BeforeAll
    static void buildTheFlow() {
        MintSettings settings = new MintSettings(dirKeys.toString(), STR_ISSUER, 3600L, "RS256",
                "raposza");
        MintKeyStore store = new MintKeyStore(settings);
        minter = new MintService(store, settings);
        flow = new OidcFlow(minter, store, new IssuerResolver(settings, 32002), settings);
    }


    private static String strCode(String strScope) {
        return flow.strIssueCode("sv-user", STR_CLIENT, STR_REDIRECT, strScope, STR_AUDIENCE,
                OidcFlow.strS256(STR_VERIFIER), "nonce-1");
    }


    private static String strErrorOf(Runnable run) {
        OidcException ex = assertThrows(OidcException.class, run::run);
        return ex.strError();
    }


    @Test
    void theRightVerifierYieldsTokensForTheUser() throws Exception {
        Map<String, Object> map = flow.mapExchangeCode(strCode("openid daml_ledger_api"), STR_CLIENT,
                STR_REDIRECT, STR_VERIFIER);

        assertEquals("Bearer", map.get("token_type"));
        assertNotNull(map.get("refresh_token"));
        String strAccess = (String) map.get("access_token");
        JWTClaimsSet claimsAccess = SignedJWT.parse(strAccess).getJWTClaimsSet();
        assertEquals("sv-user", claimsAccess.getSubject());
        assertEquals(STR_ISSUER, claimsAccess.getIssuer());
        assertEquals(List.of(STR_AUDIENCE), claimsAccess.getAudience());
        assertEquals("RS256", SignedJWT.parse(strAccess).getHeader().getAlgorithm().getName());

        JWTClaimsSet claimsId = SignedJWT.parse((String) map.get("id_token")).getJWTClaimsSet();
        assertEquals("sv-user", claimsId.getSubject());
        assertEquals(List.of(STR_CLIENT), claimsId.getAudience());
        assertEquals("nonce-1", claimsId.getStringClaim("nonce"));
        assertEquals(OidcFlow.strHalfHash(strAccess), claimsId.getStringClaim("at_hash"));
        assertNotNull(claimsId.getExpirationTime());

        assertEquals("sv-user", flow.strSubjectOf(strAccess));
    }


    @Test
    void aCodeIsSpentByItsFirstUse() {
        String strCode = strCode("openid");
        flow.mapExchangeCode(strCode, STR_CLIENT, STR_REDIRECT, STR_VERIFIER);
        assertEquals("invalid_grant",
                strErrorOf(() -> flow.mapExchangeCode(strCode, STR_CLIENT, STR_REDIRECT, STR_VERIFIER)));
    }


    @Test
    void aFailedExchangeSpendsTheCodeToo() {
        String strCode = strCode("openid");
        strErrorOf(() -> flow.mapExchangeCode(strCode, STR_CLIENT, STR_REDIRECT, STR_VERIFIER + "x"));
        assertEquals("invalid_grant",
                strErrorOf(() -> flow.mapExchangeCode(strCode, STR_CLIENT, STR_REDIRECT, STR_VERIFIER)));
    }


    @Test
    void aWrongOrMissingVerifierIsRefused() {
        assertEquals("invalid_grant", strErrorOf(() -> flow.mapExchangeCode(strCode("openid"),
                STR_CLIENT, STR_REDIRECT, STR_VERIFIER.replace('a', 'b'))));
        assertEquals("invalid_grant", strErrorOf(() -> flow.mapExchangeCode(strCode("openid"),
                STR_CLIENT, STR_REDIRECT, null)));
    }


    @Test
    void anotherRedirectOrClientIsRefused() {
        assertEquals("invalid_grant", strErrorOf(() -> flow.mapExchangeCode(strCode("openid"),
                STR_CLIENT, STR_REDIRECT + "/other", STR_VERIFIER)));
        assertEquals("invalid_grant", strErrorOf(() -> flow.mapExchangeCode(strCode("openid"),
                "another-client", STR_REDIRECT, STR_VERIFIER)));
        assertEquals("invalid_request", strErrorOf(() -> flow.mapExchangeCode(strCode("openid"),
                null, STR_REDIRECT, STR_VERIFIER)));
    }


    @Test
    void anUnknownCodeIsRefused() {
        assertEquals("invalid_grant",
                strErrorOf(() -> flow.mapExchangeCode("no-such-code", STR_CLIENT, STR_REDIRECT, STR_VERIFIER)));
    }


    @Test
    void withoutOpenidThereIsNoIdToken() {
        Map<String, Object> map = flow.mapExchangeCode(strCode("daml_ledger_api"), STR_CLIENT,
                STR_REDIRECT, STR_VERIFIER);
        assertNotNull(map.get("access_token"));
        assertFalse(map.containsKey("id_token"));
    }


    @Test
    void aRefreshTokenRenewsForItsOwnClientOnly() throws Exception {
        Map<String, Object> map = flow.mapExchangeCode(strCode("openid"), STR_CLIENT, STR_REDIRECT,
                STR_VERIFIER);
        String strRefresh = (String) map.get("refresh_token");

        Map<String, Object> mapNew = flow.mapRefresh(strRefresh, STR_CLIENT);
        assertEquals("sv-user",
                SignedJWT.parse((String) mapNew.get("access_token")).getJWTClaimsSet().getSubject());
        assertEquals("invalid_grant", strErrorOf(() -> flow.mapRefresh(strRefresh, "another-client")));
        assertEquals("invalid_grant", strErrorOf(() -> flow.mapRefresh("no-such-token", STR_CLIENT)));
    }


    @Test
    void aTokenFromAnotherIssuerHasNoSubjectHere() {
        MintResponse resp = minter.mint(new MintRequest("RS256", null, "mallory", "http://elsewhere",
                null, null, null, null, null, null, null, null, null, null, null, null), STR_ISSUER);
        assertNull(flow.strSubjectOf(resp.token()));
        assertNull(flow.strSubjectOf("not.a.token"));
    }


    @Test
    void theUserStoreChecksTheNameAndThePassword() {
        OidcUsers users = new OidcUsers("sv-user:123456, alice:pw:with:colons");
        assertTrue(users.isValid("sv-user", "123456"));
        assertTrue(users.isValid("alice", "pw:with:colons"));
        assertFalse(users.isValid("sv-user", "1234567"));
        assertFalse(users.isValid("nobody", "123456"));
        assertFalse(users.isValid(null, null));
        assertEquals(List.of("sv-user", "alice"), users.lstName());
        assertThrows(IllegalArgumentException.class, () -> new OidcUsers("no-colon"));
        assertTrue(new OidcUsers("").lstName().isEmpty());
    }


    @Test
    void theRedirectUriMustBeAbsoluteHttpWithoutAFragment() {
        assertTrue(OidcController.isRedirectUri("http://wallet.localhost:4000"));
        assertTrue(OidcController.isRedirectUri("https://app.example.com/cb?x=1"));
        assertFalse(OidcController.isRedirectUri("/relative"));
        assertFalse(OidcController.isRedirectUri("http://app.example.com/cb#frag"));
        assertFalse(OidcController.isRedirectUri("javascript:alert(1)"));
        assertFalse(OidcController.isRedirectUri(null));
    }

}
