// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwtmint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raposza.jwt.TokenException;

import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The mint without a Spring context: the claim assembly is the part that can be
 * wrong, and it does not need a servlet to be exercised.
 *
 * The key set is generated ONCE for the class. Twelve keys, six of them RSA, is
 * a second of work and there is no reason to pay it per test.
 *
 * Author Claude/bentzn
 * Generated 2026-08-19T10:30:00Z
 */
class MintServiceTest {

    private static final String STR_ISSUER = "http://127.0.0.1:32002";

    @TempDir
    static Path dirKeys;

    private static MintService minter;


    @BeforeAll
    static void buildTheMint() {
        MintSettings settings = new MintSettings(dirKeys.toString(), "", 3600L, "RS256",
                "raposza");
        minter = new MintService(new MintKeyStore(settings), settings);
    }


    private static MintRequest req(String strAlg, String strSub, String strShape) {
        return new MintRequest(strAlg, null, strSub, null, null, null, null, strShape,
                null, null, null, null, null, null, null, null);
    }


    @Test
    void theDefaultTokenIsRs256AndCarriesTheUsualClaims() throws Exception {
        MintResponse resp = minter.mint(MintRequest.empty(), STR_ISSUER);

        assertEquals("RS256", resp.alg());
        assertEquals("rs256", resp.kid());

        SignedJWT jwt = SignedJWT.parse(resp.token());
        assertEquals("RS256", jwt.getHeader().getAlgorithm().getName());
        assertEquals("rs256", jwt.getHeader().getKeyID());
        assertEquals("raposza", jwt.getJWTClaimsSet().getSubject());
        assertEquals(STR_ISSUER, jwt.getJWTClaimsSet().getIssuer());
        assertTrue(jwt.getJWTClaimsSet().getExpirationTime() != null);
        assertTrue(jwt.getJWTClaimsSet().getJWTID() != null);
    }


    @Test
    void shapeScopeFillsInTheCantonScope() throws Exception {
        MintResponse resp = minter.mint(req(null, "alice", "SCOPE"), STR_ISSUER);

        SignedJWT jwt = SignedJWT.parse(resp.token());
        assertEquals("daml_ledger_api", jwt.getJWTClaimsSet().getStringClaim("scope"));
        assertEquals("alice", jwt.getJWTClaimsSet().getSubject());
    }


    @Test
    void shapeAudienceBuildsTheParticipantAudience() throws Exception {
        MintRequest reqHere = new MintRequest(null, null, "alice", null, null, null, null,
                "AUDIENCE", null, null, null, null, null, "sandbox", null, null);
        MintResponse resp = minter.mint(reqHere, STR_ISSUER);

        SignedJWT jwt = SignedJWT.parse(resp.token());
        assertEquals(List.of("https://daml.com/jwt/aud/participant/sandbox"),
                jwt.getJWTClaimsSet().getAudience());
        assertNull(jwt.getJWTClaimsSet().getStringClaim("scope"));
    }


    @Test
    void shapeAudienceWithNothingToBuildFromIsRefused() {
        TokenException ex = assertThrows(TokenException.class,
                () -> minter.mint(req(null, "alice", "AUDIENCE"), STR_ISSUER));
        assertTrue(ex.getMessage().contains("participantId"));
    }


    @Test
    void shapeCustomWritesTheLegacyClaimWithBothArraysPresent() throws Exception {
        MintRequest reqHere = new MintRequest(null, null, "alice", null, null, null, null,
                "CUSTOM", List.of("Alice"), null, Boolean.TRUE, null, null, "sandbox", null,
                null);
        MintResponse resp = minter.mint(reqHere, STR_ISSUER);

        SignedJWT jwt = SignedJWT.parse(resp.token());
        Map<String, Object> mapClaim =
                jwt.getJWTClaimsSet().getJSONObjectClaim("https://daml.com/ledger-api");
        assertEquals(List.of("Alice"), mapClaim.get("actAs"));
        assertEquals(List.of(), mapClaim.get("readAs"));
        assertEquals(Boolean.TRUE, mapClaim.get("admin"));
        assertEquals("sandbox", mapClaim.get("participantId"));
    }


    @Test
    void everyAlgorithmCanBeAskedForByName() throws Exception {
        String[] arrAlg = {"HS256", "HS384", "HS512", "RS256", "RS384", "RS512",
                "PS256", "PS384", "PS512", "ES256", "ES384", "ES512"};
        for (String strAlg : arrAlg) {
            MintResponse resp = minter.mint(req(strAlg, "alice", null), STR_ISSUER);
            SignedJWT jwt = SignedJWT.parse(resp.token());
            assertEquals(strAlg, jwt.getHeader().getAlgorithm().getName());
            assertEquals(strAlg.toLowerCase(java.util.Locale.ROOT), jwt.getHeader().getKeyID());
        }
    }


    @Test
    void algNoneProducesAnUnsignedTokenAndNamesNoKey() throws Exception {
        MintResponse resp = minter.mint(req("none", "alice", null), STR_ISSUER);

        assertEquals("NONE", resp.alg());
        assertNull(resp.kid());
        assertTrue(JWTParser.parse(resp.token()) instanceof PlainJWT);
    }


    @Test
    void aTtlOfZeroOmitsTheExpiryEntirely() throws Exception {
        MintRequest reqHere = new MintRequest(null, null, "alice", null, null, null,
                Long.valueOf(0L), null, null, null, null, null, null, null, null, null);
        MintResponse resp = minter.mint(reqHere, STR_ISSUER);

        assertNull(SignedJWT.parse(resp.token()).getJWTClaimsSet().getExpirationTime());
        assertFalse(resp.claims().containsKey("exp"));
    }


    @Test
    void theClaimsMapOverridesAndRemoves() throws Exception {
        Map<String, Object> mapClaims = new HashMap<>();
        mapClaims.put("sub", "overridden");
        mapClaims.put("custom", "yes");
        mapClaims.put("iss", null);
        MintRequest reqHere = new MintRequest(null, null, "alice", null, null, null, null,
                null, null, null, null, null, null, null, mapClaims, null);

        MintResponse resp = minter.mint(reqHere, STR_ISSUER);
        SignedJWT jwt = SignedJWT.parse(resp.token());
        assertEquals("overridden", jwt.getJWTClaimsSet().getSubject());
        assertEquals("yes", jwt.getJWTClaimsSet().getStringClaim("custom"));
        assertNull(jwt.getJWTClaimsSet().getIssuer());
    }


    @Test
    void aBlankIssuerOmitsTheClaim() throws Exception {
        MintRequest reqHere = new MintRequest(null, null, "alice", "", null, null, null,
                null, null, null, null, null, null, null, null, null);
        assertNull(SignedJWT.parse(minter.mint(reqHere, STR_ISSUER).token())
                .getJWTClaimsSet().getIssuer());
    }


    @Test
    void anUnknownAlgorithmAndAnUnknownShapeAreBothRefused() {
        assertThrows(TokenException.class,
                () -> minter.mint(req("RS999", "alice", null), STR_ISSUER));
        assertThrows(TokenException.class,
                () -> minter.mint(req(null, "alice", "SIDEWAYS"), STR_ISSUER));
    }


    @Test
    void noneWithAnExplicitKidIsRefusedRatherThanIgnored() {
        MintRequest reqHere = new MintRequest("NONE", "rs256", "alice", null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        assertThrows(TokenException.class, () -> minter.mint(reqHere, STR_ISSUER));
    }

}
