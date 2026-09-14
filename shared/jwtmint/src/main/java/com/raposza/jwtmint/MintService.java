// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwtmint;

import com.raposza.jwt.JwtMinter;
import com.raposza.jwt.MintAlg;
import com.raposza.jwt.MintKeys;
import com.raposza.jwt.MintSigner;
import com.raposza.jwt.TokenException;
import com.raposza.jwt.TokenShape;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.OctetSequenceKey;

import java.nio.charset.StandardCharsets;
import com.nimbusds.jwt.JWTClaimsSet;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a {@link MintRequest} into a signed token.
 *
 * <h2>What it fills in and what it refuses to</h2>
 *
 * The three Canton shapes have defaults worth having - an audience-based token
 * wants `https://daml.com/jwt/aud/participant/{id}` and a scope-based one wants
 * `daml_ledger_api` - and asking for a shape is how a caller gets them without
 * typing them. A request that names no shape gets no defaults at all, because
 * the other reason this service exists is emitting tokens that are wrong on
 * purpose, and a service that quietly corrected them could not.
 *
 * <h2>The claims map is applied last, and that is the escape hatch</h2>
 *
 * Everything the service writes can be overwritten or removed by it, `exp`
 * included. An expired token, a token with no subject, a token whose audience
 * is a number - all are one field away, and none needs a code change here.
 *
 * Author Claude/bentzn
 * Generated 2026-08-19T10:30:00Z
 */
@Service
public class MintService {

    /**
     * The audience an audience-based token carries. The prefix is Canton's
     * contract, not ours.
     */
    public static final String STR_AUD_PARTICIPANT = "https://daml.com/jwt/aud/participant/";

    /** What a scope-based token carries unless told otherwise. */
    public static final String STR_SCOPE_DEFAULT = "daml_ledger_api";

    /**
     * The shortest shared secret HS256 may be signed with, in bytes.
     *
     * RFC 7518 section 3.2: the key must be at least the size of the hash
     * output. Canton's own verifier imposes no such floor, so a participant
     * will happily be configured with a shorter secret and then refuse every
     * token minted from it, with nothing on either side saying why.
     */
    public static final int N_BYTES_SECRET_MIN = 32;

    private final MintKeyStore store;

    private final MintSettings settings;


    public MintService(MintKeyStore store, MintSettings settings) {
        this.store = store;
        this.settings = settings;
    }


    /**
     * @param reqIn what to mint, or null for the service's default token
     * @param strIssuerFallback the issuer to use when neither the request nor
     *        the configuration names one
     * @return the token and its decoded claims
     * @throws TokenException when the algorithm, the key or the shape cannot be
     *         satisfied
     */
    public MintResponse mint(MintRequest reqIn, String strIssuerFallback) {
        MintRequest req = reqIn == null ? MintRequest.empty() : reqIn;

        MintAlg alg = isBlank(req.alg()) ? settings.algDefault() : MintAlg.of(req.alg());
        MintKeys keys = store.keys();

        JWK jwk = null;
        String strKid = null;
        if (alg.flagSigned()) {
            jwk = jwkSupplied(alg, req.secret());
            if (jwk == null)
                jwk = isBlank(req.kid()) ? keys.jwk(alg) : keys.jwkOfKid(req.kid());
            strKid = jwk.getKeyID();
        }
        else if (!isBlank(req.kid())) {
            throw new TokenException("alg NONE signs with no key, so kid '"
                    + req.kid() + "' cannot be used");
        }

        TokenShape shape = shapeOf(req.shape());
        long nTtl = req.ttlSeconds() == null ? settings.nTtlSecondsDefault()
                : req.ttlSeconds().longValue();
        Instant instNow = Instant.now();

        JWTClaimsSet.Builder bld = new JWTClaimsSet.Builder();

        String strIssuer = req.iss() == null
                ? (settings.strIssuerFixed() == null ? strIssuerFallback : settings.strIssuerFixed())
                : req.iss();
        if (!isBlank(strIssuer))
            bld.issuer(strIssuer.trim());

        bld.subject(isBlank(req.sub()) ? settings.strSubjectDefault() : req.sub().trim());

        List<String> lstAud = lstAudience(req, shape);
        if (!lstAud.isEmpty())
            bld.audience(lstAud);

        String strScope = strScope(req, shape);
        if (strScope != null)
            bld.claim("scope", strScope);

        bld.issueTime(Date.from(instNow));
        bld.notBeforeTime(Date.from(instNow));
        // A NON-POSITIVE TTL OMITS exp. A token that never expires is a thing
        // this rig has to be able to present, and zero is a less surprising way
        // to ask for it than an absurd number of seconds.
        if (nTtl > 0)
            bld.expirationTime(Date.from(instNow.plusSeconds(nTtl)));
        bld.jwtID(UUID.randomUUID().toString());

        if (shape == TokenShape.CUSTOM)
            bld.claim(JwtMinter.CLAIM_LEDGER_API, mapLedgerApi(req));

        // LAST. Everything above is a default and this is what a caller sent;
        // a null value REMOVES the claim, which is how a token missing a
        // mandatory field is asked for.
        if (req.claims() != null) {
            for (Map.Entry<String, Object> entClaim : req.claims().entrySet()) {
                bld.claim(entClaim.getKey(), entClaim.getValue());
            }
        }

        JWTClaimsSet claims = bld.build();
        String strToken = MintSigner.strSign(alg, jwk, claims);
        return new MintResponse(strToken, alg.name(), strKid, nTtl, claims.toJSONObject());
    }


