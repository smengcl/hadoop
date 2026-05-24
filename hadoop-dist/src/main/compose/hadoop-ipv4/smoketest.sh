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
# the IPv4-only docker-compose cluster. Verifies:
#   - hdfs put/get/ls/cat
#   - hdfs dfsadmin -report shows DataNodes registered with plain IPv4
#     xferAddrs (dotted-decimal, e.g. 172.31.99.21:9866)
#   - the NameNode JMX endpoint is reachable over IPv4 loopback
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

echo "[smoketest] === HDFS smoke ==="
echo 'hello world hello ipv4 world' > /tmp/payload.txt
hdfs dfs -mkdir -p /smoke
hdfs dfs -rm -f -skipTrash /smoke/payload.txt 2>/dev/null || true
check "hdfs put"   hdfs dfs -put -f /tmp/payload.txt /smoke/payload.txt
check "hdfs ls"    hdfs dfs -ls /smoke
check "hdfs cat"   bash -c 'diff <(hdfs dfs -cat /smoke/payload.txt) /tmp/payload.txt'
check "hdfs get"   bash -c 'rm -f /tmp/echo.txt && hdfs dfs -get /smoke/payload.txt /tmp/echo.txt && diff /tmp/echo.txt /tmp/payload.txt'

# Verify the DN's xferAddr is a plain IPv4 dotted-decimal form. This is the
# load-bearing assertion - on IPv4 the address must NOT be bracket-wrapped
# (no "[172.31.99.21]:9866"); it should be "172.31.99.21:9866".
echo "[smoketest] === DN xferAddr format ==="
xferLine=$(hdfs dfsadmin -report | awk '/^Name:/{print; exit}')
echo "    $xferLine"
check "DN xferAddr is plain IPv4" \
    bash -c "echo '$xferLine' | grep -E 'Name: 172\.31\.99\.[0-9]+:[0-9]+'"

echo "[smoketest] === NameNode JMX over IPv4 ==="
check "JMX over IPv4 loopback" \
    curl -sf 'http://127.0.0.1:9870/jmx?qry=Hadoop:service=NameNode,name=NameNodeInfo'
check "NN web UI dfshealth.html" \
    curl -sf 'http://127.0.0.1:9870/dfshealth.html'

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
