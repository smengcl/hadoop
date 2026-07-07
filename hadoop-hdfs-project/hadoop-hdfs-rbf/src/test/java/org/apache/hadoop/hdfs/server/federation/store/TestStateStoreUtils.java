/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hdfs.server.federation.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.apache.hadoop.net.NetUtils;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StateStoreUtils}, focused on the address-to-string helpers
 * that feed router/namenode addresses into the state store. These strings are
 * later read back and re-parsed (e.g. with {@link NetUtils#createSocketAddr}),
 * so an IPv6 literal must be bracketed to round-trip correctly.
 */
public class TestStateStoreUtils {

  @Test
  public void testGetHostPortStringIPv6() {
    // createUnresolved preserves the host string verbatim, so the compressed
    // literal is retained and only brackets are added.
    InetSocketAddress addr =
        InetSocketAddress.createUnresolved("fd00:dead:beef::10", 8020);
    assertEquals("[fd00:dead:beef::10]:8020",
        StateStoreUtils.getHostPortString(addr));
  }

  @Test
  public void testGetHostPortStringIPv4() {
    InetSocketAddress addr =
        InetSocketAddress.createUnresolved("192.0.2.10", 8020);
    assertEquals("192.0.2.10:8020",
        StateStoreUtils.getHostPortString(addr));
  }

  @Test
  public void testGetHostPortStringHostname() {
    InetSocketAddress addr =
        InetSocketAddress.createUnresolved("router-1.example.com", 8020);
    assertEquals("router-1.example.com:8020",
        StateStoreUtils.getHostPortString(addr));
  }

  @Test
  public void testGetHostPortStringIPv6Wildcard() {
    // The IPv6 wildcard "::" must be replaced with a routable name, just like
    // the IPv4 "0.0.0.0" wildcard - never stored/advertised verbatim.
    InetSocketAddress addr = InetSocketAddress.createUnresolved("::", 8020);
    String result = StateStoreUtils.getHostPortString(addr);
    assertFalse(result.isEmpty());
    assertFalse(result.startsWith("[::]"),
        "IPv6 wildcard should be replaced, got " + result);
  }

  @Test
  public void testGetIpPortStringIPv6RoundTrips() throws Exception {
    // getByName on a numeric literal does not trigger DNS. getIpPortString
    // renders the numeric address, which must be bracketed so it re-parses.
    InetSocketAddress addr = new InetSocketAddress(
        InetAddress.getByName("fd00:dead:beef::10"), 8020);
    String result = StateStoreUtils.getIpPortString(addr);
    assertTrue(result.startsWith("[") && result.endsWith("]:8020"),
        "IPv6 ip:port should be bracketed, got " + result);
    // The stored string must round-trip back to the same endpoint.
    InetSocketAddress parsed = NetUtils.createSocketAddr(result);
    assertEquals(8020, parsed.getPort());
    assertEquals(addr.getAddress(), parsed.getAddress());
  }

  @Test
  public void testGetHostPortStringNull() {
    assertEquals("", StateStoreUtils.getHostPortString(null));
    assertEquals("", StateStoreUtils.getIpPortString(null));
  }
}
