// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwtmint;

import com.raposza.jwt.MintKeys;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the service is, where its keys are, and the one button it has.
 *
 * Reload is served on GET as well as POST. It mutates nothing outside this
 * process - it re-reads a file - and being able to do it from an address bar is
 * worth more here than the verb being correct.
 *
 * Author Claude/bentzn
 * Generated 2026-08-19T10:30:00Z
 */
@RestController
@Tag(name = "Service", description = "What this service is and where its keys are.")
public class AdminController {

    private final MintKeyStore store;

    private final MintSettings settings;

    private final IssuerResolver resolver;


    public AdminController(MintKeyStore store, MintSettings settings, IssuerResolver resolver) {
        this.store = store;
        this.settings = settings;
        this.resolver = resolver;
    }


    /**
     * @return the endpoint list, in plain text, for whoever opened the root
     */
    @GetMapping(value = "/", produces = MediaType.TEXT_PLAIN_VALUE)
    public String strIndex() {
        String strBase = IssuerResolver.strOfRequest();
        StringBuilder sb = new StringBuilder();
        sb.append("raposza JWT mint - a TEST token service. It publishes its own\n");
        sb.append("private keys. Do not run it anywhere that matters.\n\n");
        sb.append("keys      ").append(settings.dirKeys()).append("\n\n");
        sb.append("issuer    ").append(resolver.strIssuer()).append("\n\n");
        sb.append("GET  ").append(strBase).append("/oauth2/jwks   (also /jwks.json,\n");
        sb.append("                                     /.well-known/jwks.json)\n");
        sb.append("GET  ").append(strBase).append("/oauth2/jwks-private\n");
        sb.append("GET  ").append(strBase).append("/keys\n");
        sb.append("GET  ").append(strBase)
                .append("/.well-known/oauth-authorization-server\n");
        sb.append("GET  ").append(strBase).append("/.well-known/openid-configuration\n");
        sb.append("POST ").append(strBase)
                .append("/oauth2/token  (also /oauth/token, /token)\n");
        sb.append("GET  ").append(strBase).append("/oauth2/authorize  (sign in)\n");
        sb.append("GET  ").append(strBase).append("/oauth2/userinfo\n");
        sb.append("GET  ").append(strBase).append("/oauth2/logout\n");
        sb.append("GET  ").append(strBase).append("/mint\n");
        sb.append("POST ").append(strBase).append("/mint\n");
        sb.append("GET  ").append(strBase).append("/admin/status\n");
        sb.append("GET  ").append(strBase).append("/admin/reload  (also POST)\n");
        sb.append("GET  ").append(strBase).append("/swagger-ui.html\n");
        sb.append("GET  ").append(strBase).append("/v3/api-docs\n\n");
        sb.append("examples\n");
        sb.append("  ").append(strBase).append("/mint?shape=SCOPE&sub=alice\n");
        sb.append("  ").append(strBase)
                .append("/mint?shape=AUDIENCE&sub=alice&participantId=sandbox\n");
        sb.append("  ").append(strBase).append("/mint?alg=HS256&sub=alice\n");
        sb.append("  ").append(strBase).append("/mint?alg=NONE&sub=alice\n");
        sb.append("  ").append(strBase).append("/mint?sub=alice&ttlSeconds=0\n");
        return sb.toString();
    }


    /**
     * @return where the keys are, which ones came up, and when
     */
    @Operation(summary = "Where the keys are and when they loaded",
            description = "Paths, key ids and defaults; no key"
                    + " material.\n\n"
                    + "```\ncurl http://localhost:32002/admin/status\n```")
    @GetMapping("/admin/status")
    public Map<String, Object> mapStatus() {
        return mapOf(store.keys());
    }


    /**
     * @return the same status, after re-reading the JWKS from disk
     */
    @Operation(summary = "Re-read the JWKS from disk",
            description = "Picks up a hand-edited or restored key set"
                    + " without a restart. NOTE that a running participant"
                    + " caches the JWKS for five minutes by default, so it"
                    + " sees the change up to five minutes later unless"
                    + " `jwks-cache-config.cache-expiration` is lowered.\n\n"
                    + "```\ncurl -X POST http://localhost:32002/admin/reload\n```")
    @PostMapping("/admin/reload")
    public Map<String, Object> mapReloadPosted() {
        return mapOf(store.reload());
    }


    /**
     * @return the same status, after re-reading the JWKS from disk
     */
    @GetMapping("/admin/reload")
    public Map<String, Object> mapReloadAsked() {
        return mapOf(store.reload());
    }


    private Map<String, Object> mapOf(MintKeys keys) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("service", "raposza-jwtmint");
        map.put("dirKeys", String.valueOf(keys.dirKeys()));
        map.put("filePrivate", String.valueOf(keys.filePrivate()));
        map.put("filePublic", String.valueOf(keys.filePublic()));
        map.put("loadedAt", keys.instLoaded().toString());
        map.put("kids", keys.lstKid());
        map.put("issuer", resolver.strIssuer());
        map.put("issuerPinned", Boolean.valueOf(resolver.flagPinned()));
        map.put("defaultAlg", settings.algDefault().name());
        map.put("defaultSubject", settings.strSubjectDefault());
        map.put("ttlSeconds", Long.valueOf(settings.nTtlSecondsDefault()));
        return map;
    }

}
