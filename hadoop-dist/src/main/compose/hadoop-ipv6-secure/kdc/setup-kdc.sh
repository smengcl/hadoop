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
# Initialize the MIT KDC realm, create host-based service principals for the
# IPv6-only secure Hadoop cluster, export a single shared keytab, and run the
# KDC in the foreground.

set -euo pipefail

REALM="IPV6.TEST"
MASTER_PW="masterkey"
KEYTAB_DIR="/keytabs"
KEYTAB="${KEYTAB_DIR}/hadoop.keytab"

# Every host in docker-compose.yaml that runs a Hadoop daemon. Each gets an
# hdfs/<host>, yarn/<host>, mapred/<host> and HTTP/<host> principal so that a
# daemon logging in as "<service>/_HOST" finds its own host's key in the
# shared keytab regardless of which node it runs on.
HOSTS="namenode datanode-1 datanode-2 resourcemanager nodemanager-1 historyserver"
SERVICES="hdfs yarn mapred HTTP"

echo "[kdc] Creating KDC database for realm ${REALM}"
# -s stashes the master key so krb5kdc can start non-interactively.
kdb5_util create -s -r "${REALM}" -P "${MASTER_PW}"

add_princ() {
  local princ="$1"
  # -randkey: random key; keys go to the shared keytab via ktadd below.
  kadmin.local -q "addprinc -randkey ${princ}" >/dev/null 2>&1
}

echo "[kdc] Adding service principals"
mkdir -p "${KEYTAB_DIR}"
# Start from a clean keytab so restarts do not accumulate stale entries.
rm -f "${KEYTAB}"
for host in ${HOSTS}; do
  for svc in ${SERVICES}; do
    princ="${svc}/${host}@${REALM}"
    add_princ "${princ}"
    kadmin.local -q "ktadd -k ${KEYTAB} -norandkey ${princ}" >/dev/null 2>&1
  done
done

# A plain user principal for the smoke test client. Maps to the short name
# "hdfs" under the default auth_to_local rules, i.e. the HDFS superuser.
add_princ "hdfs@${REALM}"
kadmin.local -q "ktadd -k ${KEYTAB} -norandkey hdfs@${REALM}" >/dev/null 2>&1

# World-readable inside the trusted compose network so every Hadoop container
# (which mounts ${KEYTAB_DIR}) can read it. This is a disposable test realm.
chmod 644 "${KEYTAB}"

echo "[kdc] Keytab written to ${KEYTAB} with entries:"
klist -k "${KEYTAB}" | sed 's/^/    /'

# Touch a readiness marker AFTER the keytab is complete so Hadoop containers
# can block on it before starting their daemons.
touch "${KEYTAB_DIR}/.kdc-ready"

echo "[kdc] Starting krb5kdc in the foreground"
# -n keeps krb5kdc in the foreground so it is PID-managed by tini.
exec krb5kdc -n
