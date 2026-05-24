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

package org.apache.hadoop.hdfs.security.token.delegation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.token.Token;
import org.junit.jupiter.api.Test;

/**
 * Verification tests: DelegationTokenIdentifier and its enclosing Token
 * round-trip correctly when the NameNode service address is an IPv6 literal
 * (bracketed form, e.g. "[fd00:dead:beef::21]:8020").
 *
 * The AbstractDelegationTokenIdentifier serializes owner/renewer/realUser as
 * Hadoop Text (UTF-8 length-prefixed).  Token.service is also a Text field.
 * Both paths must preserve bracket characters unmodified.
 *
 * No live cluster is needed.
 */
public class TestDelegationTokenIdentifierIPv6 {

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  /**
   * Build a DelegationTokenIdentifier, write it to bytes with write(), read
   * it back with readFields(), and return the deserialized copy.
   */
  private DelegationTokenIdentifier roundTrip(
      Text owner, Text renewer, Text realUser) throws Exception {
    DelegationTokenIdentifier orig =
        new DelegationTokenIdentifier(owner, renewer, realUser);
    orig.setIssueDate(1000L);
    orig.setMaxDate(9999L);
    orig.setSequenceNumber(77);
    orig.setMasterKeyId(3);

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    orig.write(new DataOutputStream(baos));

    DelegationTokenIdentifier decoded = new DelegationTokenIdentifier();
    decoded.readFields(
        new DataInputStream(new ByteArrayInputStream(baos.toByteArray())));
    return decoded;
  }

  /**
   * Build a Token<DelegationTokenIdentifier> whose service is set to the
   * supplied NN address string, serialize the entire Token, deserialize it,
   * and assert the service is unchanged.
   */
  private void assertTokenServiceRoundTrips(String nnAddr) throws Exception {
    DelegationTokenIdentifier id = new DelegationTokenIdentifier(
        new Text("alice"), new Text("yarn"), new Text(""));
    id.setIssueDate(500L);
    id.setMaxDate(5000L);

    ByteArrayOutputStream idBaos = new ByteArrayOutputStream();
    id.write(new DataOutputStream(idBaos));

    Token<DelegationTokenIdentifier> token = new Token<>(
        idBaos.toByteArray(),
        new byte[]{0x0a, 0x0b},
        DelegationTokenIdentifier.HDFS_DELEGATION_KIND,
        new Text(nnAddr));

    ByteArrayOutputStream tokenBaos = new ByteArrayOutputStream();
    token.write(new DataOutputStream(tokenBaos));

    Token<DelegationTokenIdentifier> decoded = new Token<>();
    decoded.readFields(
        new DataInputStream(new ByteArrayInputStream(tokenBaos.toByteArray())));

    assertEquals(nnAddr, decoded.getService().toString(),
        "Token.service must round-trip unchanged for nnAddr=" + nnAddr);
    assertEquals(DelegationTokenIdentifier.HDFS_DELEGATION_KIND,
        decoded.getKind());
  }

  // -------------------------------------------------------------------------
  // test cases
  // -------------------------------------------------------------------------

  /**
   * testDelegationTokenWithIPv6NNAddress:
   * Creates a DelegationTokenIdentifier and a Token whose service carries a
   * bracketed IPv6 NameNode address [fd00:dead:beef::21]:8020.
   * Asserts that:
   *   - owner, renewer, realUser fields survive write/readFields unchanged
   *   - issueDate, maxDate, sequenceNumber, masterKeyId survive unchanged
   *   - Token.service "[fd00:dead:beef::21]:8020" survives a full Token
   *     write/readFields cycle unchanged
   */
  @Test
  public void testDelegationTokenWithIPv6NNAddress() throws Exception {
    Text owner    = new Text("alice");
    Text renewer  = new Text("yarn");
    Text realUser = new Text("alice");

    DelegationTokenIdentifier decoded = roundTrip(owner, renewer, realUser);

    assertEquals(owner.toString(), decoded.getOwner().toString(),
        "owner must round-trip");
    // renewer goes through HadoopKerberosName.getShortName(); for a simple
    // (non-kerberos) name it is returned as-is
    assertEquals("yarn", decoded.getRenewer().toString(),
        "renewer must round-trip");
    assertEquals(realUser.toString(), decoded.getRealUser().toString(),
        "realUser must round-trip");
    assertEquals(1000L, decoded.getIssueDate());
    assertEquals(9999L, decoded.getMaxDate());
    assertEquals(77, decoded.getSequenceNumber());
    assertEquals(3, decoded.getMasterKeyId());

    assertTokenServiceRoundTrips("[fd00:dead:beef::21]:8020");
  }

  /**
   * testDelegationTokenWithIPv6Loopback:
   * Same structure but with the loopback address [::1]:8020, which exercises
   * the shortest possible bracketed IPv6 literal.
   */
  @Test
  public void testDelegationTokenWithIPv6Loopback() throws Exception {
    Text owner    = new Text("bob");
    Text renewer  = new Text("mr");
    Text realUser = new Text("");

    DelegationTokenIdentifier decoded = roundTrip(owner, renewer, realUser);

    assertEquals("bob", decoded.getOwner().toString());
    assertEquals("mr", decoded.getRenewer().toString());
    assertEquals("", decoded.getRealUser().toString());

    assertTokenServiceRoundTrips("[::1]:8020");
  }

  /**
   * testDelegationTokenIPv4StillRoundTrips:
   * Regression guard: a plain IPv4 NN address must continue to round-trip
   * correctly after any IPv6-related changes.
   */
  @Test
  public void testDelegationTokenIPv4StillRoundTrips() throws Exception {
    Text owner    = new Text("charlie");
    Text renewer  = new Text("rm");
    Text realUser = new Text("charlie");

    DelegationTokenIdentifier decoded = roundTrip(owner, renewer, realUser);

    assertEquals("charlie", decoded.getOwner().toString());
    assertEquals("rm", decoded.getRenewer().toString());

    assertTokenServiceRoundTrips("1.2.3.4:8020");
  }
}
