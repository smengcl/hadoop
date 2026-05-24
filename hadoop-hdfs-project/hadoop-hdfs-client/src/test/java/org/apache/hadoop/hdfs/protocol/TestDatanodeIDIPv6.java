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
package org.apache.hadoop.hdfs.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.hadoop.net.NetUtils;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

/**
 * Asserts that {@link DatanodeID} emits address strings whose IPv6 forms
 * are bracketed (so they round-trip through {@link NetUtils#createSocketAddr}).
 *
 * Resolves the long-standing HADOOP-17543 root cause where DataNodes
 * advertised {@code "::1:50010"} instead of {@code "[::1]:50010"}, breaking
 * every client connect.
 */
public class TestDatanodeIDIPv6 {

  @Test
  public void testXferAddrIPv4Unchanged() {
    DatanodeID id = new DatanodeID("1.2.3.4", "host", "uuid",
        9866, 9864, 9865, 9867);
    assertEquals("1.2.3.4:9866", id.getXferAddr());
    assertEquals("1.2.3.4:9864", id.getInfoAddr());
    assertEquals("1.2.3.4:9865", id.getInfoSecureAddr());
  }

  @Test
  public void testXferAddrIPv6IsBracketed() {
    DatanodeID id = new DatanodeID("fd00:dead:beef::20", "host", "uuid",
        9866, 9864, 9865, 9867);
    assertEquals("[fd00:dead:beef::20]:9866", id.getXferAddr());
    assertEquals("[fd00:dead:beef::20]:9864", id.getInfoAddr());
    assertEquals("[fd00:dead:beef::20]:9865", id.getInfoSecureAddr());
  }

  @Test
  public void testXferAddrIPv6LoopbackIsBracketed() {
    DatanodeID id = new DatanodeID("::1", "host", "uuid",
        9866, 9864, 9865, 9867);
    assertEquals("[::1]:9866", id.getXferAddr());
  }

  @Test
  public void testXferAddrIPv6RoundTripsThroughNetUtils() {
    DatanodeID id = new DatanodeID("2001:db8::1", "host", "uuid",
        9866, 9864, 9865, 9867);
    String addr = id.getXferAddr();
    assertEquals("[2001:db8::1]:9866", addr);
    InetSocketAddress sock = NetUtils.createSocketAddr(addr);
    assertEquals(9866, sock.getPort());
  }

  @Test
  public void testSetIpAddrRebuildsBracketedXferAddr() {
    DatanodeID id = new DatanodeID("1.2.3.4", "host", "uuid",
        9866, 9864, 9865, 9867);
    id.setIpAddr("::1");
    assertEquals("[::1]:9866", id.getXferAddr());
  }

  @Test
  public void testAllAddressGettersIPv6() {
    // Pin the contract that every host+port composer on DatanodeID
    // emits the bracketed IPv6 form, not just xferAddr; future
    // refactors that re-introduce field-level caching for
    // info/ipc/secure addrs would otherwise regress silently.
    DatanodeID id = new DatanodeID("fd00:dead:beef::20", "host", "uuid",
        9866, 9864, 9865, 9867);
    assertEquals("[fd00:dead:beef::20]:9866", id.getXferAddr());
    assertEquals("[fd00:dead:beef::20]:9864", id.getInfoAddr());
    assertEquals("[fd00:dead:beef::20]:9865", id.getInfoSecureAddr());
    // setIpAddr re-runs setIpAndXferPort; verify the rebuild
    id.setIpAddr("2001:db8::1");
    assertEquals("[2001:db8::1]:9866", id.getXferAddr());
    assertEquals("[2001:db8::1]:9864", id.getInfoAddr());
  }

  @Test
  public void testGetXferAddrUseHostnameStillBracketsIfHostnameIsLiteral() {
    // Unusual, but if dfs.datanode.hostname is configured as a bare IPv6
    // literal we still emit a parseable authority.
    DatanodeID id = new DatanodeID("1.2.3.4", "fd00:dead:beef::20", "uuid",
        9866, 9864, 9865, 9867);
    assertEquals("[fd00:dead:beef::20]:9866", id.getXferAddr(true));
  }
}
