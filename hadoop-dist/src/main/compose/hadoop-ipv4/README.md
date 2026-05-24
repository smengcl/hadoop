# IPv4-only Hadoop verification harness

Regression scenario that guards against IPv6 fixes accidentally breaking
standard IPv4 behaviour. See `../hadoop-ipv6/README.md` for the companion
scenario that exercises the IPv6 fixes this harness protects.

**If this scenario fails after a HADOOP-11890-related change (or any change
touching `NetUtils`, `DatanodeID`, `HttpServer2`, or address-binding logic),
the change broke IPv4.**

## What it runs

Six containers on a plain IPv4 bridge (`172.31.99.0/24`, no IPv6 subnet):
NameNode, two DataNodes, ResourceManager, NodeManager, and JobHistoryServer.
All Hadoop services bind on `0.0.0.0`; cross-service addressing uses compose
service hostnames, which Docker DNS resolves to A records. No IPv6 workaround
properties (`token.service.use_ip`, `registration.ip-hostname-check`,
`use.datanode.hostname`) are set - they are unnecessary on IPv4 and their
absence is part of what this harness verifies.

## Prerequisites

- Docker (standard installation; IPv6 daemon support is **not** required).
- A locally-built Hadoop distribution. From the repo root:

  ```bash
  mvn -DskipTests -Pdist,native -Dmaven.javadoc.skip=true package
  ```

  This produces `hadoop-dist/target/hadoop-3.6.0-SNAPSHOT/`.

## Build the image

```bash
cd hadoop-dist/src/main/compose/hadoop-ipv4
cp -r ../../../target/hadoop-3.6.0-SNAPSHOT .
docker compose build
```

## Run the cluster

```bash
docker compose up -d
docker compose ps
```

Expect six containers in state `Up`.

## Smoke test

```bash
docker compose exec namenode /opt/hadoop/smoketest.sh
```

Key assertions:

| Check | What it proves |
|---|---|
| `hdfs put` / `ls` / `cat` / `get` | Client connects to DN over IPv4 using numeric address (IP-direct mode, the IPv4 default). |
| DN xferAddr matches `172.31.99.X:port` | `DatanodeID` does not incorrectly bracket a plain IPv4 address. |
| `curl http://127.0.0.1:9870/jmx?...` | NameNode HTTP server binds on `0.0.0.0` and is reachable over IPv4 loopback. |
| WordCount on YARN | RM/NM/AM/Container all communicate over IPv4; reduces with `hello=2`. |

Web UIs from the host: `http://localhost:9870` (NN), `http://localhost:8088` (RM),
`http://localhost:19888` (JHS).

## Tear down

```bash
docker compose down -v
rm -rf hadoop-3.6.0-SNAPSHOT
```
