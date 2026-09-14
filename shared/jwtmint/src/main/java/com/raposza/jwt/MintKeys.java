// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwt;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The mint's key material: one private key per {@link MintAlg}, on disk, kept
 * across restarts.
 *
 * <h2>Permanent, and that is the whole point</h2>
 *
 * A participant is configured against a JWKS url and reads it when it first
 * verifies a token. Regenerating on every start would invalidate every token
 * already issued and every `auth-services` block already written, and the
 * failure that produces reads like a configuration error rather than like a key
 * that moved. So: load what is there, generate only what is missing, write back
 * only when something was generated.
 *
 * <h2>Two files, and the distinction is load-bearing</h2>
 *
 * <ul>
 * <li>`jwks-private.json` - every key with its private members, written 0600
 * where the file system allows it. This is what mints.</li>
 * <li>`jwks-public.json` - the same set with the private members stripped. This
 * is what a participant's `url` points at.</li>
 * </ul>
 *
 * The public set is SMALLER than the private one and not only by the members it
 * drops: an octet sequence has no public half at all, so the HS* keys are
 * absent from it entirely. A verifier that wants HS* needs the shared secret,
 * which only the private set carries.
 *
 * <h2>What this is not</h2>
 *
 * {@link JwksMaterial} is one RSA key for one participant, and stays. This is a
 * set of keys for a service whose job is to hand out tokens of every shape. The
 * two share a directory convention and nothing else.
 *
 * Author Claude/bentzn
 */
public final class MintKeys {

    public static final String STR_FILE_PRIVATE = "jwks-private.json";

    public static final String STR_FILE_PUBLIC = "jwks-public.json";

    private final Path dirKeys;

    private final Map<String, JWK> mapJwk;

    private final Instant instLoaded;


    private MintKeys(Path dirKeys, Map<String, JWK> mapJwk) {
        this.dirKeys = dirKeys;
        this.mapJwk = Collections.unmodifiableMap(mapJwk);
        this.instLoaded = Instant.now();
    }


    /**
     * @return where the keys live when nothing says otherwise
     */
    public static Path dirDefault() {
        return Path.of(System.getProperty("user.home"), ".raposza", "jwtmint", "keys");
    }


    /**
     * Load what is on disk, generate what is missing, write back if anything
     * was generated.
     *
     * A key already present under a given `kid` is NEVER replaced, even when it
     * is of a type that does not match the algorithm the kid names. Replacing
     * it would silently discard material a participant may already be
     * configured against; a mismatch surfaces at signing time instead, where
     * the message can name the key.
     *
     * @param dirKeysIn the directory, or null for {@link #dirDefault()}
     * @return the loaded set
     * @throws TokenException when the private file is unreadable or malformed
     */
    public static MintKeys ensure(Path dirKeysIn) {
        Path dirAbs = (dirKeysIn == null ? dirDefault() : dirKeysIn)
                .toAbsolutePath().normalize();

        Map<String, JWK> mapRead = new LinkedHashMap<>();
        Path filePriv = dirAbs.resolve(STR_FILE_PRIVATE);
        if (Files.isRegularFile(filePriv)) {
            JWKSet set;
            try {
                set = JWKSet.parse(Files.readString(filePriv, StandardCharsets.UTF_8));
            }
            catch (IOException ex) {
                throw new TokenException("could not read " + filePriv, ex);
            }
            catch (ParseException ex) {
                throw new TokenException("not a valid JWKS file: " + filePriv, ex);
            }

            List<JWK> lstJwk = set.getKeys();
            for (int idxJwk = 0; idxJwk < lstJwk.size(); idxJwk++) {
                JWK jwk = lstJwk.get(idxJwk);
                String strKid = jwk.getKeyID();
                if (strKid != null && !strKid.isBlank())
                    mapRead.put(strKid, jwk);
            }
        }

        boolean flagGenerated = false;
        for (MintAlg alg : MintAlg.values()) {
            if (!alg.flagSigned())
                continue;
            JWK jwk = mapRead.get(alg.strKid());
            if (jwk == null || !jwk.isPrivate()) {
                mapRead.put(alg.strKid(), alg.generate());
                flagGenerated = true;
            }
        }

        // ORDERED BY ALGORITHM so the file reads the same way twice, then
        // whatever else the operator put in the file, kept rather than dropped.
        Map<String, JWK> mapOut = new LinkedHashMap<>();
        for (MintAlg alg : MintAlg.values()) {
            if (!alg.flagSigned())
                continue;
            mapOut.put(alg.strKid(), mapRead.remove(alg.strKid()));
        }
        mapOut.putAll(mapRead);

        MintKeys keys = new MintKeys(dirAbs, mapOut);
        if (flagGenerated || !Files.isRegularFile(dirAbs.resolve(STR_FILE_PUBLIC)))
            keys.write();
        return keys;
    }


