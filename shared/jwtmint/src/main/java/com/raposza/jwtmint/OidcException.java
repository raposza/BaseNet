// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwtmint;

import org.springframework.http.HttpStatus;

/**
 * An OAuth 2.0 error: the `error` code of RFC 6749 section 5.2, its
 * description, and the status it is answered with.
 *
 * Kept apart from {@link com.raposza.jwt.TokenException}, which the rest of the
 * mint answers as `invalid_request`. The grant errors a client acts on -
 * `invalid_grant` above all - have to reach it by their own names.
 *
 * Author Claude/bentzn
 * Generated 2026-09-11T18:30:00Z
 */
public final class OidcException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String strError;

    private final HttpStatus status;


    /**
     * @param strError the RFC 6749 section 5.2 code
     * @param strDescription the human-readable `error_description`
     */
    public OidcException(String strError, String strDescription) {
        this(strError, strDescription, HttpStatus.BAD_REQUEST);
    }


    /**
     * @param strError the RFC 6749 section 5.2 code
     * @param strDescription the human-readable `error_description`
     * @param status what the token endpoint answers with
     */
    public OidcException(String strError, String strDescription, HttpStatus status) {
        super(strDescription);
        this.strError = strError;
        this.status = status;
    }


    /**
     * @return the `error` code
     */
    public String strError() {
        return strError;
    }


    /**
     * @return the HTTP status to answer with
     */
    public HttpStatus status() {
        return status;
    }

}
