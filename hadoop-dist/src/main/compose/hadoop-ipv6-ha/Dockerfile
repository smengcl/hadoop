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

# Hadoop image for the IPv6-only HA (QJM + ZKFC automatic failover) harness.
# Same freshly-built distribution as the base hadoop-ipv6 image.

FROM eclipse-temurin:21-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       bash curl iproute2 iputils-ping less procps tini \
    && rm -rf /var/lib/apt/lists/*

ARG HADOOP_DIST=hadoop-3.6.0-SNAPSHOT
COPY ${HADOOP_DIST}/ /opt/hadoop/

ENV HADOOP_HOME=/opt/hadoop \
    HADOOP_CONF_DIR=/opt/hadoop/etc/hadoop \
    HADOOP_LOG_DIR=/opt/hadoop/logs \
    PATH=/opt/hadoop/bin:/opt/hadoop/sbin:${PATH}

# Override the as-shipped configs with the IPv6 HA ones.
COPY conf/ /opt/hadoop/etc/hadoop/

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh \
    && mkdir -p /opt/hadoop/logs /data/dfs/name /data/dfs/data /data/dfs/journal

# Dual-stack with an IPv6 preference so the daemons advertise/resolve IPv6.
ENV HADOOP_OPTS="-Djava.net.preferIPv4Stack=false -Djava.net.preferIPv6Addresses=true"

ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/entrypoint.sh"]
