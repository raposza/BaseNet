// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwt;

/**
 * The three Canton token shapes. They are MUTUALLY EXCLUSIVE: a participant is
 * configured for exactly one, and presenting the wrong shape fails
 * authentication in a way that reads like a key problem.
 *
 * Ported from the DevTools3 token service rather than reinvented, because the
 * shapes encode hard-won knowledge about what Canton actually accepts.
 *
 * Author Claude/bentzn
 * Generated 2026-07-31T16:00:00Z
 */
public enum TokenShape {

    /**
     * Audience-based. `aud` is required and identifies the participant, e.g.
     * "https://daml.com/jwt/aud/participant/{participant-id}". Any scope is
     * cleared, so the token is unambiguously audience-based.
     */
    AUDIENCE,

    /**
     * Scope-based. `scope` is required, conventionally "daml_ledger_api".
     * `aud` is optional and names the target audience of the scope.
     */
    SCOPE,

    /**
     * Legacy custom claim. Carries "https://daml.com/ledger-api" holding
     * actAs, readAs, admin, applicationId, ledgerId and participantId.
     */
    CUSTOM

}
