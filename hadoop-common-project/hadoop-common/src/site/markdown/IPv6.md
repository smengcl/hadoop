<!---
  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License. See accompanying LICENSE file.
-->

Hadoop IPv6 Deployment Guide
=============================

<!-- MACRO{toc|fromDepth=0|toDepth=3} -->

Status
------

IPv6 support in Hadoop is tracked under the umbrella JIRA
[HADOOP-11890](https://issues.apache.org/jira/browse/HADOOP-11890).
As of Hadoop 3.6, the following capabilities are verified in the
`hadoop-dist/src/main/compose/hadoop-ipv6` end-to-end harness:

| Capability | Status |
|:-----------|:-------|
| NameNode RPC and HTTP bind on `[::]` | Supported (HADOOP-19695) |
| DataNode data / IPC / HTTP bind on `[::]` | Supported (HADOOP-17543) |
| HDFS client `put` / `get` / `ls` over IPv6 | Supported (HADOOP-17543) |
| NameNode Web UI DataNode links on IPv6 addresses | Supported (HADOOP-18209) |
| ResourceManager and NodeManager bind on `[::]` | Supported (HADOOP-18312) |
| YARN MapReduce job submission and execution over IPv6 | Supported (HADOOP-18312) |
| JobHistory Server bind on `[::]` | Supported |
| NetUtils bracket-aware address parsing and formatting | Supported |
| HDFS High Availability (QJM JournalNode quorum, ZKFC failover) | Supported (HADOOP-XXXXX-F8) |
| Router-Based Federation (RBF) | Supported (HADOOP-XXXXX-F9) |
| HDFS Federation multi-NameService default-FS / GetConf | Supported (HADOOP-XXXXX-F10) |
| WebHDFS redirect and exclude-DN parsing | Supported (HADOOP-XXXXX-F11) |
| Kerberos / SASL secured HDFS + YARN | Supported (HADOOP-XXXXX-F1, F2, F29) |
| SPNEGO + HTTPS web authentication | Supported (HADOOP-XXXXX-F30) |
| YARN Timeline Service (collector bind, client, entity addr) | Supported (HADOOP-XXXXX-F15) |
| YARN webapp deep-link / redirect / proxy URLs | Supported (HADOOP-18312, HADOOP-XXXXX-F12) |
| WebAppProxy over IPv6 | Supported (HADOOP-17843) |

The following areas remain as open follow-up work items under HADOOP-11890:

| Area | Follow-up |
|:-----|:----------|
| HttpFS and NFS gateway (WebHDFS itself is done) | HADOOP-11890 |
| IPv6-vs-IPv4 performance-regression baseline | HADOOP-XXXXX-F23 |
| Downstream integration matrix (Hive / Spark / HBase) | HADOOP-XXXXX-F21 |
| Bare / unbracketed IPv6 literals in metric & audit labels (cosmetic) | HADOOP-XXXXX-F22 |
| Link-local (`%zone`) address support | HADOOP-XXXXX-F25 |

The core HDFS / YARN / MapReduce data and control paths — including HA,
RBF, Federation, and Kerberos-secured clusters — are verified end to end
by the compose harnesses and CI job described in the
"Test harnesses and continuous integration" section below.
The remaining items above are peripheral or non-functional.

Prerequisites
-------------

### Operating system

IPv6 must be enabled in the Linux kernel. Verify with:

```bash
sysctl net.ipv6.conf.all.disable_ipv6
# Expected: net.ipv6.conf.all.disable_ipv6 = 0
```

If the value is `1`, enable IPv6:

```bash
sysctl -w net.ipv6.conf.all.disable_ipv6=0
sysctl -w net.ipv6.conf.default.disable_ipv6=0
```

For persistence, add these settings to `/etc/sysctl.conf` or a file
under `/etc/sysctl.d/`.

All cluster hosts must have AAAA DNS records (or `/etc/hosts` entries)
so that service hostnames resolve to IPv6 addresses.

### JVM dual-stack properties

The JVM prefers IPv4 by default. For a dual-stack cluster set
`java.net.preferIPv4Stack=false`; for a strict IPv6-only cluster also
set `java.net.preferIPv6Addresses=true`. Add these to `HADOOP_OPTS`
in `hadoop-env.sh`:

```bash
# hadoop-env.sh

# Dual-stack: allow both IPv4 and IPv6, favour IPv6 when available.
export HADOOP_OPTS="${HADOOP_OPTS} -Djava.net.preferIPv4Stack=false \
  -Djava.net.preferIPv6Addresses=true"
```

For a dual-stack cluster where IPv4 should remain the default, omit
`preferIPv6Addresses` or set it to `false`.

Deployment Modes
----------------

### Dual-stack

In dual-stack mode every Hadoop service binds on the IPv6 wildcard
(`[::]`), which on Linux covers both IPv4 and IPv6 sockets when
`net.ipv6.bindv6only` is `0` (the default). Service addresses in
configuration files use DNS hostnames that have both A and AAAA
records.  Clients resolve to whichever address family the JVM prefers.

This is the recommended migration path. A dual-stack cluster can be
reached by legacy IPv4 clients while IPv6 connectivity is being rolled
out across the network.

### IPv6-only

In IPv6-only mode the cluster network carries no IPv4 routing. Every
service address in configuration must resolve to an AAAA record, or be
written as a bracketed IPv6 literal (for example `[::]:9866`). The JVM
must be configured with `preferIPv4Stack=false` and
`preferIPv6Addresses=true`.

The `hadoop-dist/src/main/compose/hadoop-ipv6` Docker Compose harness
demonstrates this mode on a ULA subnet (`fd00:dead:beef::/64`) and is
useful for local verification before a production rollout.

Required Configuration Keys
----------------------------

Each Hadoop service has a `*-bind-host` property that controls the
network interface address the server socket listens on. Set these to
`::` to bind on the IPv6 wildcard (which also covers IPv4 on
dual-stack hosts unless `net.ipv6.bindv6only=1`).

The corresponding `*-address` properties control the address
*advertised* to other services and clients. Use DNS hostnames there,
not numeric literals, so that the correct address family is selected at
runtime via DNS.

### HDFS (`hdfs-site.xml`)

| Property | Recommended value | Notes |
|:---------|:------------------|:------|
| `dfs.namenode.rpc-bind-host` | `::` | NameNode RPC listener |
| `dfs.namenode.servicerpc-bind-host` | `::` | NameNode service RPC (DataNode heartbeats) |
| `dfs.namenode.http-bind-host` | `::` | NameNode Web UI (HADOOP-19695) |
| `dfs.namenode.rpc-address` | `<nn-hostname>:8020` | Advertised address; use a resolvable hostname |
| `dfs.namenode.http-address` | `<nn-hostname>:9870` | Advertised Web UI address |
| `dfs.datanode.address` | `[::]:9866` | DataNode data transfer |
| `dfs.datanode.http.address` | `[::]:9864` | DataNode Web UI |
| `dfs.datanode.ipc.address` | `[::]:9867` | DataNode IPC |

### YARN (`yarn-site.xml`)

| Property | Recommended value | Notes |
|:---------|:------------------|:------|
| `yarn.resourcemanager.bind-host` | `::` | ResourceManager listeners |
| `yarn.nodemanager.bind-host` | `::` | NodeManager listeners |

### MapReduce / JobHistory Server (`mapred-site.xml`)

The JobHistory Server inherits `mapreduce.jobhistory.address` and
`mapreduce.jobhistory.webapp.address`. Use a resolvable hostname in
those values; no separate `*-bind-host` key exists for JHS. If the
host has only IPv6 connectivity, set `preferIPv6Addresses=true` in
`HADOOP_OPTS` so the JVM binds the correct interface.

Previously Required Workarounds (now resolved)
----------------------------------------------

Earlier IPv6 deployments needed the properties below to sidestep
subsystems that did not yet understand numeric IPv6 addresses. Those
defects are now fixed in trunk and the numeric-IPv6 ("IP-direct") data
path is exercised end-to-end by the docker-compose harness under
`hadoop-dist/src/main/compose/hadoop-ipv6` (see the smoke test, which
runs with these workarounds removed). They are retained here only as a
reference for operators running older releases.

### `hadoop.security.token.service.use_ip` (default `true` now works)

`SecurityUtil.buildTokenService` builds delegation-token service
identifiers. When built from a numeric IPv6 address the colons in the
address used to collide with the `host:port` separator, producing
malformed tokens, so operators set this to `false` to fall back to the
hostname. `buildTokenService` now brackets IPv6 literals
([HADOOP-XXXXX-F2](https://issues.apache.org/jira/browse/HADOOP-11890)),
so a numeric-IPv6 service id such as `[fd00:dead:beef::10]:8020`
round-trips correctly and the default (`true`) is safe.

### `dfs.namenode.datanode.registration.ip-hostname-check` (default `true` now works)

During DataNode registration the NameNode checks that the DataNode's
reported address matches the address the RPC connection arrived on.
IPv6 literals can be reported in expanded (`fd00:dead:beef:0:0:0:0:21`)
or compressed (`fd00:dead:beef::21`) form, which previously failed a
naive string comparison, so operators disabled the check.
`DatanodeManager.canonicalizeAddress`
([HADOOP-XXXXX-F3](https://issues.apache.org/jira/browse/HADOOP-11890))
now normalizes both sides before comparing, so the default (`true`)
accepts a numeric-IPv6 registration.

### `dfs.client.use.datanode.hostname` (not required)

Setting this to `true` directs HDFS clients to reach DataNodes by
hostname rather than by the numeric xferAddr, which used to be
recommended as a way to avoid the unbracketed-IPv6 connect path. With
the bracket-aware `NetUtils.createSocketAddr`
([HADOOP-17543](https://issues.apache.org/jira/browse/HADOOP-17543))
the client connects directly to a DataNode's bracketed numeric IPv6
xferAddr, so neither this property nor its DataNode-side counterpart
`dfs.datanode.use.datanode.hostname` is required. Leave both at their
`false` defaults for a pure numeric-IPv6 cluster; hostname mode still
works if you prefer it.

### Hostname consistency and data locality on IPv6 hosts

For a scheduler to place a task on the node that holds its input, the
host string the NodeManager registers with the ResourceManager must
match the host string HDFS reports in the block locations. On IPv6 hosts
this can silently diverge: `InetAddress.getLocalHost().getHostName()`
often yields a short name where IPv4 yields the FQDN, and a numeric IPv6
literal may or may not resolve to a PTR name. When the two sides
disagree, node/rack matching fails and data locality collapses
([YARN-1226](https://issues.apache.org/jira/browse/HADOOP-11890)).

Two IPv6 correctness fixes are in place:
`ContainerManagerImpl` derives the NodeManager host override with
`NetUtils.createSocketAddr(...).getHostString()` instead of
`split(":")[0]` (which turned `[2001:db8::1]:8041` into `[2001`), and
`TaskAttemptImpl.isIP` now recognizes IPv6 literals so a numeric split
host is reverse-resolved to a hostname exactly as an IPv4 literal is —
keeping both sides of the locality match in the same form.

For deterministic hostnames regardless of resolver behaviour, set
explicit FQDNs: `dfs.datanode.hostname` on each DataNode and a matching
bind/address on each NodeManager, and provision forward and reverse
(`AAAA` / `PTR`) DNS. This is the recommended production setup and
removes any dependence on `getCanonicalHostName()` heuristics.

### MapReduce task JVMs must not pin `java.net.preferIPv4Stack=true`

**File:** `mapred-site.xml` (older releases only)

```xml
<property>
  <name>mapreduce.admin.map.child.java.opts</name>
  <value>-Dhadoop.metrics.log.level=WARN</value>
</property>
<property>
  <name>mapreduce.admin.reduce.child.java.opts</name>
  <value>-Dhadoop.metrics.log.level=WARN</value>
</property>
```

The daemons pick up the JVM stack preference from `HADOOP_OPTS`, but
map/reduce task JVMs (`YarnChild`) are launched by the NodeManager with
`mapreduce.admin.{map,reduce}.child.java.opts`, whose historical default
pinned `-Djava.net.preferIPv4Stack=true`. That forces an IPv4-only
socket on every task, so a task connecting to a DataNode's numeric IPv6
xferAddr fails with `java.nio.channels.UnsupportedAddressTypeException`
even though the daemons and the MR ApplicationMaster run dual-stack.
The pin has been removed from the default
([HADOOP-XXXXX-F28](https://issues.apache.org/jira/browse/HADOOP-11890)),
so on trunk no override is needed; operators on older releases must set
the properties above (dropping the `preferIPv4Stack=true` token).

Migration Path
--------------

The recommended sequence minimises disruption when migrating a production
cluster from IPv4-only to IPv6-only.

**Phase 1 — dual-stack:** Add AAAA records in DNS while keeping A records.
Set `preferIPv4Stack=false` in `HADOOP_OPTS` and do a rolling restart.
Add the `*-bind-host = ::` properties and restart again. Apply all three
workarounds from the previous section. Confirm existing IPv4 clients still
connect.

**Phase 2 — IPv6-only validation:** Set `preferIPv6Addresses=true` in
`HADOOP_OPTS`. Run a full smoke test (HDFS reads/writes, YARN job
submission, Web UI access over `[<addr>]:<port>` URLs). Monitor logs for
`IllegalArgumentException` or `UnresolvedAddressException`; these indicate
code paths not yet updated for IPv6.

**Phase 3 — IPv4 removal:** Remove A records from DNS. Optionally set
`net.ipv6.bindv6only=1` if pure IPv6-only sockets are required by policy
(this causes `[::]` to bind only the IPv6 interface and drops implicit IPv4
coverage). Keep all workarounds from Phase 1 until the upstream fixes land.

Known Caveats
-------------

### Scope identifiers / link-local addresses

#### Why zone IDs are stripped

An IPv6 *zone identifier* (also called a *scope ID*) is the `%`-suffixed
interface name appended to a link-local address to make it routable, for
example `fe80::1%eth0`.  Zone identifiers are **not permitted** inside a URI
authority component: [RFC 3986 §3.2.2](https://www.rfc-editor.org/rfc/rfc3986)
reserves `%` exclusively for percent-encoding, and any literal `%` in a URI
authority is therefore a syntax error.

Hadoop uses URI authorities pervasively — in HDFS block-location URLs,
delegation-token service identifiers, RPC address round-trips through
`createSocketAddr`, and configuration value serialisation.  To keep these
paths well-formed, `NetUtils.formatHostPort` and `NetUtils.bracketUnbracketedIPv6`
strip the zone identifier before composing the URI authority.  A one-shot
`WARN` is emitted the first time a strip occurs (once per JVM):

```
WARN  NetUtils: IPv6 zone/scope identifier stripped from address 'fe80::1%eth0'.
Zone identifiers are not valid in URI authorities (RFC 3986).
Link-local addresses (fe80::/10) are not supported as Hadoop service endpoints;
use ULA (fc00::/7) or global unicast (2000::/3) addresses.
(This warning is emitted once per JVM.)
```

#### What this means in practice

Because the zone identifier is stripped before the address is bound or
advertised, **link-local addresses (`fe80::/10`) cannot be used as Hadoop
service endpoints**.  A link-local address without its zone ID is
unroutable at the IP layer — the kernel does not know which interface to
use — so any connection attempt to the stripped address will fail.

#### Supported address scopes

| Address range | Scope | Supported |
|:--------------|:------|:----------|
| `fe80::/10`   | Link-local | **No** — requires zone ID, which is stripped |
| `fc00::/7` (including `fd00::/8`) | Unique Local Address (ULA) | **Yes** |
| `2000::/3`    | Global unicast | **Yes** |
| `::`          | Unspecified / wildcard bind | **Yes** (bind only, not advertised) |

The Docker Compose test harness in
`hadoop-dist/src/main/compose/hadoop-ipv6` uses the ULA subnet
`fd00:dead:beef::/64`, which is the recommended range for lab and
production deployments that do not have globally routable IPv6 prefixes.

#### Operational guidance

If you see the zone-id WARN in your logs during startup, the address
configured in `core-site.xml`, `hdfs-site.xml`, or `yarn-site.xml`
contains a `%` character.  Replace it with a ULA or global unicast address
that does not need a zone identifier.  Example:

```xml
<!-- Wrong: link-local address with zone identifier -->
<property>
  <name>dfs.namenode.rpc-bind-host</name>
  <value>fe80::1%eth0</value>   <!-- will be silently stripped -->
</property>

<!-- Correct: ULA address, no zone identifier needed -->
<property>
  <name>dfs.namenode.rpc-bind-host</name>
  <value>fd00:dead:beef::1</value>
</property>
```

This limitation is tracked as HADOOP-XXXXX-F25 under
[HADOOP-11890](https://issues.apache.org/jira/browse/HADOOP-11890).

**BlockPoolID format.**
HDFS NNStorage previously derived the BlockPool identifier from the
NameNode IP address without bracketing colons, producing invalid path
components on some file systems. This is fixed (HADOOP-XXXXX-7), but
clusters that were initialised before the fix will have BlockPool IDs
in the old format. Do not rename existing storage directories when
upgrading.

**YARN Web App deep links.**
YARN container addresses, standby-RM redirects, WebAppProxy, log-server
deep links, and the Router federation blocks are now bracket-aware
(HADOOP-18312 and HADOOP-XXXXX-F12). If you find a UI link or REST
redirect that still emits an unbracketed IPv6 authority, please reopen
HADOOP-18312 with the exact page/endpoint.

**Metrics and audit labels.**
Some code paths emit bare (unbracketed) IPv6 literals in `host:port`
metric labels and audit log entries. This is cosmetic but may break
log-parsing tooling. Tracked as HADOOP-XXXXX-F22 under
[HADOOP-11890](https://issues.apache.org/jira/browse/HADOOP-11890).

**Docker bridge networks.**
The Docker bridge driver requires a small IPv4 subnet alongside the
IPv6 subnet on user-defined networks. The compose test harness is
therefore technically dual-stack rather than IPv6-only even with
`enable_ipv6: true`. Hadoop services do not bind or advertise on the
IPv4 range. Production bare-metal deployments are not affected.

Test harnesses and continuous integration
------------------------------------------

IPv6 support is exercised by three Docker Compose harnesses and a
dedicated CI workflow. All use an on-host ULA subnet
(`fd00:dead:beef::/64`), so no outbound IPv6 connectivity is required.

### Compose harnesses

* **`hadoop-dist/src/main/compose/hadoop-ipv6`** — the baseline
  IPv6-only cluster (NameNode, two DataNodes, ResourceManager,
  NodeManager, JobHistory Server). Its `smoketest.sh` verifies, over the
  numeric IPv6 data path, HDFS `put`/`get`/`ls`/`cat`, hostname-mode
  regression, bracketed DataNode `xferAddr`, the NameNode JMX/UI over
  `[::1]`, MapReduce WordCount, HDFS append, snapshots, DistCp with an
  explicit `hdfs://[fd00:dead:beef::10]:8020` authority,
  TeraGen/TeraSort/TeraValidate, and DataNode decommission via
  `dfs.hosts.exclude` + `dfsadmin -refreshNodes` (HADOOP-XXXXX-F19).
* **`hadoop-dist/src/main/compose/hadoop-ipv6-ha`** — HDFS HA over IPv6:
  QJM JournalNode quorum with ZKFC automatic failover (HADOOP-XXXXX-F8).
* **`hadoop-dist/src/main/compose/hadoop-ipv6-secure`** — a
  Kerberos/SASL-secured cluster with an in-network KDC, verifying
  secured HDFS + YARN/MapReduce (HADOOP-XXXXX-F29) and SPNEGO + HTTPS
  web authentication (HADOOP-XXXXX-F30) over IPv6.

### Unit / integration tests

Build-time IPv6 coverage runs under the `ipv6-test` Maven profile, which
flips surefire to `-Djava.net.preferIPv4Stack=false
-Djava.net.preferIPv6Addresses=true` (the default build pins
`preferIPv4Stack=true`, which masks `Inet6Address` code paths):

```bash
mvn -Pipv6-test -pl hadoop-common-project/hadoop-common \
  -Dtest='TestNetUtilsIPv6,TestSecurityUtilIPv6' test
```

A test that hard-codes `"127.0.0.1"` for a bind or connect address
exercises only the IPv4 path even under this profile. New and updated
tests should instead obtain the loopback from the stack-aware helpers in
`GenericTestUtils`, which return the IPv6 loopback when the profile is
active:

* `getLoopbackAddressString()` — `"::1"` or `"127.0.0.1"`
* `getLoopbackAuthority()` — bracketed for a URI authority: `"[::1]"` or `"127.0.0.1"`
* `getLoopbackAddress()` — the corresponding `InetAddress`

Converting the remaining hard-coded loopback literals across the test
suites to these helpers is ongoing (HADOOP-XXXXX-F17).

### Continuous integration

`.github/workflows/ipv6.yml` (HADOOP-XXXXX-F16) runs two jobs:

* **unit-tests** — the push/PR gate. Path-filtered to the IPv6 surface,
  it builds the IPv6 modules and runs the curated IPv6 test classes
  under the `ipv6-test` profile. Reliable on GitHub-hosted runners
  (IPv6 loopback is available even where outbound IPv6 is not).
* **compose-smoketest** — opt-in (`workflow_dispatch`). Enables IPv6 on
  the Docker daemon, builds the distribution, and runs the baseline
  harness `smoketest.sh` end to end.

References
----------

* [HADOOP-11890](https://issues.apache.org/jira/browse/HADOOP-11890) —
  Umbrella: Support IPv6 in Hadoop

* [HADOOP-19695](https://issues.apache.org/jira/browse/HADOOP-19695) —
  HttpServer2: add IPv6 connector so Web UIs bind on `[::]`

* [HADOOP-17543](https://issues.apache.org/jira/browse/HADOOP-17543) —
  HDFS Put failed with IPv6 cluster: DatanodeID bracketed address
  round-trip through `NetUtils.createSocketAddr`

* [HADOOP-18209](https://issues.apache.org/jira/browse/HADOOP-18209) —
  HDFS NameNode UI: bracket-aware DataNode link construction in
  `dfshealth.js`

* [HADOOP-18312](https://issues.apache.org/jira/browse/HADOOP-18312) —
  YARN WebApps and `WebAppUtils` IPv6 address support

* [HADOOP-17843](https://issues.apache.org/jira/browse/HADOOP-17843) —
  WebAppProxy IPv6 support

* [HADOOP-17845](https://issues.apache.org/jira/browse/HADOOP-17845) —
  `ChecksumFileSystem`: colon-safe sidecar path construction

* Follow-up work items filed under HADOOP-11890 (placeholder ids
  `HADOOP-XXXXX-F*` pending upstream JIRA assignment): F1/F2/F29/F30
  (Kerberos/SASL/SPNEGO), F8 (HA), F9 (RBF), F10 (Federation), F11
  (WebHDFS), F12 (YARN webapp sweep), F15 (Timeline Service), F16 (CI),
  F19 (expanded smoke test).

* [Running IPv6 Hadoop with Downstream Projects](./IPv6Downstream.html) —
  operator guidance for Hive / Spark / HBase on an IPv6 cluster.

* Docker Compose test harnesses:
    * `hadoop-dist/src/main/compose/hadoop-ipv6/README.md`
    * `hadoop-dist/src/main/compose/hadoop-ipv6-ha/README.md`
    * `hadoop-dist/src/main/compose/hadoop-ipv6-secure/README.md`
