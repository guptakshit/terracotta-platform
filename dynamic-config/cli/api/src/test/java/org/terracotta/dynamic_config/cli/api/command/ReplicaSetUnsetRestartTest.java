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
import org.terracotta.diagnostic.model.LogicalServerState;
import org.terracotta.dynamic_config.api.model.Cluster;
import org.terracotta.dynamic_config.api.model.Node;
import org.terracotta.dynamic_config.api.model.Node.Endpoint;
import org.terracotta.dynamic_config.api.model.NodeContext;
import org.terracotta.dynamic_config.api.model.Testing;
import org.terracotta.dynamic_config.cli.api.BaseTest;
import org.terracotta.dynamic_config.cli.api.nomad.NomadManager;
import org.terracotta.dynamic_config.cli.api.output.OutputService;
import org.terracotta.dynamic_config.cli.api.restart.RestartProgress;
import org.terracotta.dynamic_config.cli.api.restart.RestartService;
import org.terracotta.inet.HostPort;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.terracotta.diagnostic.model.LogicalServerState.ACTIVE;
import static org.terracotta.diagnostic.model.LogicalServerState.ACTIVE_RECONNECTING;
import static org.terracotta.diagnostic.model.LogicalServerState.DIAGNOSTIC;
import static org.terracotta.diagnostic.model.LogicalServerState.PASSIVE;
import static org.terracotta.diagnostic.model.LogicalServerState.PASSIVE_SUSPENDED;
import static org.terracotta.diagnostic.model.LogicalServerState.REPLICA;
import static org.terracotta.diagnostic.model.LogicalServerState.REPLICA_SUSPENDED;
import static org.terracotta.diagnostic.model.LogicalServerState.SYNCHRONIZING;
import static org.terracotta.dynamic_config.api.model.Testing.newTestCluster;
import static org.terracotta.dynamic_config.api.model.Testing.newTestStripe;
import static org.terracotta.dynamic_config.cli.api.command.Injector.inject;
import static org.terracotta.testing.ExceptionMatcher.throwing;

/**
 * Restart handling of set/unset replica on an activated cluster (1 stripe: node1 on port 9411, node2 on port 9412)
 */
@RunWith(MockitoJUnitRunner.class)
public class ReplicaSetUnsetRestartTest extends BaseTest {

