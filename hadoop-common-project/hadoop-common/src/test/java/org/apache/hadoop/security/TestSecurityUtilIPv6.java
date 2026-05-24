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
package org.apache.hadoop.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Inet6Address;
import java.net.InetAddress;

import org.junit.jupiter.api.Test;

/**
 * Asserts that SecurityUtil's host resolver and `_HOST` substitution
 * handle IPv6 inputs without producing UnknownHostException or
 * malformed Kerberos principals.
 *
 * Run with:  mvn -P ipv6-test -Dtest=TestSecurityUtilIPv6 test
 */
public class TestSecurityUtilIPv6 {

  @Test
  public void testQualifiedHostResolverAcceptsBracketedIPv6()
      throws Exception {
    SecurityUtil.QualifiedHostResolver r =
        new SecurityUtil.QualifiedHostResolver();
    InetAddress addr = r.resolve("[::1]");
    assertNotNull(addr);
    assertTrue(addr instanceof Inet6Address,
        "expected Inet6Address for [::1], got " + addr);
    // The hostname stored on the InetAddress should be the unbracketed
    // form so callers that emit getHostName() into config do not
    // re-introduce the bracket through later concat.
    assertEquals("::1", addr.getHostName());
  }

  @Test
  public void testQualifiedHostResolverAcceptsBareIPv6() throws Exception {
    SecurityUtil.QualifiedHostResolver r =
        new SecurityUtil.QualifiedHostResolver();
    InetAddress addr = r.resolve("::1");
    assertTrue(addr instanceof Inet6Address);
  }

  @Test
  public void testQualifiedHostResolverAcceptsIPv4() throws Exception {
    // Backwards compat: existing IPv4-numeric input must still work
    // unchanged (no bracket processing).
    SecurityUtil.QualifiedHostResolver r =
        new SecurityUtil.QualifiedHostResolver();
    InetAddress addr = r.resolve("127.0.0.1");
    assertNotNull(addr);
    assertEquals("127.0.0.1", addr.getHostName());
  }

  @Test
  public void testGetServerPrincipalRejectsBracketedIPv6Host()
      throws Exception {
    // _HOST substitution must not accept a bracketed IPv6 literal as
    // the hostname; the KDC has no principal entry for that form.
    // Falling back to the local FQDN is the correct behavior.
    String principal =
        SecurityUtil.getServerPrincipal("nn/_HOST@EXAMPLE.COM", "[::1]");
    assertTrue(principal.startsWith("nn/"),
        "principal must start with service: " + principal);
    assertTrue(!principal.contains("[::1]"),
        "principal must not embed bracketed IPv6: " + principal);
    assertTrue(principal.endsWith("@EXAMPLE.COM"),
        "principal must keep realm: " + principal);
  }

  @Test
  public void testGetServerPrincipalRejectsBareIPv6Host() throws Exception {
    String principal =
        SecurityUtil.getServerPrincipal("nn/_HOST@EXAMPLE.COM", "fd00::1");
    assertTrue(!principal.contains("fd00::1"),
        "principal must not embed bare IPv6: " + principal);
  }

  @Test
  public void testGetServerPrincipalAcceptsIPv4HostUnchanged()
      throws Exception {
    // IPv4 numeric hosts (one or zero colons) are passed through to
    // preserve the historical contract.
    String principal =
        SecurityUtil.getServerPrincipal("nn/_HOST@EXAMPLE.COM", "1.2.3.4");
    assertEquals("nn/1.2.3.4@EXAMPLE.COM", principal);
  }

  @Test
  public void testGetServerPrincipalAcceptsHostnameUnchanged()
      throws Exception {
    String principal = SecurityUtil.getServerPrincipal(
        "nn/_HOST@EXAMPLE.COM", "nn.example.com");
    assertEquals("nn/nn.example.com@EXAMPLE.COM", principal);
  }
}
