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

package org.apache.hadoop.hdfs.server.namenode;

import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.InetAddress;
import java.net.URI;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link NameNode#initializeGenericKeys} derives the default FS URI
 * from an IPv6 NameNode RPC address correctly. A bare (unbracketed) IPv6
 * literal in {@code dfs.namenode.rpc-address} yields a URI with a null host
 * (registry-based authority) unless the host is bracketed; the resulting
 * {@code fs.defaultFS} must be a valid server-based URI.
 */
public class TestNameNodeGenericKeysIPv6 {

  private static URI defaultFsFor(String rpcAddress, String nsId, String nnId) {
    Configuration conf = new HdfsConfiguration();
    if (nsId == null) {
      conf.set(DFSConfigKeys.DFS_NAMENODE_RPC_ADDRESS_KEY, rpcAddress);
    } else {
      conf.set(DFSConfigKeys.DFS_NAMESERVICES, nsId);
      conf.set(DFSUtil.addKeySuffixes(
          DFSConfigKeys.DFS_HA_NAMENODES_KEY_PREFIX, nsId), nnId);
      conf.set(DFSUtil.addKeySuffixes(
          DFSConfigKeys.DFS_NAMENODE_RPC_ADDRESS_KEY, nsId, nnId), rpcAddress);
    }
    NameNode.initializeGenericKeys(conf, nsId, nnId);
    return URI.create(conf.get(FS_DEFAULT_NAME_KEY));
  }

  @Test
  public void testUnbracketedIPv6RpcAddressBecomesValidUri() throws Exception {
    URI fsUri = defaultFsFor("fd00:dead:beef::10:8020", null, null);
    // The bare IPv6 literal must be bracketed so the URI has a real host.
    assertNotNull(fsUri.getHost(), "default FS host must not be null: " + fsUri);
    assertEquals(8020, fsUri.getPort());
    assertEquals(InetAddress.getByName("fd00:dead:beef::10"),
        InetAddress.getByName(fsUri.getHost()));
  }

  @Test
  public void testFederationSuffixedUnbracketedIPv6() throws Exception {
    // The per-(nsId,nnId) generic-key path copies the suffixed RPC address into
    // dfs.namenode.rpc-address, then derives the default FS from it.
    URI fsUri = defaultFsFor("fd00:dead:beef::20:8020", "ns1", "nn1");
    assertNotNull(fsUri.getHost(), "default FS host must not be null: " + fsUri);
    assertEquals(8020, fsUri.getPort());
    assertEquals(InetAddress.getByName("fd00:dead:beef::20"),
        InetAddress.getByName(fsUri.getHost()));
  }

  @Test
  public void testBracketedIPv6RpcAddressPreservedVerbatim() {
    // Already a valid URI - the compact bracketed form is left untouched.
    URI fsUri = defaultFsFor("[fd00:dead:beef::10]:8020", null, null);
    assertEquals("hdfs://[fd00:dead:beef::10]:8020", fsUri.toString());
  }

  @Test
  public void testIPv4RpcAddressPreservedVerbatim() {
    URI fsUri = defaultFsFor("192.0.2.10:8020", null, null);
    assertEquals("hdfs://192.0.2.10:8020", fsUri.toString());
  }

  @Test
  public void testHostnameRpcAddressPreservedVerbatim() {
    URI fsUri = defaultFsFor("namenode-1.example.com:8020", null, null);
    assertEquals("hdfs://namenode-1.example.com:8020", fsUri.toString());
  }
}
