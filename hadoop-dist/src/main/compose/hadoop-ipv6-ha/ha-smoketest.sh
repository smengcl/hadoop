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
# End-to-end smoke test for the IPv6-only HDFS HA cluster. Run this from the
# HOST (not inside a container): it drives an automatic failover by stopping
# the active NameNode's container, which cannot be done from within a peer.
#
# Verifies over IPv6:
#   - both NameNodes reach an active/standby steady state (ZKFC election);
#   - a write to the logical hdfs://ns1 URI and read-back;
#   - the DataNode xferAddr is a bracketed numeric IPv6 literal;
#   - killing the active NameNode triggers ZKFC automatic failover to the
#     standby, which then serves the previously-written data (edits replayed
#     from the JournalNode quorum over IPv6);
#   - the restarted NameNode rejoins as standby.

set -uo pipefail
cd "$(dirname "$0")"

DC="docker compose"
# A DataNode container doubles as the HDFS client; it survives NN failover.
CLIENT="datanode-1"

PASS=0; FAIL=0
check() {
  local label="$1"; shift
  if "$@" >/tmp/ha-check.out 2>&1; then
    echo "[ha-smoketest] PASS  ${label}"; PASS=$((PASS + 1))
  else
    echo "[ha-smoketest] FAIL  ${label}"; sed 's/^/    /' /tmp/ha-check.out; FAIL=$((FAIL + 1))
  fi
}

# Run an hdfs command inside the client container.
hdfs_c() { ${DC} exec -T "${CLIENT}" hdfs "$@"; }

state() { hdfs_c haadmin -getServiceState "$1" 2>/dev/null | tr -d '\r'; }

# Container name for a NameNode service id.
container_for() { [[ "$1" == "nn1" ]] && echo "namenode-1" || echo "namenode-2"; }
other_nn() { [[ "$1" == "nn1" ]] && echo "nn2" || echo "nn1"; }

echo "[ha-smoketest] === Wait for HA steady state (one active, one standby) ==="
active=""; standby=""
for _ in $(seq 1 60); do
  s1=$(state nn1); s2=$(state nn2)
  if [[ "$s1" == "active" && "$s2" == "standby" ]]; then active=nn1; standby=nn2; break; fi
  if [[ "$s2" == "active" && "$s1" == "standby" ]]; then active=nn2; standby=nn1; break; fi
  sleep 3
done
if [[ -n "$active" ]]; then
  echo "[ha-smoketest] PASS  reached steady state (active=${active}, standby=${standby})"; PASS=$((PASS + 1))
else
  echo "[ha-smoketest] FAIL  no active/standby steady state (nn1=$(state nn1), nn2=$(state nn2))"; FAIL=$((FAIL + 1))
  echo "[ha-smoketest] === Summary ==="; echo "  $PASS passed, $FAIL failed"; exit 1
fi

echo "[ha-smoketest] === Write/read over the logical hdfs://ns1 URI ==="
check "leave safemode" hdfs_c dfsadmin -safemode wait
check "mkdir /ha" hdfs_c dfs -mkdir -p /ha
check "write payload to hdfs://ns1" \
    bash -c "echo 'hello ha ipv6 world' | ${DC} exec -T ${CLIENT} hdfs dfs -put -f - /ha/payload.txt"
check "read payload back" \
    bash -c "${DC} exec -T ${CLIENT} hdfs dfs -cat /ha/payload.txt | grep -q 'hello ha ipv6 world'"

echo "[ha-smoketest] === DataNode xferAddr is bracketed IPv6 ==="
${DC} exec -T "${CLIENT}" hdfs dfsadmin -report 2>/dev/null | awk '/^Name:/{print "    " $0}'
check "DN xferAddr is bracketed IPv6" \
    bash -c "${DC} exec -T ${CLIENT} hdfs dfsadmin -report 2>/dev/null | grep -E 'Name: \[fd00:dead:beef:[0-9a-f:]+\]:[0-9]+'"

echo "[ha-smoketest] === Trigger automatic failover: stop active (${active}) ==="
active_ctr=$(container_for "${active}")
${DC} stop "${active_ctr}" >/dev/null 2>&1
echo "[ha-smoketest] stopped ${active_ctr}; waiting for ${standby} to become active..."
promoted=""
for _ in $(seq 1 40); do
  if [[ "$(state "${standby}")" == "active" ]]; then promoted=yes; break; fi
  sleep 3
done
if [[ -n "$promoted" ]]; then
  echo "[ha-smoketest] PASS  ZKFC promoted ${standby} to active after failover"; PASS=$((PASS + 1))
else
  echo "[ha-smoketest] FAIL  ${standby} did not become active (state=$(state "${standby}"))"; FAIL=$((FAIL + 1))
fi

echo "[ha-smoketest] === New active serves previously-written data (QJM edit replay) ==="
check "read payload after failover" \
    bash -c "${DC} exec -T ${CLIENT} hdfs dfs -cat /ha/payload.txt | grep -q 'hello ha ipv6 world'"
check "write a new file to the new active" \
    bash -c "echo 'post-failover write' | ${DC} exec -T ${CLIENT} hdfs dfs -put -f - /ha/after.txt"

echo "[ha-smoketest] === Restart the stopped NameNode; it rejoins as standby ==="
${DC} start "${active_ctr}" >/dev/null 2>&1
rejoined=""
for _ in $(seq 1 40); do
  if [[ "$(state "${active}")" == "standby" ]]; then rejoined=yes; break; fi
  sleep 3
done
if [[ -n "$rejoined" ]]; then
  echo "[ha-smoketest] PASS  restarted ${active} rejoined as standby"; PASS=$((PASS + 1))
else
  echo "[ha-smoketest] FAIL  restarted ${active} did not reach standby (state=$(state "${active}"))"; FAIL=$((FAIL + 1))
fi

echo "[ha-smoketest] === Summary ==="
echo "  $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]] || exit 1
