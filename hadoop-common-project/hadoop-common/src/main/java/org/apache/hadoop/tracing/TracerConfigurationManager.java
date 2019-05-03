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
package org.apache.hadoop.tracing;

import java.io.IOException;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.tracing.SpanReceiverInfo.ConfigurationPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class provides functions for managing the tracer configuration at
 * runtime via an RPC protocol.
 */
@InterfaceAudience.Private
public class TracerConfigurationManager implements TraceAdminProtocol {
  private static final Logger LOG =
      LoggerFactory.getLogger(TracerConfigurationManager.class);

  private final String confPrefix;
  private final Configuration conf;

  public TracerConfigurationManager(String confPrefix, Configuration conf) {
    this.confPrefix = confPrefix;
    this.conf = conf;
  }

  public synchronized SpanReceiverInfo[] listSpanReceivers()
      throws IOException {
    return new SpanReceiverInfo[0];
  }

  public synchronized long addSpanReceiver(SpanReceiverInfo info)
      throws IOException {
      return 0;
  }

  public synchronized void removeSpanReceiver(long spanReceiverId)
      throws IOException {
  }
}
