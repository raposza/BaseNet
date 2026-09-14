// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwtmint;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * What a caller wants minted.
 *
 * <h2>The component names are the JSON field names</h2>
 *
 * So they are `alg`, `sub`, `aud` and not `strAlgorithm`, `strSubject`,
 * `lstAudience`. This record is a wire contract rather than a domain type: the
 * names are the ones a JWT carries and the ones every example on the internet
 * uses, and a caller composing a curl line should not have to learn a second
 * spelling of `aud`.
 *
 * EVERY COMPONENT IS OPTIONAL. A null means "the service decides"; it does not
 * mean "omit the claim". To omit a claim the service would otherwise write,
 * send it as null inside {@link #claims}, which is applied last and removes as
 * well as sets.
 *
 * <h2>The examples are the ones that work against this window's sandbox</h2>
 *
 * `participant_admin` is the user Canton creates for itself, `sandbox` is the
 * participant node name this application starts, and the two derived values -
 * the audience and the scope - follow Canton's own conventions. So the
 * body Swagger fills in mints a token the running participant accepts, rather
 * than one that has to be edited first.
 *
 * @param alg one of {@link com.raposza.jwt.MintAlg}, including NONE
 * @param kid a specific key, overriding the one `alg` would pick
 * @param sub the `sub` claim
 * @param iss the `iss` claim; blank omits it, null takes the service's
 * @param aud the `aud` claim, one value or several
 * @param scope the `scope` claim
 * @param ttlSeconds lifetime; 0 or less omits `exp` entirely
 * @param shape AUDIENCE, SCOPE or CUSTOM to fill the Canton defaults for that
 *        shape, or null to write only what is asked for
 * @param actAs CUSTOM only
 * @param readAs CUSTOM only
 * @param admin CUSTOM only
 * @param applicationId CUSTOM only
 * @param ledgerId CUSTOM only
 * @param participantId CUSTOM only, and the audience AUDIENCE derives when no
 *        `aud` is given
 * @param claims merged LAST over everything above, so it can override or remove
 *        any claim the service writes
 *
 * Author Claude/bentzn
 * Generated 2026-08-19T10:30:00Z
 */
public record MintRequest(
        @Schema(description = "Signing algorithm, including NONE.", example = "RS256")
        String alg,

        @Schema(description = "A specific key, overriding the one `alg` picks."
                + " The key id is the algorithm in lower case.", example = "rs256")
        String kid,

        @Schema(description = "The `sub` claim - the ledger user the token speaks for.",
                example = "participant_admin")
        String sub,

        @Schema(description = "The `iss` claim. Blank omits it; null takes this"
                + " service's own issuer.", example = "http://localhost:32002")
        String iss,

        @Schema(description = "The `aud` claim.",
                example = "[\"https://daml.com/jwt/aud/participant/sandbox\"]")
        List<String> aud,

        @Schema(description = "The `scope` claim.", example = "daml_ledger_api")
        String scope,

        @Schema(description = "Lifetime in seconds. 0 or less writes no `exp` at all.",
                example = "3600")
        Long ttlSeconds,

        @Schema(description = "AUDIENCE, SCOPE, CUSTOM or RAW.", example = "AUDIENCE")
        String shape,

        @Schema(description = "CUSTOM only.", example = "[\"Alice\"]")
        List<String> actAs,

        @Schema(description = "CUSTOM only.", example = "[\"Alice\"]")
        List<String> readAs,

        @Schema(description = "CUSTOM only.", example = "true")
        Boolean admin,

        @Schema(description = "CUSTOM only.", example = "raposza")
        String applicationId,

        @Schema(description = "CUSTOM only.", example = "sandbox")
        String ledgerId,

        @Schema(description = "The participant the AUDIENCE shape builds its audience"
                + " from.", example = "sandbox")
        String participantId,

        @Schema(description = "Merged LAST, over everything above. A null value here"
                + " REMOVES a claim, which is how an expired or issuer-less token is"
                + " asked for.", example = "{\"exp\":1}")
        Map<String, Object> claims,

        @Schema(description = "The HS* shared secret to sign with, INSTEAD of this"
                + " service's own key. The UTF-8 bytes of this string are the HMAC"
                + " key, which is the same reading Canton's `unsafe-jwt-hmac-256`"
                + " takes of the `secret` in its auth-services block - so a"
                + " participant configured with a secret can be handed a token"
                + " minted with the same one. Ignored for every asymmetric"
                + " algorithm, which has no shared half. RFC 7518 section 3.2"
                + " requires at least 32 bytes for HS256.",
                example = "raposza-sandbox-unsafe-shared-secret")
        String secret) {

    /**
     * @return a request that asks for nothing in particular, which mints the
     *         service's default token
     */
    public static MintRequest empty() {
        return new MintRequest(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

}
