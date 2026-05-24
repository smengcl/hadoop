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

The following areas are not yet fully verified and remain as open
follow-up work items under HADOOP-11890:

| Area | Follow-up |
|:-----|:----------|
| HDFS High Availability (JournalNode quorum, ZKFC, fencing) | HADOOP-XXXXX-F8 |
| Router-Based Federation (RBF) | HADOOP-XXXXX-F9 |
| HDFS Federation multi-NameService | HADOOP-XXXXX-F10 |
| WebHDFS / HttpFS / NFS gateway | HADOOP-XXXXX-F11 |
| Kerberos / SASL authentication over IPv6 | HADOOP-XXXXX-F1, HADOOP-XXXXX-F2 |
| YARN Timeline Service | HADOOP-XXXXX-F15 |
| WebAppProxy over IPv6 | HADOOP-17845, HADOOP-17843 |

Operators who rely on HA, RBF, Federation, or Kerberos should treat
IPv6 deployment as experimental until those follow-up items are resolved.

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

Required Workarounds
--------------------

Several Hadoop subsystems have not yet been updated to handle IPv6
addresses correctly. Until the underlying JIRAs are resolved, operators
must set the following properties.

### `hadoop.security.token.service.use_ip` — set to `false`

**File:** `core-site.xml`

```xml
<property>
  <name>hadoop.security.token.service.use_ip</name>
  <value>false</value>
</property>
```

`SecurityUtil.buildTokenService` constructs delegation-token service
identifiers. When the identifier is built from a numeric IPv6 address
the colon characters in the address collide with the `host:port`
separator, producing malformed tokens. Setting this property to `false`
instructs Hadoop to use the hostname instead of the IP address as the
service identifier, keeping the format unambiguous.

This workaround will become unnecessary once
[HADOOP-XXXXX-F2](https://issues.apache.org/jira/browse/HADOOP-11890)
adds bracket-aware handling to `buildTokenService`.

### `dfs.namenode.datanode.registration.ip-hostname-check` — set to `false`

**File:** `hdfs-site.xml`

```xml
<property>
  <name>dfs.namenode.datanode.registration.ip-hostname-check</name>
  <value>false</value>
</property>
```

During DataNode registration the NameNode verifies that the DataNode's
reported IP address can be resolved back to a hostname matching the
registration hostname. With IPv6 addresses the reverse DNS name is in
`ip6.arpa.` form, which often does not match the forward DNS hostname.
Disabling this check allows DataNodes to register without a matching
PTR record.

This workaround will become unnecessary once
[HADOOP-XXXXX-F3](https://issues.apache.org/jira/browse/HADOOP-11890)
canonicalizes IPv6 addresses in the registration check.

### `dfs.client.use.datanode.hostname` — set to `true` (recommended)

**File:** `hdfs-site.xml`

```xml
<property>
  <name>dfs.client.use.datanode.hostname</name>
  <value>true</value>
</property>
```

This property directs HDFS clients to connect to DataNodes using the
hostname from the block location rather than the numeric IP address.
It is not strictly required after the bracket-aware address formatting
introduced in [HADOOP-17543](https://issues.apache.org/jira/browse/HADOOP-17543),
but it is recommended for clarity and for environments where DataNode
IP addresses are not routable from clients. When set, also enable
`dfs.datanode.use.datanode.hostname=true` on the DataNode side so
inter-DataNode pipeline connections follow the same path.

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
YARN container addresses and Web App deep links have not been fully
audited for bracket-awareness. Known gaps are tracked under
[HADOOP-18312](https://issues.apache.org/jira/browse/HADOOP-18312).

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

* Docker Compose test harness:
  `hadoop-dist/src/main/compose/hadoop-ipv6/README.md`
