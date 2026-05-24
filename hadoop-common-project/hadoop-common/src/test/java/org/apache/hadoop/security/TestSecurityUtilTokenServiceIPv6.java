/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;

import org.apache.hadoop.io.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link SecurityUtil#buildTokenService(InetSocketAddress)}
 * produces bracketed IPv6 token service identifiers when
 * {@code hadoop.security.token.service.use_ip=true} (the default), so that
 * the resulting string is a valid URI authority and round-trips cleanly
 * through {@link URI#create}.
 */
public class TestSecurityUtilTokenServiceIPv6 {

  /** Save the flag so tests do not bleed into each other. */
  private boolean savedUseIp;

  @BeforeEach
  public void saveFlag() {
    savedUseIp = SecurityUtil.useIpForTokenService;
  }

  @AfterEach
  public void restoreFlag() {
    SecurityUtil.setTokenServiceUseIp(savedUseIp);
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  /**
   * Build an InetSocketAddress that is resolved to the given numeric IP.
   * Using InetAddress.getByAddress avoids any DNS lookup.
   */
  private static InetSocketAddress resolvedAddr(String numericIp, int port)
      throws Exception {
    InetAddress ia = InetAddress.getByName(numericIp);
    return new InetSocketAddress(ia, port);
  }

  // -----------------------------------------------------------------------
  // Tests
  // -----------------------------------------------------------------------

  /**
   * With useIp=true (the default), an IPv6 loopback address must produce
   * {@code [::1]:8020} — i.e. the IP is bracket-wrapped.
   */
  @Test
  public void testBuildTokenServiceIPv6Bracketed() throws Exception {
    SecurityUtil.setTokenServiceUseIp(true);
    InetSocketAddress addr = resolvedAddr("::1", 8020);
    Text service = SecurityUtil.buildTokenService(addr);
    // JDK Inet6Address.getHostAddress() returns the expanded 8-group
    // form, so the bracketed result is "[0:0:0:0:0:0:0:1]:8020" rather
    // than "[::1]:8020". The bracket-wrapping is what matters for URI
    // round-tripping; HADOOP-XXXXX-F22 (NetUtils.normalizeIp) is the
    // separate fix for canonicalising the inner form.
    String s = service.toString();
    assertTrue(s.startsWith("[") && s.endsWith("]:8020"),
        "IPv6 token service must be bracket-wrapped, was: " + s);
  }

  /**
   * With useIp=true, an IPv4 address must produce {@code 127.0.0.1:8020}
   * unchanged (no brackets added).
   */
  @Test
  public void testBuildTokenServiceIPv4Unchanged() throws Exception {
    SecurityUtil.setTokenServiceUseIp(true);
    InetSocketAddress addr = resolvedAddr("127.0.0.1", 8020);
    Text service = SecurityUtil.buildTokenService(addr);
    assertEquals("127.0.0.1:8020", service.toString(),
        "IPv4 token service must not be bracketed");
  }

  /**
   * With useIp=false the hostname (not the numeric IP) must appear in the
   * service identifier, lower-cased.
   */
  @Test
  public void testBuildTokenServiceUseHostnameWhenUseIpFalse()
      throws Exception {
    SecurityUtil.setTokenServiceUseIp(false);
    // Use a hostname-based address so getHostName() returns what we expect.
    InetSocketAddress addr =
        InetSocketAddress.createUnresolved("namenode.example.com", 8020);
    Text service = SecurityUtil.buildTokenService(addr);
    assertEquals("namenode.example.com:8020", service.toString(),
        "When useIp=false the hostname must be used");
  }

  /**
   * Round-trip: the bracketed IPv6 service string produced by
   * buildTokenService must survive {@code URI.create("dummy://" + service)}
   * without throwing, and the URI's host must be the unbracketed IPv6
   * literal.
   */
  @Test
  public void testIPv6ServiceStringRoundTripViaURI() throws Exception {
    SecurityUtil.setTokenServiceUseIp(true);
    InetSocketAddress addr = resolvedAddr("::1", 8020);
    Text service = SecurityUtil.buildTokenService(addr);

    // URI.create must not throw on a bracketed IPv6 authority.
    URI uri = assertDoesNotThrow(
        () -> URI.create("dummy://" + service.toString()),
        "URI.create must not throw for bracketed IPv6 service string");

    assertNotNull(uri);
    // URI.getHost() preserves the brackets for IPv6 (per Java URI),
    // and returns the JDK-default expanded form. The contract being
    // verified here is that URI.create did NOT throw.
    String h = uri.getHost();
    assertTrue(h != null && h.startsWith("[") && h.endsWith("]"),
        "URI host must be a bracketed IPv6 literal, was: " + h);
    assertEquals(8020, uri.getPort(),
        "URI port must round-trip correctly");
  }
}
