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
# IPv6 performance-baseline harness. Run inside the namenode container.
#
# Captures a small, repeatable HDFS + YARN performance baseline so an
# IPv6-only run can be compared against an IPv4 (or dual-stack) run of the
# same workload. It does NOT itself decide pass/fail: an IPv6-vs-IPv4
# regression is a delta between two runs of this script, one per stack.
# See PERF.md for how to obtain and compare the two baselines.
#
# Workloads:
#   * TestDFSIO write + read  -> aggregate throughput (MB/s) and average IO
#   * TeraSort (teragen + terasort) -> wall-clock seconds
#
# Usage:
#   bench-ipv6.sh [LABEL]      # LABEL tags the results file (default: ipv6)
#
# Results are written to /tmp/bench-<LABEL>.txt and echoed to stdout.

set -euo pipefail
trap 'echo "[bench] FAILED at line $LINENO"; exit 1' ERR

LABEL="${1:-ipv6}"
RESULTS="/tmp/bench-${LABEL}.txt"
: > "$RESULTS"

# Small sizes keep the harness fast; scale up for a real baseline via the
# env overrides below.
DFSIO_NFILES="${DFSIO_NFILES:-4}"
DFSIO_SIZE="${DFSIO_SIZE:-64MB}"
TERA_ROWS="${TERA_ROWS:-500000}"   # 500k * 100B = ~50MB

TESTS_JAR=$(ls /opt/hadoop/share/hadoop/mapreduce/hadoop-mapreduce-client-jobclient-*-tests.jar 2>/dev/null | head -1)
EX_JAR=$(ls /opt/hadoop/share/hadoop/mapreduce/hadoop-mapreduce-examples-*.jar 2>/dev/null | head -1)
# ls|head hides a no-match behind head's exit 0, so check explicitly.
[[ -f "$TESTS_JAR" ]] || { echo "[bench] ERROR: jobclient tests jar not found"; exit 1; }
[[ -f "$EX_JAR" ]] || { echo "[bench] ERROR: mapreduce examples jar not found"; exit 1; }

record() { echo "$1" | tee -a "$RESULTS"; }

echo "[bench] Waiting for HDFS to leave safemode..."
hdfs dfsadmin -safemode wait >/dev/null

record "# IPv6 performance baseline (label=${LABEL})"
record "# DFSIO: ${DFSIO_NFILES} files x ${DFSIO_SIZE}; TeraSort: ${TERA_ROWS} rows"
record ""

# TestDFSIO is not registered in this jar's MapredTestDriver, so run it by
# fully-qualified class name with the tests jar on HADOOP_CLASSPATH.
DFSIO="org.apache.hadoop.fs.TestDFSIO"

# ---- TestDFSIO write ------------------------------------------------------
echo "[bench] TestDFSIO -write ..."
HADOOP_CLASSPATH="$TESTS_JAR" hadoop "$DFSIO" -Dtest.build.data=/bench/dfsio \
    -write -nrFiles "$DFSIO_NFILES" -fileSize "$DFSIO_SIZE" \
    >/tmp/dfsio-write.log 2>&1 || { cat /tmp/dfsio-write.log; exit 1; }
# TestDFSIO prints a summary block; pull the throughput and average IO rate.
w_thr=$(grep -E 'Throughput mb/sec' /tmp/dfsio-write.log | tail -1 | sed 's/.*: *//')
w_avg=$(grep -E 'Average IO rate mb/sec' /tmp/dfsio-write.log | tail -1 | sed 's/.*: *//')
# An empty metric means the run failed or the log format changed - fail
# loudly rather than record "NA" and exit 0 as if the benchmark succeeded.
[[ -n "$w_thr" && -n "$w_avg" ]] \
    || { echo "[bench] ERROR: could not parse DFSIO write metrics"; cat /tmp/dfsio-write.log; exit 1; }
record "TestDFSIO write  Throughput(MB/s): ${w_thr}   AvgIORate(MB/s): ${w_avg}"

# ---- TestDFSIO read -------------------------------------------------------
echo "[bench] TestDFSIO -read ..."
HADOOP_CLASSPATH="$TESTS_JAR" hadoop "$DFSIO" -Dtest.build.data=/bench/dfsio \
    -read -nrFiles "$DFSIO_NFILES" -fileSize "$DFSIO_SIZE" \
    >/tmp/dfsio-read.log 2>&1 || { cat /tmp/dfsio-read.log; exit 1; }
r_thr=$(grep -E 'Throughput mb/sec' /tmp/dfsio-read.log | tail -1 | sed 's/.*: *//')
r_avg=$(grep -E 'Average IO rate mb/sec' /tmp/dfsio-read.log | tail -1 | sed 's/.*: *//')
[[ -n "$r_thr" && -n "$r_avg" ]] \
    || { echo "[bench] ERROR: could not parse DFSIO read metrics"; cat /tmp/dfsio-read.log; exit 1; }
record "TestDFSIO read   Throughput(MB/s): ${r_thr}   AvgIORate(MB/s): ${r_avg}"
HADOOP_CLASSPATH="$TESTS_JAR" hadoop "$DFSIO" -Dtest.build.data=/bench/dfsio -clean \
    >/dev/null 2>&1 || true

# ---- TeraSort (teragen + terasort), wall-clock ----------------------------
hdfs dfs -rm -r -f -skipTrash /bench/tera-in /bench/tera-out >/dev/null 2>&1 || true
echo "[bench] teragen ${TERA_ROWS} rows ..."
t0=$(date +%s)
yarn jar "$EX_JAR" teragen "$TERA_ROWS" /bench/tera-in >/tmp/teragen.log 2>&1 \
    || { cat /tmp/teragen.log; exit 1; }
t1=$(date +%s)
echo "[bench] terasort ..."
yarn jar "$EX_JAR" terasort /bench/tera-in /bench/tera-out >/tmp/terasort.log 2>&1 \
    || { cat /tmp/terasort.log; exit 1; }
t2=$(date +%s)
record "TeraGen   elapsed(s): $((t1 - t0))"
record "TeraSort  elapsed(s): $((t2 - t1))"
hdfs dfs -rm -r -f -skipTrash /bench/tera-in /bench/tera-out >/dev/null 2>&1 || true

record ""
record "# Done. Compare with a run of the same script/sizes on an IPv4 cluster."
echo "[bench] Results written to ${RESULTS}"
