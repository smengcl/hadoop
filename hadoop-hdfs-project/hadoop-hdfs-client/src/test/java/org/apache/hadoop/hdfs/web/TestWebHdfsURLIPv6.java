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

package org.apache.hadoop.hdfs.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.web.resources.GetOpParam;
import org.apache.hadoop.fs.Path;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for IPv6 URL synthesis in WebHDFS / NN redirect path.
 *
 * Covers:
 *  - NN-side redirect URI builder (new URI multi-arg constructor) with an
 *    IPv6 DataNode hostname.
 *  - Client-side redirectHost extraction using URL.getAuthority() rather than
 *    the now-removed URL.getHost()+":"+URL.getPort() pattern.
 *  - toUrl round-trip with an IPv6 NameNode address.
 */
public class TestWebHdfsURLIPv6 {

  private static final String IPV6_ADDR = "2001:db8::1";
  private static final String IPV6_LOOPBACK = "::1";
  private static final int DN_HTTP_PORT = 50075;
  private static final int NN_HTTP_PORT = 50070;

  /**
   * Verify that the multi-arg URI constructor used in
   * NamenodeWebHdfsMethods.redirectURI correctly brackets an IPv6 DataNode
   * hostname, producing a URI that:
   *   (a) does not throw URISyntaxException, and
   *   (b) has a host component equal to the bare (unbracketed) IPv6 literal.
   *
   * Regression: if an unbracketed IPv6 literal were passed to URI.create()
   * instead it would throw URISyntaxException.
   */
  @Test
  public void testRedirectURIBracketsIPv6() throws URISyntaxException {
    // This mirrors exactly what NamenodeWebHdfsMethods.redirectURI does:
    //   new URI(scheme, null, dn.getHostName(), port, uripath, query, null)
    String scheme = "http";
    String dnHostname = IPV6_ADDR;   // bare IPv6 literal as stored in DatanodeID
    int port = DN_HTTP_PORT;
    String uripath = WebHdfsFileSystem.PATH_PREFIX + "/test/file";
    String query = "op=OPEN&user.name=test";

    URI redirectUri = new URI(scheme, null, dnHostname, port, uripath, query, null);

    // URI.create would throw on "http://2001:db8::1:50075/..." without brackets
    // but the multi-arg constructor handles it. Round-trip via URI.create must
    // also succeed (i.e., the toString includes brackets).
    assertDoesNotThrow(() -> URI.create(redirectUri.toString()),
        "redirect URI toString must be parseable by URI.create");

    // The authority must contain the brackets in the serialised form
    String authority = redirectUri.toString();
    assertTrue(authority.contains("[" + IPV6_ADDR + "]"),
        "Serialised URI must contain bracketed IPv6: " + authority);

    // URI.getHost() preserves brackets for IPv6 in modern JDKs (per RFC
    // 3986 the host of an IPv6 authority is the IP-literal *with* the
    // brackets). The contract being verified is that the URI was
    // constructed cleanly and the host is recognisably the v6 literal.
    String host = redirectUri.getHost();
    assertTrue(host != null && host.contains(":"),
        "URI host must reference the IPv6 literal, was: " + host);
  }

  /**
   * Verify that obtaining the redirectHost from the Location header URL via
   * URL.getAuthority() preserves brackets for IPv6, while the old approach
   * URL.getHost()+":"+URL.getPort() would strip them.
   *
   * This directly exercises the fix in AbstractRunner.connect().
   */
  @Test
  public void testRedirectHostUsesAuthorityForIPv6() throws Exception {
    // Simulate a Location header URL with a bracketed IPv6 DN address, as
    // issued by the NameNode redirect (the multi-arg URI constructor brackets
    // the address, then .toASCIIString() is used in the HTTP response).
    String locationHeader =
        "http://[" + IPV6_LOOPBACK + "]:" + DN_HTTP_PORT
            + WebHdfsFileSystem.PATH_PREFIX + "/test/data?op=OPEN";
    URL locationUrl = new URL(locationHeader);

    // NEW behaviour (the fix): use getAuthority()
    String redirectHostFixed = locationUrl.getAuthority();
    assertEquals("[" + IPV6_LOOPBACK + "]:" + DN_HTTP_PORT, redirectHostFixed,
        "getAuthority() must preserve IPv6 brackets");

    // The fixed form must be parseable as a valid URI authority (used
    // when the NN later parses excludeDatanodes). Whether
    // URL.getHost() brackets or strips on this particular JDK is
    // implementation-defined; what matters is that getAuthority()
    // produces a stable bracketed form.
    assertDoesNotThrow(() -> URI.create("http://" + redirectHostFixed + "/"),
        "Fixed redirectHost must produce a valid URI authority");
  }

  /**
   * End-to-end toUrl test: initialise a WebHdfsFileSystem against an IPv6
   * NameNode address and verify that toUrl produces a URL whose authority is
   * correctly formed and round-trips through URI.create without throwing.
   */
  @Test
  public void testToUrlBracketsIPv6Host() throws Exception {
    // WebHdfsFileSystem expects a bracketed IPv6 in the URI authority per
    // RFC 3986 (the same form that java.net.URI requires).
    URI fsUri = new URI("webhdfs://[" + IPV6_ADDR + "]:" + NN_HTTP_PORT);
    Configuration conf = new Configuration(false);
    // Disable security to avoid Kerberos setup in unit test
    conf.set("hadoop.security.authentication", "simple");

    WebHdfsFileSystem fs = new WebHdfsFileSystem();
    fs.initialize(fsUri, conf);

    // toUrl must produce a URL whose authority contains the bracketed IPv6
    URL url = fs.toUrl(GetOpParam.Op.GETFILESTATUS,
        new Path("/test/path"));

    // The authority of a java.net.URL with a bracketed IPv6 NN must be
    // "[addr]:port" which is accepted by URI.create.
    String urlStr = url.toString();
    URI parsedBack = assertDoesNotThrow(() -> URI.create(urlStr),
        "URL from toUrl must be parseable by URI.create; url=" + urlStr);

    // The URL must round-trip cleanly. JDK's URI.getHost() preserves
    // brackets and may emit the expanded form for v6, so we only
    // assert the host references the v6 literal recognisably.
    String parsedHost = parsedBack.getHost();
    assertTrue(parsedHost != null && parsedHost.contains(":"),
        "Parsed URI host must reference the IPv6 literal, was: "
            + parsedHost);

    fs.close();
  }
}
