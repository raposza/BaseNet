// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.kms.mock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * The key file. One line per key, fields separated by a vertical bar:
 *
 * <pre>
 * &lt;key id&gt;|&lt;kind&gt;|&lt;spec&gt;|&lt;base64 private&gt;|&lt;base64 public&gt;
 * </pre>
 *
 * Private keys are written in the clear. THIS IS NOT SAFE AND IS NOT MEANT TO
 * BE: it exists so a participant can be restarted without losing its identity
 * in a disposable test network. Never point it at anything you care about.
 *
 * The whole file is rewritten on every change, which is correct for the
 * handful of keys a participant holds and would not be for anything larger.
 *
 * Author Claude/bentzn
 *
 * @author Claude/bentzn
 */
public final class MockKeyStore {

    /** What a stored key is for. */
    public enum Kind { SIGNING, ENCRYPTION, SYMMETRIC }

    /** One stored key. */
    public static final class Entry {
        private final String strId;
        private final Kind kind;
        private final String strSpec;
        private final byte[] arrPrivate;
        private final byte[] arrPublic;

        Entry(String strId, Kind kind, String strSpec, byte[] arrPrivate, byte[] arrPublic) {
            this.strId = strId;
            this.kind = kind;
            this.strSpec = strSpec;
            this.arrPrivate = arrPrivate;
            this.arrPublic = arrPublic;
        }

        /**
         * @return the opaque key id Canton stores
         */
        public String strId() {
            return strId;
        }

        /**
         * @return what the key is for
         */
        public Kind kind() {
            return kind;
        }

        /**
         * @return the driver key specification this key was generated for
         */
        public String strSpec() {
            return strSpec;
        }

        /**
         * @return the private key bytes, PKCS#8 for a key pair and raw for a symmetric key
         */
        public byte[] arrPrivate() {
            return arrPrivate;
        }

        /**
         * @return the public key bytes, X.509 SubjectPublicKeyInfo, or null for a symmetric key
         */
        public byte[] arrPublic() {
            return arrPublic;
        }
    }

    private static final String STR_HEADER =
            "# raposza mock kms key store - PLAINTEXT PRIVATE KEYS, TEST USE ONLY";

    private final Path pathFile;
    private final Map<String, Entry> mapKeys = new LinkedHashMap<>();

    public MockKeyStore(String strPath) {
        this.pathFile = Paths.get(strPath);
        load();
    }

    private synchronized void load() {
        mapKeys.clear();
        if (!Files.isReadable(pathFile))
            return;
        List<String> lstLine;
        try {
            lstLine = Files.readAllLines(pathFile, StandardCharsets.UTF_8);
        }
        catch (IOException ex) {
            throw new UncheckedIOException("cannot read key file " + pathFile, ex);
        }
        for (String strLine : lstLine) {
            if (strLine.isBlank() || strLine.startsWith("#"))
                continue;
            String[] arrPart = strLine.split("\\|", -1);
            if (arrPart.length < 5)
                throw new IllegalStateException("malformed line in key file " + pathFile + ": " + strLine);
            byte[] arrPriv = Base64.getDecoder().decode(arrPart[3]);
            byte[] arrPub = arrPart[4].isEmpty() ? null : Base64.getDecoder().decode(arrPart[4]);
            mapKeys.put(arrPart[0],
                    new Entry(arrPart[0], Kind.valueOf(arrPart[1]), arrPart[2], arrPriv, arrPub));
        }
    }

    private synchronized void flush() {
        List<String> lstLine = new ArrayList<>();
        lstLine.add(STR_HEADER);
        for (Entry entry : mapKeys.values()) {
            lstLine.add(entry.strId() + "|" + entry.kind().name() + "|" + entry.strSpec() + "|"
                    + Base64.getEncoder().encodeToString(entry.arrPrivate()) + "|"
                    + (entry.arrPublic() == null ? "" : Base64.getEncoder().encodeToString(entry.arrPublic())));
        }
        try {
            Path dirParent = pathFile.toAbsolutePath().getParent();
            if (dirParent != null)
                Files.createDirectories(dirParent);
            Files.write(pathFile, lstLine, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        }
        catch (IOException ex) {
            throw new UncheckedIOException("cannot write key file " + pathFile, ex);
        }
    }

    /**
     * @param entry the key to add
     */
    public synchronized void put(Entry entry) {
        mapKeys.put(entry.strId(), entry);
        flush();
    }

    /**
     * @param strId the key id
     * @return the stored key, or null when there is none
     */
    public synchronized Entry get(String strId) {
        return mapKeys.get(strId);
    }

    /**
     * @param strId the key id
     * @return true when the key was present and is now gone
     */
    public synchronized boolean remove(String strId) {
        boolean flagGone = mapKeys.remove(strId) != null;
        if (flagGone)
            flush();
        return flagGone;
    }

    /**
     * @return how many keys are held
     */
    public synchronized int cntKeys() {
        return mapKeys.size();
    }

    /**
     * @param entry a stored key pair
     * @param strAlgo the JCA algorithm its key factory answers to
     * @return the private key
     * @throws java.security.GeneralSecurityException when the stored bytes do not parse
     */
    public static PrivateKey keyPrivate(Entry entry, String strAlgo)
            throws java.security.GeneralSecurityException {
        return KeyFactory.getInstance(strAlgo).generatePrivate(new PKCS8EncodedKeySpec(entry.arrPrivate()));
    }

    /**
     * @param entry a stored key pair
     * @param strAlgo the JCA algorithm its key factory answers to
     * @return the public key
     * @throws java.security.GeneralSecurityException when the stored bytes do not parse
     */
    public static PublicKey keyPublic(Entry entry, String strAlgo)
            throws java.security.GeneralSecurityException {
        return KeyFactory.getInstance(strAlgo).generatePublic(new X509EncodedKeySpec(entry.arrPublic()));
    }

    /**
     * @param entry a stored symmetric key
     * @return the secret key
     */
    public static SecretKey keySymmetric(Entry entry) {
        return new SecretKeySpec(entry.arrPrivate(), "AES");
    }
}
