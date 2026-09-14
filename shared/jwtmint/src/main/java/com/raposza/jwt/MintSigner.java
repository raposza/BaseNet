// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

/**
 * Serialises a claims set under a {@link MintAlg}.
 *
 * Separate from {@link JwtMinter}, which builds a token from a
 * {@link TokenSpec} for a connection profile and is deliberately narrow. This
 * takes a claims set that has already been decided - including one that is
 * deliberately malformed - and does nothing to it but sign it. A mint whose
 * signer edited the claims could not emit the token a caller asked for, which
 * is the only thing a test rig is for.
 *
 * The signer is chosen from the KEY TYPE rather than from the algorithm, and
 * the algorithm then goes in the header. That is what makes a mismatch report
 * itself: a `kid` naming an RSA key presented with ES256 fails inside Nimbus
 * with a message about the key, rather than producing a token that no verifier
 * can explain.
 *
 * Author Claude/bentzn
 * Generated 2026-08-19T10:30:00Z
 */
public final class MintSigner {

    private MintSigner() {
    }


    /**
     * @param alg how to sign; NONE produces an unsigned token
     * @param jwk the private key, ignored and permitted to be null for NONE
     * @param claims what the token carries
     * @return the compact serialisation
     * @throws TokenException when the key is unusable or signing fails
     */
    public static String strSign(MintAlg alg, JWK jwk, JWTClaimsSet claims) {
        if (alg == null)
            throw new TokenException("no algorithm supplied");
        if (claims == null)
            throw new TokenException("no claims supplied");

        // THREE SEGMENTS, the last of them empty. A participant that accepts
        // this is misconfigured, and being able to prove that is the reason
        // this branch exists.
        if (!alg.flagSigned())
            return new PlainJWT(claims).serialize();

        if (jwk == null)
            throw new TokenException("no key supplied for " + alg.name());
        if (!jwk.isPrivate()) {
            throw new TokenException("key '" + jwk.getKeyID()
                    + "' has no private part; cannot sign");
        }

        try {
            JWSHeader header = new JWSHeader.Builder(alg.alg())
                    .keyID(jwk.getKeyID())
                    .type(JOSEObjectType.JWT)
                    .build();
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(signer(alg, jwk));
            return jwt.serialize();
        }
        catch (JOSEException ex) {
            throw new TokenException("could not sign with '" + jwk.getKeyID()
                    + "' as " + alg.name(), ex);
        }
    }


    /**
     * @param alg named only so the failure message can carry it
     * @param jwk the key whose type decides the signer
     * @return a signer for that key
     * @throws JOSEException when the key is rejected by the signer
     */
    private static JWSSigner signer(MintAlg alg, JWK jwk) throws JOSEException {
        KeyType kty = jwk.getKeyType();
        if (KeyType.OCT.equals(kty))
            return new MACSigner((OctetSequenceKey) jwk);
        if (KeyType.EC.equals(kty))
            return new ECDSASigner((ECKey) jwk);
        if (KeyType.RSA.equals(kty))
            return new RSASSASigner((RSAKey) jwk);
        throw new TokenException("key '" + jwk.getKeyID() + "' is a " + kty
                + ", which cannot sign " + alg.name());
    }

}
