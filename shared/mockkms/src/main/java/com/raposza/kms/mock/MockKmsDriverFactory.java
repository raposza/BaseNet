// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.kms.mock;

import com.digitalasset.canton.crypto.kms.driver.api.v1.KmsDriverFactory;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import pureconfig.ConfigCursor;
import pureconfig.ConfigReader;
import pureconfig.ConfigWriter;
import pureconfig.error.ConfigReaderFailures;
import scala.Function1;
import scala.Option;
import scala.concurrent.ExecutionContext;
import scala.util.Either;
import scala.util.Right;

/**
 * Loads {@link MockKmsDriver}. Canton finds this class through the two
 * META-INF/services files beside it and matches it by {@link #name()} against
 * the participant's {@code crypto.kms.name}.
 *
 * The configuration block it reads:
 *
 * <pre>
 * crypto.kms {
 *   type = driver
 *   name = "raposza-mock-kms"
 *   config = {
 *     key-file = "/mockkms/keys.txt"
 *     audit-logging = true
 *   }
 * }
 * </pre>
 *
 * Author Claude/bentzn
 *
 * @author Claude/bentzn
 */
public final class MockKmsDriverFactory implements KmsDriverFactory {

    /** What the participant's {@code crypto.kms.name} must say. */
    public static final String STR_NAME = "raposza-mock-kms";

    /** Where the keys go when the configuration does not say. */
    public static final String STR_FILE_DEFAULT = "/mockkms/keys.txt";

    @Override
    public String name() {
        return STR_NAME;
    }

    @Override
    public int version() {
        return 1;
    }

    /**
     * Scala compiles its trait vals into a setter the implementor must carry.
     * {@link #version()} answers directly, so there is nothing to set.
     *
     * @param nVersion ignored
     */
    @Override
    public void com$digitalasset$canton$crypto$kms$driver$api$v1$KmsDriverFactory$_setter_$version_$eq(int nVersion) {
        // nothing to do - version() is a method here, not a field
    }

    @Override
    public Option<String> buildInfo() {
        return Option.apply("raposza-mockkms");
    }

    @Override
    public ConfigReader<Object> configReader() {
        Function1<ConfigCursor, Either<ConfigReaderFailures, Object>> fn = cursor -> {
            Either<ConfigReaderFailures, ConfigValue> either = cursor.asConfigValue();
            if (either.isLeft()) {
                @SuppressWarnings("unchecked")
                Either<ConfigReaderFailures, Object> eitherLeft = (Either<ConfigReaderFailures, Object>) (Either<?, ?>) either;
                return eitherLeft;
            }
            // Past the cursor, everything is the plain Java typesafe-config API.
            ConfigValue value = (ConfigValue) ((Right<ConfigReaderFailures, ConfigValue>) either).value();
            Config cfg = ((ConfigObject) value).toConfig();
            String strFile = cfg.hasPath("key-file") ? cfg.getString("key-file") : STR_FILE_DEFAULT;
            boolean flagAudit = cfg.hasPath("audit-logging") && cfg.getBoolean("audit-logging");
            return new Right<>(new MockKmsDriverConfig(strFile, flagAudit));
        };
        return ConfigReader.fromCursor(fn);
    }

    @Override
    public ConfigWriter<Object> configWriter(boolean flagConfidential) {
        Function1<Object, ConfigValue> fn = obj -> {
            MockKmsDriverConfig cfg = (MockKmsDriverConfig) obj;
            Map<String, Object> mapOut = new LinkedHashMap<>();
            // A key file path is not a credential, so nothing is withheld when
            // the caller asks for a confidential rendering.
            mapOut.put("key-file", cfg.fileKeys());
            mapOut.put("audit-logging", Boolean.valueOf(cfg.flagAudit()));
            return ConfigValueFactory.fromMap(mapOut);
        };
        return ConfigWriter.fromFunction(fn);
    }

    @Override
    public Object create(Object objConfig, Function1<Class<?>, Logger> fnLogger, ExecutionContext ec) {
        MockKmsDriverConfig cfg = (MockKmsDriverConfig) objConfig;
        return new MockKmsDriver(cfg, fnLogger.apply(MockKmsDriver.class));
    }
}
