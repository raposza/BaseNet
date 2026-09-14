// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwtmint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The people who can sign in: `raposza.jwtmint.users`, a comma-separated list
 * of `name:password`.
 *
 * <h2>A test system's user store</h2>
 *
 * The concept is the production one: a person proves who they are to the
 * identity provider, and the application they are signing in to never sees
 * the password. The hardening is not: the passwords sit in the configuration
 * as typed, and there is no lockout and no policy. Keycloak, or whatever
 * replaces this mint, keeps its own users; nothing outside this class reads
 * these.
 *
 * The name is the `sub` of every token issued to that person.
 *
 * Author Claude/bentzn
 */
@Component
public final class OidcUsers {

    private static final Logger log = LoggerFactory.getLogger(OidcUsers.class);

    private final Map<String, String> mapPassword;


    /**
     * @param strUsers `name:password` pairs, comma separated; blank means
     *        nobody can sign in
     * @throws IllegalArgumentException when a pair has no name or no colon
     */
    public OidcUsers(@Value("${raposza.jwtmint.users:}") String strUsers) {
        Map<String, String> map = new LinkedHashMap<>();
        if (strUsers != null) {
            for (String strPair : strUsers.split(",")) {
                String strTrim = strPair.trim();
                if (strTrim.isEmpty())
                    continue;
                int idxColon = strTrim.indexOf(':');
                if (idxColon <= 0) {
                    throw new IllegalArgumentException("raposza.jwtmint.users: '"
                            + strTrim.replaceAll(":.*", ":...") + "' is not name:password");
                }
                map.put(strTrim.substring(0, idxColon), strTrim.substring(idxColon + 1));
            }
        }
        this.mapPassword = Collections.unmodifiableMap(map);
        // NAMES ONLY. A password never reaches a log line.
        log.info("users {}", map.keySet());
    }


    /**
     * @return the names, in the order configured
     */
    public List<String> lstName() {
        return new ArrayList<>(mapPassword.keySet());
    }


    /**
     * @param strName what was typed as the name
     * @param strPassword what was typed as the password
     * @return true when that user exists and that is its password
     */
    public boolean isValid(String strName, String strPassword) {
        if (strName == null || strPassword == null)
            return false;
        String strWant = mapPassword.get(strName);
        if (strWant == null)
            return false;
        return MessageDigest.isEqual(strWant.getBytes(StandardCharsets.UTF_8),
                strPassword.getBytes(StandardCharsets.UTF_8));
    }

}
