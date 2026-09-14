<!-- Copyright (c) 2026 bentzn -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# The pet shop fixture

A small Daml model, here so the load test has real templates and real choices
to submit rather than a synthetic ping. It is not part of BaseNet and nothing
in the network depends on it.

`daml/Main.daml` declares the model; `daml.yaml.template` is the project file
with its `sdk-version` left as a placeholder, which `build.sh` fills in.

```
./build.sh            build with SDK 3.4.11 (the default)
./build.sh 3.5.7      or with another SDK your participant accepts
```

The DAR and its package id land under `~/.raposza/fixtures/petshop/`, outside
this tree, because a DAR is build output.

What the load test drives, one cycle at a time: a plain create, a create by a
second party with an `ensure` clause, a consuming choice that fetches across
templates, and a non-consuming choice - so the active contract set grows for
the length of the run instead of staying flat.
