<!-- Copyright (c) 2026 bentzn -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Moderate load testing - a ten-hour run

**14 September 2026.** One BaseNet Member under a paced, concurrent workload
for ten hours: **144,000 transactions at 4.00 per second, zero failures, no pod
restart, and no memory growth in either JVM.** This report is what was run,
what was measured, what the numbers mean, and - as carefully - what they do not
mean.

## 1 - Summary

| measure | value |
| --- | --- |
| duration | 36,001 s (10 h 0 m) |
| cycles completed | 36,000 |
| transactions | 144,000 |
| failed | **0** |
| achieved rate | 4.00 tx/s |
| latency median | 472 ms |
| latency p95 | 497 ms |
| latency max | 6,164 ms (during warm-up) |
| pod restarts | 0 |
| participant peak memory | 1,624 Mi against a 16,384 Mi limit |
| validator-app peak memory | 1,621 Mi against a 6,144 Mi limit |

The active contract set grew for the entire run. Nothing was pruned and nothing
was archived beyond what the workload's own choices archive.

## 2 - What was under test

A **BaseNet Member** - the deployment an organisation runs against a Canton
Network: a participant, a validator app, the wallet and name-service UIs, and
PostgreSQL - connected to a **BaseNet Validator**, the private network side,
running in a separate namespace on the same cluster.

* Participant version 3.5.14.
* The Helm values as this repository ships them, in `member/values/`. **No
  memory overlay was applied**, so every component ran at the charts' own
  generous sizing rather than a tuned one.
* A single-node Kubernetes cluster on a 32-core workstation with 64 GiB of RAM,
  local-path storage.
* The Member's participant, database and validator app had been reinstalled 70
  minutes before the run, so it began with a small ledger rather than a day's
  accumulation.

The run was executed against an **export of this repository**, not against the
tree it was built from - the artefact a user would clone, rather than the
working copy.

## 3 - The workload

`member/loadtest.py` drives the participant's **JSON Ledger API** over a local
port-forward, authenticating the production way: an RS256 token minted by an
OpenID provider for the participant's own administrator user, with the audience
the participant requires. No back door and no static token.

The fixture is the pet shop model in `member/petshop/`. One **cycle** is four
transactions:

1. a plain create,
2. a create by a second party with an `ensure` clause,
3. a consuming choice that fetches across templates,
4. a non-consuming choice.

Four client threads submit cycles concurrently, paced to one cycle per second
in aggregate - four transactions a second. Every submission's latency, status
and any error is written to a CSV as it happens.

Memory was sampled from **outside** the workload by `shared/jvm_watch.py`,
every 120 seconds, 296 samples: resident set size, the cgroup's own accounting,
committed heap and metaspace, per container. A driver that probed its own
target would compete with the thing it measures, so the two are separate
processes. **No garbage collection was ever forced**, so committed heap is
committed and not a live set.

## 4 - Throughput and latency

Four transactions a second is a deliberate, moderate rate. The synchronizer
limits a single member to 300 transactions per 60 seconds - five a second - so
this run sat at 80% of a configured ceiling and nowhere near a resource limit.

**The latency figures are round-trip cost, not capacity.** A 25 ms spread
between the median of 472 ms and the p95 of 497 ms is what an unsaturated
system's round trip looks like. Nothing here establishes how fast this stack
can go; it establishes what a transaction costs when it is not queueing.

**The 6,164 ms maximum is warm-up.** It and the 5,926 ms submission beside it
are one transaction pair 90 seconds into the run, behind a 1,372 ms create
during setup. **Every other submission in ten hours completed in under
1,012 ms.** Steady-state worst case is approximately one second. The only other
cluster worth noting is four submissions between 936 and 1,012 ms within half a
minute, roughly six hours in, which cost nothing and did not repeat.

## 5 - Memory, hour by hour

Peak per hour, in mebibytes. `cgroup` is the kernel's own accounting for the
container, which is what an out-of-memory kill is decided against; `RSS` is the
resident set of the process.

