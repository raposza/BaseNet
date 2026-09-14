// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwtmint;

import com.raposza.jwt.MintKeys;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The one key set the service mints from, and the one thing that can be swapped
 * while it runs.
 *
 * <h2>Why reload is explicit rather than a file watcher</h2>
 *
 * The keys change when somebody changes them: a hand-edited JWKS, a directory
 * restored from a backup, a key deleted to force a regeneration. A watcher
 * would pick up a half-written file - the private set is written before the
 * public one - and would then serve a set that never existed. An explicit
 * reload happens when the operator says the file is finished, which is the only
 * moment that is true.
 *
 * The reference is volatile and replaced whole. A request in flight keeps the
 * set it started with rather than seeing half of each.
 *
 * Author Claude/bentzn
 * Generated 2026-08-19T10:30:00Z
 */
@Component
public final class MintKeyStore {

    private static final Logger log = LoggerFactory.getLogger(MintKeyStore.class);

    private final MintSettings settings;

    private volatile MintKeys keys;


    public MintKeyStore(MintSettings settings) {
        this.settings = settings;
        this.keys = load();
    }


    /**
     * @return the set every request mints from
     */
    public MintKeys keys() {
        return keys;
    }


    /**
     * Re-reads the JWKS from disk, generating anything that is missing.
     *
     * @return the set now in use
     */
    public MintKeys reload() {
        MintKeys keysNew = load();
        this.keys = keysNew;
        return keysNew;
    }


    private MintKeys load() {
        MintKeys keysNew = MintKeys.ensure(settings.dirKeys());
        // KEY IDS ONLY. Nothing about a key beyond its id may reach a log line,
        // and the ids are exactly what an operator needs to see to know which
        // set came up.
        log.info("keys {} from {}", keysNew.lstKid(), keysNew.dirKeys());
        return keysNew;
    }

}