  @SuppressWarnings("unchecked")
  private final NomadManager<NodeContext> nomadManagerMock = mock(NomadManager.class);
  private final RestartService restartServiceMock = mock(RestartService.class);
  private final RestartProgress restartProgress = mock(RestartProgress.class);
  private final AtomicReference<Collection<Endpoint>> restarting = new AtomicReference<>();

  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();
    outputService = mock(OutputService.class);
    lenient().when(restartServiceMock.restartNodes(any(), any(), any())).thenAnswer(invocation -> {
      restarting.set(invocation.getArgument(0));
      return restartProgress;
    });
    lenient().when(restartProgress.getErrors()).thenReturn(Collections.emptyMap());
  }

  @Test
  public void test_set_replica_restarts_all_nodes_at_once() throws Exception {
    Cluster cluster = cluster(false);
    mockCluster(cluster, ACTIVE, PASSIVE);
    when(topologyServiceMock("localhost", 9411).mustBeRestarted()).thenReturn(true);
    when(topologyServiceMock("localhost", 9412).mustBeRestarted()).thenReturn(true);
    allNodesRestart();

    // auto-restart is not set: the restart must be done anyway
    setReplicaOnNode1().run();

    verify(restartServiceMock).restartNodes(argThat(endpoints -> names(endpoints).equals(Set.of("node1", "node2"))), any(), eq(EnumSet.of(REPLICA_SUSPENDED, REPLICA, DIAGNOSTIC)));
    verify(outputService, never()).warn(anyString(), any());
  }

  @Test
  public void test_set_replica_warns_when_nodes_fail_to_restart() throws Exception {
    Cluster cluster = cluster(false);
    mockCluster(cluster, ACTIVE, PASSIVE);
    when(topologyServiceMock("localhost", 9411).mustBeRestarted()).thenReturn(true);
    when(topologyServiceMock("localhost", 9412).mustBeRestarted()).thenReturn(true);
    // no node came back
    when(restartProgress.await(any())).thenReturn(Collections.emptyMap());

    setReplicaOnNode1().run();

    verify(outputService).warn(
        eq("IMPORTANT: The replica change has been applied, but nodes: {} may not have been restarted. Please verify their state and restart them manually to apply the change."),
        eq("node1, node2"));
  }

  @Test
  public void test_unset_replica_restarts_only_the_other_nodes_of_the_stripe() throws Exception {
    Cluster cluster = cluster(true);
    // node1 has been failed over to active by replica-failover, node2 is in diagnostic mode
    mockCluster(cluster, ACTIVE, DIAGNOSTIC);
    when(topologyServiceMock("localhost", 9412).mustBeRestarted()).thenReturn(true);
    allNodesRestart();

    unsetReplicaOnNode1().run();

    // the change is sent through diagnostic because the current configuration is a replica cluster
    verify(nomadManagerMock).runConfigurationChangeViaDiagnostic(any(), any(), any());
    verify(nomadManagerMock, never()).runConfigurationChange(any(), any(), any(), any());
    // only node2 is restarted, node1 is the new active
    verify(restartServiceMock).restartNodes(argThat(endpoints -> names(endpoints).equals(Set.of("node2"))), any(), eq(EnumSet.of(ACTIVE, ACTIVE_RECONNECTING, PASSIVE, PASSIVE_SUSPENDED, SYNCHRONIZING)));
    verify(outputService, never()).warn(anyString(), any());
  }

  @Test
  public void test_unset_replica_fails_if_replica_node_not_failed_over() throws Exception {
    Cluster cluster = cluster(true);
    mockCluster(cluster, REPLICA, DIAGNOSTIC);

    assertThat(
        () -> unsetReplicaOnNode1().run(),
        is(throwing(instanceOf(IllegalStateException.class)).andMessage(is(equalTo(
            "Cannot unset replica on nodes: node1 because they are not ACTIVE. Please use the replica-failover command to fail over the replica nodes.")))));
    verify(restartServiceMock, never()).restartNodes(any(), any(), any());
  }

  private Cluster cluster(boolean node1IsReplica) {
    Node node1 = Testing.newTestNode("node1", "localhost", 9411, Testing.N_UIDS[1]);
    if (node1IsReplica) {
      node1.setReplica(true).setRelayHostname("relay-host").setRelayPort(9510).setRelayGroupPort(9530);
    }
    return newTestCluster("my-cluster", newTestStripe("stripe1", Testing.S_UIDS[1]).addNodes(
        node1,
        Testing.newTestNode("node2", "localhost", 9412, Testing.N_UIDS[2])));
  }

  private void mockCluster(Cluster cluster, LogicalServerState node1State, LogicalServerState node2State) {
    when(topologyServiceMock("localhost", 9411).getRuntimeNodeContext()).thenReturn(new NodeContext(cluster, Testing.N_UIDS[1]));
    when(topologyServiceMock("localhost", 9411).getUpcomingNodeContext()).thenReturn(new NodeContext(cluster, Testing.N_UIDS[1]));
    when(topologyServiceMock("localhost", 9411).isActivated()).thenReturn(true);
    when(topologyServiceMock("localhost", 9412).isActivated()).thenReturn(true);
    when(diagnosticServiceMock("localhost", 9411).getLogicalServerState()).thenReturn(node1State);
    when(diagnosticServiceMock("localhost", 9412).getLogicalServerState()).thenReturn(node2State);
  }

  private void allNodesRestart() throws InterruptedException {
    when(restartProgress.await(any())).thenAnswer(invocation -> restarting.get().stream().collect(toMap(identity(), endpoint -> ACTIVE)));
  }

  private SetAction setReplicaOnNode1() {
    SetAction command = new SetAction();
    init(command,
        "stripe.1.node.1.replica=true",
        "stripe.1.node.1.relay-hostname=relay-host",
        "stripe.1.node.1.relay-port=9510",
        "stripe.1.node.1.relay-group-port=9530");
    return command;
  }

  private UnsetAction unsetReplicaOnNode1() {
    UnsetAction command = new UnsetAction();
    init(command, "stripe.1.node.1.replica");
    return command;
  }

  private void init(ConfigurationMutationAction command, String... settings) {
    inject(command, asList(diagnosticServiceProvider, multiDiagnosticServiceProvider, nomadManagerMock, restartServiceMock, stopService, nomadEntityProvider, outputService, jsonFactory, json));
    command.setNodes(List.of(HostPort.create("localhost", 9411)));
    command.setConfigurationInputs(Stream.of(settings).map(ConfigurationInput::new).collect(toList()));
  }

  private static Set<String> names(Collection<Endpoint> endpoints) {
    return endpoints.stream().map(Endpoint::getNodeName).collect(toSet());
  }
}
