<!-- Copyright (c) 2026 bentzn -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Moderate load testing - a KMS-backed Member

**14 September 2026.** The same paced workload as the ten-hour run, against a
Member whose participant holds its private keys in a **KMS driver** rather than
in its own database: **57,600 transactions at 4.00 per second, zero failures,
no pod restart, and no memory growth.** Every transaction view the participant
received cost an RSA-2048 OAEP decryption through that driver.

The question this run exists to answer is narrow: **what does moving a
participant's keys out to a KMS cost?** The answer, at this rate, is nothing
measurable. Read alongside `ModerateLoadTesting_20260914.md`, which is the same
workload without the KMS and is the only comparison used here.

## 1 - Summary

| measure | value |
| --- | --- |
| duration | 14,401 s (4 h 0 m) |
| cycles completed | 14,400 |
| transactions | 57,600 |
| failed | **0** |
| achieved rate | 4.00 tx/s |
| latency median | 473 ms |
| latency p95 | 496 ms |
| latency max | 1,505 ms |
| pod restarts | 0 |
| participant memory, steady state | 1,446 - 1,452 Mi RSS against a 16,384 Mi limit |
| validator-app memory, steady state | 1,078 - 1,204 Mi RSS against a 6,144 Mi limit |

The active contract set grew for the entire run. Nothing was pruned and nothing
was archived beyond what the workload's own choices archive.

## 2 - What was different from the ten-hour run

One thing, deliberately. The Member's participant was configured with
`crypto.provider = kms` and a **KMS Driver v1** loaded onto its class path, so
that its signing and encryption private keys live outside the participant
entirely. Everything else - the chart values, the fixture, the rate, the
cluster, the participant version 3.5.14 - is as the earlier run.

The driver used is the one in this repository, `shared/mockkms/`. It is a
**mock**: it keeps its keys in a plaintext file and provides none of the
protection a real KMS does. It exists so that a Member can be run in the shape
an organisation runs it - keys held by a key management service, reached
through the driver interface - and so that the real driver can be dropped in
its place by changing two settings. **Nothing in this report is a statement
about the security of that arrangement.** It is a statement about its cost.

For this run the participant held **two EC P-256 signing keys and one RSA-2048
encryption key**, all generated through the driver at founding.

## 3 - Throughput and latency, against the non-KMS run

| measure | without KMS (10 h) | with KMS (4 h) |
| --- | --- | --- |
| median | 472 ms | 473 ms |
| p95 | 497 ms | 496 ms |
| maximum | 6,164 ms (warm-up) | 1,505 ms |
| failures | 0 of 144,000 | 0 of 57,600 |

One millisecond either way, on both the median and the p95 - which is to say
the difference is below what this measurement can resolve.

That is less surprising than it first looks. A participant does not perform a
KMS operation per transaction in the way the phrase suggests: session keys are
cached, so the asymmetric operations that reach the driver are a fraction of
the traffic, and each one is a single RSA private-key operation on the same
machine. A **remote** KMS would add a network round trip per operation and this
result says nothing about that case.

**Zero failures is the more important half of the table.** A KMS driver sits on
the critical path for reading any transaction view addressed to the node, and a
failure there is not degradation - the participant treats it as unrecoverable,
drops its sequencer subscription and restarts. Four hours of continuous traffic
produced no such failure.

## 4 - Memory

Flat, and marginally below the non-KMS run.

| | start of run | end of run | movement |
| --- | --- | --- | --- |
| participant RSS | 1,446 Mi | 1,452 Mi | +6 Mi |
| participant cgroup | 1,481 Mi | 1,559 Mi | +78 Mi |
| participant heap committed | 696 Mi | 696 Mi | none |

Four hours and 57,600 transactions apart, with the ledger growing throughout.
The ten-hour run's participant sat at 1,502-1,541 Mi RSS and 1,609-1,624 Mi
cgroup, so the KMS-backed node is slightly lower rather than higher. The driver
holds three keys in memory and writes nothing after start-up; there is no
mechanism here by which it would grow.

## 5 - A measurement trap worth knowing about

**The run's own summary reported a participant peak of 2,290 Mi RSS and 2,909
Mi cgroup** - about 1,300 Mi above the ten-hour run. Taken at face value that
reads as the KMS costing a great deal of memory. It is an artefact, and the
reasoning that rules it out is worth repeating because the same trap is
available to anyone using these tools.

* **The peak is the first sample**, taken the moment the run began. The second
  sample, two minutes later, reads 1,446 Mi and so does every sample after it.
* **`validator-app` shows the same spike** - 2,875 Mi at the first sample,
  1,534 Mi for the rest of the run - and `validator-app` has no KMS driver in
  it at all. Whatever moved the figure moved a component the driver cannot
  touch.
* **The cause is a JVM that had just started.** Both pods had been replaced
  minutes before the run, and the charts set `-XX:InitialRAMPercentage=75`, so
  a large heap is committed at start-up and returned by the collector once the
  process settles.

`jvm_watch.py` takes its first sample immediately and reports the maximum
across all samples as the peak, calculating headroom against it. That is honest
about what the kernel saw, and it is not a description of the run. **If the
first sample is the peak, the figure is a start-up measurement** - read the
per-interval rows in the CSV instead. The ten-hour run happened to avoid this
because its pods had been up for 70 minutes before sampling started.

## 6 - What this run does NOT show

* **It is not a capacity result.** The rate was chosen, not found: 4 tx/s
  against a per-member cap of 5 is pacing, not pressure. The stack was never
  near saturation, and neither was the driver.
* **It is not a result about a remote KMS.** The driver here performs its
  cryptography in the participant's own process. A cloud KMS adds a network
  round trip per operation, and nothing here bounds that.
* **It is not a security result.** The driver is a mock with plaintext keys. It
  reproduces the SHAPE of a KMS-backed participant, not its protection.
* **It is four hours, not ten.** Memory over a longer period with a KMS remains
  extrapolation from the earlier run.
* **It is one Member and one workload shape.** The fixture only grows the
  ledger; a workload that archives would exercise a different profile.
* **Native Memory Tracking was off**, because the shipped values do not enable
  it. Every non-heap figure is a subtraction rather than an attribution.

## 7 - Reproducing it

`FLAG_MO_KMS=1` in `basenet.conf.local` before the Member is created - a
participant's root namespace key cannot be rotated, so a Member cannot be moved
between KMS and non-KMS afterwards. Build the driver first:

```
bash shared/mockkms/build.sh /path/to/canton-open-source-<version>.jar
```

Then the identity provider running, the Member installed and onboarded, the
fixture built with `member/petshop/build.sh`, and:

```
cd member
nohup ./run-loadtest.sh memberorg-a --for 14400 --rate 1 --every 120 \
    > /tmp/bn-kms-load.log 2>&1 &
```

`--every 120` rather than the 30-second default: each sample runs a command
inside every container, and a probe that samples too hard becomes part of what
it measures.

The KMS calls themselves are logged to the participant's stdout, not to a file:

```
kubectl -n memberorg-a logs deploy/participant -c participant | grep "KMS operation"
```

Both CSVs and the summary tables are written to `/tmp` and named in the run's
own output. **Tear the Member down before taking any idle memory baseline
afterwards** - and note that tearing it down destroys the volume holding the
keys, which for this driver means the Member's identity goes with it.
