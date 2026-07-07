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

set -euo pipefail

# Block until the KDC has created the realm and exported the shared keytab.
# Hadoop daemons log in from their keytab (dfs.*.keytab.file), so the keytab
# must exist before any daemon starts.
echo "[entrypoint] Waiting for KDC keytab at /keytabs/hadoop.keytab ..."
for _ in $(seq 1 120); do
  if [[ -f /keytabs/.kdc-ready && -f /keytabs/hadoop.keytab ]]; then
    break
  fi
  sleep 1
done
if [[ ! -f /keytabs/hadoop.keytab ]]; then
  echo "[entrypoint] ERROR: KDC keytab never appeared" >&2
  exit 1
fi
echo "[entrypoint] Keytab present; continuing."

# Format the NameNode's storage on first start of the namenode service.
if [[ "${1:-}" == "hdfs" && "${2:-}" == "namenode" ]]; then
  if [[ ! -f /data/dfs/name/current/VERSION ]]; then
    echo "[entrypoint] Formatting fresh NameNode storage at /data/dfs/name"
    /opt/hadoop/bin/hdfs namenode -format -nonInteractive -force ipv6securecluster
  fi
fi

exec /opt/hadoop/bin/"$@"
