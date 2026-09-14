// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwtmint;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyType;

/**
 * One row of the key table.
 *
 * `published` is the column that matters and the one nobody expects: a
 * symmetric key has no public half, so it is NOT in `/jwks.json` and a
 * participant configured with a JWKS url can never verify an HS* token. What
 * such a participant needs is the shared secret, which is in the private set
 * under `k`.
 *
 * @param alg the algorithm this key exists for
 * @param kid its key id, which is the algorithm in lower case
 * @param kty RSA, EC or oct
 * @param detail the key size, or the curve for an EC key
 * @param published whether it appears in the public JWKS
 *
 * Author Claude/bentzn
 * Generated 2026-08-19T10:30:00Z
 */
public record KeyRow(String alg, String kid, String kty, String detail, boolean published) {

    /**
     * @param strAlg the algorithm name
     * @param jwk the key
     * @return the row describing it, carrying no key material
     */
    public static KeyRow of(String strAlg, JWK jwk) {
        KeyType kty = jwk.getKeyType();
        String strDetail;
        boolean flagPublished;

        if (KeyType.EC.equals(kty)) {
            strDetail = ((ECKey) jwk).getCurve().getName();
            flagPublished = true;
        }
        else if (KeyType.OCT.equals(kty)) {
            strDetail = jwk.size() + " bit shared secret";
            flagPublished = false;
        }
        else {
            strDetail = jwk.size() + " bit";
            flagPublished = true;
        }

        return new KeyRow(strAlg, jwk.getKeyID(), kty.getValue(), strDetail, flagPublished);
    }

}
