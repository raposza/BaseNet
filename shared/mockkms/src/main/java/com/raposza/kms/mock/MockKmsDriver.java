// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.kms.mock;

import com.digitalasset.canton.crypto.kms.driver.api.v1.EncryptionAlgoSpec;
import com.digitalasset.canton.crypto.kms.driver.api.v1.EncryptionKeySpec;
import com.digitalasset.canton.crypto.kms.driver.api.v1.KeySpec;
import com.digitalasset.canton.crypto.kms.driver.api.v1.KmsDriver;
import com.digitalasset.canton.crypto.kms.driver.api.v1.KmsDriverException;
import com.digitalasset.canton.crypto.kms.driver.api.v1.KmsDriverHealth;
import com.digitalasset.canton.crypto.kms.driver.api.v1.PublicKey;
import com.digitalasset.canton.crypto.kms.driver.api.v1.SigningAlgoSpec;
import com.digitalasset.canton.crypto.kms.driver.api.v1.SigningKeySpec;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.util.concurrent.Callable;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.slf4j.Logger;
import scala.Option;
import scala.concurrent.Future;
import scala.concurrent.Future$;
import scala.runtime.BoxedUnit;

/**
 * A Canton KMS Driver v1 whose keys live in a plaintext file.
 *
 * WHAT THIS IS FOR. A disposable network needs a participant that can restart
 * without losing its identity, and the vendor's own mock driver keeps its keys
 * in memory. This one writes them to disk instead. It is otherwise a mock: the
 * private keys are readable by anyone who can read the file, there is no access
 * control, no rotation and no attestation. It reproduces the SHAPE of a
 * KMS-backed participant, not its security.
 *
 * WHAT IT ADVERTISES. Signing on EC P-256, EC P-384 and Ed25519, and asymmetric
 * encryption on RSA-2048 with OAEP/SHA-256. It does NOT advertise secp256k1,
 * which JDK 16 removed from SunEC, nor the ECIES encryption specification,
 * which the JDK does not implement. Canton negotiates down to what a driver
 * publishes, so a participant will use the subset above.
 *
 * Author Claude/bentzn
 *
 * @author Claude/bentzn
 */
public final class MockKmsDriver implements KmsDriver {

    private static final int N_GCM_TAG_BITS = 128;
    private static final int N_GCM_IV_BYTES = 12;

    private final MockKmsDriverConfig cfg;
    private final MockKeyStore store;
    private final Logger logger;
    private final SecureRandom rnd = new SecureRandom();

    public MockKmsDriver(MockKmsDriverConfig cfg, Logger logger) {
        this.cfg = cfg;
        this.store = new MockKeyStore(cfg.fileKeys());
        this.logger = logger;
        logger.info("Raposza mock KMS driver ready, key file {} holding {} key(s). "
                + "Private keys are stored UNENCRYPTED - test use only.",
                cfg.fileKeys(), Integer.valueOf(store.cntKeys()));
    }

    // ---- the pieces every operation shares -------------------------------

    /**
     * Runs an operation, logs it when audit logging is on, and turns any
     * failure into the exception type Canton expects. Nothing here is
     * retryable: a local file and the JCE do not fail transiently the way a
     * cloud KMS does, so a failure here is a real one and Canton should not
     * spin on it.
     *
     * @param <T> what the operation returns
     * @param strOp the operation's name, for the audit log
     * @param call the operation
     * @return a completed future
     */
    private <T> Future<T> runOp(String strOp, Callable<T> call) {
        if (cfg.flagAudit())
            logger.info("Sending request: KMS operation `{}`.", strOp);
        try {
            T result = call.call();
            if (cfg.flagAudit())
                logger.info("Received response: KMS operation `{}` succeeded.", strOp);
            return Future$.MODULE$.successful(result);
        }
        catch (Throwable ex) {
            if (cfg.flagAudit())
                logger.warn("Request failed: KMS operation `" + strOp + "`", ex);
            return Future$.MODULE$.failed(
                    new KmsDriverException(new RuntimeException("KMS operation `" + strOp + "` failed", ex), false));
        }
    }

