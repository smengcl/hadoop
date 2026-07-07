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

# Format the NameNode's storage on first start of the namenode service.
if [[ "${1:-}" == "hdfs" && "${2:-}" == "namenode" ]]; then
  if [[ ! -f /data/dfs/name/current/VERSION ]]; then
    echo "[entrypoint] Formatting fresh NameNode storage at /data/dfs/name"
    /opt/hadoop/bin/hdfs namenode -format -nonInteractive -force ipv6cluster
  fi
  # dfs.hosts.exclude must point at a file that exists; create it empty so the
  # smoke test's decommission step can populate it and run refreshNodes.
  touch /opt/hadoop/etc/hadoop/dfs.hosts.exclude
fi

exec /opt/hadoop/bin/"$@"
