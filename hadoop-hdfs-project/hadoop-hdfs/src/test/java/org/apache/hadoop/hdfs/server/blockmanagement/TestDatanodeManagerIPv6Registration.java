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
package org.apache.hadoop.hdfs.server.blockmanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for DatanodeManager.canonicalizeAddress(), which normalises IP
 * address strings to RFC 5952 compressed form before the
 * dfs.namenode.datanode.registration.ip-hostname-check comparison.
 */
public class TestDatanodeManagerIPv6Registration {

  /**
   * Two representations of the same IPv6 address must produce the same
   * canonical string so that the hostname-check does not falsely reject
   * a DataNode whose DNS returns one form while the JDK InetAddress returns
   * the other.
   */
  @Test
  public void testEquivalentIPv6FormsCanonicalizeEqual()
      throws UnknownHostException {
    // Expanded form: fd00:dead:beef:0:0:0:0:21
    InetAddress expanded =
        InetAddress.getByName("fd00:dead:beef:0:0:0:0:21");
    // Compressed form: fd00:dead:beef::21
    InetAddress compressed =
        InetAddress.getByName("fd00:dead:beef::21");

    String canonicalExpanded = DatanodeManager.canonicalizeAddress(expanded);
    String canonicalCompressed = DatanodeManager.canonicalizeAddress(compressed);

    assertEquals(canonicalExpanded, canonicalCompressed,
        "Expanded and compressed IPv6 forms must canonicalize to the same string");
  }

  /**
   * IPv4 dotted-quad must survive canonicalization unchanged so that
   * existing IPv4 clusters are unaffected.
   */
  @Test
  public void testIPv4UnchangedByCanonicalizer()
      throws UnknownHostException {
    InetAddress ipv4 = InetAddress.getByName("192.168.1.100");
    String canonical = DatanodeManager.canonicalizeAddress(ipv4);
    assertEquals("192.168.1.100", canonical,
        "IPv4 dotted-quad must be returned unchanged");
  }

  /**
   * An IPv4 address and an IPv6 address must not compare equal after
   * canonicalization, confirming that mixed-family addresses remain distinct.
   */
  @Test
  public void testMixedFamilyAddressesAreNotEqual()
      throws UnknownHostException {
    InetAddress ipv4 = InetAddress.getByName("192.168.1.1");
    InetAddress ipv6 = InetAddress.getByName("::1");

    String canonicalV4 = DatanodeManager.canonicalizeAddress(ipv4);
    String canonicalV6 = DatanodeManager.canonicalizeAddress(ipv6);

    assertNotEquals(canonicalV4, canonicalV6,
        "IPv4 and IPv6 canonical forms must not be equal");
  }
}
