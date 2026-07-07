# Dual-stack (IPv4 + IPv6) Hadoop verification harness

Regression scenario for a **dual-stack** deployment: every host has both an
IPv4 and an IPv6 address at the same time. It is the middle case between the
`../hadoop-ipv4` (IPv4-only) and `../hadoop-ipv6` (IPv6-only) harnesses, and it
verifies the two things that only a dual-stack host can break:

1. **IPv6 is chosen when both are available.** With the JVM preferring IPv6
   (`-Djava.net.preferIPv6Addresses=true`) and Docker DNS publishing both A and
   AAAA records, services must address each other over IPv6. The DataNode must
   advertise a **bracketed IPv6** xferAddr, not its IPv4 address.
2. **A single `[::]` connector serves both stacks.** Binding on the IPv6
   wildcard must still accept an IPv4-mapped client (Linux default
   `net.ipv6.bindv6only=0`), so the NameNode HTTP server is reachable over both
   `127.0.0.1` and `[::1]`.

**If this scenario fails after a HADOOP-11890-related change, the change broke
dual-stack addressing** — most likely the "prefer IPv6 but fall back to / accept
IPv4" behaviour.

## What it runs

Six containers on a dual-stack bridge with an IPv6 ULA subnet
(`fd00:d0:d0::/64`) and an IPv4 subnet (`172.31.98.0/24`): NameNode, two
DataNodes, ResourceManager, NodeManager, and JobHistoryServer. Each is assigned
**both** a static IPv4 and a static IPv6 address. Services bind on the IPv6
wildcard `[::]` and address each other via compose service hostnames.

## Prerequisites

- Docker with **IPv6 enabled** on the daemon. In `/etc/docker/daemon.json`:

  ```json
  { "ipv6": true, "ip6tables": true }
  ```

  then `systemctl restart docker` (Docker Desktop: Settings → Docker Engine).
- A locally-built Hadoop distribution. From the repo root:

  ```bash
  mvn -DskipTests -Pdist,native -Dmaven.javadoc.skip=true package
  ```

  This produces `hadoop-dist/target/hadoop-3.6.0-SNAPSHOT/`.

## Build the image

```bash
cd hadoop-dist/src/main/compose/hadoop-dualstack
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
| `eth0` has both `172.31.98.X` and `fd00:d0:d0::X` | The host is genuinely dual-stack. |
| `hdfs put` / `ls` / `cat` / `get` | Client reaches the DN over the preferred (IPv6) stack. |
| DN xferAddr matches `[fd00:d0:d0::X]:port` | IPv6 is chosen over the available IPv4, and `DatanodeID` brackets it. |
| JMX over `127.0.0.1` **and** `[::1]` | One `[::]` connector serves both stacks. |
| WordCount on YARN | RM/NM/AM/Container communicate correctly on a dual-stack host. |

Web UIs from the host: `http://localhost:9870` (NN), `http://localhost:8088`
(RM), `http://localhost:19888` (JHS).

## Tear down

```bash
docker compose down -v
rm -rf hadoop-3.6.0-SNAPSHOT
```