| hour | participant RSS | participant cgroup | validator-app RSS | validator-app cgroup |
| --- | --- | --- | --- | --- |
| 1 | 1512 | 1609 | 1469 | **1621** |
| 2 | 1502 | 1615 | 1468 | 1573 |
| 3 | 1511 | 1618 | 1405 | 1573 |
| 4 | 1525 | 1619 | 1433 | 1575 |
| 5 | 1538 | 1621 | 1420 | 1545 |
| 6 | 1531 | 1623 | 1422 | 1546 |
| 7 | 1541 | 1623 | 1413 | 1534 |
| 8 | 1539 | 1613 | 1419 | 1534 |
| 9 | 1523 | 1624 | 1415 | 1523 |
| 10 | **1551** | **1624** | 1364 | 1523 |

Peak committed heap was 752 Mi on the participant and 800 Mi on the validator
app; peak metaspace 336 Mi and 260 Mi.

**The participant is flat.** Its cgroup peak moved from 1,609 Mi to 1,624 Mi
across ten hours and 144,000 transactions - fifteen mebibytes, decelerating -
while the active contract set grew the entire time. Resident memory rose 39 Mi
on the same curve. That is caches filling and then stopping, not a leak.

**The validator app settles; it does not grow.** Its peak is highest in the
first hour, 1,621 Mi, and falls almost monotonically to 1,523 Mi by the tenth.
Resident memory falls with it, 1,469 Mi to 1,364 Mi. A shorter run against the
same component had shown a monotonic climb and could not distinguish a JVM
still settling after a restart from one that leaks. Ten hours distinguishes
them.

**A whole-run peak hides direction.** Taken as one maximum, the validator app's
1,621 Mi looks like a steady-state figure to size against. Read hour by hour it
is plainly start-up. Any long-run figure used for capacity planning should be
read by interval rather than as a single number - which is why the sampler
writes every sample and not only its summary.

## 6 - What this says about sizing

The shipped limits are 16,384 Mi for the participant and 6,144 Mi for the
validator app, against observed peaks of 1,624 Mi and 1,621 Mi: roughly ten
times and four times what ten hours of this workload actually needed. A limit
is a cap rather than a reservation, so on a workstation that costs nothing -
but it is why this repository also offers a sized install.

`run-optimized.sh` plans a Member at a 1,856 Mi limit for the participant and
1,536 Mi for the validator app. Against this run:

| component | planned limit | observed | reading |
| --- | --- | --- | --- |
| participant | 1856 | 1624, flat all run | holds, about 230 Mi of margin |
| validator-app | 1536 | 1621 in hour 1, 1523 by hour 10 | clears the steady state, not the first four hours |

The validator app's planned limit sits above hours five to ten and below hours
one to four, where the observed peaks are 1,573 to 1,621 Mi. That is marginal
rather than wrong, and the risk falls in the period right after an install,
which is exactly when a deployment is least likely to be watched. Raising its
reserve so the limit becomes 1,600 Mi clears the whole observed range.

**Neither number should be adopted without a run at that sizing.** A memory
reserve measured under a generous limit does not transfer cleanly to a capped
one: the same assumption, in the opposite direction, is what produced an
out-of-memory kill in earlier work here.

## 7 - What this run does NOT show

* **It is not a capacity result.** The rate was chosen, not found. Nothing was
  pushed to a limit that was not a configured cap.
* **It is one Member.** How the network side behaves with several Members under
  sustained load is untested here.
* **It is one workload shape.** The fixture creates and exercises contracts and
  never prunes, so the ledger only grows. A workload that archives would
  exercise a different memory profile entirely.
* **Restart cost against a large ledger is unmeasured.** Ten hours of growth
  cost nothing in resident memory while the process stayed up; what it costs a
  participant to start against that ledger is a different question and this run
  does not answer it.
* **Native Memory Tracking was off**, because the shipped values do not enable
  it. Every non-heap figure here is a subtraction rather than an attribution.

## 8 - Reproducing it

The identity provider must be running, the Member installed and onboarded, and
the fixture built - `member/petshop/build.sh`.

```
cd member
nohup ./run-loadtest.sh memberorg-a --for 36000 --rate 1 --every 120 \
    > /tmp/bn-soak.log 2>&1 &
```

`--every 120` rather than the 30-second default: each sample runs one command
inside every container, and over ten hours the default would be 1,200 sampling
rounds. A probe that samples too hard becomes part of what it measures.

Both CSVs and the summary tables are written to `/tmp` and named in the run's
own output. **Tear the Member down before taking any idle memory baseline
afterwards** - the ledger this leaves behind is 144,000 transactions deep, and
no idle figure taken on it is comparable with one taken on a fresh install.
