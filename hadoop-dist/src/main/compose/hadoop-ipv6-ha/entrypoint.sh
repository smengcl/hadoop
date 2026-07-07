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
# Role-aware entrypoint for the IPv6-only HA harness. The compose "command"
# selects a role:
#   journalnode            - start a JournalNode
#   datanode               - start a DataNode
#   namenode nn1|nn2       - format/bootstrap as needed, then run the NameNode
#                            (background) plus its ZKFC (foreground)

set -euo pipefail

# Block until a TCP endpoint accepts connections. Uses bash /dev/tcp, which
# resolves the host and connects over IPv6 when it has an AAAA record.
wait_for_port() {
  local host="$1" port="$2" tries="${3:-90}"
  for _ in $(seq 1 "${tries}"); do
    if (exec 3<>"/dev/tcp/${host}/${port}") 2>/dev/null; then
      exec 3>&- 3<&-
      return 0
    fi
    sleep 2
  done
  echo "[entrypoint] ERROR: timed out waiting for ${host}:${port}" >&2
  return 1
}

ROLE="${1:-}"

case "${ROLE}" in
  zookeeper)
    # Standalone ZooKeeper from the bundled jar, with a config that binds the
    # client port on the IPv6 wildcard (clientPortAddress=:: in zoo.cfg). Run
    # java directly so we control the JVM flags (the hdfs launcher is not
    # involved); prefer IPv6 so the bind resolves to an IPv6 socket.
    mkdir -p /data/zk
    exec java -Djava.net.preferIPv4Stack=false -Djava.net.preferIPv6Addresses=true \
      -cp "$(/opt/hadoop/bin/hadoop classpath)" \
      org.apache.zookeeper.server.ZooKeeperServerMain \
      /opt/hadoop/etc/hadoop/zoo.cfg
    ;;

  journalnode)
    exec /opt/hadoop/bin/hdfs journalnode
    ;;

  datanode)
    exec /opt/hadoop/bin/hdfs datanode
    ;;

  namenode)
    NNID="${2:?namenode role requires an id (nn1|nn2)}"

    echo "[entrypoint] Waiting for the JournalNode quorum..."
    wait_for_port journalnode-1 8485
    wait_for_port journalnode-2 8485
    wait_for_port journalnode-3 8485
    echo "[entrypoint] Waiting for ZooKeeper..."
    wait_for_port zookeeper 2181

    if [[ "${NNID}" == "nn1" ]]; then
      if [[ ! -f /data/dfs/name/current/VERSION ]]; then
        echo "[entrypoint] Formatting nn1 storage and the HA ZK znode"
        /opt/hadoop/bin/hdfs namenode -format -nonInteractive -force -clusterid ha-ipv6cluster
        /opt/hadoop/bin/hdfs zkfc -formatZK -nonInteractive -force
      fi
    else
      echo "[entrypoint] Waiting for nn1 RPC before bootstrapping standby..."
      wait_for_port namenode-1 8020
      if [[ ! -f /data/dfs/name/current/VERSION ]]; then
        echo "[entrypoint] Bootstrapping standby ${NNID} from the active NameNode"
        # nn1 may still be coming up; retry until the fsimage copy succeeds.
        until /opt/hadoop/bin/hdfs namenode -bootstrapStandby -nonInteractive -force; do
          echo "[entrypoint] bootstrapStandby not ready yet; retrying in 3s"
          sleep 3
        done
      fi
    fi

    # Run the NameNode as a background daemon, then ZKFC in the foreground.
    # ZKFC drives the initial active election and automatic failover; keeping
    # it as PID 1's child ties the container lifetime to the failover controller.
    echo "[entrypoint] Starting NameNode daemon (${NNID})"
    /opt/hadoop/bin/hdfs --daemon start namenode
    echo "[entrypoint] Starting ZKFC (foreground)"
    exec /opt/hadoop/bin/hdfs zkfc
    ;;

  *)
    exec /opt/hadoop/bin/"$@"
    ;;
esac
