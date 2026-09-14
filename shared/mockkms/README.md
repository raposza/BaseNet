<!-- Copyright (c) 2026 bentzn -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Mock KMS driver

A Canton KMS Driver v1 that keeps its keys in a plaintext file.

**The private keys are written unencrypted and are readable by anyone who can
read the file.** That is deliberate. This exists so a participant in a
disposable network can be restarted without losing its identity, which the
vendor's own in-memory mock driver cannot do. It reproduces the shape of a
KMS-backed participant, not its security, and it must never be pointed at
anything that matters.

## Why a participant needs this at all

A Canton participant's root namespace key cannot be rotated, and every party
allocated on the node carries that key's fingerprint after the `::` in its id.
A participant that loses its keys cannot resume its identity: it needs a fresh
founding, and every party id in the environment changes. Pods restart for
ordinary reasons - a memory limit, a values change, a node reboot - so on a
KMS-backed participant those restarts are only survivable if the keys outlive
the process.

## Build

```
./build.sh /path/to/canton-open-source-<version>.jar
```

Take that jar from the participant image rather than from a local Canton
install. The driver binds to Canton and pureconfig classes that carry no
compatibility promise across patch versions, and the jar the image runs is the
only one whose signatures matter.

## Use

`FLAG_MO_KMS=1` in `basenet.conf.local`, then the ordinary MemberOrg sequence.
`memberorg-secrets.sh` puts this jar into a secret, `memberorg-install.sh`
mounts it at `/mockkms`, names it on `EXTRA_CLASSPATH`, and points the
participant's `crypto.kms` at it. Nothing is pushed to a registry and no image
is built.

The settings that govern it are `STR_MO_KMS_*` in `basenet.conf`. The key file
defaults to `/persistent-data/mockkms-keys.txt`, on the participant chart's own
volume, so it survives a pod restart.

**The audit log goes to stdout, not to a file.** `KMS_LOG_FILE_NAME` is set and
has no appender behind it: Canton's logback configuration only defines the KMS
appender when a file appender is on, and the participant image's entrypoint
passes `--log-file-appender=off`. Read the calls with

```
kubectl -n <memberorg> logs deploy/participant -c participant | grep "KMS operation"
```

**Neither script here carries an execute bit**, because the apply-scripts that
write them do not set one. Run them as `bash ./build.sh ...` and
`bash ./selftest.sh ...`.

**KMS on and KMS off are two different foundings.** A participant's root
namespace key cannot be rotated, so a MemberOrg cannot be moved from one to the
other - decide before `memberorg-secrets.sh` and leave it. `memberorg-teardown.sh`
deletes the namespace and the volume with it, which takes the keys too; that is
what teardown means here.

To use a real driver instead, build its jar, point `STR_MO_KMS_JAR` at it and
set `STR_MO_KMS_NAME` to the name its factory registers. Nothing else changes.
an init container's output, the key file needs somewhere writable that survives
a restart.

## What it supports

Signing on EC P-256, EC P-384 and Ed25519. Asymmetric encryption on RSA-2048
with OAEP/SHA-256. Symmetric encryption with AES-256-GCM.

It does not advertise secp256k1, which JDK 16 removed from SunEC, nor the ECIES
encryption specification, which the JDK does not implement. Canton negotiates
down to what a driver publishes.
