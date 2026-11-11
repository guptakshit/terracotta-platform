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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.diagnostic.client.DiagnosticService;
import org.terracotta.diagnostic.model.LogicalServerState;
import org.terracotta.dynamic_config.api.model.Cluster;
import org.terracotta.dynamic_config.api.model.DisasterRecoveryMode;
import org.terracotta.dynamic_config.api.model.Node;
import org.terracotta.dynamic_config.api.model.Node.Endpoint;
import org.terracotta.dynamic_config.api.model.SettingName;
import org.terracotta.dynamic_config.cli.api.command.Injector.Inject;
import org.terracotta.inet.HostPort;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static java.util.Collections.emptyList;
import static org.terracotta.diagnostic.common.DiagnosticConstants.MBEAN_SERVER;
import static org.terracotta.diagnostic.model.LogicalServerState.REPLICA;
import static org.terracotta.diagnostic.model.LogicalServerState.REPLICA_SUSPENDED;
import static org.terracotta.diagnostic.model.LogicalServerState.UNREACHABLE;

// TODO [REPLICA-EXTRA-NODES - to be owned by another developer]: the failover is now done from the client side.
//  Before, unsetting replica was failing over the replica nodes through ReplicaChangeHandler (now removed).
//  Now:
//  1. each replica node (from the configuration) which is not ACTIVE yet is failed over with a JMX call through
//     the diagnostic port (Server#replicaFailoverToActive), and we wait for it to become ACTIVE
//  2. only when all the replica nodes are ACTIVE (so each stripe has 1 active), the replica flag is unset on them
//     with a normal unset, which restarts the other nodes of the stripes (in diagnostic mode) so that they join the
//     new actives as passives
//  If some replica nodes cannot be failed over (offline, not synced, ...), the command fails after failing over the
//  others, and the user can fix the problem and run the command again: replica nodes already ACTIVE are skipped.
//  To verify: the Server MBean exposes replicaFailoverToActive through the diagnostic port (it was called locally by
//  ReplicaChangeHandler with server.getManagement()), and it returns "true" when the transition succeeded.
//  Tests to add (and existing tests to update):
//  - unit: replica nodes in PASSIVE-REPLICA are failed over (JMX call) then unset is called on the replica nodes only
//  - unit: replica nodes already ACTIVE are not failed over again, and unset is called
//  - unit: one replica node unreachable or not becoming ACTIVE => the other ones are failed over, unset is NOT called, the command fails listing the node
//  - unit: no replica node in the configuration => the command fails
//  - IT ReplicaFailoverCommand2x1IT: update the tests, the failover now happens before the nomad change, so with a
//    prepare/commit failure the nodes are ACTIVE and replica=true, and running replica-failover again completes the unset
//  - IT: 1x2 replica cluster: replica-failover => node-1-1 becomes ACTIVE without restart, node-1-2 is restarted and becomes PASSIVE
//  - IT: 2x2 replica cluster with node-2-1 offline: replica-failover fails, stripe 1 is ACTIVE; start node-2-1, run again => all ACTIVE and PASSIVE
//  - IT: unset replica directly on a replica cluster in PASSIVE-REPLICA state is refused (use replica-failover)
public class ReplicaFailoverAction extends RemoteAction {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReplicaFailoverAction.class);

  @Inject
  public UnsetAction unsetAction = new UnsetAction();

  private List<HostPort> nodes = emptyList();

  public void setNodes(List<HostPort> nodes) {
    this.nodes = nodes;
  }

  @Override
  public void run() {
    Map<Endpoint, LogicalServerState> allNodes = findRuntimePeersStatus(nodes);
    Cluster cluster = getUpcomingCluster(nodes);

    List<Node> replicaNodes = cluster.getNodes().stream().filter(DisasterRecoveryMode::isReplica).toList();
    if (replicaNodes.isEmpty()) {
      throw new IllegalStateException("Cannot execute replica-failover: no replica node found in the cluster configuration.");
    }

    // 1. fail over each replica node which is not ACTIVE yet
    Collection<String> notFailedOver = new TreeSet<>();
    for (Node replicaNode : replicaNodes) {
      Endpoint endpoint = allNodes.keySet().stream().filter(e -> e.getNodeUID().equals(replicaNode.getUID())).findFirst().orElse(null);
      LogicalServerState state = endpoint == null ? UNREACHABLE : allNodes.get(endpoint);

      if (state.isActive()) {
        output.info("Node: {} is already ACTIVE", endpoint);

      } else if (state == REPLICA || state == REPLICA_SUSPENDED) {
        if (state == REPLICA_SUSPENDED) {
          output.warn("Node: {} is in PASSIVE-REPLICA-START state (not fully synced). Trying to fail it over. If it fails to transition to ACTIVE, please seek support.", endpoint);
        }
        if (!failoverToActive(endpoint) || !waitForActive(endpoint)) {
          notFailedOver.add(replicaNode.getName() + " (" + state + ")");
        }

      } else {
        notFailedOver.add(replicaNode.getName() + " (" + state + ")");
      }
    }

    if (!notFailedOver.isEmpty()) {
      throw new IllegalStateException("Replica-failover is not complete: the following replica nodes did not become ACTIVE: " + toString(notFailedOver) + ". " +
          "The other replica nodes have been failed over. Please fix these nodes and run the replica-failover command again to complete the failover.");
    }

    // 2. all stripes have their replica node ACTIVE: unset the replica flag, which restarts the other nodes of the stripes
    unsetAction.setNodes(nodes);
    List<ConfigurationInput> inputs = replicaNodes.stream()
        .map(node -> new ConfigurationInput(node.getName() + ":" + SettingName.REPLICA))
        .toList();
    unsetAction.setConfigurationInputs(inputs);
    unsetAction.run();
  }

  private boolean failoverToActive(Endpoint endpoint) {
    output.info("Failing over replica node: {} to ACTIVE", endpoint);
    try (DiagnosticService diagnosticService = diagnosticServiceProvider.fetchDiagnosticService(endpoint.getHostPort().createInetSocketAddress())) {
      String result = diagnosticService.invoke(MBEAN_SERVER, "replicaFailoverToActive");
      if ("true".equals(result)) {
        return true;
      }
      LOGGER.warn("Failed to fail over replica node: {} to ACTIVE: {}", endpoint, result);
      return false;
    } catch (RuntimeException e) {
      LOGGER.warn("Failed to fail over replica node: {} to ACTIVE: {}", endpoint, e.getMessage(), e);
      return false;
    }
  }
}
