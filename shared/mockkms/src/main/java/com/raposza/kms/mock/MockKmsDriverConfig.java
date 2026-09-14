// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.kms.mock;

/**
 * The driver's own configuration, as it appears under the participant's
 * {@code crypto.kms.config} block.
 *
 * Author Claude/bentzn
 *
 * @author Claude/bentzn
 */
public final class MockKmsDriverConfig {

    /** Where the keys are kept. Plain text, unencrypted, deliberately unsafe. */
    private final String fileKeys;

    /** Whether every operation is logged through the KMS audit logger. */
    private final boolean flagAudit;

    public MockKmsDriverConfig(String fileKeys, boolean flagAudit) {
        this.fileKeys = fileKeys;
        this.flagAudit = flagAudit;
    }

    /**
     * @return the path of the key file
     */
    public String fileKeys() {
        return fileKeys;
    }

    /**
     * @return true when audit logging is on
     */
    public boolean flagAudit() {
        return flagAudit;
    }

    @Override
    public String toString() {
        return "MockKmsDriverConfig(key-file=" + fileKeys + ", audit-logging=" + flagAudit + ")";
    }
}