    private static <A> scala.collection.immutable.Set<A> setOf(A... arrItem) {
        @SuppressWarnings("unchecked")
        scala.collection.immutable.Set<A> set =
                (scala.collection.immutable.Set<A>) scala.collection.immutable.Set$.MODULE$.empty();
        for (A item : arrItem) {
            @SuppressWarnings("unchecked")
            scala.collection.immutable.Set<A> setNext = (scala.collection.immutable.Set<A>) set.$plus(item);
            set = setNext;
        }
        return set;
    }

    /** The key id is a hash of the public key, so it is stable and opaque. */
    private static String strIdOf(byte[] arrBytes) throws Exception {
        byte[] arrHash = MessageDigest.getInstance("SHA-256").digest(arrBytes);
        StringBuilder bld = new StringBuilder(arrHash.length * 2);
        for (byte b : arrHash) {
            bld.append(Character.forDigit((b >> 4) & 0xF, 16));
            bld.append(Character.forDigit(b & 0xF, 16));
        }
        return bld.toString();
    }

    private MockKeyStore.Entry entryOf(String strKeyId) {
        MockKeyStore.Entry entry = store.get(strKeyId);
        if (entry == null)
            throw new IllegalArgumentException("key not found: " + strKeyId);
        return entry;
    }

    // ---- the specification tables ----------------------------------------
    //
    // These four strings are the only place a driver specification is turned
    // into a JCA name. A specification we do not name here is one we do not
    // advertise, and Canton will never ask for it.

    private static String strKeyAlgo(String strSpec) {
        switch (strSpec) {
            case "EcP256":
            case "EcP384":
                return "EC";
            case "EcCurve25519":
                return "Ed25519";
            case "Rsa2048":
                return "RSA";
            default:
                throw new IllegalArgumentException("unsupported key specification: " + strSpec);
        }
    }

    private static String strSigAlgo(SigningAlgoSpec spec) {
        String strName = spec.getClass().getSimpleName().replace("$", "");
        switch (strName) {
            case "EcDsaSha256":
                return "SHA256withECDSA";
            case "EcDsaSha384":
                return "SHA384withECDSA";
            case "Ed25519":
                return "Ed25519";
            default:
                throw new IllegalArgumentException("unsupported signing algorithm: " + strName);
        }
    }

    private static String strEncAlgo(EncryptionAlgoSpec spec) {
        String strName = spec.getClass().getSimpleName().replace("$", "");
        if ("RsaEsOaepSha256".equals(strName))
            return "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
        throw new IllegalArgumentException("unsupported encryption algorithm: " + strName);
    }

