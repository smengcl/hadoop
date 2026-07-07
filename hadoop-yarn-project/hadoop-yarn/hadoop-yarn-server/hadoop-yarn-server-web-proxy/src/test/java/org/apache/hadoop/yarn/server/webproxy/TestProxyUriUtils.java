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

package org.apache.hadoop.yarn.server.webproxy;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.apache.hadoop.util.Lists;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.server.utils.BuilderUtils;
import org.apache.hadoop.yarn.util.TrackingUriPlugin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class TestProxyUriUtils {
  @Test
  void testGetPathApplicationId() {
    assertEquals("/proxy/application_100_0001",
        ProxyUriUtils.getPath(BuilderUtils.newApplicationId(100l, 1)));
    assertEquals("/proxy/application_6384623_0005",
        ProxyUriUtils.getPath(BuilderUtils.newApplicationId(6384623l, 5)));
  }

  @Test
  void testGetPathApplicationIdBad() {
    assertThrows(IllegalArgumentException.class, () -> {
      ProxyUriUtils.getPath(null);
    });
  }

  @Test
  void testGetPathApplicationIdString() {
    assertEquals("/proxy/application_6384623_0005",
        ProxyUriUtils.getPath(BuilderUtils.newApplicationId(6384623l, 5), null));
    assertEquals("/proxy/application_6384623_0005/static/app",
        ProxyUriUtils.getPath(BuilderUtils.newApplicationId(6384623l, 5), "/static/app"));
    assertEquals("/proxy/application_6384623_0005/",
        ProxyUriUtils.getPath(BuilderUtils.newApplicationId(6384623l, 5), "/"));
    assertEquals("/proxy/application_6384623_0005/some/path",
        ProxyUriUtils.getPath(BuilderUtils.newApplicationId(6384623l, 5), "some/path"));
  }

  @Test
  void testGetPathAndQuery() {
    assertEquals("/proxy/application_6384623_0005/static/app?foo=bar",
        ProxyUriUtils.getPathAndQuery(BuilderUtils.newApplicationId(6384623l, 5), "/static/app",
            "?foo=bar", false));

    assertEquals("/proxy/application_6384623_0005/static/app?foo=bar&bad=good&proxyapproved=true",
        ProxyUriUtils.getPathAndQuery(BuilderUtils.newApplicationId(6384623l, 5), "/static/app",
            "foo=bar&bad=good", true));
  }

  @Test
  void testGetProxyUri() throws Exception {
    URI originalUri = new URI("http://host.com/static/foo?bar=bar");
    URI proxyUri = new URI("http://proxy.net:8080/");
    ApplicationId id = BuilderUtils.newApplicationId(6384623l, 5);
    URI expected = new URI("http://proxy.net:8080/proxy/application_6384623_0005/static/foo?bar=bar");
    URI result = ProxyUriUtils.getProxyUri(originalUri, proxyUri, id);
    assertEquals(expected, result);
  }


  @Test
  void testGetProxyUriNull() throws Exception {
    URI originalUri = null;
    URI proxyUri = new URI("http://proxy.net:8080/");
    ApplicationId id = BuilderUtils.newApplicationId(6384623l, 5);
    URI expected = new URI("http://proxy.net:8080/proxy/application_6384623_0005/");
    URI result = ProxyUriUtils.getProxyUri(originalUri, proxyUri, id);
    assertEquals(expected, result);
  }

  @Test
  void testGetProxyUriFromPluginsReturnsNullIfNoPlugins()
      throws URISyntaxException {
    ApplicationId id = BuilderUtils.newApplicationId(6384623l, 5);
    List<TrackingUriPlugin> list =
        Lists.newArrayListWithExpectedSize(0);
    assertNull(ProxyUriUtils.getUriFromTrackingPlugins(id, list));
  }

  @Test
  void testGetProxyUriFromPluginsReturnsValidUriWhenAble()
      throws URISyntaxException {
    ApplicationId id = BuilderUtils.newApplicationId(6384623l, 5);
    List<TrackingUriPlugin> list =
        Lists.newArrayListWithExpectedSize(2);
    // Insert a plugin that returns null.
    list.add(new TrackingUriPlugin() {
      public URI getTrackingUri(ApplicationId id) throws URISyntaxException {
        return null;
      }
    });
    // Insert a plugin that returns a valid URI.
    list.add(new TrackingUriPlugin() {
      public URI getTrackingUri(ApplicationId id) throws URISyntaxException {
        return new URI("http://history.server.net/");
      }
    });
    URI result = ProxyUriUtils.getUriFromTrackingPlugins(id, list);
    assertNotNull(result);

  }

  // -----------------------------------------------------------------------
  // IPv6 tests (HADOOP-17843)
  // -----------------------------------------------------------------------

  /**
   * An AM reporting a bare IPv6 tracking URL without a scheme must be
   * accepted by {@link ProxyUriUtils#getUriFromAMUrl} and must produce a
   * syntactically valid URI whose authority contains a bracketed IPv6 host.
   *
   * <p>Before the fix, {@code new URI("http://fd00:dead:beef::21:8088/foo")}
   * threw {@link URISyntaxException} because the IPv6 literal was not
   * bracketed.
   */
  @Test
  void testGetUriFromAMUrlIPv6NoBrackets() throws URISyntaxException {
    // AM-reported URL: no scheme, bare IPv6 literal as host
    String noSchemeUrl = "fd00:dead:beef::21:8088/foo/bar";
    URI result = ProxyUriUtils.getUriFromAMUrl("http://", noSchemeUrl);

    assertNotNull(result, "URI must not be null");
    // The URI constructor must not have thrown, and the result must be valid
    assertDoesNotThrow(() -> URI.create(result.toString()),
        "Produced URI must be syntactically valid");

    // Host in a URI authority for an IPv6 literal is wrapped in brackets
    String host = result.getHost();
    assertNotNull(host, "URI host must not be null");
    // URI.getHost() strips the brackets; we just verify it is non-empty and
    // contains at least one colon (confirming it is IPv6, not a hostname)
    assertTrue(host.contains(":"),
        "URI host should be an IPv6 literal, got: " + host);

    // The string representation must use the bracketed form so it is a
    // valid RFC-3986 authority
    assertTrue(result.toString().contains("["),
        "URI string must bracket the IPv6 host, got: " + result);

    assertEquals(8088, result.getPort(),
        "Port must be preserved");
    assertEquals("/foo/bar", result.getPath(),
        "Path must be preserved");
  }

  /**
   * A bare IPv6 AM URL whose query string appears before any path (i.e. no
   * '/' after the authority) must still bracket only the host:port and keep
   * the query intact, not fold the query into the authority.
   */
  @Test
  void testGetUriFromAMUrlIPv6QueryBeforeSlash() throws URISyntaxException {
    String noSchemeUrl = "fd00:dead:beef::21:8088?app=1&x=2";
    URI result = ProxyUriUtils.getUriFromAMUrl("http://", noSchemeUrl);

    assertNotNull(result);
    assertDoesNotThrow(() -> URI.create(result.toString()),
        "Produced URI must be syntactically valid");
    assertEquals(8088, result.getPort(), "Port must be preserved");
    assertEquals("app=1&x=2", result.getQuery(), "Query must be preserved");
    assertTrue(result.toString().contains("["),
        "URI string must bracket the IPv6 host, got: " + result);
  }

  /**
   * A pre-bracketed IPv6 AM URL must pass through unchanged.
   */
  @Test
  void testGetUriFromAMUrlIPv6AlreadyBracketed() throws URISyntaxException {
    String noSchemeUrl = "[fd00:dead:beef::21]:8088/foo/bar";
    URI result = ProxyUriUtils.getUriFromAMUrl("http://", noSchemeUrl);

    assertNotNull(result);
    assertDoesNotThrow(() -> URI.create(result.toString()));
    assertEquals(8088, result.getPort());
    assertEquals("/foo/bar", result.getPath());
    // URI.getHost() returns the host without brackets
    assertTrue(result.getHost().contains(":"),
        "URI host should be an IPv6 literal");
  }

  /**
   * An AM reporting an IPv6 tracking URL with a query string must preserve
   * both the path and the query parameters.
   */
  @Test
  void testGetUriFromAMUrlIPv6WithQuery() throws URISyntaxException {
    String noSchemeUrl = "fd00:dead:beef::21:8088/foo/bar?a=b&c=d";
    URI result = ProxyUriUtils.getUriFromAMUrl("http://", noSchemeUrl);

    assertNotNull(result);
    assertDoesNotThrow(() -> URI.create(result.toString()),
        "Produced URI must be syntactically valid");
    assertTrue(result.toString().contains("["),
        "URI string must bracket the IPv6 host");
    assertEquals(8088, result.getPort(), "Port must be preserved");
    assertEquals("/foo/bar", result.getPath(), "Path must be preserved");
    assertEquals("a=b&c=d", result.getQuery(), "Query must be preserved");
  }

  /**
   * {@link ProxyUriUtils#getProxyUri} must produce a valid URI when the proxy
   * URI contains an IPv6 host.  The proxy URI uses the bracketed form in its
   * authority, which is a standard URI; this test confirms getProxyUri passes
   * the authority through unchanged.
   */
  @Test
  void testGetProxyUriIPv6ProxyHost() throws Exception {
    URI originalUri = new URI("http://[fd00:dead:beef::21]:8088/static/foo?bar=bar");
    URI proxyUri = new URI("http://[fd00::1]:8080/");
    ApplicationId id = BuilderUtils.newApplicationId(6384623L, 5);

    URI result = ProxyUriUtils.getProxyUri(originalUri, proxyUri, id);

    assertNotNull(result);
    assertDoesNotThrow(() -> URI.create(result.toString()),
        "Produced URI must be syntactically valid");
    // Scheme, host, and port must come from the proxy URI
    assertEquals("http", result.getScheme());
    assertEquals("[fd00::1]:8080", result.getAuthority());
    assertTrue(result.getPath().startsWith("/proxy/application_6384623_0005"),
        "Path must be rooted under the proxy path");
    assertEquals("bar=bar", result.getQuery(), "Query must be preserved");
  }

  /**
   * {@link ProxyUriUtils#getUriFromAMUrl} must not regress for standard
   * IPv4 / hostname inputs.
   */
  @Test
  void testGetUriFromAMUrlIPv4Unchanged() throws URISyntaxException {
    URI result = ProxyUriUtils.getUriFromAMUrl("http://", "1.2.3.4:8088/app");
    assertEquals("http://1.2.3.4:8088/app", result.toString());
    assertEquals("/app", result.getPath());
    assertEquals(8088, result.getPort());
  }

  @Test
  void testGetUriFromAMUrlHostnameUnchanged() throws URISyntaxException {
    URI result = ProxyUriUtils.getUriFromAMUrl("http://", "myhost.example.com:8088/app/1");
    assertEquals("http://myhost.example.com:8088/app/1", result.toString());
  }
}
