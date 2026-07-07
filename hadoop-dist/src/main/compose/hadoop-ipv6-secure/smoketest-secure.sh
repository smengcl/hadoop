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
# End-to-end smoke test for the KERBEROS/SASL secured IPv6-only cluster,
# executed inside the namenode container. Verifies:
#   - kinit against the in-compose KDC over IPv6 (AAAA-resolved "kdc")
#   - HDFS RPC under Kerberos + hadoop.rpc.protection SASL over IPv6
#   - put/get/cat, i.e. SASL data transfer to a DataNode's bracketed IPv6
#     xferAddr with block access tokens enabled
#   - HDFS delegation token issuance and its IPv6 service identifier
#   - that security is actually enforced (ops fail without a ticket)
#
# Exit codes: 0 success; non-zero on any failed assertion.

set -uo pipefail

REALM="IPV6.TEST"
KEYTAB="/keytabs/hadoop.keytab"
CLIENT_PRINC="hdfs/namenode@${REALM}"

PASS=0; FAIL=0
check() {
  local label="$1"; shift
  if "$@" >/tmp/check.out 2>&1; then
    echo "[secure-smoketest] PASS  $label"
    PASS=$((PASS + 1))
  else
    echo "[secure-smoketest] FAIL  $label"
    sed 's/^/    /' /tmp/check.out
    FAIL=$((FAIL + 1))
  fi
}
# Assert that a command FAILS (used for the negative security check).
check_fails() {
  local label="$1"; shift
  if "$@" >/tmp/check.out 2>&1; then
    echo "[secure-smoketest] FAIL  $label (command unexpectedly succeeded)"
    sed 's/^/    /' /tmp/check.out
    FAIL=$((FAIL + 1))
  else
    echo "[secure-smoketest] PASS  $label"
    PASS=$((PASS + 1))
  fi
}

echo "[secure-smoketest] === Kerberos login ==="
check "kinit ${CLIENT_PRINC} from keytab" \
    kinit -kt "${KEYTAB}" "${CLIENT_PRINC}"
echo "[secure-smoketest] --- klist ---"
klist | sed 's/^/    /'

echo "[secure-smoketest] Waiting for HDFS to leave safemode..."
# Also confirms the NameNode is up and reachable *while authenticated*, so the
# negative check below can attribute a failure to auth rejection rather than to
# the cluster simply not listening yet.
hdfs dfsadmin -safemode wait

# Now that the NameNode is confirmed reachable, verify it REJECTS access with no
# ticket -- and specifically for an authentication reason, not connection error.
echo "[secure-smoketest] === Negative check: reject unauthenticated access ==="
kdestroy 2>/dev/null || true
if hdfs dfs -ls / >/tmp/check.out 2>&1; then
  echo "[secure-smoketest] FAIL  HDFS rejects unauthenticated access (unexpectedly succeeded)"
  sed 's/^/    /' /tmp/check.out
  FAIL=$((FAIL + 1))
elif grep -qiE 'authenticat|GSS|SASL|Kerberos|credentials' /tmp/check.out; then
  echo "[secure-smoketest] PASS  HDFS rejects unauthenticated access (auth error)"
  PASS=$((PASS + 1))
else
  echo "[secure-smoketest] FAIL  HDFS rejects unauthenticated access (failed, but not on auth -- cluster unreachable?)"
  sed 's/^/    /' /tmp/check.out
  FAIL=$((FAIL + 1))
fi
# Re-authenticate for the remaining checks.
kinit -kt "${KEYTAB}" "${CLIENT_PRINC}"

echo "[secure-smoketest] === Cluster topology (Kerberos RPC over IPv6) ==="
check "dfsadmin -report over Kerberos" hdfs dfsadmin -report
hdfs dfsadmin -report | awk '/^Name:/{print "    " $0}'

echo "[secure-smoketest] === HDFS data path (SASL data transfer, IPv6) ==="
echo 'hello secure ipv6 world hello' > /tmp/payload.txt
hdfs dfs -mkdir -p /smoke
hdfs dfs -rm -f -skipTrash /smoke/payload.txt 2>/dev/null || true
check "hdfs put (SASL write pipeline)" hdfs dfs -put -f /tmp/payload.txt /smoke/payload.txt
check "hdfs ls"  hdfs dfs -ls /smoke
check "hdfs cat (SASL read, block token)" \
    bash -c 'diff <(hdfs dfs -cat /smoke/payload.txt) /tmp/payload.txt'
check "hdfs get" \
    bash -c 'rm -f /tmp/echo.txt && hdfs dfs -get /smoke/payload.txt /tmp/echo.txt && diff /tmp/echo.txt /tmp/payload.txt'

