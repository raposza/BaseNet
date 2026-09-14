// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwt;

import java.time.Duration;
import java.util.List;

/**
 * What a token should contain. Built once from a connection profile and reused;
 * the minter turns it into a signed JWT on demand.
 *
 * @param shape which of the three Canton shapes to emit
 * @param strSubject the `sub` claim, normally the ledger user id
 * @param strIssuer the `iss` claim, null to omit
 * @param strAudience the `aud` claim; REQUIRED for AUDIENCE, optional otherwise
 * @param strScope the `scope` claim; REQUIRED for SCOPE, ignored for AUDIENCE
 * @param ttl how long the minted token is valid for
 * @param lstActAs CUSTOM only: parties the token may act as
 * @param lstReadAs CUSTOM only: parties the token may read as
 * @param flagAdmin CUSTOM only: whether the token carries admin rights
 * @param idApplication CUSTOM only, may be null
 * @param idLedger CUSTOM only, may be null
 * @param idParticipant CUSTOM only, may be null
 *
 * Author Claude/bentzn
 */
public record TokenSpec(TokenShape shape, String strSubject, String strIssuer,
        String strAudience, String strScope, Duration ttl, List<String> lstActAs,
        List<String> lstReadAs, boolean flagAdmin, String idApplication, String idLedger,
        String idParticipant) {

    public TokenSpec {
        if (shape == null)
            throw new IllegalArgumentException("shape is required");
        if (ttl == null || ttl.isZero() || ttl.isNegative())
            throw new IllegalArgumentException("ttl must be positive");

        if (shape == TokenShape.AUDIENCE) {
            if (isBlank(strAudience))
                throw new IllegalArgumentException("audience is required for an audience-based token");
            // Cleared rather than rejected: a profile may legitimately carry a
            // default scope that does not apply to this shape.
            strScope = null;
        }
        if (shape == TokenShape.SCOPE && isBlank(strScope))
            throw new IllegalArgumentException("scope is required for a scope-based token");

        lstActAs = lstActAs == null ? List.of() : List.copyOf(lstActAs);
        lstReadAs = lstReadAs == null ? List.of() : List.copyOf(lstReadAs);
    }


    /**
     * Derives a spec from a profile's scope and audience.
     *
     * The two are mutually exclusive. A profile carrying both is a
     * configuration error rather than something to resolve by precedence:
     * guessing which the participant wants produces an authentication failure
     * that looks like a key problem.
     *
     * @param nameProfile profile name, for the error message only
     * @param strScope the profile's scope, may be null
     * @param strAudience the profile's audience, may be null
     * @param strSubject the ledger user id
     * @param ttl token lifetime
     * @return the spec
     * @throws IllegalArgumentException when both scope and audience are set
     */
    public static TokenSpec fromProfile(String nameProfile, String strScope, String strAudience,
            String strSubject, Duration ttl) {
        boolean flagScope = !isBlank(strScope);
        boolean flagAudience = !isBlank(strAudience);

        if (flagScope && flagAudience) {
            throw new IllegalArgumentException("profile '" + nameProfile
                    + "' sets both scope and audience; Canton accepts one shape, not both");
        }
        if (flagAudience)
            return new TokenSpec(TokenShape.AUDIENCE, strSubject, null, strAudience, null, ttl,
                    null, null, false, null, null, null);
        if (flagScope)
            return new TokenSpec(TokenShape.SCOPE, strSubject, null, null, strScope, ttl,
                    null, null, false, null, null, null);

        return new TokenSpec(TokenShape.CUSTOM, strSubject, null, null, null, ttl,
                null, null, false, null, null, null);
    }


    static boolean isBlank(String str) {
        return str == null || str.isBlank();
    }

}
