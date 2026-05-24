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
package org.apache.hadoop.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NetUtils} address-formatting helpers focused on IPv6
 * literals (bracketing, zone-id stripping, and authority round-tripping).
 *
 * Runs on any JVM regardless of {@code java.net.preferIPv4Stack} - tests use
 * literal address strings rather than DNS lookups.
 */
public class TestNetUtilsIPv6 {

  @Test
  public void testFormatHostPortIPv4() {
    assertEquals("127.0.0.1:8020", NetUtils.formatHostPort("127.0.0.1", 8020));
    assertEquals("nn.example.com:8020",
        NetUtils.formatHostPort("nn.example.com", 8020));
  }

  @Test
  public void testFormatHostPortBareIPv6IsBracketed() {
    assertEquals("[::1]:8020", NetUtils.formatHostPort("::1", 8020));
    assertEquals("[2001:db8::1]:50010",
        NetUtils.formatHostPort("2001:db8::1", 50010));
    assertEquals("[fd00:dead:beef::10]:9870",
        NetUtils.formatHostPort("fd00:dead:beef::10", 9870));
  }

  @Test
  public void testFormatHostPortBracketedIPv6PassesThrough() {
    assertEquals("[::1]:8020", NetUtils.formatHostPort("[::1]", 8020));
  }

  @Test
  public void testFormatHostPortStripsZoneId() {
    assertEquals("[fe80::1]:8020",
        NetUtils.formatHostPort("fe80::1%eth0", 8020));
    assertEquals("[fe80::1]:8020",
        NetUtils.formatHostPort("fe80::1%17", 8020));
  }

  @Test
  public void testFormatHostPortStripsZoneIdInsideBrackets() {
    // Bracketed input that carries a zone-id inside the brackets: the
    // zone must be stripped without producing a double-bracketed result.
    assertEquals("[fe80::1]:8020",
        NetUtils.formatHostPort("[fe80::1%eth0]", 8020));
  }

  @Test
  public void testFormatHostPortIPv4Mapped() {
    // IPv4-mapped IPv6 literal: contains both ":" and ".".
    assertEquals("[::ffff:1.2.3.4]:8020",
        NetUtils.formatHostPort("::ffff:1.2.3.4", 8020));
  }

  @Test
  public void testFormatHostPortNullReturnsLiteralNull() {
    // Documented behavior: null host produces a non-null sentinel that
    // existing callers tolerate. Pinned with this test to detect any
    // future change to a real null/throw.
    assertEquals("null:8020", NetUtils.formatHostPort(null, 8020));
  }

  @Test
  public void testFormatAddressFromInet6() throws Exception {
    InetAddress v6 = InetAddress.getByName("::1");
    assertTrue(v6 instanceof Inet6Address);
    InetSocketAddress addr = new InetSocketAddress(v6, 8020);
    // JDK Inet6Address.getHostAddress() returns the fully-expanded
    // 8-group form (e.g. "0:0:0:0:0:0:0:1"), not the compressed "::1".
    // The bracket-wrapping is what matters for URI round-tripping.
    String s = NetUtils.formatAddress(addr);
    assertTrue(s.startsWith("[") && s.endsWith(":8020"),
        "bracketed v6 expected, was: " + s);
  }

