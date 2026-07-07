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

import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.util.TrackingUriPlugin;
import org.apache.hadoop.yarn.webapp.util.WebAppUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.List;

import static org.apache.hadoop.yarn.util.StringHelper.ujoin;

public class ProxyUriUtils {
  @SuppressWarnings("unused")
  private static final Logger LOG = LoggerFactory.getLogger(
      ProxyUriUtils.class);

  /**Name of the servlet to use when registering the proxy servlet. */
  public static final String PROXY_SERVLET_NAME = "proxy";
  /**Base path where the proxy servlet will handle requests.*/
  public static final String PROXY_BASE = "/proxy/";
  /**Path component added when the proxy redirects the connection.*/
  public static final String REDIRECT = "redirect/";
  /**Path Specification for the proxy servlet.*/
  public static final String PROXY_PATH_SPEC = PROXY_BASE+"*";
  /**Query Parameter indicating that the URI was approved.*/
  public static final String PROXY_APPROVAL_PARAM = "proxyapproved";
  
  private static String uriEncode(Object o) {
    try {
      assert (o != null) : "o cannot be null";
      return URLEncoder.encode(o.toString(), "UTF-8");
    } catch (UnsupportedEncodingException e) {
      //This should never happen
      throw new RuntimeException("UTF-8 is not supported by this system?", e);
    }
  }
  
  /**
   * Get the proxied path for an application.
   *
   * @param id the application id to use
   * @return the base path to that application through the proxy
   */
  public static String getPath(ApplicationId id) {
    return getPath(id, false);
  }

  /**
   * Get the proxied path for an application.
   *
   * @param id the application id to use
   * @param redirected whether the path should contain the redirect component
   * @return the base path to that application through the proxy
   */
  public static String getPath(ApplicationId id, boolean redirected) {
    if (id == null) {
      throw new IllegalArgumentException("Application id cannot be null ");
    }

    if (redirected) {
      return ujoin(PROXY_BASE, REDIRECT, uriEncode(id));
    } else {
      return ujoin(PROXY_BASE, uriEncode(id));
    }
  }

  /**
   * Get the proxied path for an application.
   *
   * @param id the application id to use
   * @param path the rest of the path to the application
   * @return the base path to that application through the proxy
   */
  public static String getPath(ApplicationId id, String path) {
    return getPath(id, path, false);
  }

  /**
   * Get the proxied path for an application.
   *
   * @param id the application id to use
   * @param path the rest of the path to the application
   * @param redirected whether the path should contain the redirect component
   * @return the base path to that application through the proxy
   */
  public static String getPath(ApplicationId id, String path,
      boolean redirected) {
    if (path == null) {
      return getPath(id, redirected);
    } else {
      return ujoin(getPath(id, redirected), path);
    }
  }
  
  /**
   * Get the proxied path for an application
   * @param id the id of the application
   * @param path the path of the application.
   * @param query the query parameters
   * @param approved true if the user has approved accessing this app.
   * @return the proxied path for this app.
   */
  public static String getPathAndQuery(ApplicationId id, String path, 
      String query, boolean approved) {
    StringBuilder newp = new StringBuilder();
    newp.append(getPath(id, path));
    boolean first = appendQuery(newp, query, true);
    if(approved) {
      appendQuery(newp, PROXY_APPROVAL_PARAM+"=true", first);
    }
    return newp.toString();
  }
  
  private static boolean appendQuery(StringBuilder builder, String query, 
      boolean first) {
    if(query != null && !query.isEmpty()) {
      if(first && !query.startsWith("?")) {
        builder.append('?');
      }
      if(!first && !query.startsWith("&")) {
        builder.append('&');
      }
      builder.append(query);
      return false;
    }
    return first;
  }
  
  /**
   * Get a proxied URI for the original URI.
   * @param originalUri the original URI to go through the proxy, or null if
   * a default path "/" can be used. 
   * @param proxyUri the URI of the proxy itself, scheme, host and port are used.
   * @param id the id of the application
   * @return the proxied URI
   */
  public static URI getProxyUri(URI originalUri, URI proxyUri,
      ApplicationId id) {
    try {
      String path = getPath(id, originalUri == null ? "/" : originalUri.getPath());
      return new URI(proxyUri.getScheme(), proxyUri.getAuthority(), path,
          originalUri == null ? null : originalUri.getQuery(),
          originalUri == null ? null : originalUri.getFragment());
    } catch (URISyntaxException e) {
      throw new RuntimeException("Could not proxy "+originalUri, e);
    }
  }
  
