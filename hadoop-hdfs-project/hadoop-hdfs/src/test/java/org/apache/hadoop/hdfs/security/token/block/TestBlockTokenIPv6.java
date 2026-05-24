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

package org.apache.hadoop.hdfs.security.token.block;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.EnumSet;

import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.token.Token;
import org.junit.jupiter.api.Test;

/**
 * Verification tests: BlockTokenIdentifier and its enclosing Token round-trip
 * correctly when DataNode transfer addresses are IPv6 (bracketed form).
 *
 * No live cluster is needed; serialization is exercised via
 * BlockTokenIdentifier.write / readFields on a ByteArrayOutputStream.
 *
 * The Token.service field carries the DataNode xferAddr string
 * (e.g. "[fd00:dead:beef::21]:50010") and must survive a full write/readFields
 * cycle unchanged.
 */
public class TestBlockTokenIPv6 {

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  /**
   * Build a BlockTokenIdentifier with the supplied storageIds, write it to
   * bytes, read it back, and return the deserialized copy.
   *
   * useProto=false exercises the legacy Writable path, which uses WritableUtils
   * string encoding for every String field.
   */
  private BlockTokenIdentifier roundTripLegacy(String userId,
      String blockPoolId, long blockId,
      String[] storageIds) throws Exception {
    BlockTokenIdentifier orig = new BlockTokenIdentifier(
        userId, blockPoolId, blockId,
        EnumSet.of(BlockTokenIdentifier.AccessMode.READ),
        new StorageType[]{StorageType.DEFAULT},
        storageIds,
        false /* useProto=false -> legacy path */);
    orig.setExpiryDate(99999L);
    orig.setKeyId(42);

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    orig.writeLegacy(new DataOutputStream(baos));

    byte[] bytes = baos.toByteArray();
    BlockTokenIdentifier decoded = new BlockTokenIdentifier();
    decoded.readFieldsLegacy(
        new DataInputStream(new ByteArrayInputStream(bytes)));
    return decoded;
  }

  /**
   * Build a Token<BlockTokenIdentifier> whose service is set to the supplied
   * xferAddr string, write the entire Token, read it back, and assert the
   * service survives unchanged.
   */
  private void assertTokenServiceRoundTrips(String xferAddr) throws Exception {
    BlockTokenIdentifier id = new BlockTokenIdentifier(
        "testUser", "BP-123", 1001L,
        EnumSet.of(BlockTokenIdentifier.AccessMode.READ),
        new StorageType[]{StorageType.DEFAULT},
        new String[]{"DS-uuid-1"},
        false);
    id.setExpiryDate(12345L);
    id.setKeyId(7);

    ByteArrayOutputStream idBaos = new ByteArrayOutputStream();
    id.writeLegacy(new DataOutputStream(idBaos));

    Token<BlockTokenIdentifier> token = new Token<>(
        idBaos.toByteArray(),
        new byte[]{0x01, 0x02},
        BlockTokenIdentifier.KIND_NAME,
        new Text(xferAddr));

    // Serialize the whole Token
    ByteArrayOutputStream tokenBaos = new ByteArrayOutputStream();
    token.write(new DataOutputStream(tokenBaos));

    // Deserialize
    Token<BlockTokenIdentifier> decoded = new Token<>();
    decoded.readFields(
        new DataInputStream(new ByteArrayInputStream(tokenBaos.toByteArray())));

    assertEquals(xferAddr, decoded.getService().toString(),
        "Token.service must round-trip unchanged for xferAddr=" + xferAddr);
    assertEquals(BlockTokenIdentifier.KIND_NAME, decoded.getKind());
  }

  // -------------------------------------------------------------------------
  // test cases
  // -------------------------------------------------------------------------

  /**
   * testBlockTokenSerializeDeserializeIPv6:
   * Creates a BlockTokenIdentifier whose storageIds array contains a string
   * that embeds a bracketed IPv6 address (the form used in DataNode xferAddr).
   * Writes via writeLegacy, reads via readFieldsLegacy, asserts all fields
   * equal including the bracketed address string inside storageIds.
   *
   * Also asserts that Token.service carrying "[fd00:dead:beef::21]:50010"
   * survives a full Token write/readFields cycle unchanged.
   */
  @Test
  public void testBlockTokenSerializeDeserializeIPv6() throws Exception {
    String ipv6 = "fd00:dead:beef::21";
    // storageIds in practice carry storage UUIDs, but the encoding path
    // (WritableUtils.writeString / readString) is the same code used for any
    // string field; we deliberately put a bracketed IPv6 address here to
    // exercise that the UTF-8 bracket characters survive intact.
    String storageIdWithBrackets = "[" + ipv6 + "]:50010";
    String[] storageIds = {storageIdWithBrackets};

    BlockTokenIdentifier decoded = roundTripLegacy(
        "alice", "BP-987654321-1.2.3.4-1234567890", 42L, storageIds);

    assertEquals("alice", decoded.getUserId());
    assertEquals("BP-987654321-1.2.3.4-1234567890", decoded.getBlockPoolId());
    assertEquals(42L, decoded.getBlockId());
    assertArrayEquals(storageIds, decoded.getStorageIds(),
        "storageIds containing bracketed IPv6 must round-trip exactly");

    // Also verify that the bracketed address survives as Token.service
    assertTokenServiceRoundTrips("[" + ipv6 + "]:50010");
  }

  /**
   * testBlockTokenWithIPv6Loopback:
   * Same as testBlockTokenSerializeDeserializeIPv6 but with the loopback
   * address ::1, which is the shortest valid IPv6 literal and is especially
   * likely to be mis-parsed if bracket handling is wrong.
   */
  @Test
  public void testBlockTokenWithIPv6Loopback() throws Exception {
    String storageIdLoopback = "[::1]:50010";
    String[] storageIds = {storageIdLoopback};

    BlockTokenIdentifier decoded = roundTripLegacy(
        "bob", "BP-loopback", 7L, storageIds);

    assertArrayEquals(storageIds, decoded.getStorageIds(),
        "storageIds with ::1 loopback address must round-trip exactly");

    assertTokenServiceRoundTrips("[::1]:50010");
  }

  /**
   * testBlockTokenIPv4StillRoundTrips:
   * Regression guard: plain IPv4 addresses must continue to round-trip
   * correctly after any IPv6-related changes.
   */
  @Test
  public void testBlockTokenIPv4StillRoundTrips() throws Exception {
    String[] storageIds = {"1.2.3.4:50010"};

    BlockTokenIdentifier decoded = roundTripLegacy(
        "charlie", "BP-ipv4-pool", 100L, storageIds);

    assertArrayEquals(storageIds, decoded.getStorageIds(),
        "IPv4 address in storageIds must round-trip exactly");
    assertEquals("charlie", decoded.getUserId());

    assertTokenServiceRoundTrips("1.2.3.4:50010");
  }
}
