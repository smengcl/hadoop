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
# NameNode Web UI IPv6 check. Run inside the namenode container.
#
# The dfshealth.html page renders the DataNode table client-side: its
# JavaScript (dfshealth.js) reads the NameNodeInfo JMX bean's "LiveNodes"
# attribute and builds a hyperlink to each DataNode's HTTP info address.
# HADOOP-18209 fixed that JS to bracket IPv6 literals so the generated
# href is "http://[fd00::..]:9864" rather than the invalid
# "http://fd00::..:9864". A real headless browser is not available in this
# minimal image, so instead of driving the DOM we validate the two things
# that determine whether the browser-rendered links work:
#
#   1. Every DataNode HTTP/xfer address the page's JS consumes is already a
#      bracketed IPv6 authority in the JMX feed.
#   2. Fetching each of those DataNode info addresses (the exact URL the
#      browser would navigate to) returns HTTP 200 over IPv6.
#
# Exit codes: 0 success; non-zero on any failed assertion.

set -euo pipefail
trap 'echo "[ui-check] FAILED at line $LINENO"; exit 1' ERR

PASS=0; FAIL=0
check() {
  local label="$1"; shift
  if "$@" >/tmp/ui-check.out 2>&1; then
    echo "[ui-check] PASS  $label"
    PASS=$((PASS + 1))
  else
    echo "[ui-check] FAIL  $label"
    sed 's/^/    /' /tmp/ui-check.out
    FAIL=$((FAIL + 1))
  fi
}

# Negative assertions: the JMX feed ($CLEAN, set below) must contain NO
# unbracketed IPv6 authority. These are functions rather than inline
# `bash -c "...$CLEAN..."` because $CLEAN is JSON full of double quotes -
# embedding it in a sub-shell string would terminate the quoting and turn
# the assertion into a no-op that always passes. As functions they run in
# this shell where "$CLEAN" is a single properly-quoted argument. A bare
# literal (e.g. fd00:..:9864, no brackets) makes `grep -qv '['` match -> the
# `!` makes the function fail.
no_unbracketed_infoaddr() {
  ! printf '%s' "$CLEAN" \
    | grep -oE '"infoAddr":"[0-9a-fA-F]*:[0-9a-fA-F:]+"' \
    | grep -qv '\['
}
no_unbracketed_xferaddr() {
  ! printf '%s' "$CLEAN" \
    | grep -oE '"xferaddr":"[0-9a-fA-F]*:[0-9a-fA-F:]+"' \
    | grep -qv '\['
}

NN_UI="http://[::1]:9870"

echo "[ui-check] === NameNode UI shell loads over IPv6 ==="
check "GET /dfshealth.html" curl -g -sf "${NN_UI}/dfshealth.html"
# The page bootstraps these static assets; a 200 on the JS proves the
# bracketing logic is actually served to the browser.
check "GET /static/dfs-dust.js" curl -g -sf "${NN_UI}/static/dfs-dust.js"

echo "[ui-check] === DataNode link data in the NameNodeInfo JMX feed ==="
JMX=$(curl -g -sf \
  "${NN_UI}/jmx?qry=Hadoop:service=NameNode,name=NameNodeInfo")
# The LiveNodes value is a JSON string embedded (escaped) in the JMX JSON;
# strip the backslashes so the inner "infoAddr"/"xferaddr" fields are plain.
CLEAN=$(printf '%s' "$JMX" | tr -d '\\')

# Collect the DataNode HTTP info addresses the UI turns into hyperlinks.
mapfile -t INFO_ADDRS < <(printf '%s' "$CLEAN" \
  | grep -oE '"infoAddr":"[^"]+"' \
  | sed 's/"infoAddr":"//; s/"$//' | sort -u)
echo "[ui-check]   infoAddr entries: ${INFO_ADDRS[*]:-<none>}"

check "at least two DataNodes present in LiveNodes" \
    test "${#INFO_ADDRS[@]}" -ge 2

# Every infoAddr / xferaddr must be a bracketed IPv6 authority. A regression
# in the JMX feed (bare literal) would make the JS build a broken href.
BRACKETED='^\[[0-9a-fA-F:]+\]:[0-9]+$'
for a in "${INFO_ADDRS[@]}"; do
  check "infoAddr is bracketed IPv6: $a" bash -c "[[ '$a' =~ $BRACKETED ]]"
done

check "no unbracketed IPv6 infoAddr in feed" no_unbracketed_infoaddr
check "no unbracketed IPv6 xferaddr in feed" no_unbracketed_xferaddr

echo "[ui-check] === DataNode deep-link URLs the browser builds actually work ==="
for a in "${INFO_ADDRS[@]}"; do
  # This is exactly the href dfshealth.js constructs: scheme + bracketed
  # authority. It must resolve to the DataNode Web UI over IPv6.
  check "DataNode UI reachable: http://$a/" \
      curl -g -sf "http://${a}/" -o /dev/null
done

echo "[ui-check] === Summary ==="
echo "  $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]] || exit 1
