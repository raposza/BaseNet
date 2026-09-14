// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0

import com.digitalasset.canton.crypto.kms.driver.api.v1.EncryptionAlgoSpec;
import com.digitalasset.canton.crypto.kms.driver.api.v1.EncryptionKeySpec;
import com.digitalasset.canton.crypto.kms.driver.api.v1.KmsDriver;
import com.digitalasset.canton.crypto.kms.driver.api.v1.KmsDriverFactory;
import com.digitalasset.canton.crypto.kms.driver.api.v1.PublicKey;
import com.digitalasset.canton.crypto.kms.driver.api.v1.SigningAlgoSpec;
import com.digitalasset.canton.crypto.kms.driver.api.v1.SigningKeySpec;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.util.ServiceLoader;
import org.slf4j.LoggerFactory;
import scala.Option;
import scala.concurrent.Future;

/**
 * Author Claude/bentzn
 *
 * @author Claude/bentzn
 */
public final class SelfTest {

    private static int cntFail = 0;

    private static void check(String strWhat, boolean flagOk) {
        System.out.println((flagOk ? "PASS  " : "FAIL  ") + strWhat);
        if (!flagOk)
            cntFail++;
    }

    /** Every future this driver returns is already completed, so no waiting. */
    private static <T> T value(Future<T> future) {
        return future.value().get().get();
    }

    public static void main(String[] arrArg) throws Exception {
        String strKeyFile = arrArg[0];

        // 1 - discovery, exactly as Canton does it
        KmsDriverFactory factory = null;
        for (KmsDriverFactory candidate : ServiceLoader.load(KmsDriverFactory.class)) {
            System.out.println("found driver: " + candidate.name());
            if ("raposza-mock-kms".equals(candidate.name()))
                factory = candidate;
        }
        check("ServiceLoader finds raposza-mock-kms", factory != null);
        if (factory == null) {
            System.exit(1);
        }
        check("factory reports API version 1", factory.version() == 1);

        // 2 - the configuration block, as the participant would carry it
        ConfigValue value = ConfigFactory
                .parseString("key-file = \"" + strKeyFile + "\"\naudit-logging = true\n")
                .root();
        Object cfg = value(scala.concurrent.Future$.MODULE$.successful(
                factory.configReader()
                        .from(pureconfig.ConfigCursor.apply(value,
                                scala.collection.immutable.List$.MODULE$.<String>empty()))
                        .toOption().get()));
        check("ConfigReader parses the config block", cfg != null);

        // 3 - generate, sign, verify
        KmsDriver driver = (KmsDriver) factory.create(cfg,
                cls -> LoggerFactory.getLogger(cls),
                scala.concurrent.ExecutionContext$.MODULE$.global());

        String strKeyId = value(driver.generateSigningKeyPair(
                SigningKeySpec.EcP256$.MODULE$, Option.apply("selftest"),
                io.opentelemetry.context.Context.root()));
        check("generated a signing key", strKeyId != null && !strKeyId.isEmpty());

        byte[] arrData = "the corpus is the authority".getBytes(StandardCharsets.UTF_8);
        byte[] arrSig = value(driver.sign(arrData, strKeyId,
                SigningAlgoSpec.EcDsaSha256$.MODULE$, io.opentelemetry.context.Context.root()));

        PublicKey pub = value(driver.getPublicKey(strKeyId, io.opentelemetry.context.Context.root()));
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(pub.key())));
        verifier.update(arrData);
        check("the signature verifies against the public key", verifier.verify(arrSig));

        // 3b - THE ENCRYPTION ROUND TRIP. The driver only ever decrypts; the
        // encryptor is Canton, using the public key we hand back. So the test
        // has to encrypt the way Canton does - RSA-OAEP with SHA-256 as BOTH the
        // OAEP digest and the MGF1 digest - and that pairing is exactly what a
        // JDK transformation name gets wrong on its own.
        String strEncId = value(driver.generateEncryptionKeyPair(
                EncryptionKeySpec.Rsa2048$.MODULE$, Option.apply("selftest-enc"),
                io.opentelemetry.context.Context.root()));
        PublicKey pubEnc = value(driver.getPublicKey(strEncId, io.opentelemetry.context.Context.root()));
        Cipher enc = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        enc.init(Cipher.ENCRYPT_MODE,
                KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pubEnc.key())),
                new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                        PSource.PSpecified.DEFAULT));
        byte[] arrSecret = "randomness of a view".getBytes(StandardCharsets.UTF_8);
        byte[] arrCipher = enc.doFinal(arrSecret);
        byte[] arrPlain = value(driver.decryptAsymmetric(arrCipher, strEncId,
                EncryptionAlgoSpec.RsaEsOaepSha256$.MODULE$, io.opentelemetry.context.Context.root()));
        check("an RSA-OAEP-SHA256 ciphertext decrypts back",
                java.util.Arrays.equals(arrSecret, arrPlain));

        // A control that must be ABLE TO FAIL: a ciphertext made with the JDK's
        // default MGF1 (SHA-1) must be refused. If this ever passes, the driver
        // is accepting something Canton would never send, and the check above
        // proves nothing.
        Cipher encWrong = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        encWrong.init(Cipher.ENCRYPT_MODE,
                KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pubEnc.key())));
        byte[] arrWrong = encWrong.doFinal(arrSecret);
        boolean flagRejected;
        try {
            value(driver.decryptAsymmetric(arrWrong, strEncId,
                    EncryptionAlgoSpec.RsaEsOaepSha256$.MODULE$,
                    io.opentelemetry.context.Context.root()));
            flagRejected = false;
        }
        catch (Exception ex) {
            flagRejected = true;
        }
        check("a mismatched-MGF1 ciphertext is refused", flagRejected);

        check("the key file exists on disk", Files.isReadable(Paths.get(strKeyFile)));
        driver.close();

        // 4 - the restart. A second driver over the same file must still hold it.
        KmsDriver driverAgain = (KmsDriver) factory.create(cfg,
                cls -> LoggerFactory.getLogger(cls),
                scala.concurrent.ExecutionContext$.MODULE$.global());
        PublicKey pubAgain = value(driverAgain.getPublicKey(strKeyId,
                io.opentelemetry.context.Context.root()));
        check("the key survives a driver restart",
                java.util.Arrays.equals(pub.key(), pubAgain.key()));

        byte[] arrSigAgain = value(driverAgain.sign(arrData, strKeyId,
                SigningAlgoSpec.EcDsaSha256$.MODULE$, io.opentelemetry.context.Context.root()));
        verifier.initVerify(KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(pubAgain.key())));
        verifier.update(arrData);
        check("the reloaded private key still signs", verifier.verify(arrSigAgain));
        driverAgain.close();

        System.out.println(cntFail == 0 ? "SELFTEST GREEN" : "SELFTEST RED, " + cntFail + " failure(s)");
        System.exit(cntFail == 0 ? 0 : 1);
    }
}