  /**
   * Create a URI from a no-scheme URL such as is returned by the AM.
   *
   * <p>An AM may report a tracking URL without a scheme, e.g.
   * {@code host:port/path} or, for IPv6, {@code fd00::1:8088/path}.  When
   * the host is a bare IPv6 literal the authority portion must be bracketed
   * before the string is fed to {@link URI#URI(String)} because the
   * single-argument constructor rejects unbracketed IPv6 literals in the
   * authority ({@code new URI("http://fd00::1:8088/")} throws
   * {@link URISyntaxException}).
   *
   * @param scheme   the scheme prefix to prepend when the URL has none
   *                 (e.g. {@code "http://"})
   * @param noSchemeUrl the URL as reported by the AM, with or without a scheme
   * @return a URI with a scheme
   * @throws URISyntaxException if the url is not formatted correctly.
   */
  public static URI getUriFromAMUrl(String scheme, String noSchemeUrl)
      throws URISyntaxException {
    /*
     * Whether or not the AM reported a scheme, a bare IPv6 literal in the
     * authority must be bracketed before the string is handed to
     * new URI(String), which otherwise rejects it (see bracketAuthorityInUrl).
     */
    if (getSchemeFromUrl(noSchemeUrl).isEmpty()) {
      // No scheme reported by the AM (the common case): prepend the configured
      // scheme, then bracket the authority.
      return new URI(bracketAuthorityInUrl(scheme + noSchemeUrl));
    } else {
      // AM reported its own scheme (honour it), but still bracket the authority.
      return new URI(bracketAuthorityInUrl(noSchemeUrl));
    }
  }

  /**
   * Bracket a bare IPv6 literal in the authority portion of a URL so that
   * {@link URI#URI(String)} accepts it. Handles URLs with or without a
   * scheme, e.g. {@code http://fd00::1:8088/path} or {@code fd00::1:8088/path}.
   * The authority runs from just after {@code ://} (or the start of the
   * string) up to the first {@code /}. This is a no-op for IPv4 literals,
   * hostnames, and already-bracketed IPv6 literals.
   *
   * @param url a URL string, with or without a scheme
   * @return the same URL with any bare IPv6 authority bracketed
   */
  private static String bracketAuthorityInUrl(String url) {
    int schemeEnd = url.indexOf("://");
    int authStart = (schemeEnd >= 0) ? schemeEnd + 3 : 0;
    String prefix = url.substring(0, authStart);
    String remainder = url.substring(authStart);
    // The authority ends at the first '/', '?' or '#'. A query or fragment can
    // legitimately appear before any path (e.g. "host:port?a=b"); if we did not
    // stop at those, the query/fragment would be folded into the authority and
    // encodeHostPortForPathSegment would mangle it.
    int boundary = -1;
    for (int i = 0; i < remainder.length(); i++) {
      char c = remainder.charAt(i);
      if (c == '/' || c == '?' || c == '#') {
        boundary = i;
        break;
      }
    }
    String authority = (boundary >= 0)
        ? remainder.substring(0, boundary)
        : remainder;
    String rest = (boundary >= 0) ? remainder.substring(boundary) : "";
    return prefix + WebAppUtils.encodeHostPortForPathSegment(authority) + rest;
  }

  /**
   * Returns the first valid tracking link, if any, from the given id from the
   * given list of plug-ins, if any.
   * 
   * @param id the id of the application for which the tracking link is desired
   * @param trackingUriPlugins list of plugins from which to get the tracking link
   * @return the desired link if possible, otherwise null
   * @throws URISyntaxException
   */
  public static URI getUriFromTrackingPlugins(ApplicationId id,
      List<TrackingUriPlugin> trackingUriPlugins)
      throws URISyntaxException {
    URI toRet = null;
    for(TrackingUriPlugin plugin : trackingUriPlugins)
    {
      toRet = plugin.getTrackingUri(id);
      if (toRet != null)
      {
        return toRet;
      }
    }
    return null;
  }
  
  /**
   * Returns the scheme if present in the url
   * eg. "https://issues.apache.org/jira/browse/YARN" {@literal ->} "https"
   */
  public static String getSchemeFromUrl(String url) {
    int index = 0;
    if (url != null) {
      index = url.indexOf("://");
    }
    if (index > 0) {
      return url.substring(0, index);
    } else {
      return "";
    }
  }

}