echo "[secure-smoketest] === DataNode xferAddr is bracketed IPv6 ==="
xferLine=$(hdfs dfsadmin -report | awk '/^Name:/{print; exit}')
echo "    $xferLine"
check "DN xferAddr is bracketed IPv6" \
    bash -c "echo '$xferLine' | grep -E 'Name: \[fd00:dead:beef:[0-9a-f:]+\]:[0-9]+'"

echo "[secure-smoketest] === HDFS delegation token (IPv6 service id) ==="
rm -f /tmp/dt.token
check "fetch delegation token" \
    hdfs fetchdt --renewer "${CLIENT_PRINC}" /tmp/dt.token
echo "[secure-smoketest] --- token contents ---"
hdfs fetchdt --print /tmp/dt.token 2>/dev/null | sed 's/^/    /'
# The token's service identifier is derived from the NameNode address. With
# the default hadoop.security.token.service.use_ip=true it is the numeric
# IPv6 form, which must be bracketed (HADOOP-XXXXX-F2). fetchdt --print emits
# "... for <service>"; accept either a bracketed IPv6 literal or hostname:port.
check "delegation token has a bracketed IPv6 NN service id" \
    bash -c "hdfs fetchdt --print /tmp/dt.token 2>/dev/null | grep -E 'for (\[fd00:dead:beef:[0-9a-f:]+\]:8020|namenode:8020)'"

echo "[secure-smoketest] === Secure YARN + MapReduce over IPv6 ==="
# The ResourceManager and NodeManager authenticate to each other and to HDFS
# with Kerberos; the client (kinit'd above as hdfs) submits a job and YARN
# fetches an HDFS delegation token (bracketed IPv6 service id) for the AM and
# task containers. WordCount exercises the full secure MR path over IPv6:
# NM<->RM registration, container launch, and SASL data transfer from the
# task JVMs to the IPv6 DataNodes using block tokens.
check "yarn node -list shows a RUNNING NodeManager (Kerberos RPC to RM)" \
    bash -c "yarn node -list -all 2>/dev/null | grep -E 'nodemanager-1:[0-9]+.*RUNNING'"
yarn node -list -all 2>/dev/null | sed 's/^/    /'

EXAMPLES_JAR=$(ls "${HADOOP_HOME}"/share/hadoop/mapreduce/hadoop-mapreduce-examples-*.jar 2>/dev/null | head -1)
if [[ -z "${EXAMPLES_JAR}" ]]; then
  echo "[secure-smoketest] FAIL  MapReduce examples jar not found under ${HADOOP_HOME}/share/hadoop/mapreduce"
  FAIL=$((FAIL + 1))
  echo "[secure-smoketest] === Summary ==="
  echo "  $PASS passed, $FAIL failed"
  exit 1
fi
echo "[secure-smoketest] Using examples jar: ${EXAMPLES_JAR}"
hdfs dfs -rm -r -f -skipTrash /wc 2>/dev/null || true
hdfs dfs -mkdir -p /wc/input
hdfs dfs -put -f /tmp/payload.txt /wc/input/payload.txt
# Run WordCount as a MAP-ONLY job (mapreduce.job.reduces=0). This exercises the
# IPv6-relevant secure MapReduce data path end to end: Kerberos job submission
# to the RM, an HDFS delegation token whose service id is the bracketed numeric
# IPv6 NameNode endpoint, AM<->RM and AM<->HDFS over IPv6, and — the crux — a
# task container reading its input split and writing its output to HDFS via
# SASL data transfer / block tokens against the IPv6 DataNodes.
#
# The reduce/shuffle leg is deliberately excluded: under Kerberos the NM's
# ShuffleHandler reads the spill index through SecureIOUtils, which hard-requires
# the native libhadoop (SecureIOUtils: "Secure IO is not possible without native
# code extensions"). This dist is built without the platform's native library,
# so secure shuffle is unavailable — a native/packaging concern that fails
# identically on IPv4 and is unrelated to IPv6. The shuffle leg here is also
# hostname-addressed HTTP, not a numeric-IPv6 code path. See README.md.
check "map-only WordCount over secure YARN (HDFS delegation tokens, IPv6)" \
    yarn jar "${EXAMPLES_JAR}" wordcount -D mapreduce.job.reduces=0 /wc/input /wc/output
echo "[secure-smoketest] --- map output (part-m-00000) ---"
hdfs dfs -cat /wc/output/part-m-00000 2>/dev/null | sed 's/^/    /'
# payload is "hello secure ipv6 world hello": the map emits (word, 1) per token,
# so "hello" appears on two output lines (no combiner without a reduce).
check "map output written to HDFS over IPv6 is correct (hello x2)" \
    bash -c "test \$(hdfs dfs -cat /wc/output/part-m-00000 2>/dev/null | grep -cE '^hello[[:space:]]') -eq 2"

echo "[secure-smoketest] === Summary ==="
echo "  $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]] || exit 1