    /**
     * @return the directory holding both files
     */
    public Path dirKeys() {
        return dirKeys;
    }


    public Path filePrivate() {
        return dirKeys.resolve(STR_FILE_PRIVATE);
    }


    public Path filePublic() {
        return dirKeys.resolve(STR_FILE_PUBLIC);
    }


    /**
     * @return when this set was read from disk
     */
    public Instant instLoaded() {
        return instLoaded;
    }


    /**
     * @param alg the algorithm
     * @return its key
     * @throws TokenException for NONE, or when the key is absent
     */
    public JWK jwk(MintAlg alg) {
        if (alg == null)
            throw new TokenException("no algorithm supplied");
        if (!alg.flagSigned())
            throw new TokenException("NONE has no key");

        JWK jwk = mapJwk.get(alg.strKid());
        if (jwk == null)
            throw new TokenException("no key '" + alg.strKid() + "' in " + filePrivate());
        return jwk;
    }


    /**
     * @param strKid the key id
     * @return that key
     * @throws TokenException when no key carries that id, naming what does
     */
    public JWK jwkOfKid(String strKid) {
        if (strKid == null || strKid.isBlank())
            throw new TokenException("no key id supplied");

        JWK jwk = mapJwk.get(strKid.trim());
        if (jwk == null) {
            throw new TokenException("no key '" + strKid + "'; known: "
                    + String.join(", ", mapJwk.keySet()));
        }
        return jwk;
    }


    /**
     * @return every key, private members included, in algorithm order
     */
    public List<JWK> lstJwk() {
        return new ArrayList<>(mapJwk.values());
    }


    /**
     * @return the key ids, in algorithm order
     */
    public List<String> lstKid() {
        return new ArrayList<>(mapJwk.keySet());
    }


    /**
     * @return a JWKS document carrying every private member; never log this
     */
    public String renderPrivateJwks() {
        List<String> lstJson = new ArrayList<>();
        for (JWK jwk : mapJwk.values()) {
            lstJson.add(jwk.toJSONString());
        }
        return strRenderSet(lstJson);
    }


    /**
     * @return a JWKS document with the private members stripped, and with the
     *         symmetric keys absent entirely because they have no public half
     */
    public String renderPublicJwks() {
        List<String> lstJson = new ArrayList<>();
        for (JWK jwk : mapJwk.values()) {
            // SKIPPED BY TYPE rather than by trusting toPublicJWK to return
            // null for an octet sequence. Publishing a shared secret in the
            // set a participant fetches is the one mistake here that would not
            // announce itself, so it is refused by a rule that cannot depend on
            // another library's null contract.
            if (KeyType.OCT.equals(jwk.getKeyType()))
                continue;
            JWK jwkPublic = jwk.toPublicJWK();
            if (jwkPublic != null)
                lstJson.add(jwkPublic.toJSONString());
        }
        return strRenderSet(lstJson);
    }


    /**
     * Writes both files, creating the directory.
     *
     * @throws TokenException when either write fails
     */
    public void write() {
        writeFile(filePrivate(), renderPrivateJwks());
        JwksMaterial.restrictToOwner(filePrivate());
        writeFile(filePublic(), renderPublicJwks());
    }


    @Override
    public String toString() {
        return "mint keys: " + mapJwk.size() + " in " + dirKeys;
    }


    private static String strRenderSet(List<String> lstJson) {
        return "{\"keys\":[" + String.join(",", lstJson) + "]}\n";
    }


    private static void writeFile(Path fileOut, String strJson) {
        try {
            Path dirParent = fileOut.getParent();
            if (dirParent != null && !Files.isDirectory(dirParent))
                Files.createDirectories(dirParent);
            Files.writeString(fileOut, strJson, StandardCharsets.UTF_8);
        }
        catch (IOException ex) {
            throw new TokenException("could not write " + fileOut, ex);
        }
    }

}
