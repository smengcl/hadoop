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
# End-to-end smoke test executed inside the namenode container against
# the IPv6-only docker-compose cluster. Verifies:
#   - hdfs put/get/ls/cat
#   - hdfs dfsadmin -report shows DataNodes registered with bracketed
#     IPv6 xferAddrs
#   - the NameNode JMX endpoint is reachable over IPv6
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

echo "[smoketest] Waiting for HDFS to leave safemode..."
hdfs dfsadmin -safemode wait

echo "[smoketest] === Cluster topology ==="
hdfs dfsadmin -report

echo "[smoketest] === HDFS smoke (hostname mode) ==="
echo 'hello world hello ipv6 world' > /tmp/payload.txt
hdfs dfs -mkdir -p /smoke
hdfs dfs -rm -f -skipTrash /smoke/payload.txt 2>/dev/null || true
check "hdfs put"   hdfs dfs -put -f /tmp/payload.txt /smoke/payload.txt
check "hdfs ls"    hdfs dfs -ls /smoke
check "hdfs cat"   bash -c 'diff <(hdfs dfs -cat /smoke/payload.txt) /tmp/payload.txt'
check "hdfs get"   bash -c 'rm -f /tmp/echo.txt && hdfs dfs -get /smoke/payload.txt /tmp/echo.txt && diff /tmp/echo.txt /tmp/payload.txt'

# The hostname-mode path above masks any regression in the bracketed-IP
# connect path (HADOOP-17543: NetUtils.createSocketAddr for "[ipv6]:port",
# DataTransferSaslUtil.getPeerAddress for the same). Repeat cat / get
# with dfs.client.use.datanode.hostname=false so the client connects
# directly to the DN's bracketed IPv6 xferAddr.
echo "[smoketest] === HDFS smoke (IP-direct mode, exercises HADOOP-17543) ==="
IP_DIRECT="-Ddfs.client.use.datanode.hostname=false"
check "IP-direct hdfs cat" \
    bash -c "diff <(hdfs dfs $IP_DIRECT -cat /smoke/payload.txt) /tmp/payload.txt"
check "IP-direct hdfs get" \
    bash -c "rm -f /tmp/echo-ip.txt && hdfs dfs $IP_DIRECT -get /smoke/payload.txt /tmp/echo-ip.txt && diff /tmp/echo-ip.txt /tmp/payload.txt"

# Verify the DN's xferAddr is actually a bracketed IPv6 form. This is the
# load-bearing assertion - if DatanodeID dropped the bracket, the JSON
# would carry "fd00:dead:beef::21:9866" and the dfsadmin report would
# show malformed addresses (or DFSClient.connectToDN would have already
# failed above).
echo "[smoketest] === DN xferAddr format ==="
xferLine=$(hdfs dfsadmin -report | awk '/^Name:/{print; exit}')
echo "    $xferLine"
# JDK emits the fully-expanded 8-group v6 form (e.g. fd00:dead:beef:0:0:0:0:21),
# not the "::" compressed form. Accept either.
check "DN xferAddr is bracketed IPv6" \
    bash -c "echo '$xferLine' | grep -E 'Name: \[fd00:dead:beef:[0-9a-f:]+\]:[0-9]+'"

echo "[smoketest] === NameNode JMX over IPv6 ==="
check "JMX over IPv6 loopback" \
    curl -g -sf 'http://[::1]:9870/jmx?qry=Hadoop:service=NameNode,name=NameNodeInfo'
check "NN web UI dfshealth.html" \
    curl -g -sf 'http://[::1]:9870/dfshealth.html'

echo "[smoketest] === MapReduce WordCount on YARN ==="
hdfs dfs -rm -r -f -skipTrash /smoke/wcout 2>/dev/null || true
EX_JAR=$(ls /opt/hadoop/share/hadoop/mapreduce/hadoop-mapreduce-examples-*.jar | head -1)
check "wordcount job submit + complete" \
    bash -c "yarn jar '$EX_JAR' wordcount /smoke/payload.txt /smoke/wcout"
check "wordcount output (hello=2)" \
    bash -c "hdfs dfs -cat /smoke/wcout/part-r-00000 | grep -E '^hello[[:space:]]+2$'"

echo "[smoketest] === Summary ==="
echo "  $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]] || exit 1
