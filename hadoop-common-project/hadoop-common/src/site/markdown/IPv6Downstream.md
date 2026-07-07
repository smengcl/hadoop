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

Running IPv6 Hadoop with Downstream Projects
============================================

<!-- MACRO{toc|fromDepth=0|toDepth=2} -->

Scope
-----

This page is guidance for operators who run downstream projects
(Hive, Spark, HBase, and similar) against an IPv6-only or dual-stack
Hadoop cluster. It is a companion to the
[Hadoop IPv6 Deployment Guide](./IPv6.html), which covers the Hadoop
services themselves.

The downstream projects are **not** built or tested by the Hadoop project's
IPv6 CI, and their releases carry their own IPv6 caveats. Nothing here
certifies a downstream project as IPv6-clean; it identifies the
Hadoop-side surfaces they depend on (all of which are now IPv6-aware) and
gives a per-project checklist so their own IPv6 gaps can be isolated
quickly from Hadoop's.

What downstream clients depend on
---------------------------------

Every Hadoop client — regardless of the compute engine on top — reaches
the cluster through the same handful of address-handling surfaces. All of
them are IPv6-aware as of Hadoop 3.6:

| Client surface | Backing fix | Notes for downstream |
|:---------------|:------------|:---------------------|
| `fs.defaultFS` / NameNode RPC URI | HADOOP-17543, F10 | Configure with a bracketed authority, e.g. `hdfs://[fd00::10]:8020`. A bare literal is rejected by `java.net.URI`. |
| DataNode data path (`createSocketAddr`) | HADOOP-17543 | Bracketed numeric `xferAddr` round-trips through the client. |
| Delegation / block tokens (`SecurityUtil`) | F1, F2, F14 | Token service ids are bracketed; `hadoop.security.token.service.use_ip` default (`true`) works. Downstream code that parses a token service string as `host:port` must be bracket-aware. |
| Kerberos `_HOST` substitution | F1 | `_HOST` resolves to an FQDN, never a bare IPv6 literal. |
| YARN RM/AM RPC + web addresses | HADOOP-18312, F12 | RM/NM web addresses are bracketed at the source. |
| WebHDFS redirect URLs | F11 | Redirects to DataNodes carry bracketed IPv6 authorities. |
| Configuration address parsing | `NetUtils.createSocketAddr` | Accepts bracketed `[::1]:8020` and bare `::1:8020`; strips `%zone` ids (F25). |

The recurring downstream failure mode is **not** in Hadoop: it is
application code that builds a URL or address by string concatenation
(`host + ":" + port`) or splits one with `split(":")` / `indexOf(":")`.
For an IPv6 host both patterns break. When a downstream job fails with
`java.net.URISyntaxException: Illegal character in hostname` or
`UnknownHostException` on a `host` that looks like a truncated IPv6
literal, the bug is almost always on the application side.

Per-project checklist
---------------------

Use these to isolate a downstream IPv6 problem from a Hadoop one. In all
cases, first confirm the Hadoop cluster itself passes the
`hadoop-dist/src/main/compose/hadoop-ipv6` smoke test.

### Hive

* Set `fs.defaultFS` and the metastore DB / Thrift URIs with bracketed
  IPv6 authorities. The metastore connection URI (JDBC) has its own
  bracketing rules — a JDBC URL to an IPv6 host must be bracketed.
* HiveServer2 / metastore bind addresses: bind on `[::]` and verify the
  advertised address in the ZooKeeper service discovery entry is
  bracketed.
* Sanity job: `INSERT` + `SELECT` over a partitioned table (exercises the
  HDFS write pipeline and split computation over IPv6 DataNodes).

### Spark

* `spark.driver.host` / `spark.driver.bindAddress`: an IPv6 driver host
  must be bracketed in the config and in any `--conf` passed on the CLI.
* On YARN, the AM and executors inherit the cluster's addresses; confirm
  the executor JVMs are **not** launched with
  `-Djava.net.preferIPv4Stack=true` (the analogue of Hadoop's F28
  MapReduce fix). Check `spark.executor.extraJavaOptions` and the cluster
  MapReduce admin opts.
* Sanity job: a shuffle-heavy job (e.g. `groupBy` + `count`) to exercise
  executor-to-executor block fetches over IPv6.

### HBase

* `hbase.rootdir` on HDFS uses a bracketed IPv6 authority.
* RegionServer / Master `*.ipc.address` bind on `[::]`; the ZooKeeper
  `znode` advertises server names — confirm they are bracketed or are
  resolvable hostnames (prefer hostnames + AAAA records for HBase).
* Sanity flow: create table, put, scan, and a region move (exercises
  RS-to-RS and RS-to-HDFS over IPv6).

General guidance
----------------

* **Prefer hostnames with AAAA records** for downstream service discovery
  where the project's own IPv6 support is immature. Numeric IPv6 literals
  stress every string-parsing path; hostnames avoid the bracketing
  question for the application layer while still exercising IPv6 at the
  socket layer.
* **Avoid link-local (`%zone`) addresses.** Hadoop strips zone ids (F25),
  and most downstream config parsers do not understand them at all. Use a
  ULA (`fd00::/8`) or global unicast address.
* **Run downstream JVMs dual-stack.** Match Hadoop's stance
  (`-Djava.net.preferIPv4Stack=false -Djava.net.preferIPv6Addresses=true`)
  so an IPv4 loopback does not shadow the IPv6 path.

References
----------

* [Hadoop IPv6 Deployment Guide](./IPv6.html)
* [HADOOP-11890](https://issues.apache.org/jira/browse/HADOOP-11890) —
  Umbrella: Support IPv6 in Hadoop
