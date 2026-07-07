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

# Self-contained image used by the IPv6-only docker-compose verification
# harness. Stages a freshly-built Hadoop distribution on top of a Temurin
# JDK so we can validate the IPv6 fixes on the very tree that is being
# changed. The IPv4 hadoop-dist/src/main/docker image relies on
# apache/hadoop-runner for that purpose; we deliberately do not, because
# that image bundles a stable Hadoop and would mask IPv6 bugs in trunk.

FROM eclipse-temurin:21-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       bash curl iproute2 iputils-ping less procps tini \
    && rm -rf /var/lib/apt/lists/*

# HADOOP_DIST is the path (relative to the build context) of an unpacked
# Hadoop distribution (i.e. the contents of hadoop-dist/target/hadoop-X.Y.Z).
ARG HADOOP_DIST=hadoop-3.6.0-SNAPSHOT
COPY ${HADOOP_DIST}/ /opt/hadoop/

ENV HADOOP_HOME=/opt/hadoop \
    HADOOP_CONF_DIR=/opt/hadoop/etc/hadoop \
    HADOOP_LOG_DIR=/opt/hadoop/logs \
    PATH=/opt/hadoop/bin:/opt/hadoop/sbin:${PATH}

# Override the as-shipped configs with the IPv6-targeted ones.
COPY conf/ /opt/hadoop/etc/hadoop/

# Smoketest and UI check are invoked manually inside the namenode container.
COPY smoketest.sh /opt/hadoop/smoketest.sh
COPY ui-ipv6-check.sh /opt/hadoop/ui-ipv6-check.sh
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /opt/hadoop/smoketest.sh /opt/hadoop/ui-ipv6-check.sh \
       /usr/local/bin/entrypoint.sh \
    && mkdir -p /opt/hadoop/logs /data/dfs/name /data/dfs/data

# Run the JVM dual-stack with an IPv6 preference. The compose harness
# verifies HDFS / YARN over IPv6 even when an IPv4 path co-exists.
ENV HADOOP_OPTS="-Djava.net.preferIPv4Stack=false -Djava.net.preferIPv6Addresses=true"

ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/entrypoint.sh"]