  @Test
  public void testFormatAddressFromInet4() throws Exception {
    InetSocketAddress addr =
        new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 8020);
    // IPv4 emits the numeric form without brackets.
    assertEquals("127.0.0.1:8020", NetUtils.formatAddress(addr));
  }

  @Test
  public void testFormatAddressUnresolved() {
    InetSocketAddress addr =
        InetSocketAddress.createUnresolved("nn.example.com", 8020);
    assertEquals("nn.example.com:8020", NetUtils.formatAddress(addr));
    InetSocketAddress v6 = InetSocketAddress.createUnresolved("::1", 8020);
    assertEquals("[::1]:8020", NetUtils.formatAddress(v6));
  }

  @Test
  public void testGetHostPortStringIPv6Bracketed() throws Exception {
    InetSocketAddress addr =
        new InetSocketAddress(InetAddress.getByName("::1"), 8020);
    String s = NetUtils.getHostPortString(addr);
    assertTrue(s.startsWith("[") && s.endsWith("]:8020"),
        "bracketed v6 expected, was: " + s);
    // Round-trip: the emitted string must parse back to an equivalent socket.
    InetSocketAddress parsed = NetUtils.createSocketAddr(s);
    assertEquals(addr.getPort(), parsed.getPort());
    assertEquals(addr.getAddress(), parsed.getAddress());
  }

  @Test
  public void testGetPortFromHostPortStringBracketedIPv6() {
    assertEquals(8020, NetUtils.getPortFromHostPortString("[::1]:8020"));
    assertEquals(9870,
        NetUtils.getPortFromHostPortString("[fd00:dead:beef::10]:9870"));
  }

  @Test
  public void testGetPortFromHostPortStringRejectsBareIPv6() {
    assertThrows(IllegalArgumentException.class,
        () -> NetUtils.getPortFromHostPortString("::1:8020"));
  }

  @Test
  public void testGetPortFromHostPortStringIPv4StillWorks() {
    assertEquals(8020, NetUtils.getPortFromHostPortString("127.0.0.1:8020"));
    assertEquals(8020,
        NetUtils.getPortFromHostPortString("nn.example.com:8020"));
  }

  @Test
  public void testBracketUnbracketedIPv6HostPort() {
    assertEquals("[::1]:8020", NetUtils.bracketUnbracketedIPv6("::1:8020"));
    assertEquals("[2001:db8::1]:50010",
        NetUtils.bracketUnbracketedIPv6("2001:db8::1:50010"));
    assertEquals("[fe80::1]:8020",
        NetUtils.bracketUnbracketedIPv6("fe80::1%eth0:8020"));
  }

  @Test
  public void testBracketUnbracketedIPv6Bare() {
    // Bare IPv6 literals (no port) are bracketed in place.
    assertEquals("[::1]", NetUtils.bracketUnbracketedIPv6("::1"));
    assertEquals("[2001:db8::1]",
        NetUtils.bracketUnbracketedIPv6("2001:db8::1"));
    assertEquals("[fe80::1]",
        NetUtils.bracketUnbracketedIPv6("fe80::1%eth0"));
  }

  @Test
  public void testBracketUnbracketedIPv6PassThrough() {
    // Already bracketed: leave alone.
    assertEquals("[::1]:8020", NetUtils.bracketUnbracketedIPv6("[::1]:8020"));
    // IPv4: leave alone.
    assertEquals("127.0.0.1:8020",
        NetUtils.bracketUnbracketedIPv6("127.0.0.1:8020"));
    // Hostname: leave alone.
    assertEquals("nn.example.com:8020",
        NetUtils.bracketUnbracketedIPv6("nn.example.com:8020"));
    // Garbage: leave alone (URI parsing will surface the error).
    assertEquals("not::a:literal",
        NetUtils.bracketUnbracketedIPv6("not::a:literal"));
  }

  @Test
  public void testCreateSocketAddrBracketedIPv6() {
    InetSocketAddress addr = NetUtils.createSocketAddr("[::1]:8020");
    assertEquals(8020, addr.getPort());
    assertFalse(addr.isUnresolved());
    assertTrue(addr.getAddress() instanceof Inet6Address);
    assertEquals("0:0:0:0:0:0:0:1", addr.getAddress().getHostAddress());
  }

  @Test
  public void testCreateSocketAddrBareIPv6IsBracketed() {
    // The fix: bare IPv6 input is auto-bracketed before URI parsing.
    InetSocketAddress addr = NetUtils.createSocketAddr("::1:8020");
    assertEquals(8020, addr.getPort());
    assertFalse(addr.isUnresolved());
    assertTrue(addr.getAddress() instanceof Inet6Address);
  }

  @Test
  public void testCreateSocketAddrIPv6WithScheme() {
    InetSocketAddress addr =
        NetUtils.createSocketAddr("hdfs://[::1]:8020/some/path");
    assertEquals(8020, addr.getPort());
    assertTrue(addr.getAddress() instanceof Inet6Address);
  }

  @Test
  public void testCreateSocketAddrIPv6WithZoneIdStripped() {
    // Zone identifiers are not legal in URI authorities. createSocketAddr
    // strips them; the resulting socket binds the unzoned address.
    InetSocketAddress addr = NetUtils.createSocketAddr("fe80::1%eth0:8020");
    assertEquals(8020, addr.getPort());
  }

  @Test
  public void testGetConnectAddressIPv6Wildcard() throws Exception {
    // Skip if the JVM resolves localhost to an unreachable address.
    InetAddress wildcard = InetAddress.getByName("::");
    assumeFalse(wildcard == null, "JVM cannot resolve ::");
    InetSocketAddress wild = new InetSocketAddress(wildcard, 8020);
    InetSocketAddress connect = NetUtils.getConnectAddress(wild);
    // The returned address must be reachable (not the any-local sentinel).
    assertFalse(connect.getAddress().isAnyLocalAddress(),
        "getConnectAddress should not return the wildcard");
    assertEquals(8020, connect.getPort());
  }

  @Test
  public void testGetConnectAddressIPv4Wildcard() throws Exception {
    InetSocketAddress wild =
        new InetSocketAddress(InetAddress.getByName("0.0.0.0"), 8020);
    InetSocketAddress connect = NetUtils.getConnectAddress(wild);
    assertFalse(connect.getAddress().isAnyLocalAddress());
    assertEquals(8020, connect.getPort());
  }
}
