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

package org.apache.hadoop.yarn.webapp.util;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;

import org.apache.hadoop.yarn.conf.YarnConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for IPv6 address handling in WebAppUtils.
 */
public class TestWebAppUtilsIPv6 {

  /**
   * getResolvedAddress must bracket an IPv6 literal host.
   * fd00::1 is a global ULA address – NOT loopback/anylocal –
   * so the else-branch (address.getHostName()) is exercised.
   */
  @Test
  public void testGetResolvedAddressBracketsIPv6() throws Exception {
    InetSocketAddress addr =
        new InetSocketAddress(InetAddress.getByName("fd00::1"), 8088);
    String result = WebAppUtils.getResolvedAddress(addr);
    assertTrue(result.startsWith("[") && result.endsWith("]:8088"),
        "Expected bracketed IPv6 authority like [fd00::1]:8088 but got: " + result);
  }

  /**
   * getHttpSchemePrefixedURL must bracket bare IPv6, leave IPv4 and
   * hostnames unchanged (apart from prepending the scheme).
   */
  @Test
  public void testGetHttpSchemePrefixedURLWithIPv6() {
    YarnConfiguration conf = new YarnConfiguration();
    // bare IPv6
    String result = WebAppUtils.getHttpSchemePrefixedURL(conf, "fd00::1:8088");
    assertTrue(result.startsWith("http://[") && result.endsWith("]:8088"),
        "Expected http://[...]:8088 but got: " + result);
  }

  @Test
  public void testGetHttpSchemePrefixedURLWithHostname() {
    YarnConfiguration conf = new YarnConfiguration();
    String result = WebAppUtils.getHttpSchemePrefixedURL(conf, "host.example:8088");
    assertEquals("http://host.example:8088", result);
  }

  @Test
  public void testGetHttpSchemePrefixedURLWithIPv4() {
    YarnConfiguration conf = new YarnConfiguration();
    String result = WebAppUtils.getHttpSchemePrefixedURL(conf, "127.0.0.1:8088");
    assertEquals("http://127.0.0.1:8088", result);
  }

  /**
   * setRMWebAppPort must preserve the IPv6 host and update only the port,
   * keeping the result bracketed.
   */
  @Test
  public void testSetRMWebAppPortPreservesIPv6Host() {
    YarnConfiguration conf = new YarnConfiguration();
    conf.set(YarnConfiguration.RM_WEBAPP_ADDRESS, "[fd00::1]:8088");
    WebAppUtils.setRMWebAppPort(conf, 9999);
    String updated = conf.get(YarnConfiguration.RM_WEBAPP_ADDRESS);
    assertEquals("[fd00::1]:9999", updated,
        "Expected [fd00::1]:9999 after port update but got: " + updated);
  }
}
