# IPv6-only Hadoop verification harness

End-to-end test bed for the `dev/ipv6-only` stack. Boots a NameNode, two
DataNodes, ResourceManager, NodeManager, and JobHistoryServer on a Docker
network with IPv6 enabled and a ULA subnet (`fd00:dead:beef::/64`). All
Hadoop services bind on the IPv6 wildcard (`[::]`); cross-service
addressing uses compose hostnames, which Docker DNS resolves over AAAA.

## Prerequisites

- Docker daemon with IPv6 support (OrbStack and Docker Desktop both work).
- A locally-built Hadoop distribution. From the repo root:

  ```bash
  mvn -DskipTests -Pdist,native -Dmaven.javadoc.skip=true package
  ```

  This produces `hadoop-dist/target/hadoop-3.6.0-SNAPSHOT/`.

## Build the image

The Dockerfile expects an unpacked dist directory next to it (the build
context is the compose directory itself):

```bash
cd hadoop-dist/src/main/compose/hadoop-ipv6
cp -r ../../../target/hadoop-3.6.0-SNAPSHOT .
docker compose build
```

(Alternatively, build with a custom build-arg:
`docker build -t hadoop-ipv6:dev --build-arg HADOOP_DIST=hadoop-3.6.0-SNAPSHOT .`)

## Run the cluster

```bash
docker compose up -d
docker compose ps
```

Expect six containers in state `Up`. Logs from any service:

```bash
docker compose logs -f namenode
```

## Smoke test

```bash
docker compose exec namenode /opt/hadoop/smoketest.sh
```

The script asserts each of:

| Check | What it proves |
|---|---|
| `hdfs put` / `ls` / `cat` / `get` | Client connect to DN over IPv6 (HADOOP-17543: bracketed `xferAddr` round-trips through `NetUtils.createSocketAddr`). |
| DN xferAddr matches `[fd00:...]:port` | `DatanodeID.setIpAndXferPort` brackets IPv6 literals (commit 2). |
| `curl -g http://[::1]:9870/jmx?...` | NameNode HTTP server has an IPv6 connector (HADOOP-19695). |
| `curl -g http://[::1]:9870/dfshealth.html` | NN UI renders; the JS bracket-aware DN link extraction (HADOOP-18209, commit 4) does not regress. |
| WordCount on YARN | RM/NM/AM/Container all communicate over IPv6 hostnames; reduces with `hello=2`. |

Web UIs from the host: `http://[::1]:9870` (NN), `http://[::1]:8088` (RM),
`http://[::1]:19888` (JHS).

## Tear down

```bash
docker compose down -v
rm -rf hadoop-3.6.0-SNAPSHOT
```

## Failure-mode catalogue

What to look for if the smoke test fails. Each row points at the file
that owns the broken behavior so you can iterate without re-reading the
whole stack.

| Symptom | Likely culprit |
|---|---|
| `IllegalArgumentException: Does not contain a valid host:port authority: ::1:50010` in DataNode logs | `NetUtils.createSocketAddr` did not bracket a bare IPv6 input. Recheck `NetUtils.bracketUnbracketedIPv6` (commit 1). |
| `dfsadmin -report` shows `Name: ::1:9866` (no brackets) | `DatanodeID.setIpAndXferPort` is still concatenating raw. Recheck commit 2 changed every formatter to `NetUtils.formatHostPort`. |
| NN web UI loads but DataNode link 404s, with a host like `fd00` (truncated) | `dfshealth.js` host-extraction not bracket-aware (commit 4 not applied or stale browser cache). |
| DataNode never registers (`0 datanodes` in `dfsadmin -report`) with a hostname-check rejection in the NN log | `DatanodeManager.canonicalizeAddress` (HADOOP-XXXXX-F3) is missing/stale, so the expanded vs. compressed IPv6 forms fail to match under the default `dfs.namenode.datanode.registration.ip-hostname-check=true`. This compose runs with the check enabled and relies on that fix. |
| `UnresolvedAddressException` on hostname like `datanode-1` | Docker DNS gave only A record but JVM picked v6 first (or vice versa). Confirm `enable_ipv6: true` on `v6net` and `HADOOP_OPTS` includes `preferIPv6Addresses=true`. |
| MapReduce task fails with `java.nio.channels.UnsupportedAddressTypeException` in `DFSInputStream`/`newConnectedPeer` | The task JVM opened an IPv4-only socket (pinned `preferIPv4Stack=true`) and tried to reach a DataNode's numeric IPv6 xferAddr. Fixed by dropping the pin from `mapreduce.admin.{map,reduce}.child.java.opts` default (HADOOP-XXXXX-F28); on older releases override those opts. |
| YARN container dies with `java.net.SocketException: Network is unreachable` | NM advertised an IPv6 address that the AM cannot reach. Confirm the NM's `yarn.nodemanager.bind-host` is `::`. |

## Known limitations

This harness intentionally does not cover:

- Kerberos / SASL (out of scope for the dev/ipv6-only stack).
- HA / Federation / RBF Router.
- The full YARN web app deep-link fix sweep (HADOOP-18312); only basic
  RM and JHS pages are exercised.
- True IPv6-only host networking. Docker still requires an IPv4 subnet
  on user-defined bridges, so a tiny 169.254 link-local v4 range
  coexists. Hadoop services do not bind or advertise on that range.
