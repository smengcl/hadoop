<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0
-->

# IPv6-only secure (Kerberos/SASL) Hadoop verification harness

Companion to `../hadoop-ipv6` (the non-secure harness). This scenario stands
up an MIT Kerberos KDC plus a **secured** HDFS + YARN/MapReduce cluster and
drives it entirely over IPv6, to verify that Hadoop's authentication and SASL
paths work when every host is IPv6-only.

## What it verifies

### Phase 1 — secure HDFS

- `kinit` against the in-compose KDC, reached by its AAAA-resolved `kdc` name.
- HDFS RPC under Kerberos with `hadoop.rpc.protection` SASL over IPv6.
- **SASL data transfer** (`dfs.data.transfer.protection`) — put/get/cat to a
  DataNode's bracketed numeric IPv6 xferAddr, with block-access tokens on.
- HDFS delegation token issuance and its IPv6 service identifier
  (`[fd00:dead:beef:1:0:0:0:10]:8020`, bracketed per HADOOP-XXXXX-F2).
- Negative check: HDFS rejects access with no Kerberos ticket.

### Phase 2 — secure YARN + MapReduce

- NodeManager registers with the ResourceManager over Kerberos RPC on IPv6.
- A MapReduce job is submitted under Kerberos to the RM at its IPv6 endpoint;
  YARN mints an HDFS delegation token whose service id is the bracketed numeric
  IPv6 NameNode endpoint and hands it to the AM and task containers.
- A task container reads its input split and writes its output to HDFS via
  SASL data transfer / block tokens against the IPv6 DataNodes.

### Phase 3 — SPNEGO + HTTPS web authentication

- The daemon web plane is HTTPS-only (`dfs.http.policy`/`yarn.http.policy =
  HTTPS_ONLY`) with a Kerberos SPNEGO filter; the NameNode HTTPS server binds an
  IPv6 socket (`[fd00:dead:beef:1::10]:9871`).
- A web request with no ticket is rejected `401`; a SPNEGO-negotiated request
  (`curl --negotiate`, cert verified against the exported PEM by SAN hostname)
  returns `200`.
- WebHDFS `LISTSTATUS` over HTTPS+SPNEGO, and `OPEN` — where the NameNode
  307-redirects to a DataNode HTTPS URL carrying an HDFS delegation token (whose
  service id is the bracketed IPv6 NameNode endpoint) and the DataNode serves
  the bytes over TLS.

The smoke test (`smoketest-secure.sh`) asserts all of the above; a green run
prints `17 passed, 0 failed`.

## Topology

| Service | Host | IPv6 |
|---|---|---|
| KDC (MIT krb5) | `kdc` | `fd00:dead:beef:1::5` |
| NameNode | `namenode` | `fd00:dead:beef:1::10` |
| DataNode 1 | `datanode-1` | `fd00:dead:beef:1::21` |
| DataNode 2 | `datanode-2` | `fd00:dead:beef:1::22` |
| ResourceManager | `resourcemanager` | `fd00:dead:beef:1::30` |
| NodeManager 1 | `nodemanager-1` | `fd00:dead:beef:1::41` |
| JobHistory | `historyserver` | `fd00:dead:beef:1::50` |

A distinct ULA subnet (`fd00:dead:beef:1::/64`) and host ports (`19871`,
`18090`, `29888`) let this run alongside the non-secure `hadoop-ipv6` harness.

## Run it

```bash
# From the repo root, build the distribution once:
mvn -DskipTests -Pdist package

cd hadoop-dist/src/main/compose/hadoop-ipv6-secure
cp -r ../../../target/hadoop-3.6.0-SNAPSHOT .
docker compose build
docker compose up -d
# Wait ~40s for the KDC to mint keytabs and the DataNodes to register, then:
docker compose exec namenode /opt/hadoop/smoketest-secure.sh
```

The KDC container (`Dockerfile.kdc` + `kdc/setup-kdc.sh`) creates realm
`IPV6.TEST`, adds host-based `hdfs`/`yarn`/`mapred`/`HTTP` principals for every
node into a shared `hadoop.keytab`, and exports it to a volume the Hadoop
containers mount.

## Design notes / deliberate simplifications

