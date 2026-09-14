// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.OctetSequenceKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;

import java.util.Locale;

/**
 * Every signing algorithm the mint can emit, including the one that does not
 * sign.
 *
 * <h2>Why NONE is a member rather than a special case</h2>
 *
 * A test rig for authorization has to be able to present a token a participant
 * MUST refuse, and `alg: none` is the canonical one. Modelling it as a member
 * means the refusal path is reached by the same call as every other, so a
 * caller cannot forget to exercise it - and nothing downstream has to branch on
 * a null algorithm.
 *
 * <h2>One key per algorithm, not one key per key type</h2>
 *
 * RS256 and PS512 could share an RSA key and ES256 could not share with ES512
 * at all. Giving every algorithm its own `kid` costs six RSA generations once,
 * on first start, and buys a `kid` that says what it is for: a participant
 * configured against `rs256` and presented a token signed by `ps256` fails in a
 * way the key id alone explains.
 *
 * <h2>The HMAC keys are not in the public JWKS, and cannot be</h2>
 *
 * An octet sequence has no public half. A verifier that wants HS* needs the
 * shared secret itself, which is what the private JWKS endpoint carries in the
 * `k` member. That is not an oversight in the JWKS - it is what a symmetric key
 * is.
 *
 * Author Claude/bentzn
 */
public enum MintAlg {

    /** Unsigned. A `PlainJWT`: three segments, the last of them empty. */
    NONE,

    HS256,

    HS384,

    HS512,

    RS256,

    RS384,

    RS512,

    PS256,

    PS384,

    PS512,

    ES256,

    ES384,

    ES512;

    /**
     * Nimbus refuses anything smaller and so should this. A test harness that
     * normalises a weak key teaches the wrong shape to whatever copies it.
     */
    private static final int N_BITS_RSA = 2048;


    /**
     * @return the key id this algorithm's key carries, which is the algorithm
     *         name in lower case
     */
    public String strKid() {
        return name().toLowerCase(Locale.ROOT);
    }


    /**
     * @return whether this algorithm needs a key at all
     */
    public boolean flagSigned() {
        return this != NONE;
    }


    /**
     * @return the JOSE algorithm
     * @throws TokenException for NONE, which has no JWS algorithm
     */
    public JWSAlgorithm alg() {
        if (this == NONE)
            throw new TokenException("NONE is not a JWS algorithm");
        return JWSAlgorithm.parse(name());
    }


    /**
     * @return a freshly generated private key for this algorithm, carrying
     *         {@link #strKid()} as its key id
     * @throws TokenException for NONE, or when generation fails
     */
    public JWK generate() {
        if (this == NONE)
            throw new TokenException("NONE has no key to generate");

        try {
            if (name().startsWith("HS")) {
                return new OctetSequenceKeyGenerator(nBits())
                        .keyID(strKid())
                        .keyUse(KeyUse.SIGNATURE)
                        .algorithm(alg())
                        .generate();
            }
            if (name().startsWith("ES")) {
                return new ECKeyGenerator(curve())
                        .keyID(strKid())
                        .keyUse(KeyUse.SIGNATURE)
                        .algorithm(alg())
                        .generate();
            }
            return new RSAKeyGenerator(N_BITS_RSA)
                    .keyID(strKid())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(alg())
                    .generate();
        }
        catch (JOSEException ex) {
            throw new TokenException("could not generate a key for " + name(), ex);
        }
    }


    /**
     * @return the HMAC key size in bits
     * @throws TokenException when this is not an HS algorithm
     */
    public int nBits() {
        switch (this) {
            case HS256:
                return 256;
            case HS384:
                return 384;
            case HS512:
                return 512;
            default:
                throw new TokenException(name() + " is not an HMAC algorithm");
        }
    }


    /**
     * @return the curve
     * @throws TokenException when this is not an ES algorithm
     */
    public Curve curve() {
        switch (this) {
            case ES256:
                return Curve.P_256;
            case ES384:
                return Curve.P_384;
            case ES512:
                // P-521, not P-512. The algorithm name and the curve name do
                // not agree and never have; a key generated on P-256 here is
                // refused at signing time with a message about the curve.
                return Curve.P_521;
            default:
                throw new TokenException(name() + " is not an EC algorithm");
        }
    }


    /**
     * @param strName an algorithm name, in any case
     * @return the member
     * @throws TokenException when nothing matches, naming what does
     */
    public static MintAlg of(String strName) {
        if (strName == null || strName.isBlank())
            throw new TokenException("no algorithm supplied");

        String strWanted = strName.trim().toUpperCase(Locale.ROOT);
        for (MintAlg alg : values()) {
            if (alg.name().equals(strWanted))
                return alg;
        }

        StringBuilder sb = new StringBuilder();
        for (MintAlg alg : values()) {
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(alg.name());
        }
        throw new TokenException("unknown algorithm '" + strName + "'; known: " + sb);
    }

}
