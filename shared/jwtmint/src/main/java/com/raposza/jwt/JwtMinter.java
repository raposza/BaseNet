// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Signs a TokenSpec with a JWK.
 *
 * Claim emission follows the DevTools3 token service, which is what the
 * participants in reach are already configured against.
 *
 * Author Claude/bentzn
 * Generated 2026-07-31T16:00:00Z
 */
public final class JwtMinter {

    /** The legacy Daml claim. Its name is part of Canton's contract, not ours. */
    public static final String CLAIM_LEDGER_API = "https://daml.com/ledger-api";

    private final JWK jwk;


    /**
     * @param jwk a private JWK; RSA and EC are supported
     * @throws TokenException when the key is public-only or of an unsupported type
     */
    public JwtMinter(JWK jwk) {
        if (jwk == null)
            throw new TokenException("no key supplied");
        if (!jwk.isPrivate())
            throw new TokenException("key '" + describeKey(jwk) + "' has no private part; cannot sign");

        KeyType kty = jwk.getKeyType();
        if (!KeyType.RSA.equals(kty) && !KeyType.EC.equals(kty))
            throw new TokenException("unsupported key type: " + kty);

        this.jwk = jwk;
    }


    /**
     * @param spec what the token should contain
     * @return a signed, compact-serialised JWT
     * @throws TokenException when signing fails
     */
    public String mint(TokenSpec spec) {
        Instant instNow = Instant.now();
        JWTClaimsSet.Builder bld = new JWTClaimsSet.Builder();

        if (!TokenSpec.isBlank(spec.strIssuer()))
            bld.issuer(spec.strIssuer());
        if (!TokenSpec.isBlank(spec.strSubject()))
            bld.subject(spec.strSubject());
        if (!TokenSpec.isBlank(spec.strAudience()))
            bld.audience(spec.strAudience());

        bld.issueTime(Date.from(instNow));
        bld.expirationTime(Date.from(instNow.plus(spec.ttl())));
        bld.jwtID(UUID.randomUUID().toString());

        if (spec.shape() == TokenShape.SCOPE)
            bld.claim("scope", spec.strScope());

        if (spec.shape() == TokenShape.CUSTOM)
            bld.claim(CLAIM_LEDGER_API, ledgerApiClaim(spec));

        try {
            JWSAlgorithm alg = algorithmFor();
            JWSHeader header = new JWSHeader.Builder(alg).keyID(jwk.getKeyID()).build();
            SignedJWT jwt = new SignedJWT(header, bld.build());
            jwt.sign(signer());
            return jwt.serialize();
        }
        catch (JOSEException ex) {
            throw new TokenException("could not sign token with key '" + describeKey(jwk) + "'", ex);
        }
    }


    /**
     * The legacy claim body.
     *
     * admin and the two party arrays are ALWAYS written, empty if unset,
     * because Canton reads the shape rather than probing for presence and an
     * absent array is not the same as an empty one.
     */
    private static Map<String, Object> ledgerApiClaim(TokenSpec spec) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("actAs", spec.lstActAs());
        map.put("readAs", spec.lstReadAs());
        map.put("admin", Boolean.valueOf(spec.flagAdmin()));
        if (!TokenSpec.isBlank(spec.idApplication()))
            map.put("applicationId", spec.idApplication());
        if (!TokenSpec.isBlank(spec.idLedger()))
            map.put("ledgerId", spec.idLedger());
        if (!TokenSpec.isBlank(spec.idParticipant()))
            map.put("participantId", spec.idParticipant());
        return map;
    }


    private JWSAlgorithm algorithmFor() {
        if (jwk.getAlgorithm() != null)
            return JWSAlgorithm.parse(jwk.getAlgorithm().getName());
        if (KeyType.EC.equals(jwk.getKeyType()))
            return JWSAlgorithm.ES256;
        return JWSAlgorithm.RS256;
    }


    private JWSSigner signer() throws JOSEException {
        if (KeyType.EC.equals(jwk.getKeyType()))
            return new ECDSASigner((ECKey) jwk);
        return new RSASSASigner((RSAKey) jwk);
    }


    /**
     * A key description safe to put in a message: key id and type only, never
     * any component of the key itself.
     */
    static String describeKey(JWK jwk) {
        String kid = jwk.getKeyID();
        return (kid == null || kid.isBlank() ? "<no kid>" : kid) + "/" + jwk.getKeyType();
    }

}
