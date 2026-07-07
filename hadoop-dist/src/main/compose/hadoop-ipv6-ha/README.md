<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0
-->

# IPv6-only HDFS High Availability verification harness

Companion to `../hadoop-ipv6` (the base non-secure harness). This scenario
stands up a full HA cluster driven entirely over IPv6 and verifies automatic
failover end to end:

- **two NameNodes** (`nn1`, `nn2`) in nameservice `ns1`;
- a **three-node JournalNode quorum** for QJM shared edits
  (`qjournal://journalnode-1:8485;journalnode-2:8485;journalnode-3:8485/ns1`);
- **ZooKeeper + ZKFC** for automatic failover;
- **two DataNodes**, addressed in IP-direct mode (bracketed numeric IPv6
  xferAddr).

## What it verifies

`ha-smoketest.sh` (run from the **host** — it drives failover with
`docker compose stop`) asserts, over IPv6 (10 checks):

- both NameNodes reach an active/standby steady state via the ZKFC election
  (ZooKeeper reached over IPv6 by its AAAA-resolved name);
- a write to the logical `hdfs://ns1` URI and read-back
  (ConfiguredFailoverProxyProvider resolving the active NameNode);
- the DataNode `xferAddr` is a bracketed numeric IPv6 literal;
- stopping the active NameNode triggers **ZKFC automatic failover** to the
  standby, which then serves the previously-written data — i.e. the edits were
  replayed from the JournalNode quorum over IPv6;
- a write succeeds against the newly-promoted active;
- the restarted NameNode rejoins as standby.

A green run prints `10 passed, 0 failed`.

## Topology

| Service | Host | IPv6 |
|---|---|---|
| ZooKeeper | `zookeeper` | `fd00:dead:beef:2::5` |
| JournalNode 1/2/3 | `journalnode-1/2/3` | `fd00:dead:beef:2::11/12/13` |
| NameNode nn1 | `namenode-1` | `fd00:dead:beef:2::21` |
| NameNode nn2 | `namenode-2` | `fd00:dead:beef:2::22` |
| DataNode 1/2 | `datanode-1/2` | `fd00:dead:beef:2::31/32` |

A dedicated ULA subnet (`fd00:dead:beef:2::/64`) and host ports (`39870`,
`39871`) let this run alongside the other `hadoop-ipv6*` harnesses.

## Run it

```bash
# From the repo root, build the distribution once:
mvn -DskipTests -Pdist package

cd hadoop-dist/src/main/compose/hadoop-ipv6-ha
cp -r ../../../target/hadoop-3.6.0-SNAPSHOT .
docker compose build
docker compose up -d
# Wait ~30s for the quorum to form and ZKFC to elect an active, then:
./ha-smoketest.sh          # from the HOST, not inside a container
```

## Design notes

- **Role-aware entrypoint.** The compose `command` selects a role
  (`journalnode`, `datanode`, `namenode nn1|nn2`, `zookeeper`). `nn1` formats
  storage and the HA ZK znode on first boot; `nn2` bootstraps from `nn1`. Each
  NameNode container runs the NameNode as a background daemon plus its ZKFC in
  the foreground, so ZKFC drives the election and the container lives as long as
  the failover controller.
- **ZooKeeper over IPv6.** ZooKeeper is run from the Hadoop image using the
  bundled `zookeeper` jar and a hand-written `conf/zoo.cfg` with
  `clientPortAddress=::`, so the client port binds the IPv6 wildcard
  (`[::]:2181`). The stock `zookeeper` Docker image auto-injects a
  `server.N=localhost:...` line that pins the bind to IPv4, so it is not used.
- **Fencing.** QJM's epoch-based fencing is the real split-brain guard; the
  configured `shell(/bin/true)` fencer is the required always-succeeds fallback
  (the failed-over-from node is already gone when its container is stopped).
- **IP-direct DataNodes.** As in the base harness, the DataNodes advertise a
  bracketed numeric IPv6 xferAddr (no `use.datanode.hostname`), exercising the
  bracket-aware data path under HA.

## Not covered here

Secure (Kerberos) HA is out of scope for this harness; the secure planes are
verified separately under `../hadoop-ipv6-secure`.
