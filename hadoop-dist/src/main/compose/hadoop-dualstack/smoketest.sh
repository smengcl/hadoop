#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# End-to-end smoke test executed inside the namenode container against the
# dual-stack (IPv4 + IPv6) docker-compose cluster. It verifies not just that
# the cluster works, but that IPv6 is actually exercised while IPv4 is live:
#   - the container really is dual-stack (eth0 has both an IPv4 and a global
#     IPv6 address)
#   - hdfs put/get/ls/cat
#   - the DN registers a BRACKETED IPv6 xferAddr even though an IPv4 address is
#     also available (JVM prefers IPv6) - the load-bearing dual-stack assertion
#   - the NameNode HTTP server is reachable over BOTH the IPv4 loopback
#     (127.0.0.1) and the IPv6 loopback ([::1]) - a single [::] connector
#     serving both stacks
#   - WordCount via MapReduce on YARN completes
#
# Exit codes: 0 success; non-zero on any failed assertion.

set -euo pipefail
trap 'echo "[smoketest] FAILED at line $LINENO"; exit 1' ERR

PASS=0; FAIL=0
check() {
  local label="$1"; shift
  if "$@" >/tmp/check.out 2>&1; then
    echo "[smoketest] PASS  $label"
    PASS=$((PASS + 1))
  else
    echo "[smoketest] FAIL  $label"
    sed 's/^/    /' /tmp/check.out
    FAIL=$((FAIL + 1))
  fi
}

echo "[smoketest] === Dual-stack interface ==="
# Prove the host really has both stacks before asserting anything about which
# one Hadoop chose. The v4 and v6 subnets are the ones pinned in the compose
# file; matching them (not just "any inet/inet6") avoids counting the IPv4
# loopback or a link-local address as success.
ip -o addr show dev eth0 || ip -o addr show
check "eth0 has an IPv4 address" \
    bash -c "ip -o -4 addr show dev eth0 | grep -qE 'inet 172\.31\.98\.'"
check "eth0 has a global IPv6 address" \
    bash -c "ip -o -6 addr show dev eth0 | grep -qE 'inet6 fd00:d0:d0::'"

echo "[smoketest] Waiting for HDFS to leave safemode..."
hdfs dfsadmin -safemode wait

echo "[smoketest] === Cluster topology ==="
hdfs dfsadmin -report

echo "[smoketest] === HDFS smoke ==="
echo 'hello world hello dualstack world' > /tmp/payload.txt
hdfs dfs -mkdir -p /smoke
hdfs dfs -rm -f -skipTrash /smoke/payload.txt 2>/dev/null || true
check "hdfs put"   hdfs dfs -put -f /tmp/payload.txt /smoke/payload.txt
check "hdfs ls"    hdfs dfs -ls /smoke
check "hdfs cat"   bash -c 'diff <(hdfs dfs -cat /smoke/payload.txt) /tmp/payload.txt'
check "hdfs get"   bash -c 'rm -f /tmp/echo.txt && hdfs dfs -get /smoke/payload.txt /tmp/echo.txt && diff /tmp/echo.txt /tmp/payload.txt'

# The load-bearing dual-stack assertion: with both stacks live and the JVM
# preferring IPv6, the DN must advertise a BRACKETED IPv6 xferAddr (e.g.
# "[fd00:d0:d0::21]:9866"), not its IPv4 address. This proves the IPv6 data
# path is chosen over an available IPv4 one and that DatanodeID brackets it.
echo "[smoketest] === DN xferAddr format (must be bracketed IPv6) ==="
xferLine=$(hdfs dfsadmin -report | awk '/^Name:/{print; exit}')
echo "    $xferLine"
# Accept either the compressed ("fd00:d0:d0::21") or expanded
# ("fd00:d0:d0:0:0:0:0:21") IPv6 form - the JDK may report either; both are
# bracketed and both carry our ULA prefix, which is what matters here.
check "DN xferAddr is bracketed IPv6" \
    bash -c "echo '$xferLine' | grep -E 'Name: \[fd00:d0:d0:[0-9a-f:]+\]:[0-9]+'"

echo "[smoketest] === NameNode JMX over both loopbacks ==="
check "JMX over IPv4 loopback (127.0.0.1)" \
    curl -sf 'http://127.0.0.1:9870/jmx?qry=Hadoop:service=NameNode,name=NameNodeInfo'
check "JMX over IPv6 loopback ([::1])" \
    curl -sf 'http://[::1]:9870/jmx?qry=Hadoop:service=NameNode,name=NameNodeInfo'
check "NN web UI dfshealth.html over IPv6 loopback" \
    curl -sf 'http://[::1]:9870/dfshealth.html'

echo "[smoketest] === MapReduce WordCount on YARN ==="
hdfs dfs -rm -r -f -skipTrash /smoke/wcout 2>/dev/null || true
EX_JAR=$(ls /opt/hadoop/share/hadoop/mapreduce/hadoop-mapreduce-examples-*.jar | head -1)
[[ -f "$EX_JAR" ]] || { echo "[smoketest] FAIL  examples jar not found"; exit 1; }
check "wordcount job submit + complete" \
    bash -c "yarn jar '$EX_JAR' wordcount /smoke/payload.txt /smoke/wcout"
check "wordcount output (hello=2)" \
    bash -c "hdfs dfs -cat /smoke/wcout/part-r-00000 | grep -E '^hello[[:space:]]+2$'"

echo "[smoketest] === Summary ==="
echo "  $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]] || exit 1
