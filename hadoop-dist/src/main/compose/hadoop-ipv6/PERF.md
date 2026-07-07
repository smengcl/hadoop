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

# IPv6 vs IPv4 performance-regression harness

`bench-ipv6.sh` captures a small, repeatable HDFS + YARN performance
baseline. Its purpose is to detect a *performance regression* introduced
by the IPv6 code paths — i.e. a meaningful throughput/latency delta
between the same workload run on an IPv6 stack and on an IPv4 stack. The
IPv6 address handling (bracketing, `formatHostPort`, `createSocketAddr`)
is all on the connection-setup path, not the data path, so the expectation
is **no measurable steady-state delta**; this harness is how you confirm
that on your hardware.

## What it measures

| Workload | Metric |
|:---------|:-------|
| `TestDFSIO -write` | aggregate throughput and average IO rate (MB/s) |
| `TestDFSIO -read`  | aggregate throughput and average IO rate (MB/s) |
| `TeraGen`          | wall-clock seconds |
| `TeraSort`         | wall-clock seconds |

Sizes are deliberately small so the harness finishes quickly. Override via
environment variables for a realistic baseline:

```bash
DFSIO_NFILES=16 DFSIO_SIZE=1GB TERA_ROWS=10000000 bench-ipv6.sh ipv6
```

## Running the IPv6 baseline

```bash
cd hadoop-dist/src/main/compose/hadoop-ipv6
docker compose up -d
# wait for the cluster, then:
docker compose exec -T namenode /opt/hadoop/bench-ipv6.sh ipv6
docker compose cp namenode:/tmp/bench-ipv6.txt ./bench-ipv6.txt
```

## Obtaining the IPv4 baseline to compare against

This harness runs on the IPv6-only compose cluster; the IPv4 baseline must
come from an equivalent IPv4 run of the **same** script and sizes on the
**same** hardware. Two practical options:

1. **IPv4 compose cluster.** Run the same workload on the standard IPv4
   `hadoop-dist/src/main/docker` cluster (or a copy of this harness with
   the IPv6 addressing removed), producing `bench-ipv4.txt`.
2. **Same host, IPv4 loopback.** On a dual-stack single-node setup, run
   the JVM with `-Djava.net.preferIPv4Stack=true` and IPv4 bind addresses
   to force the IPv4 path, tagging the run `bench-ipv6.sh ipv4`.

Then diff the two result files:

```bash
paste bench-ipv6.txt bench-ipv4.txt
```

A regression is a throughput drop (or elapsed-time increase) on the IPv6
run that is outside run-to-run noise (take the median of 3+ runs per
stack; DFSIO and TeraSort on small data are noisy, so treat differences
under ~10% as noise unless they reproduce).

## Notes

* The compose network is a ULA subnet on the Docker bridge, so absolute
  numbers reflect the container network stack, not bare metal — only the
  IPv6-vs-IPv4 *delta on the same setup* is meaningful here.
* `bench-ipv6.sh` cleans up its HDFS scratch dirs (`/bench/*`) after each
  workload, so it is safe to re-run on a live cluster.
