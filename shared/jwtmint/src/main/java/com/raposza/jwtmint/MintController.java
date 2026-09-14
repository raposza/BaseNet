// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwtmint;

import com.raposza.jwt.MintAlg;
import com.raposza.jwt.MintKeys;

import com.nimbusds.jose.jwk.JWK;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The key set and the mint itself.
 *
 * Both halves of the JWKS are served. The public one is what a participant is
 * pointed at; the private one is what a second minting process is pointed at,
 * so that everything issuing tokens against a participant uses the key that
 * participant already trusts without anyone copying a file. It also carries the
 * HS* shared secrets, which are the only way to use those algorithms at all.
 *
 * Every interesting path has a `.txt` twin returning the bare value, so a shell
 * that wants one string does not need a JSON parser.
 *
 * <h2>The query parameters are declared on the operation</h2>
 *
 * `/mint` and `/mint.txt` bind a raw parameter map so the two cannot drift
 * apart, which leaves the generated document with no parameters at all to show.
 * They are therefore declared on {@link Operation#parameters()} by hand - the
 * same fourteen names {@link MintRequest} carries, each with a value that works
 * as it stands.
 *
 * Author Claude/bentzn
 * Generated 2026-08-19T18:00:00Z
 */
@RestController
@Tag(name = "Keys and tokens",
        description = "The signing keys, and the tokens minted from them.")
public class MintController {

    private final MintKeyStore store;

    private final MintService minter;

    private final IssuerResolver resolver;


    public MintController(MintKeyStore store, MintService minter, IssuerResolver resolver) {
        this.store = store;
        this.minter = minter;
        this.resolver = resolver;
    }


    @Operation(summary = "The PUBLIC key set - point a participant here",
            description = "**Standard: RFC 7517** - a JWK Set. The key parameters"
                    + " themselves are RFC 7518. Serving it under"
                    + " `/.well-known/jwks.json` is the OpenID Connect Discovery"
                    + " 1.0 convention; the authoritative location is whatever"
                    + " `jwks_uri` in the discovery document says, which is"
                    + " `/oauth2/jwks`.\n\n"
                    + "RSA and EC public keys only; no private member is ever in"
                    + " this document.\n\n"
                    + "This is the url a Canton `jwt-jwks` auth-service reads:\n\n"
                    + "```\nauth-services = [{\n"
                    + "  type = jwt-jwks\n"
                    + "  url = \"http://localhost:32002/jwks.json\"\n"
                    + "  target-audience = \"https://daml.com/jwt/aud/participant/sandbox\"\n"
                    + "}]\n```\n\n"
                    + "**The HS\\* keys are NOT here.** A symmetric key has no public"
                    + " half, so a participant configured with a JWKS url can never"
                    + " verify an HS\\* token.\n\n"
                    + "```\ncurl http://localhost:32002/jwks.json\n```")
    @GetMapping(value = {IssuerResolver.STR_PATH_JWKS, "/jwks.json",
            "/.well-known/jwks.json"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public String strJwksPublic() {
        return store.keys().renderPublicJwks();
    }


    @Operation(summary = "The FULL key set, private members included",
            description = "**Standard: RFC 7517** for the document, including the"
                    + " private members of section 4 and the `k` member of RFC 7518"
                    + " section 6.4. The PATH is not standard: no specification"
                    + " publishes private keys over HTTP, for the obvious"
                    + " reason.\n\n"
                    + "**UNSAFE. For development use only.** This returns the private"
                    + " exponents of every RSA and EC key and the `k` member of every"
                    + " HMAC key. Anyone who fetches it can mint a token this service's"
                    + " participants will accept.\n\n"
                    + "It exists so a second process - a script, a test, another tool -"
                    + " can mint against the same key without anyone copying a file, and"
                    + " because the `k` member is the only way to use HS\\* at all.\n\n"
                    + "```\ncurl http://localhost:32002/jwks-private.json\n```")
    @GetMapping(value = {IssuerResolver.STR_PATH_JWKS_PRIVATE, "/jwks-private.json"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public String strJwksPrivate() {
        return store.keys().renderPrivateJwks();
    }


    @Operation(summary = "One row per key: algorithm, key id, type, size",
            description = "Not a standard - this service's own summary of what RFC 7517"
                    + " documents describe. Carries no key material.\n\n"
                    + "The key id is the algorithm in lower case, so `rs256` signs"
                    + " RS256. Pass one as `kid` to `/mint` to sign with a key the"
                    + " algorithm would not have chosen, which is how a"
                    + " deliberately mismatched token is made.\n\n"
                    + "```\ncurl http://localhost:32002/keys\n```")
    @GetMapping("/keys")
    public List<KeyRow> lstKeys() {
        MintKeys keys = store.keys();
        List<KeyRow> lstOut = new ArrayList<>();
        for (MintAlg alg : MintAlg.values()) {
            if (!alg.flagSigned())
                continue;
            JWK jwk = keys.jwk(alg);
            lstOut.add(KeyRow.of(alg.name(), jwk));
        }
        return lstOut;
    }


    @Operation(summary = "The same rows, tab separated",
            description = "`alg`, `kid`, `type`, `detail`, `published`, one line"
                    + " each, for a shell that does not want to parse JSON.\n\n"
                    + "```\ncurl -s http://localhost:32002/keys.txt | column -t\n```")
    @GetMapping(value = "/keys.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public String strKeys() {
        StringBuilder sb = new StringBuilder();
        List<KeyRow> lstRow = lstKeys();
        for (int idxRow = 0; idxRow < lstRow.size(); idxRow++) {
            KeyRow row = lstRow.get(idxRow);
            sb.append(row.alg()).append('\t')
                    .append(row.kid()).append('\t')
                    .append(row.kty()).append('\t')
                    .append(row.detail()).append('\t')
                    .append(row.published()).append('\n');
        }
        return sb.toString();
    }


    @Operation(summary = "Mint a token from a JSON body - the full surface",
            description = "**Standard: the OUTPUT only.** What comes back is an RFC 7519"
                    + " JWT, signed as an RFC 7515 JWS with an RFC 7518 algorithm. The"
                    + " request shape is this service's own; nothing standard describes"
                    + " an endpoint that mints whatever it is asked for.\n\n"
                    + "**UNSAFE. For development use only.** No credential is checked and"
                    + " any token can be asked for, including ones a participant must"
                    + " refuse.\n\n"
                    + "Every field is optional. `shape` fills in the Canton defaults:"
                    + " `AUDIENCE` writes `aud` as"
                    + " `https://daml.com/jwt/aud/participant/{participantId}`, `SCOPE`"
                    + " writes `scope` as `daml_ledger_api`, `CUSTOM` writes the legacy"
                    + " `https://daml.com/ledger-api` claim (2.x only; measured REFUSED"
                    + " on 3.5.11). Omit it, or send `RAW`, and nothing is filled in.\n\n"
                    + "`claims` is merged LAST, over everything the service writes, and"
                    + " a null value there REMOVES a claim. That is how an expired"
                    + " token, or one with no issuer, is asked for:\n\n"
                    + "```\ncurl -X POST http://localhost:32002/mint \\\n"
                    + "  -H 'content-type: application/json' \\\n"
                    + "  -d '{\"alg\":\"HS512\",\"sub\":\"alice\",\"ttlSeconds\":30,\n"
                    + "       \"claims\":{\"exp\":1,\"iss\":null}}'\n```\n\n"
                    + "The response carries the token AND its decoded claims, because"
                    + " the first question about a refused token is what was in it.")
    @PostMapping(value = "/mint", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MintResponse mintPosted(@RequestBody(required = false) MintRequest req) {
        return minter.mint(req, resolver.strIssuer());
    }


    @Operation(summary = "Mint a token from query parameters",
            description = "**Standard: the OUTPUT only** - an RFC 7519 JWT, signed per"
                    + " RFC 7515. The same as the POST, with the parameter names being"
                    + " the JSON field names. `aud`, `actAs` and `readAs` take comma"
                    + " separated lists. `claims` has no query form - post JSON for"
                    + " that, since only JSON can carry a null.\n\n"
                    + "**UNSAFE. For development use only.**\n\n"
                    + "An audience-based token for the sandbox participant:\n\n"
                    + "```\ncurl 'http://localhost:32002/mint?shape=AUDIENCE"
                    + "&sub=participant_admin&participantId=sandbox'\n```\n\n"
                    + "A scope-based one:\n\n"
                    + "```\ncurl 'http://localhost:32002/mint?shape=SCOPE"
                    + "&sub=participant_admin'\n```\n\n"
                    + "A token that never expires, and one that is not signed at all:\n\n"
                    + "```\ncurl 'http://localhost:32002/mint?sub=alice&ttlSeconds=0'\n"
                    + "curl 'http://localhost:32002/mint?sub=alice&alg=NONE'\n```",
            parameters = {
                    @Parameter(name = "shape", in = ParameterIn.QUERY, example = "AUDIENCE",
                            description = "AUDIENCE, SCOPE, CUSTOM or RAW."),
                    @Parameter(name = "sub", in = ParameterIn.QUERY,
                            example = "participant_admin",
                            description = "The `sub` claim - the ledger user the token"
                                    + " speaks for."),
                    @Parameter(name = "participantId", in = ParameterIn.QUERY,
                            example = "sandbox",
                            description = "The participant the AUDIENCE shape builds its"
                                    + " audience from."),
                    @Parameter(name = "aud", in = ParameterIn.QUERY,
                            example = "https://daml.com/jwt/aud/participant/sandbox",
                            description = "The `aud` claim, comma separated for several."),
                    @Parameter(name = "scope", in = ParameterIn.QUERY,
                            example = "daml_ledger_api",
                            description = "The `scope` claim."),
                    @Parameter(name = "ttlSeconds", in = ParameterIn.QUERY, example = "3600",
                            description = "Lifetime. 0 or less writes no `exp` at all."),
                    @Parameter(name = "alg", in = ParameterIn.QUERY, example = "RS256",
                            description = "Signing algorithm, including NONE."),
                    @Parameter(name = "kid", in = ParameterIn.QUERY, example = "rs256",
                            description = "A specific key, overriding the one `alg` picks."),
                    @Parameter(name = "iss", in = ParameterIn.QUERY,
                            example = "http://localhost:32002",
                            description = "The `iss` claim. Blank omits it."),
                    @Parameter(name = "actAs", in = ParameterIn.QUERY, example = "Alice",
                            description = "CUSTOM only. Comma separated."),
                    @Parameter(name = "readAs", in = ParameterIn.QUERY, example = "Alice",
                            description = "CUSTOM only. Comma separated."),
                    @Parameter(name = "admin", in = ParameterIn.QUERY, example = "true",
                            description = "CUSTOM only."),
                    @Parameter(name = "applicationId", in = ParameterIn.QUERY,
                            example = "raposza", description = "CUSTOM only."),
                    @Parameter(name = "ledgerId", in = ParameterIn.QUERY, example = "sandbox",
                            description = "CUSTOM only.")})
    @GetMapping("/mint")
    public MintResponse mintAsked(@RequestParam Map<String, String> mapParam) {
        return minter.mint(reqOf(mapParam), resolver.strIssuer());
    }


    @Operation(summary = "The token and nothing else",
            description = "**Standard: the OUTPUT only** - the RFC 7515 compact"
                    + " serialisation, bare, so a shell can use it directly. Same"
                    + " parameters as `GET /mint`.\n\n"
                    + "**UNSAFE. For development use only.**\n\n"
                    + "```\nTOKEN=$(curl -s 'http://localhost:32002/mint.txt?"
                    + "shape=AUDIENCE&sub=participant_admin&participantId=sandbox')\n"
                    + "curl -H \"Authorization: Bearer $TOKEN\" \\\n"
                    + "     http://localhost:22211/v2/users\n```",
            parameters = {
                    @Parameter(name = "shape", in = ParameterIn.QUERY, example = "AUDIENCE",
                            description = "AUDIENCE, SCOPE, CUSTOM or RAW."),
                    @Parameter(name = "sub", in = ParameterIn.QUERY,
                            example = "participant_admin",
                            description = "The `sub` claim - the ledger user the token"
                                    + " speaks for."),
                    @Parameter(name = "participantId", in = ParameterIn.QUERY,
                            example = "sandbox",
                            description = "The participant the AUDIENCE shape builds its"
                                    + " audience from."),
                    @Parameter(name = "scope", in = ParameterIn.QUERY,
                            example = "daml_ledger_api",
                            description = "The `scope` claim."),
                    @Parameter(name = "ttlSeconds", in = ParameterIn.QUERY, example = "3600",
                            description = "Lifetime. 0 or less writes no `exp` at all."),
                    @Parameter(name = "alg", in = ParameterIn.QUERY, example = "RS256",
                            description = "Signing algorithm, including NONE."),
                    @Parameter(name = "kid", in = ParameterIn.QUERY, example = "rs256",
                            description = "A specific key, overriding the one `alg` picks.")})
    @GetMapping(value = "/mint.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public String strMintAsked(@RequestParam Map<String, String> mapParam) {
        return minter.mint(reqOf(mapParam), resolver.strIssuer()).token() + "\n";
    }


    /**
     * Query parameters bound BY NAME rather than through fourteen annotated
     * arguments, so `/mint` and `/mint.txt` share one binding and cannot drift
     * apart when a field is added.
     *
     * @param mapParam the raw parameters
     * @return the request they describe
     */
    private static MintRequest reqOf(Map<String, String> mapParam) {
        return new MintRequest(mapParam.get("alg"), mapParam.get("kid"), mapParam.get("sub"),
                mapParam.get("iss"), lstOf(mapParam.get("aud")), mapParam.get("scope"),
                nOf(mapParam.get("ttlSeconds")), mapParam.get("shape"),
                lstOf(mapParam.get("actAs")), lstOf(mapParam.get("readAs")),
                flagOf(mapParam.get("admin")), mapParam.get("applicationId"),
                mapParam.get("ledgerId"), mapParam.get("participantId"), null,
                mapParam.get("secret"));
    }


    /**
     * @param strValue a comma separated list, or null
     * @return its parts, or null when there were none
     */
    private static List<String> lstOf(String strValue) {
        if (strValue == null || strValue.isBlank())
            return null;

        List<String> lstOut = new ArrayList<>();
        String[] arrPart = strValue.split(",");
        for (int idxPart = 0; idxPart < arrPart.length; idxPart++) {
            String strPart = arrPart[idxPart].trim();
            if (!strPart.isEmpty())
                lstOut.add(strPart);
        }
        return lstOut.isEmpty() ? null : lstOut;
    }


    private static Long nOf(String strValue) {
        if (strValue == null || strValue.isBlank())
            return null;
        try {
            return Long.valueOf(strValue.trim());
        }
        catch (NumberFormatException ex) {
            throw new IllegalArgumentException("ttlSeconds is not a number: " + strValue);
        }
    }


    private static Boolean flagOf(String strValue) {
        if (strValue == null || strValue.isBlank())
            return null;
        return Boolean.valueOf(Boolean.parseBoolean(strValue.trim()));
    }

}
