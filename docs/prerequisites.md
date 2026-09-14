<!-- Copyright (c) 2026 bentzn -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Prerequisites

## Tools

`helm`, `kubectl` and `python3` on the machine running the scripts. `helm` must
be able to reach the vendor's OCI registry to pull the charts.

## The cluster

Any Kubernetes cluster you are willing to destroy. Single-node k3s on a
workstation is enough for the whole stack: sequencer, mediator, participant,
scan, SV, validator and four PostgreSQL instances.

Budget roughly 6 CPU and 20 GiB of memory for the requests as they are set in
`values/`, and a storage class that provisions PVCs on the spot. k3s ships
`local-path`, which is what `basenet.conf` selects.

## One k3s installation flag, and it matters

k3s's default pod and service CIDRs are `10.42.0.0/16` and `10.43.0.0/16`. If
either of those overlaps an address your host already routes - a VPN, a lab
network, a second interface - the cluster comes up and then every system pod
fails identically, with an error that looks like a firewall problem and is not.
Four pods failing with the same message is one fault, not four.

Install off the default range:

```
curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="--write-kubeconfig-mode 644 \
    --cluster-cidr=10.244.0.0/16 --service-cidr=10.245.0.0/16 --disable traefik" sh -
```

Check your own routes first (`ip route`) and pick ranges that collide with
nothing.

## Removing it

`./teardown.sh` deletes the namespace and leaves k3s and the cached container
images alone, so the next install pulls nothing. To remove k3s itself,
including the image cache: `sudo /usr/local/bin/k3s-uninstall.sh`.
