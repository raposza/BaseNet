// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwtmint;

import java.util.Map;

/**
 * A minted token and, beside it, exactly what went into it.
 *
 * The decoded claims are returned rather than left for the caller to base64.
 * The whole point of this service is being able to see why a participant
 * refused something, and a response carrying only the compact serialisation
 * makes every diagnosis start with a decoding step.
 *
 * As with {@link MintRequest}, the component names are the wire names.
 *
 * @param token the compact serialisation
 * @param alg how it was signed; "NONE" when it was not
 * @param kid which key signed it, null for NONE
 * @param expiresIn the lifetime in seconds that was asked for; 0 or less means
 *        no `exp` claim was written
 * @param claims the payload, decoded
 *
 * Author Claude/bentzn
 */
public record MintResponse(String token, String alg, String kid, long expiresIn,
        Map<String, Object> claims) {
}
