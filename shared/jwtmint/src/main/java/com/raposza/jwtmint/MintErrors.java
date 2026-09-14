// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwtmint;

import com.raposza.jwt.TokenException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A refused request answers 400 with the reason in the body.
 *
 * The reasons this service produces are written to be read by a person - they
 * name the unknown algorithm and list the known ones, name the missing key and
 * list the present ones - and a 500 with a stack trace throws all of that away.
 * The messages carry no key material, which {@link TokenException} states as
 * its own contract.
 *
 * Author Claude/bentzn
 * Generated 2026-08-19T10:30:00Z
 */
@RestControllerAdvice
public class MintErrors {

    /**
     * @param ex what the mint refused
     * @return 400 and the reason
     */
    @ExceptionHandler({TokenException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> refused(RuntimeException ex) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", "invalid_request");
        map.put("error_description", String.valueOf(ex.getMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(map);
    }

}
