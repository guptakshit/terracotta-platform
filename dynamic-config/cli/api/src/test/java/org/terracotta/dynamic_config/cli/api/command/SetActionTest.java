/*
 * Copyright Terracotta, Inc.
 * Copyright IBM Corp. 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terracotta.dynamic_config.cli.api.command;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.terracotta.dynamic_config.api.model.Cluster;
import org.terracotta.dynamic_config.api.model.NodeContext;
import org.terracotta.dynamic_config.api.model.Testing;
import org.terracotta.dynamic_config.cli.api.BaseTest;
import org.terracotta.inet.HostPort;

import java.util.List;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.terracotta.dynamic_config.api.model.Testing.newTestCluster;
import static org.terracotta.dynamic_config.api.model.Testing.newTestStripe;
import static org.terracotta.dynamic_config.cli.api.command.Injector.inject;
import static org.terracotta.testing.ExceptionMatcher.throwing;

@RunWith(MockitoJUnitRunner.class)
public class SetActionTest extends BaseTest {

  private final Cluster cluster = newTestCluster(
      "my-cluster",
      newTestStripe("stripe1", Testing.S_UIDS[1]).addNodes(
          Testing.newTestNode("node1", "localhost", 9411, Testing.N_UIDS[1]),
          Testing.newTestNode("node2", "localhost", 9412, Testing.N_UIDS[2])
              .setRelay(true)
              .setReplicaHostname("replica-host")
              .setReplicaPort(9510)
      ));

  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();

    when(topologyServiceMock("localhost", 9411).getRuntimeNodeContext()).thenReturn(new NodeContext(cluster, Testing.N_UIDS[1]));
    when(topologyServiceMock("localhost", 9411).getUpcomingNodeContext()).thenReturn(new NodeContext(cluster, Testing.N_UIDS[1]));
    when(topologyServiceMock("localhost", 9411).isActivated()).thenReturn(false);
  }

  @Test
  public void test_set_replica_fails_when_relay_is_disabled_in_same_command() {
    SetAction command = command(
        "stripe.1.node.2.relay=false",
        "stripe.1.node.1.replica=true",
        "stripe.1.node.1.relay-hostname=relay-host",
        "stripe.1.node.1.relay-port=9410",
        "stripe.1.node.1.relay-group-port=9430");

    assertThat(
        command::run,
        is(throwing(instanceOf(IllegalStateException.class)).andMessage(is(equalTo(
            "Cannot set replica on nodes: node1 while relay is enabled on nodes: node2. " +
                "Disable relay on all nodes first, then set replica in a separate command.")))));
  }

  @Test
  public void test_set_replica_fails_when_relay_is_still_enabled() {
    SetAction command = command(
        "stripe.1.node.1.replica=true",
        "stripe.1.node.1.relay-hostname=relay-host",
        "stripe.1.node.1.relay-port=9410",
        "stripe.1.node.1.relay-group-port=9430");

    assertThat(
        command::run,
        is(throwing(instanceOf(IllegalStateException.class)).andMessage(is(equalTo(
            "Cannot set replica on nodes: node1 while relay is enabled on nodes: node2. " +
                "Disable relay on all nodes first, then set replica in a separate command.")))));
  }

  private SetAction command(String... settings) {
    SetAction command = new SetAction();
    inject(command, asList(diagnosticServiceProvider, multiDiagnosticServiceProvider, nomadManager, restartService, stopService, nomadEntityProvider, outputService, jsonFactory, json));
    command.setNodes(List.of(HostPort.create("localhost", 9411)));
    command.setConfigurationInputs(Stream.of(settings).map(ConfigurationInput::new).collect(toList()));
    return command;
  }
}
