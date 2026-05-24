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

import java.net.InetAddress;

import org.junit.jupiter.api.Test;

/**
 * Reverse-DNS name construction for IPv4 (in-addr.arpa) and IPv6 (ip6.arpa).
 */
public class TestDNSReverseIPv6 {

  @Test
  public void testReverseNameIPv4() throws Exception {
    InetAddress addr = InetAddress.getByName("1.2.3.4");
    assertEquals("4.3.2.1.in-addr.arpa", DNS.buildReverseDnsName(addr));
  }

  @Test
  public void testReverseNameIPv4Loopback() throws Exception {
    InetAddress addr = InetAddress.getByName("127.0.0.1");
    assertEquals("1.0.0.127.in-addr.arpa", DNS.buildReverseDnsName(addr));
  }

  @Test
  public void testReverseNameIPv6Loopback() throws Exception {
    InetAddress addr = InetAddress.getByName("::1");
    // RFC 3596 example: ::1 reverses to 1.0.0.0...0.0.0.0.ip6.arpa
    assertEquals(
        "1.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0."
            + "0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.ip6.arpa",
        DNS.buildReverseDnsName(addr));
  }

  @Test
  public void testReverseNameIPv6Documentation() throws Exception {
    // 2001:db8::1 -> nibble-reversed
    InetAddress addr = InetAddress.getByName("2001:db8::1");
    String reversed = DNS.buildReverseDnsName(addr);
    assertEquals(
        "1.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0."
            + "0.0.0.0.0.0.0.0.8.b.d.0.1.0.0.2.ip6.arpa",
        reversed);
  }

  @Test
  public void testReverseNameIPv6LinkLocal() throws Exception {
    // fe80::1 - exercises high-nibble (0xfe) byte; output must be
    // lowercase per Character.forDigit.
    InetAddress addr = InetAddress.getByName("fe80::1");
    assertEquals(
        "1.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0."
            + "0.0.0.0.0.0.0.0.0.0.0.0.0.8.e.f.ip6.arpa",
        DNS.buildReverseDnsName(addr));
  }

  @Test
  public void testReverseNameIPv4MappedRoutesToInAddrArpa() throws Exception {
    // ::ffff:1.2.3.4 carries the IPv4 octets in the low 4 bytes; the
    // PTR record convention is to look these up under in-addr.arpa,
    // not the nibble-reversed ip6.arpa. Pre-fix code (split on ".")
    // happened to do this; the new byte-driven builder must match.
    InetAddress addr = InetAddress.getByName("::ffff:1.2.3.4");
    assertEquals("4.3.2.1.in-addr.arpa", DNS.buildReverseDnsName(addr));
  }

  @Test
  public void testReverseNameIPv4CompatibleStaysOnIp6Arpa() throws Exception {
    // RFC 4291 deprecated the IPv4-compatible form (::a.b.c.d). It
    // shares its byte layout with the unspecified (::) and loopback
    // (::1) addresses, so we deliberately do NOT route it to
    // in-addr.arpa - the nibble-reversed ip6.arpa form is the
    // unambiguous answer.
    InetAddress addr = InetAddress.getByName("::1.2.3.4");
    assertEquals(
        "4.0.3.0.2.0.1.0.0.0.0.0.0.0.0.0."
            + "0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.ip6.arpa",
        DNS.buildReverseDnsName(addr));
  }
}
