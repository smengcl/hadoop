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

package org.apache.hadoop.yarn.client.api.impl;

import java.net.URI;

import org.junit.jupiter.api.Test;

import org.apache.hadoop.yarn.conf.YarnConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that {@link TimelineConnector#constructResURI} produces a valid
 * RFC 3986 URI when the timeline server address is an IPv6 literal. Before
 * HADOOP-XXXXX-F15 the bare {@code host:port} string was concatenated
 * straight into {@code URI.create}, which throws for an IPv6 address.
 */
public class TestTimelineConnectorIPv6 {

  private static final String PATH = "/ws/v2/timeline/";

  @Test
  public void testConstructResURIBracketsIPv6Address() {
    YarnConfiguration conf = new YarnConfiguration();
    // A bare IPv6 authority "fd00::1:8188" would break URI.create without
    // bracketing. The host must come back bracketed.
    URI uri = TimelineConnector.constructResURI(conf, "fd00::1:8188", PATH);
    assertEquals("http", uri.getScheme());
    // URI.getHost() returns the bracketed form for an IPv6 literal on JDK 17.
    assertEquals("[fd00::1]", uri.getHost());
    assertEquals(8188, uri.getPort());
    assertEquals(PATH, uri.getPath());
  }

  @Test
  public void testConstructResURIBracketedIPv6Idempotent() {
    YarnConfiguration conf = new YarnConfiguration();
    URI uri = TimelineConnector.constructResURI(conf, "[fd00::1]:8188", PATH);
    assertEquals("[fd00::1]", uri.getHost());
    assertEquals(8188, uri.getPort());
  }

  @Test
  public void testConstructResURIHostnameUnchanged() {
    YarnConfiguration conf = new YarnConfiguration();
    URI uri = TimelineConnector.constructResURI(conf, "ats.example.com:8188",
        PATH);
    assertEquals("ats.example.com", uri.getHost());
    assertEquals(8188, uri.getPort());
    assertEquals(PATH, uri.getPath());
  }

  @Test
  public void testConstructResURIIPv4Unchanged() {
    YarnConfiguration conf = new YarnConfiguration();
    URI uri = TimelineConnector.constructResURI(conf, "127.0.0.1:8188", PATH);
    assertEquals("127.0.0.1", uri.getHost());
    assertEquals(8188, uri.getPort());
  }
}
