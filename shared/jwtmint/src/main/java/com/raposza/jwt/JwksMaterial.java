// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.ParseException;
import java.util.List;

/**
 * The key material a participant is configured against, and the private half
 * that mints tokens for it.
 *
 * This is the generating end of what a JWKS consumer reads. It
 * lives here rather than in the sandbox because the sandbox is one caller of
 * two - a participant started with a JWKS auth service and a client minting
 * against it need the SAME key, and a second implementation of RSA JWK
 * encoding is exactly the commodity, security-critical duplication that ought
 * not to exist twice in one repository.
 *
 * Nothing about the encoding is hand-rolled: Nimbus generates the key and
 * serialises both halves. The RSA CRT members alone (p, q, dp, dq, qi) are a
 * long tail of base64url edge cases that a bespoke encoder gets subtly wrong
 * and then passes its own tests.
 *
 * Two files, and the distinction is the whole point:
 *
 * <ul>
 * <li>the PRIVATE JWKS, which stays with whoever mints, and is written 0600
 * where the file system allows it;</li>
 * <li>the PUBLIC JWKS, which is what the participant's `url` points at, and
 * carries no `d` and no CRT members.</li>
 * </ul>
 *
 * Author Claude/bentzn
 */
public final class JwksMaterial {

    public static final String STR_DEFAULT_KEY_ID = "raposza-sandbox";

    /**
     * Nimbus refuses anything smaller, and so should we. The sandbox is a test
     * harness, but a test harness that normalises a weak key teaches the wrong
     * shape to whatever copies it.
     */
    private static final int N_KEY_SIZE = 2048;

    private final RSAKey jwkPrivate;


    private JwksMaterial(RSAKey jwkPrivate) {
        this.jwkPrivate = jwkPrivate;
    }


    /**
     * @param idKey the key id, carried in the JWK and in the `kid` header of
     *        every token minted from it; null or blank takes the default
     * @return freshly generated RS256 material, not yet on disk
     * @throws TokenException when the key cannot be generated
     */
    public static JwksMaterial generate(String idKey) {
        String idKeyUsed = (idKey == null || idKey.isBlank()) ? STR_DEFAULT_KEY_ID : idKey.trim();
        try {
            RSAKey jwk = new RSAKeyGenerator(N_KEY_SIZE)
                    .keyID(idKeyUsed)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .generate();
            return new JwksMaterial(jwk);
        }
        catch (JOSEException ex) {
            throw new TokenException("could not generate an RSA key", ex);
        }
    }


    /**
     * @param fileJwks a JWKS file holding at least one private RSA key
     * @return material wrapping the first such key
     * @throws TokenException when the file is unreadable, malformed, or holds
     *         no private RSA key
     */
    public static JwksMaterial load(Path fileJwks) {
        if (fileJwks == null)
            throw new TokenException("no JWKS file supplied");
        if (!Files.isReadable(fileJwks))
            throw new TokenException("JWKS file is not readable: " + fileJwks);

        JWKSet set;
        try {
            set = JWKSet.parse(Files.readString(fileJwks, StandardCharsets.UTF_8));
        }
        catch (IOException ex) {
            throw new TokenException("could not read JWKS file: " + fileJwks, ex);
        }
        catch (ParseException ex) {
            throw new TokenException("not a valid JWKS file: " + fileJwks, ex);
        }

        List<JWK> lstJwk = set.getKeys();
        for (int idxJwk = 0; idxJwk < lstJwk.size(); idxJwk++) {
            JWK jwk = lstJwk.get(idxJwk);
            if (jwk.isPrivate() && jwk instanceof RSAKey)
                return new JwksMaterial((RSAKey) jwk);
        }
        throw new TokenException("no private RSA key in " + fileJwks + " ("
                + lstJwk.size() + " key(s) present)");
    }


