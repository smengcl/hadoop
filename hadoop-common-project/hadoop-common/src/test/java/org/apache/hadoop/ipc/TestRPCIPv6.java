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

package org.apache.hadoop.ipc;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.ipc.Client.ConnectionId;
import org.apache.hadoop.ipc.protobuf.TestProtos;
import org.apache.hadoop.ipc.protobuf.TestRpcServiceProtos;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.thirdparty.protobuf.BlockingService;
import org.apache.hadoop.thirdparty.protobuf.RpcController;
import org.apache.hadoop.thirdparty.protobuf.ServiceException;
import org.apache.hadoop.security.SaslRpcServer.AuthMethod;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Stress-tests the Hadoop RPC engine (Server + Client + ConnectionId hashing)
 * against IPv6 loopback endpoints.  Existing tests target IPv4; this class
 * specifically exercises the ::1 code paths.
 *
 * <p>HADOOP-XXXXX-F27
 */
@Timeout(60)
public class TestRPCIPv6 {

  private static final int BATCH = 100;
  private static final int HALF = BATCH / 2;

  private Configuration conf;
  private RPC.Server server;
  private InetSocketAddress serverAddr;

  // -----------------------------------------------------------------------
  // IPv6 availability check
  // -----------------------------------------------------------------------

  /**
   * Returns true if the JVM can bind/connect to the IPv6 loopback address.
   */
  private static boolean isIPv6Available() {
    try {
      InetAddress loopback6 = InetAddress.getByName("::1");
      if (!(loopback6 instanceof Inet6Address)) {
        return false;
      }
      // A quick bind attempt on an ephemeral port to confirm the OS supports it.
      try (java.net.ServerSocket ss = new java.net.ServerSocket()) {
        ss.bind(new InetSocketAddress(loopback6, 0));
      }
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  // -----------------------------------------------------------------------
  // Minimal PB server implementation (no-op ping only)
  // -----------------------------------------------------------------------

  private static class MinimalPBServerImpl
      implements TestRpcBase.TestRpcService {

    @Override
    public TestProtos.EmptyResponseProto ping(
        RpcController unused, TestProtos.EmptyRequestProto request)
        throws ServiceException {
      return TestProtos.EmptyResponseProto.newBuilder().build();
    }

    @Override
    public TestProtos.EchoResponseProto echo(
        RpcController unused, TestProtos.EchoRequestProto request)
        throws ServiceException {
      return TestProtos.EchoResponseProto.newBuilder()
          .setMessage(request.getMessage()).build();
    }

    @Override
    public TestProtos.EmptyResponseProto error(
        RpcController unused, TestProtos.EmptyRequestProto request)
        throws ServiceException {
      throw new ServiceException("error");
    }

    @Override
    public TestProtos.EmptyResponseProto error2(
        RpcController unused, TestProtos.EmptyRequestProto request)
        throws ServiceException {
      throw new ServiceException("error2");
    }

    @Override
    public TestProtos.EmptyResponseProto slowPing(
        RpcController unused, TestProtos.SlowPingRequestProto request)
        throws ServiceException {
      return TestProtos.EmptyResponseProto.newBuilder().build();
    }

    @Override
    public TestProtos.EchoResponseProto2 echo2(
        RpcController controller, TestProtos.EchoRequestProto2 request)
        throws ServiceException {
      return TestProtos.EchoResponseProto2.newBuilder()
          .addAllMessage(request.getMessageList()).build();
    }

    @Override
    public TestProtos.AddResponseProto add(
        RpcController controller, TestProtos.AddRequestProto request)
        throws ServiceException {
      return TestProtos.AddResponseProto.newBuilder()
          .setResult(request.getParam1() + request.getParam2()).build();
    }

    @Override
    public TestProtos.AddResponseProto add2(
        RpcController controller, TestProtos.AddRequestProto2 request)
        throws ServiceException {
      int sum = 0;
      for (Integer n : request.getParamsList()) {
        sum += n;
      }
      return TestProtos.AddResponseProto.newBuilder().setResult(sum).build();
    }

    @Override
    public TestProtos.EmptyResponseProto testServerGet(
        RpcController controller, TestProtos.EmptyRequestProto request)
        throws ServiceException {
      return TestProtos.EmptyResponseProto.newBuilder().build();
    }

    @Override
    public TestProtos.ExchangeResponseProto exchange(
        RpcController controller, TestProtos.ExchangeRequestProto request)
        throws ServiceException {
      return TestProtos.ExchangeResponseProto.newBuilder()
          .addAllValues(request.getValuesList()).build();
    }

    @Override
    public TestProtos.EmptyResponseProto sleep(
        RpcController controller, TestProtos.SleepRequestProto request)
        throws ServiceException {
      return TestProtos.EmptyResponseProto.newBuilder().build();
    }

    @Override
    public TestProtos.EmptyResponseProto lockAndSleep(
        RpcController controller, TestProtos.SleepRequestProto request)
        throws ServiceException {
      return TestProtos.EmptyResponseProto.newBuilder().build();
    }

    @Override
    public TestProtos.AuthMethodResponseProto getAuthMethod(
        RpcController controller, TestProtos.EmptyRequestProto request)
        throws ServiceException {
      try {
        AuthMethod authMethod =
            UserGroupInformation.getCurrentUser()
                .getAuthenticationMethod().getAuthMethod();
        return TestProtos.AuthMethodResponseProto.newBuilder()
            .setCode(authMethod.code)
            .setMechanismName(authMethod.getMechanismName())
            .build();
      } catch (IOException e) {
        throw new ServiceException(e);
      }
    }

    @Override
    public TestProtos.UserResponseProto getAuthUser(
        RpcController controller, TestProtos.EmptyRequestProto request)
        throws ServiceException {
      try {
        return TestProtos.UserResponseProto.newBuilder()
            .setUser(UserGroupInformation.getCurrentUser().toString())
            .build();
      } catch (IOException e) {
        throw new ServiceException(e);
      }
    }

    @Override
    public TestProtos.EchoResponseProto echoPostponed(
        RpcController controller, TestProtos.EchoRequestProto request)
        throws ServiceException {
      return TestProtos.EchoResponseProto.newBuilder()
          .setMessage(request.getMessage()).build();
    }

    @Override
    public TestProtos.EmptyResponseProto sendPostponed(
        RpcController controller, TestProtos.EmptyRequestProto request)
        throws ServiceException {
      return TestProtos.EmptyResponseProto.newBuilder().build();
    }

    @Override
    public TestProtos.UserResponseProto getCurrentUser(
        RpcController controller, TestProtos.EmptyRequestProto request)
        throws ServiceException {
      try {
        return TestProtos.UserResponseProto.newBuilder()
            .setUser(UserGroupInformation.getCurrentUser().toString())
            .build();
      } catch (IOException e) {
        throw new ServiceException(e);
      }
    }

    @Override
    public TestProtos.UserResponseProto getServerRemoteUser(
        RpcController controller, TestProtos.EmptyRequestProto request)
        throws ServiceException {
      UserGroupInformation remoteUser = Server.getRemoteUser();
      String user = (remoteUser == null) ? "" : remoteUser.toString();
      return TestProtos.UserResponseProto.newBuilder().setUser(user).build();
    }
  }

  // -----------------------------------------------------------------------
  // JUnit lifecycle
  // -----------------------------------------------------------------------

  @BeforeEach
  public void setUp() throws IOException {
    assumeTrue(isIPv6Available(), "IPv6 not available on this host");

    conf = new Configuration();
    RPC.setProtocolEngine(conf, TestRpcBase.TestRpcService.class,
        ProtobufRpcEngine2.class);
    UserGroupInformation.setConfiguration(conf);

    BlockingService service =
        TestRpcServiceProtos.TestProtobufRpcProto
            .newReflectiveBlockingService(new MinimalPBServerImpl());

    server = new RPC.Builder(conf)
        .setProtocol(TestRpcBase.TestRpcService.class)
        .setInstance(service)
        .setBindAddress("::1")
        .setPort(0)           // OS picks a free port
        .setNumHandlers(1)
        .setVerbose(false)
        .build();
    server.start();

    serverAddr = NetUtils.getConnectAddress(server);
  }

  @AfterEach
  public void tearDown() {
    if (server != null) {
      server.stop();
    }
  }

  // -----------------------------------------------------------------------
  // Helper
  // -----------------------------------------------------------------------

  private TestRpcBase.TestRpcService newProxy() throws Exception {
    return RPC.getProtocolProxy(
        TestRpcBase.TestRpcService.class,
        0,
        serverAddr,
        UserGroupInformation.getCurrentUser(),
        conf,
        NetUtils.getDefaultSocketFactory(conf),
        RPC.getRpcTimeout(conf),
        null,
        null).getProxy();
  }

  private static void closeProxy(TestRpcBase.TestRpcService proxy) {
    if (proxy != null) {
      RPC.stopProxy(proxy);
    }
  }

  private static final TestProtos.EmptyRequestProto EMPTY =
      TestProtos.EmptyRequestProto.newBuilder().build();

  // -----------------------------------------------------------------------
  // Test 1 – server binds on ::1 and reports a reachable IPv6 address
  // -----------------------------------------------------------------------

  @Test
  public void testServerBoundOnIPv6() {
    InetAddress addr = serverAddr.getAddress();
    // JDK Inet6Address.getHostAddress() returns the expanded form
    // ("0:0:0:0:0:0:0:1") rather than the compressed "::1". The
    // contract being verified is that the server bound to the v6
    // loopback - either form is correct.
    org.junit.jupiter.api.Assertions.assertTrue(
        addr.isLoopbackAddress() && addr instanceof java.net.Inet6Address,
        "Server should be bound to v6 loopback, got: "
            + addr.getHostAddress());
    org.junit.jupiter.api.Assertions.assertTrue(serverAddr.getPort() > 0,
        "Port must be positive after bind");
  }

  // -----------------------------------------------------------------------
  // Test 2 – 100 concurrent proxies all succeed with a no-op ping
  // -----------------------------------------------------------------------

  @Test
  public void testHundredProxiesPingIPv6() throws Exception {
    List<TestRpcBase.TestRpcService> proxies = new ArrayList<>(BATCH);
    try {
      for (int i = 0; i < BATCH; i++) {
        proxies.add(newProxy());
      }
      int failures = 0;
      for (TestRpcBase.TestRpcService proxy : proxies) {
        try {
          proxy.ping(null, EMPTY);
        } catch (ServiceException e) {
          failures++;
        }
      }
      assertEquals(0, failures,
          "All 100 RPC pings over IPv6 should succeed");
    } finally {
      proxies.forEach(TestRPCIPv6::closeProxy);
    }
  }

  // -----------------------------------------------------------------------
  // Test 3 – cache churn: close 50, open 50 more, all succeed
  // -----------------------------------------------------------------------

  @Test
  public void testCacheChurnIPv6() throws Exception {
    List<TestRpcBase.TestRpcService> proxies = new ArrayList<>(BATCH);
    try {
      // Open initial 100 and ping each
      for (int i = 0; i < BATCH; i++) {
        proxies.add(newProxy());
      }
      for (TestRpcBase.TestRpcService proxy : proxies) {
        proxy.ping(null, EMPTY);
      }

      // Close first 50 (forces cache eviction)
      List<TestRpcBase.TestRpcService> toClose = new ArrayList<>(proxies.subList(0, HALF));
      toClose.forEach(TestRPCIPv6::closeProxy);
      proxies.subList(0, HALF).clear();

      // Open 50 new proxies and ping each
      for (int i = 0; i < HALF; i++) {
        TestRpcBase.TestRpcService p = newProxy();
        proxies.add(p);
        p.ping(null, EMPTY); // assert success immediately
      }
    } finally {
      proxies.forEach(TestRPCIPv6::closeProxy);
    }
    // If we reach here all 50 re-opened pings succeeded
  }

  // -----------------------------------------------------------------------
  // Test 4 – ConnectionId equals/hashCode contract for IPv6 addresses
  // -----------------------------------------------------------------------

  /**
   * Verifies the equals/hashCode contract on {@link ConnectionId} when both
   * instances target the same {@code [::1]:port} endpoint — one built from an
   * {@link InetSocketAddress} resolved via {@link InetAddress} and one
   * constructed from the string hostname {@code "::1"}.
   *
   * <p>NOTE: If this test fails with "ConnectionId instances do not compare
   * equal", that indicates a defect in {@link ConnectionId#hashCode()} or
   * {@link ConnectionId#equals} for IPv6 addresses.  The hashCode uses
   * {@link InetSocketAddress#getHostName()} which returns the string passed
   * to the constructor; when one ConnectionId is created from an
   * {@code InetAddress} (hostName == numeric "::1") and another from a
   * bracketed string {@code "[::1]"}, the hostNames differ and the hash/equals
   * breaks.  Do NOT fix here — file a follow-up JIRA.
   */
  @Test
  public void testConnectionIdHashEqualsIPv6() throws Exception {
    int port = serverAddr.getPort();

    // Address from resolved InetAddress (as the server returns it)
    InetSocketAddress fromInetAddr =
        new InetSocketAddress(InetAddress.getByName("::1"), port);

    // Address from plain string hostname — same host, same port
    InetSocketAddress fromStringAddr =
        new InetSocketAddress("::1", port);

    UserGroupInformation ugi = UserGroupInformation.getCurrentUser();

    ConnectionId id1 = ConnectionId.getConnectionId(
        fromInetAddr, TestRpcBase.TestRpcService.class, ugi, 0, null, conf);
    ConnectionId id2 = ConnectionId.getConnectionId(
        fromStringAddr, TestRpcBase.TestRpcService.class, ugi, 0, null, conf);

    // Contract: if a.equals(b) then a.hashCode() == b.hashCode()
    // Surface any defect here but do not fix it.
    assertEquals(id1.hashCode(), id2.hashCode(),
        "DEFECT CANDIDATE: ConnectionId hashCode mismatch for IPv6 – "
            + "fromInetAddr hostName='" + fromInetAddr.getHostName()
            + "' vs fromStringAddr hostName='" + fromStringAddr.getHostName()
            + "'");
    assertEquals(id1, id2,
        "DEFECT CANDIDATE: ConnectionId.equals mismatch for IPv6 – "
            + "id1.address=" + id1.getAddress()
            + " id2.address=" + id2.getAddress());
  }

  // -----------------------------------------------------------------------
  // Test 5 – sanity: ConnectionId for [::1]:port from server address equals
  //          itself (reflexive) and matches a second instance built the same way
  // -----------------------------------------------------------------------

  @Test
  public void testConnectionIdReflexiveIPv6() throws Exception {
    UserGroupInformation ugi = UserGroupInformation.getCurrentUser();

    ConnectionId id = ConnectionId.getConnectionId(
        serverAddr, TestRpcBase.TestRpcService.class, ugi, 0, null, conf);

    assertEquals(id, id, "ConnectionId must equal itself");
    assertEquals(id.hashCode(), id.hashCode(),
        "ConnectionId.hashCode must be stable");

    // Second instance from the same address
    ConnectionId id2 = ConnectionId.getConnectionId(
        serverAddr, TestRpcBase.TestRpcService.class, ugi, 0, null, conf);
    assertEquals(id, id2,
        "Two ConnectionIds for the same server address must be equal");
    assertEquals(id.hashCode(), id2.hashCode(),
        "Two ConnectionIds for the same server address must have equal hashCodes");
  }
}
