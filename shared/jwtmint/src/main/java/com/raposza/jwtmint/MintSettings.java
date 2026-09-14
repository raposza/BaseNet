// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwtmint;

import com.raposza.jwt.MintAlg;
import com.raposza.jwt.MintKeys;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * What the service was configured with, resolved once.
 *
 * The blank defaults are not missing values. A blank key directory means the
 * shared one under the home directory, and a blank issuer means "whatever
 * address this request arrived on" - which is the only answer that works for a
 * caller inside a virtual machine and a caller on the host at the same time.
 *
 * Author Claude/bentzn
 * Generated 2026-08-19T10:30:00Z
 */
@Component
public final class MintSettings {

    private final Path dirKeys;

    private final String strIssuerFixed;

    private final long nTtlSecondsDefault;

    private final MintAlg algDefault;

    private final String strSubjectDefault;


    /**
     * @param strDirKeys where the JWKS lives, blank for the default
     * @param strIssuer a fixed `iss`, blank to derive it per request
     * @param nTtlSeconds token lifetime when a request does not say
     * @param strAlg the algorithm when a request does not say
     * @param strSubject the `sub` when a request does not say
     */
    public MintSettings(@Value("${raposza.jwtmint.dir-keys:}") String strDirKeys,
            @Value("${raposza.jwtmint.issuer:}") String strIssuer,
            @Value("${raposza.jwtmint.ttl-seconds:86400}") long nTtlSeconds,
            @Value("${raposza.jwtmint.default-alg:RS256}") String strAlg,
            @Value("${raposza.jwtmint.default-subject:raposza}") String strSubject) {
        this.dirKeys = (strDirKeys == null || strDirKeys.isBlank())
                ? MintKeys.dirDefault()
                : Path.of(strDirKeys.trim()).toAbsolutePath().normalize();
        this.strIssuerFixed = (strIssuer == null || strIssuer.isBlank()) ? null : strIssuer.trim();
        this.nTtlSecondsDefault = nTtlSeconds;
        this.algDefault = MintAlg.of(strAlg);
        this.strSubjectDefault = (strSubject == null || strSubject.isBlank())
                ? "raposza" : strSubject.trim();
    }


    public Path dirKeys() {
        return dirKeys;
    }


    /**
     * @return a configured issuer, or null when it is to be derived from the
     *         request
     */
    public String strIssuerFixed() {
        return strIssuerFixed;
    }


    public long nTtlSecondsDefault() {
        return nTtlSecondsDefault;
    }


    public MintAlg algDefault() {
        return algDefault;
    }


    public String strSubjectDefault() {
        return strSubjectDefault;
    }

}