    /**
     * @param spec the driver encryption algorithm specification
     * @return the OAEP parameters it denotes, with BOTH digests named
     */
    static OAEPParameterSpec specOaep(EncryptionAlgoSpec spec) {
        String strName = spec.getClass().getSimpleName().replace("$", "");
        if ("RsaEsOaepSha256".equals(strName))
            return new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                    PSource.PSpecified.DEFAULT);
        throw new IllegalArgumentException("unsupported encryption algorithm: " + strName);
    }

    private static String strSpecName(Object spec) {
        return spec.getClass().getSimpleName().replace("$", "");
    }

    // ---- what the driver supports ----------------------------------------

    @Override
    public scala.collection.immutable.Set<SigningKeySpec> supportedSigningKeySpecs() {
        return setOf(
                (SigningKeySpec) com.digitalasset.canton.crypto.kms.driver.api.v1.SigningKeySpec.EcP256$.MODULE$,
                (SigningKeySpec) com.digitalasset.canton.crypto.kms.driver.api.v1.SigningKeySpec.EcP384$.MODULE$,
                (SigningKeySpec) com.digitalasset.canton.crypto.kms.driver.api.v1.SigningKeySpec.EcCurve25519$.MODULE$);
    }

    @Override
    public scala.collection.immutable.Set<SigningAlgoSpec> supportedSigningAlgoSpecs() {
        return setOf(
                (SigningAlgoSpec) com.digitalasset.canton.crypto.kms.driver.api.v1.SigningAlgoSpec.EcDsaSha256$.MODULE$,
                (SigningAlgoSpec) com.digitalasset.canton.crypto.kms.driver.api.v1.SigningAlgoSpec.EcDsaSha384$.MODULE$,
                (SigningAlgoSpec) com.digitalasset.canton.crypto.kms.driver.api.v1.SigningAlgoSpec.Ed25519$.MODULE$);
    }

    @Override
    public scala.collection.immutable.Set<EncryptionKeySpec> supportedEncryptionKeySpecs() {
        return setOf(
                (EncryptionKeySpec) com.digitalasset.canton.crypto.kms.driver.api.v1.EncryptionKeySpec.Rsa2048$.MODULE$);
    }

    @Override
    public scala.collection.immutable.Set<EncryptionAlgoSpec> supportedEncryptionAlgoSpecs() {
        return setOf(
                (EncryptionAlgoSpec) com.digitalasset.canton.crypto.kms.driver.api.v1.EncryptionAlgoSpec.RsaEsOaepSha256$.MODULE$);
    }

    @Override
    public Future<KmsDriverHealth> health() {
        return Future$.MODULE$.successful(
                (KmsDriverHealth) com.digitalasset.canton.crypto.kms.driver.api.v1.KmsDriverHealth.Ok$.MODULE$);
    }

    // ---- generation ------------------------------------------------------

    @Override
    public Future<String> generateSigningKeyPair(SigningKeySpec spec, Option<String> optName,
            io.opentelemetry.context.Context ctx) {
        return runOp("generate signing keypair", () -> {
            String strSpec = strSpecName(spec);
            KeyPair pair = pairFor(strSpec);
            String strId = strIdOf(pair.getPublic().getEncoded());
            store.put(new MockKeyStore.Entry(strId, MockKeyStore.Kind.SIGNING, strSpec,
                    pair.getPrivate().getEncoded(), pair.getPublic().getEncoded()));
            return strId;
        });
    }

    @Override
    public Future<String> generateEncryptionKeyPair(EncryptionKeySpec spec, Option<String> optName,
            io.opentelemetry.context.Context ctx) {
        return runOp("generate encryption keypair", () -> {
            String strSpec = strSpecName(spec);
            KeyPair pair = pairFor(strSpec);
            String strId = strIdOf(pair.getPublic().getEncoded());
            store.put(new MockKeyStore.Entry(strId, MockKeyStore.Kind.ENCRYPTION, strSpec,
                    pair.getPrivate().getEncoded(), pair.getPublic().getEncoded()));
            return strId;
        });
    }

    private KeyPair pairFor(String strSpec) throws Exception {
        switch (strSpec) {
            case "EcP256": {
                KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
                gen.initialize(new ECGenParameterSpec("secp256r1"), rnd);
                return gen.generateKeyPair();
            }
            case "EcP384": {
                KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
                gen.initialize(new ECGenParameterSpec("secp384r1"), rnd);
                return gen.generateKeyPair();
            }
            case "EcCurve25519": {
                KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
                return gen.generateKeyPair();
            }
            case "Rsa2048": {
                KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
                gen.initialize(2048, rnd);
                return gen.generateKeyPair();
            }
            default:
                throw new IllegalArgumentException("unsupported key specification: " + strSpec);
        }
    }

    @Override
    public Future<String> generateSymmetricKey(Option<String> optName, io.opentelemetry.context.Context ctx) {
        return runOp("generate symmetric key", () -> {
            KeyGenerator gen = KeyGenerator.getInstance("AES");
            gen.init(256, rnd);
            SecretKey key = gen.generateKey();
            String strId = strIdOf(key.getEncoded());
            store.put(new MockKeyStore.Entry(strId, MockKeyStore.Kind.SYMMETRIC, "Aes256Gcm",
                    key.getEncoded(), null));
            return strId;
        });
    }

    // ---- use -------------------------------------------------------------

    @Override
    public Future<byte[]> sign(byte[] arrData, String strKeyId, SigningAlgoSpec spec,
            io.opentelemetry.context.Context ctx) {
        return runOp("sign", () -> {
            MockKeyStore.Entry entry = entryOf(strKeyId);
            PrivateKey key = MockKeyStore.keyPrivate(entry, strKeyAlgo(entry.strSpec()));
            Signature sig = Signature.getInstance(strSigAlgo(spec));
            sig.initSign(key, rnd);
            sig.update(arrData);
            return sig.sign();
        });
    }

    @Override
    public Future<byte[]> decryptAsymmetric(byte[] arrCipher, String strKeyId, EncryptionAlgoSpec spec,
            io.opentelemetry.context.Context ctx) {
        return runOp("decrypt asymmetric", () -> {
            MockKeyStore.Entry entry = entryOf(strKeyId);
            PrivateKey key = MockKeyStore.keyPrivate(entry, strKeyAlgo(entry.strSpec()));
            Cipher cipher = Cipher.getInstance(strEncAlgo(spec));
            // THE PARAMETERS MUST BE NAMED. "OAEPWithSHA-256AndMGF1Padding" sets
            // the OAEP digest to SHA-256 and leaves MGF1 on SHA-1 - the JDK's
            // documented default, and NOT what RsaEsOaepSha256 means. Without
            // this the padding check fails on every ciphertext Canton sends and
            // the participant cannot read a view addressed to it.
            cipher.init(Cipher.DECRYPT_MODE, key, specOaep(spec));
            return cipher.doFinal(arrCipher);
        });
    }

    @Override
    public Future<byte[]> encryptSymmetric(byte[] arrData, String strKeyId,
            io.opentelemetry.context.Context ctx) {
        return runOp("encrypt symmetric", () -> {
            SecretKey key = MockKeyStore.keySymmetric(entryOf(strKeyId));
            byte[] arrIv = new byte[N_GCM_IV_BYTES];
            rnd.nextBytes(arrIv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(N_GCM_TAG_BITS, arrIv));
            byte[] arrBody = cipher.doFinal(arrData);
            byte[] arrOut = new byte[arrIv.length + arrBody.length];
            System.arraycopy(arrIv, 0, arrOut, 0, arrIv.length);
            System.arraycopy(arrBody, 0, arrOut, arrIv.length, arrBody.length);
            return arrOut;
        });
    }

    @Override
    public Future<byte[]> decryptSymmetric(byte[] arrCipher, String strKeyId,
            io.opentelemetry.context.Context ctx) {
        return runOp("decrypt symmetric", () -> {
            SecretKey key = MockKeyStore.keySymmetric(entryOf(strKeyId));
            if (arrCipher.length <= N_GCM_IV_BYTES)
                throw new IllegalArgumentException("ciphertext too short to carry an iv");
            byte[] arrIv = new byte[N_GCM_IV_BYTES];
            System.arraycopy(arrCipher, 0, arrIv, 0, N_GCM_IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(N_GCM_TAG_BITS, arrIv));
            return cipher.doFinal(arrCipher, N_GCM_IV_BYTES, arrCipher.length - N_GCM_IV_BYTES);
        });
    }

    @Override
    public Future<PublicKey> getPublicKey(String strKeyId, io.opentelemetry.context.Context ctx) {
        return runOp("get public key", () -> {
            MockKeyStore.Entry entry = entryOf(strKeyId);
            if (entry.arrPublic() == null)
                throw new IllegalArgumentException("not an asymmetric key: " + strKeyId);
            return new PublicKey(entry.arrPublic(), keySpecOf(entry.strSpec()));
        });
    }

    private static KeySpec keySpecOf(String strSpec) {
        switch (strSpec) {
            case "EcP256":
                return com.digitalasset.canton.crypto.kms.driver.api.v1.SigningKeySpec.EcP256$.MODULE$;
            case "EcP384":
                return com.digitalasset.canton.crypto.kms.driver.api.v1.SigningKeySpec.EcP384$.MODULE$;
            case "EcCurve25519":
                return com.digitalasset.canton.crypto.kms.driver.api.v1.SigningKeySpec.EcCurve25519$.MODULE$;
            case "Rsa2048":
                return com.digitalasset.canton.crypto.kms.driver.api.v1.EncryptionKeySpec.Rsa2048$.MODULE$;
            default:
                throw new IllegalArgumentException("unsupported key specification: " + strSpec);
        }
    }

    @Override
    public Future<BoxedUnit> keyExistsAndIsActive(String strKeyId, io.opentelemetry.context.Context ctx) {
        return runOp("key exists and is active", () -> {
            entryOf(strKeyId);
            return BoxedUnit.UNIT;
        });
    }

    @Override
    public Future<BoxedUnit> deleteKey(String strKeyId, io.opentelemetry.context.Context ctx) {
        return runOp("delete key", () -> {
            store.remove(strKeyId);
            return BoxedUnit.UNIT;
        });
    }

    @Override
    public void close() {
        // The key file is written on every change, so there is nothing to flush.
    }
}