    /**
     * A key built from a secret the CALLER supplied, rather than one this
     * service generated.
     *
     * <h2>Why this exists at all</h2>
     *
     * A participant configured with `unsafe-jwt-hmac-256` verifies against a
     * plaintext secret its operator chose. This service's own HS256 key is
     * random and kept in its key store, so before this every HS* token it
     * minted was unverifiable against any participant that had not been handed
     * that key - which is to say, against every participant. The symmetric
     * column could be configured and could not be exercised.
     *
     * <h2>The UTF-8 bytes ARE the key, and that is the thing to measure</h2>
     *
     * Canton takes the `secret` from its configuration and uses it as an HMAC
     * key; this takes the same string and does the same. It is the only reading
     * under which the two sides can ever agree, and it is not confirmed here by
     * anything but that argument - a token this mints either verifies against a
     * participant holding the same string or it does not, and that is a
     * measurement rather than a claim.
     *
     * <h2>Thirty-two bytes, and the refusal is the standard's</h2>
     *
     * RFC 7518 section 3.2 requires a key at least as long as the hash output,
     * so 32 bytes for HS256. A shorter one is REFUSED here rather than padded:
     * padding would produce a key that is not the operator's secret, the token
     * would fail to verify, and the reason would be invisible on both sides.
     *
     * @param alg the algorithm asked for
     * @param strSecret what the caller supplied, or blank for none
     * @return the key to sign with, or null to use this service's own
     * @throws TokenException when a secret is supplied for an algorithm that
     *         has no shared half, or is too short for the one that does
     */
    private static JWK jwkSupplied(MintAlg alg, String strSecret) {
        if (isBlank(strSecret))
            return null;

        byte[] arrKey = strSecret.getBytes(StandardCharsets.UTF_8);
        if (arrKey.length < N_BYTES_SECRET_MIN) {
            throw new TokenException("a shared secret for " + alg.name() + " must be at"
                    + " least " + N_BYTES_SECRET_MIN + " bytes (RFC 7518 section 3.2);"
                    + " this one is " + arrKey.length);
        }

        try {
            return new OctetSequenceKey.Builder(arrKey).keyID(alg.strKid()).build();
        }
        catch (RuntimeException ex) {
            throw new TokenException("that secret cannot be used as an " + alg.name()
                    + " key: " + ex.getMessage());
        }
    }


    /**
     * @param strShape AUDIENCE, SCOPE, CUSTOM, RAW, or blank
     * @return the shape, or null when no defaults are to be applied
     * @throws TokenException when the name matches nothing
     */
    private static TokenShape shapeOf(String strShape) {
        if (isBlank(strShape))
            return null;

        String strWanted = strShape.trim().toUpperCase(Locale.ROOT);
        if ("RAW".equals(strWanted) || "NONE".equals(strWanted))
            return null;
        for (TokenShape shape : TokenShape.values()) {
            if (shape.name().equals(strWanted))
                return shape;
        }
        throw new TokenException("unknown shape '" + strShape
                + "'; known: AUDIENCE, SCOPE, CUSTOM, RAW");
    }


    /**
     * @param req the request
     * @param shape the resolved shape, or null
     * @return what goes in `aud`, possibly empty
     */
    private static List<String> lstAudience(MintRequest req, TokenShape shape) {
        List<String> lstOut = new ArrayList<>();
        if (req.aud() != null) {
            for (String strAud : req.aud()) {
                if (!isBlank(strAud))
                    lstOut.add(strAud.trim());
            }
        }
        if (!lstOut.isEmpty())
            return lstOut;

        if (shape == TokenShape.AUDIENCE) {
            if (isBlank(req.participantId())) {
                throw new TokenException("shape AUDIENCE needs either an explicit aud or a"
                        + " participantId to build one from");
            }
            lstOut.add(STR_AUD_PARTICIPANT + req.participantId().trim());
        }
        return lstOut;
    }


    /**
     * @param req the request
     * @param shape the resolved shape, or null
     * @return the scope claim, or null when none is to be written
     */
    private static String strScope(MintRequest req, TokenShape shape) {
        if (!isBlank(req.scope()))
            return req.scope().trim();
        if (shape == TokenShape.SCOPE)
            return STR_SCOPE_DEFAULT;
        return null;
    }


    /**
     * The legacy claim body.
     *
     * admin and the two party arrays are ALWAYS written, empty if unset,
     * because Canton reads the shape rather than probing for presence and an
     * absent array is not the same as an empty one.
     *
     * @param req the request
     * @return the claim value
     */
    private static Map<String, Object> mapLedgerApi(MintRequest req) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("actAs", req.actAs() == null ? List.of() : req.actAs());
        map.put("readAs", req.readAs() == null ? List.of() : req.readAs());
        map.put("admin", Boolean.valueOf(req.admin() != null && req.admin().booleanValue()));
        if (!isBlank(req.applicationId()))
            map.put("applicationId", req.applicationId().trim());
        if (!isBlank(req.ledgerId()))
            map.put("ledgerId", req.ledgerId().trim());
        if (!isBlank(req.participantId()))
            map.put("participantId", req.participantId().trim());
        return map;
    }


    private static boolean isBlank(String str) {
        return str == null || str.isBlank();
    }

}