- **Hostname-based addressing.** Kerberos principals are host-based
  (`hdfs/_HOST@REALM`), so all addresses use forward-resolvable hostnames and
  `krb5.conf` sets `rdns = false`. This mirrors a real Kerberos+DNS deployment
  and is the natural way to exercise the IPv6 security paths.
- **`ignore.secure.ports.for.testing=true`.** Secure DataNodes normally require
  privileged ports (jsvc/root) or `HTTPS_ONLY`. This harness uses neither, so it
  waives that requirement while keeping SASL data transfer fully active — the
  IPv6 path under test.
- **`hadoop.security.authorization=false`.** Service-level authorization
  reverse-resolves the peer IP to build the expected client principal, but
  Docker's embedded DNS is asymmetric (forward `datanode-1` -> `fd00::21`,
  reverse `fd00::21` -> `hadoop-ipv6-secure-datanode-1-1.<proj>_v6net`). That
  forward/reverse mismatch — identical on IPv4 — is a Docker artifact, not an
  IPv6 concern, so authorization ACLs are left off; authentication (the IPv6
  path) stays on. A real deployment with consistent PTR records can enable it.
- **Self-signed TLS with a shared multi-SAN cert.** The Dockerfile generates one
  keypair whose SAN list covers every service hostname, plus a truststore and an
  exported PEM, baked into the image so every container shares identical TLS
  material (and the shared SPNEGO cookie secret). `curl` verifies the server
  cert against the PEM and connects by a SAN-listed hostname. A real deployment
  would use a proper CA and per-host certs; the IPv6 path under test (HTTPS bind
  + SPNEGO negotiation) is the same.
- **Generic SPNEGO filter** (`AuthenticationFilterInitializer` +
  `hadoop.http.authentication.type=kerberos`). This protects every daemon
  console uniformly (without it `/jmx` is reachable unauthenticated); WebHDFS's
  own delegation-token handling still takes precedence on the DataNode redirect
  leg, so token-based reads keep working.
- **`*.kerberos.principal.pattern = *` for the YARN/MR server principals.**
  When an RPC client validates the server's advertised Kerberos principal it
  otherwise reverse-resolves the server IP to fill in `_HOST`, and Docker's
  embedded DNS returns a synthetic PTR name (`<container>.<proj>_v6net`) that
  does not match the hostname the daemon logs in as. HDFS ships
  `dfs.namenode.kerberos.principal.pattern=*` by default for exactly this
  load-balancer / multi-name case; YARN and the JobHistory client have no such
  default, so `yarn-site.xml`/`mapred-site.xml` set it here. Same forward/reverse
  asymmetry as the `hadoop.security.authorization=false` note above — a Docker
  artifact, not IPv6.
- **IPv6 preference on the MapReduce container JVMs.** The Hadoop daemons prefer
  IPv6 via the image's `HADOOP_OPTS`, but YARN builds a fresh JVM command line
  for the AM and task containers that does not inherit it. Because the Docker
  bridge is forced to carry a link-local IPv4 subnet alongside IPv6 and the JVM
  prefers IPv4 by default, `mapred-site.xml` pins
  `-Djava.net.preferIPv6Addresses=true` on the framework (admin) opts. Without
  it a container resolves `namenode` to its `169.254.x` address and, under
  Kerberos, HDFS delegation-token selection fails (the token's service id is the
  numeric IPv6 endpoint) and auth falls through to KERBEROS with no ticket.

## Not yet covered

- **Reduce/shuffle under Kerberos** needs the native `libhadoop` library: the
  NodeManager's ShuffleHandler reads the spill index through `SecureIOUtils`,
  which throws *"Secure IO is not possible without native code extensions"* when
  security is on and no native library is present. This distribution is built
  without the platform's native library, so the Phase 2 check runs WordCount as
  a **map-only** job (`mapreduce.job.reduces=0`), which still exercises every
  IPv6-relevant secure path (submission, tokens, AM, and task HDFS read/write
  over SASL to the IPv6 DataNodes). The excluded shuffle leg is a native/
  packaging concern that fails identically on IPv4 and is hostname-addressed
  HTTP rather than a numeric-IPv6 code path.
