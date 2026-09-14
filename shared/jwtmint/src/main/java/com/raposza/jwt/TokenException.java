// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwt;

/**
 * A token could not be produced or key material could not be read.
 *
 * The message must never carry key material or a token: it reaches logs and
 * the status bar.
 *
 * Author Claude/bentzn
 * Generated 2026-07-31T16:00:00Z
 */
public class TokenException extends RuntimeException {

    private static final long serialVersionUID = 1L;


    public TokenException(String strMessage) {
        super(strMessage);
    }


    public TokenException(String strMessage, Throwable cause) {
        super(strMessage, cause);
    }

}
