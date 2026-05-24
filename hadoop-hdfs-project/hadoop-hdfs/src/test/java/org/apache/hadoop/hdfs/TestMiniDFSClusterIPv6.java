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
package org.apache.hadoop.hdfs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.net.NetUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests that MiniDFSCluster can be configured to bind on an IPv6 loopback
 * address via {@link MiniDFSCluster.Builder#bindHost(String)}.
 *
 * The test is skipped automatically on environments where the IPv6 loopback
 * (::1) is not available (e.g. IPv4-only CI hosts).
 */
public class TestMiniDFSClusterIPv6 {

  private static final String IPV6_LOOPBACK = "::1";
  private static final int FILE_SIZE_BYTES = 1024 * 1024; // 1 MB

  /**
   * Verifies that a MiniDFSCluster bound to {@code ::1}:
   * <ul>
   *   <li>starts successfully and becomes active;</li>
   *   <li>can write and read back a 1 MB file byte-for-byte;</li>
   *   <li>exposes a DataNode whose transfer address is in bracketed IPv6
   *       form (e.g. {@code [::1]:port}), validating the end-to-end fix from
   *       HADOOP-17543.</li>
   * </ul>
   */
  @Test
  @Timeout(120)
  public void testBindHostIPv6() throws Exception {
    // Skip when ::1 is not available on this host.
    try {
      InetAddress loopback6 = InetAddress.getByName(IPV6_LOOPBACK);
      assumeTrue(loopback6 instanceof Inet6Address,
          "Skipping: ::1 did not resolve to an Inet6Address");
    } catch (Exception e) {
      assumeTrue(false,
          "Skipping: could not resolve IPv6 loopback address: " + e.getMessage());
    }

    Configuration conf = new HdfsConfiguration();
    MiniDFSCluster cluster = null;
    try {
      cluster = new MiniDFSCluster.Builder(conf)
          .bindHost(IPV6_LOOPBACK)
          .numDataNodes(1)
          .build();
      cluster.waitActive();

      DistributedFileSystem dfs = cluster.getFileSystem();
      Path testFile = new Path("/testIPv6Data.bin");

      // Write 1 MB of random bytes.
      byte[] written = new byte[FILE_SIZE_BYTES];
      new Random().nextBytes(written);
      try (FSDataOutputStream out = dfs.create(testFile, (short) 1)) {
        out.write(written);
      }

      // Read back and verify byte-equality.
      byte[] read = new byte[FILE_SIZE_BYTES];
      try (FSDataInputStream in = dfs.open(testFile)) {
        int offset = 0;
        while (offset < FILE_SIZE_BYTES) {
          int n = in.read(read, offset, FILE_SIZE_BYTES - offset);
          if (n < 0) {
            break;
          }
          offset += n;
        }
      }
      assertArrayEquals(written, read,
          "Data read back from HDFS must match what was written");

      // Validate that the DataNode transfer address is in bracketed IPv6 form.
      DataNode dn = cluster.getDataNodes().get(0);
      String xferAddr = NetUtils.getHostPortString(dn.getXferAddress());
      assertTrue(xferAddr.startsWith("["),
          "DataNode xferAddr must be in bracketed IPv6 form but was: " + xferAddr);
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }
}