    /**
     * Load if the file is there, generate and write it if not.
     *
     * This is the form a repeated run wants. Regenerating on every start would
     * invalidate every token already issued against the old key, and the
     * failure that produces reads like a configuration error rather than like
     * a key that moved.
     *
     * @param fileJwksPrivate where the private JWKS lives
     * @param idKey key id to use when generating; ignored on the load path
     * @return the material
     */
    public static JwksMaterial ensure(Path fileJwksPrivate, String idKey) {
        if (fileJwksPrivate == null)
            throw new TokenException("no JWKS file supplied");
        if (Files.isRegularFile(fileJwksPrivate))
            return load(fileJwksPrivate);

        JwksMaterial material = generate(idKey);
        material.writePrivateJwks(fileJwksPrivate);
        return material;
    }


    /**
     * The URL form a participant is given for a JWKS on the local file system.
     *
     * On 3.5.11 an `http:` URL IS fetched and its keys ARE used - a token
     * minted from this material reached the Ledger API and was accepted,
     * which is the call the note below says is required.
     *
     * On Canton 2.9 in production a `file:` URL is accepted by
     * `jwt-rs-256-jwks`. `file:` is still NOT measured on 3.x - `JwtJwks.url` is a
     * NonEmptyString, so any scheme parses, and the scheme is only exercised
     * when the first token is verified. A stack that starts is therefore not
     * evidence about this.
     *
     * @param fileJwks the public JWKS on disk
     * @return an absolute file: URL
     */
    public static String urlFor(Path fileJwks) {
        if (fileJwks == null)
            throw new TokenException("no JWKS file supplied");
        return fileJwks.toAbsolutePath().normalize().toUri().toString();
    }


    /**
     * @return the private JWK; never log this or any part of it
     */
    public JWK jwk() {
        return jwkPrivate;
    }


    public String idKey() {
        return jwkPrivate.getKeyID();
    }


    public JwtMinter minter() {
        return new JwtMinter(jwkPrivate);
    }


    /**
     * @return a JWKS document carrying the private members
     */
    public String renderPrivateJwks() {
        return renderSet(jwkPrivate.toJSONString());
    }


    /**
     * @return a JWKS document with the private members stripped; this is what
     *         the participant reads
     */
    public String renderPublicJwks() {
        return renderSet(jwkPrivate.toPublicJWK().toJSONString());
    }


    /**
     * @param fileOut where to write; parent directories are created
     * @return the absolute path written
     */
    public Path writePrivateJwks(Path fileOut) {
        Path fileWritten = write(fileOut, renderPrivateJwks());
        restrictToOwner(fileWritten);
        return fileWritten;
    }


    /**
     * @param fileOut where to write; parent directories are created
     * @return the absolute path written
     */
    public Path writePublicJwks(Path fileOut) {
        return write(fileOut, renderPublicJwks());
    }


    /**
     * Key id and type only. Safe for a log line or a status bar.
     */
    public String describe() {
        return JwtMinter.describeKey(jwkPrivate);
    }


    @Override
    public String toString() {
        return "jwks material: " + describe();
    }


    private static String renderSet(String strJwk) {
        return "{\"keys\":[" + strJwk + "]}\n";
    }


    private static Path write(Path fileOut, String strJson) {
        if (fileOut == null)
            throw new TokenException("no output file supplied");

        Path fileAbs = fileOut.toAbsolutePath().normalize();
        try {
            Path dirParent = fileAbs.getParent();
            if (dirParent != null && !Files.isDirectory(dirParent))
                Files.createDirectories(dirParent);
            Files.writeString(fileAbs, strJson, StandardCharsets.UTF_8);
        }
        catch (IOException ex) {
            throw new TokenException("could not write " + fileAbs, ex);
        }
        return fileAbs;
    }


    /**
     * Best effort, and deliberately silent when it cannot be done. A file
     * system without POSIX permissions is not a reason to refuse to write the
     * key, and throwing here would make the sandbox unusable on Windows for a
     * reason that has nothing to do with Canton.
     */
    static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
        catch (UnsupportedOperationException | IOException ex) {
            // Non-POSIX file system. The file is written either way.
        }
    }

}
