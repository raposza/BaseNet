// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwtmint;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The JWT mint.
 *
 * <h2>What it is for</h2>
 *
 * Testing authorization, not being secure. It holds a permanent key per
 * algorithm, publishes both halves of the set - the private one too, on
 * request - and mints a token from whatever a caller asks for, including
 * tokens that are deliberately wrong. Every one of those is a property a
 * production identity provider must not have.
 *
 * <h2>Why it is one service and outside the profiles</h2>
 *
 * A participant is configured against a JWKS url at start. If the key material
 * belonged to a profile, selecting a different Canton would move the url and
 * every token already issued would stop verifying against the node that was
 * still running. One mint, one key set, one address: a token minted for the
 * 2.9 stack is the same token the 3.5 stack accepts, and the only thing that
 * differs between them is the `auth-services` block each was given.
 *
 * <h2>Endpoints</h2>
 *
 * The `/oauth2/` names are a PROJECT CONVENTION and no part of any protocol.
 * The older spellings are kept because configurations written before it point
 * at them; a conforming client reads both from the discovery document and is
 * untouched by either.
 *
 * <pre>
 * GET  /                                what this serves, in plain text
 * GET  /oauth2/jwks                     the PUBLIC set - a participant's `url`
 * GET  /jwks.json                       the same, and /.well-known/jwks.json
 * GET  /oauth2/jwks-private             the FULL set, private members and all
 * GET  /jwks-private.json               the same
 * GET  /keys                            algorithm, kid and key size per key
 * GET  /.well-known/openid-configuration   the OpenID Provider configuration
 * GET  /.well-known/oauth-authorization-server   the same document, RFC 8414
 * GET  /oauth2/authorize                sign in - the login page, then a code
 * POST /oauth2/token                    authorization_code, refresh_token,
 *                                       client_credentials; form or JSON
 * POST /oauth/token                     the same, and POST /token
 * GET  /oauth2/userinfo                 the signed-in user, for its access token
 * GET  /oauth2/logout                   back to post_logout_redirect_uri
 * POST /mint                            a token from an explicit claims request
 * GET  /mint                            the same through query parameters
 * GET  /admin/status                    where the keys are and when they loaded
 * POST /admin/reload                    re-read the JWKS from disk
 * GET  /admin/reload                    the same, so a browser can do it
 * GET  /swagger-ui.html                 the whole of the above, interactive
 * GET  /v3/api-docs                     the OpenAPI document behind it
 * </pre>
 *
 * <h2>One issuer</h2>
 *
 * `IssuerResolver` settles it once at startup, and the discovery document's
 * `issuer`, the endpoint URLs it advertises and the `iss` claim of every token
 * are all that same string. Before that it was derived per request, so a token
 * minted at loopback and a document fetched from a virtual machine disagreed.
 *
 * Author Claude/bentzn
 * Generated 2026-08-19T10:30:00Z
 */
@SpringBootApplication
@OpenAPIDefinition(info = @Info(title = "raposza JWT mint",
        version = "0.1.0",
        description = "A **test** token service for a local Canton sandbox. It publishes its own private keys and mints whatever it is asked for, including tokens a participant must refuse. Do not run it anywhere that matters.\n\nEach endpoint below says what it does, which standard it implements where there is one, and carries a command that runs as it stands."))
public class JwtMintApp {

    public static void main(String[] arrArg) {
        SpringApplication.run(JwtMintApp.class, arrArg);
    }

}
