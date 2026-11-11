/*
 * Copyright Terracotta, Inc.
 * Copyright IBM Corp. 2024, 2026
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
import org.terracotta.common.struct.TimeUnit;
import org.terracotta.common.struct.Tuple2;
import org.terracotta.diagnostic.client.connection.DiagnosticServices;
import org.terracotta.diagnostic.model.LogicalServerState;
import org.terracotta.dynamic_config.api.model.Cluster;
import org.terracotta.dynamic_config.api.model.Configuration;
import org.terracotta.dynamic_config.api.model.DisasterRecoveryMode;
import org.terracotta.dynamic_config.api.model.Node;
import org.terracotta.dynamic_config.api.model.Node.Endpoint;
import org.terracotta.dynamic_config.api.model.Operation;
import org.terracotta.dynamic_config.api.model.PropertyHolder;
import org.terracotta.dynamic_config.api.model.Stripe;
import org.terracotta.dynamic_config.api.model.UID;
import org.terracotta.dynamic_config.api.model.nomad.MultiSettingNomadChange;
import org.terracotta.dynamic_config.api.model.nomad.SettingNomadChange;
import org.terracotta.dynamic_config.api.service.ClusterValidator;
import org.terracotta.dynamic_config.cli.api.restart.RestartProgress;
import org.terracotta.inet.HostPort;

import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static java.lang.System.lineSeparator;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.terracotta.diagnostic.model.LogicalServerState.ACTIVE;
import static org.terracotta.diagnostic.model.LogicalServerState.ACTIVE_RECONNECTING;
import static org.terracotta.diagnostic.model.LogicalServerState.DIAGNOSTIC;
import static org.terracotta.diagnostic.model.LogicalServerState.PASSIVE;
import static org.terracotta.diagnostic.model.LogicalServerState.PASSIVE_SUSPENDED;
import static org.terracotta.diagnostic.model.LogicalServerState.RELAY;
import static org.terracotta.diagnostic.model.LogicalServerState.REPLICA;
import static org.terracotta.diagnostic.model.LogicalServerState.REPLICA_SUSPENDED;
import static org.terracotta.diagnostic.model.LogicalServerState.SYNCHRONIZING;
import static org.terracotta.dynamic_config.api.model.ClusterState.ACTIVATED;
import static org.terracotta.dynamic_config.api.model.ClusterState.CONFIGURING;
import static org.terracotta.dynamic_config.api.model.Requirement.CLUSTER_ONLINE;
import static org.terracotta.dynamic_config.api.model.Requirement.CLUSTER_RESTART;
import static org.terracotta.dynamic_config.api.model.Requirement.NODE_RESTART;
import static org.terracotta.dynamic_config.api.model.Scope.NODE;

public abstract class ConfigurationMutationAction extends ConfigurationAction {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationMutationAction.class);

  protected boolean autoRestart;
  protected boolean force;

  protected ConfigurationMutationAction(Operation operation) {
    super(operation);
  }

  public void setAutoRestart(boolean autoRestart) {
    this.autoRestart = autoRestart;
  }

  public void setForce(boolean force) {
    this.force = force;
  }

  @Override
  public void run() {
    validate();
    if (configurations.isEmpty()) {
      // no remaining configuration changes left to process
      output.info("Command successful!");
      return;
    }
    LOGGER.debug("Validating the new configuration change(s) against the topology of: {}", node);

    // get the remote topology, apply the parameters, and validate that the cluster is still valid
    Cluster originalCluster = getUpcomingCluster(node);
    Cluster updatedCluster = originalCluster.clone();

    // will keep track of the targeted nodes for the changes of a node setting
    Collection<String> nodesRequiringRestart = new TreeSet<>();
    Collection<String> targetedNodes = new TreeSet<>();

    // applying the set/unset operation to the cluster in memory for validation
    for (Configuration c : configurations) {
      Collection<? extends PropertyHolder> targets = c.apply(updatedCluster);

      // keep track of the node targeted by this configuration line
      // remember: a node setting can be set using a cluster or stripe namespace to target several nodes at once
      // Note: this validation is only for node-specific settings
      targets.stream()
          .filter(o -> o.getScope() == NODE)
          .map(PropertyHolder::getName)
          .filter(originalCluster::containsNode)
          // we filter out relay nodes, they are not contacted to apply configuration changes at runtime
          // Instead, they will be automatically restarted to sync configuration changes during passive sync
          .filter(name -> !originalCluster.is(name, DisasterRecoveryMode.RELAY))
          .forEach(name -> {
            targetedNodes.add(name);
            if (c.getSetting().requires(NODE_RESTART)) {
              nodesRequiringRestart.add(name);
            }
          });
    }

    ensureRelayIsDisabledBeforeSettingReplica(originalCluster, updatedCluster);

    if (updatedCluster.equals(originalCluster)) {
      String message = "The requested update will not result in any change to the cluster configuration.";
      output.out(message);
      LOGGER.warn(lineSeparator() +
          "=======================================================================================" + lineSeparator() +
          message + lineSeparator() +
          "=======================================================================================" + lineSeparator());
      return;
    }

    // get the current state of the nodes
    // this call can take some time, and we can have some timeout
    Map<Endpoint, LogicalServerState> onlineNodes = findOnlineRuntimePeers(node);
    LOGGER.debug("Online nodes: {}", onlineNodes);

    boolean allOnlineNodesActivated = areAllNodesActivated(onlineNodes.keySet());

    new ClusterValidator(updatedCluster).validate(allOnlineNodesActivated ? ACTIVATED : CONFIGURING);

    if (allOnlineNodesActivated) {
      licenseValidation(node, updatedCluster);
    }

    // ensure that the nodes targeted by the set or unset command in the namespaces are all online so that they can validate the change
    Collection<String> missingTargetedNodes = new TreeSet<>(targetedNodes);
    onlineNodes.keySet().stream().map(Endpoint::getNodeName).forEach(missingTargetedNodes::remove);
    if (!missingTargetedNodes.isEmpty()) {
      throw new IllegalStateException("Some nodes that are targeted by the change are not reachable and thus cannot be validated. " +
          "Please ensure these nodes are online, or remove them from the request: " + toString(missingTargetedNodes));
    }

    LOGGER.debug("New configuration change(s) can be sent");

    if (allOnlineNodesActivated) {
      // Filter out relay nodes from onlineNodes, they are not contacted to apply configuration changes at runtime
      // onlineRelayNodes will contain the list of relay nodes we will automatically restart
      // to sync configuration changes during passive sync
      final Collection<Endpoint> onlineRelayNodes = removeRelayNodes(onlineNodes);

      // nodes becoming replica, or not being replica anymore (failover)
      final Collection<String> newReplicaNodes = replicaNodesDiff(updatedCluster, originalCluster);
      final Collection<String> formerReplicaNodes = replicaNodesDiff(originalCluster, updatedCluster);
      if (!newReplicaNodes.isEmpty() && !formerReplicaNodes.isEmpty()) {
        throw new IllegalArgumentException("Cannot set replica on nodes: " + toString(newReplicaNodes) + " and unset replica on nodes: " + toString(formerReplicaNodes) + " in the same command");
      }
      ensureReplicaNodesAreFailedOver(onlineNodes, formerReplicaNodes);

      // cluster is active, we need to run a nomad change and eventually a restart
      MultiSettingNomadChange changes = getNomadChanges(updatedCluster);
      ensureClusterIsReadyForChange(originalCluster, onlineNodes, requiresAllNodesAlive());
      output.info("Applying new configuration change(s) to activated nodes: {}", toString(onlineNodes.keySet()));
      runConfigurationChange(originalCluster, updatedCluster, onlineNodes, changes);

      // display unreachable nodes
      final Set<String> unreachableRelayNodes = originalCluster.getNodes()
        .stream()
        .filter(DisasterRecoveryMode::isRelay)
        .filter(node -> node.getEndpoints().stream().noneMatch(onlineRelayNodes::contains))
        .map(Node::getName)
        .collect(Collectors.toSet());
      if (!unreachableRelayNodes.isEmpty()) {
        output.info("Relay nodes: {} are unreachable. Please restart them manually to apply the configuration changes.", toString(unreachableRelayNodes));
      }

      RestartProgress relayRestartProgress = null;
      if (!onlineRelayNodes.isEmpty()) {
        output.info("Performing automatic restart of relay nodes: {}. " +
          "If the restart fails, please verify the nodes status or restart manually.", toString(onlineRelayNodes));
        relayRestartProgress = restartService.restartNodesIfPassives(
          onlineRelayNodes,
          Duration.ofMillis(restartDelay.getQuantity(TimeUnit.MILLISECONDS)),
          EnumSet.of(ACTIVE, ACTIVE_RECONNECTING, PASSIVE, RELAY));
      }
      // do we need to restart to apply the changes ?
      if (!newReplicaNodes.isEmpty()) {
        restartAfterReplicaSet(updatedCluster, onlineNodes);
      } else if (!formerReplicaNodes.isEmpty()) {
        restartAfterReplicaUnset(updatedCluster, onlineNodes, formerReplicaNodes);
      } else if (changes.getChanges().stream().map(SettingNomadChange::getSetting).anyMatch(setting -> setting.requires(CLUSTER_RESTART))) {
        output.out("Restart required for cluster");
        if (autoRestart) {
          if (isReplicaCluster(originalCluster)) {
            restartNodes(onlineNodes.keySet());
          } else {
            rollingRestart(updatedCluster, onlineNodes.keySet().stream().collect(toMap(Endpoint::getNodeName, identity())));
          }
        } else {
          LOGGER.warn(lineSeparator() +
              "====================================================================" + lineSeparator() +
              "IMPORTANT: A manual restart of the cluster is required to apply the changes" + lineSeparator() +
              "====================================================================" + lineSeparator());
        }
      } else {
        final long numberOfDifferentSettingsToChange = configurations.stream()
            .map(Configuration::getSetting)
            .distinct()
            .count();
        final long numberOfDifferentSettingsRequiringRestart = configurations.stream()
            .map(Configuration::getSetting)
            .distinct()
            .filter(setting -> setting.requires(NODE_RESTART))
            .count();

        if (numberOfDifferentSettingsToChange != numberOfDifferentSettingsRequiringRestart) {
          // if the user updates more than 1 setting that does not require a restart, or a mix of settings which
          // do and do not require a restart, we will go there.
          // I.e. set backup-dir
          // I.e. set log-dir + backup-dir
          // In that case, we might already have some nodes inside nodesRequiringRestart.
          // But we need to add into this collection the other nodes that are targeted AND could have vetoed a change to be applied at runtime
          for (Endpoint endpoint : new LinkedHashSet<>(onlineNodes.keySet())) {
            try {
              if (targetedNodes.contains(endpoint.getNodeName()) && mustBeRestarted(endpoint)) {
                nodesRequiringRestart.add(endpoint.getNodeName());
              }
            } catch (RuntimeException e) {
              // some nodes might have failed over and not be reachable anymore
              LOGGER.warn("Node: " + endpoint + " is not reachable anymore: {}", e.getMessage(), e);
              onlineNodes.remove(endpoint);
            }
          }
        }

        if (!nodesRequiringRestart.isEmpty()) {
          List<HostPort> addresses = onlineNodes.keySet()
              .stream()
              .filter(endpoint -> nodesRequiringRestart.contains(endpoint.getNodeName()))
              .map(Endpoint::getHostPort)
              .collect(toList());
          output.out("Restart required for nodes: {} ", toString(addresses));
          if (autoRestart) {
            if (isReplicaCluster(originalCluster)) {
              restartNodes(onlineNodes.keySet().stream()
                .filter(endpoint -> nodesRequiringRestart.contains(endpoint.getNodeName()))
                .collect(toList()));
            } else {
              rollingRestart(updatedCluster, onlineNodes.keySet().stream()
                .filter(endpoint -> nodesRequiringRestart.contains(endpoint.getNodeName()))
                .collect(toMap(Endpoint::getNodeName, identity())));
            }
          } else {
            LOGGER.warn(lineSeparator() +
                "=======================================================================================" + lineSeparator() +
                "IMPORTANT: A manual restart of nodes: " + toString(nodesRequiringRestart) + " is required to apply the changes" + lineSeparator() +
                "=======================================================================================" + lineSeparator());
          }
        }
      }

      if (!onlineRelayNodes.isEmpty()) {
        followRestart(relayRestartProgress, onlineRelayNodes);
      }
    } else {
      // cluster is not active, we just need to replace the topology
      output.info("Applying new configuration change(s) to nodes: {}", toString(onlineNodes.keySet()));
      try (DiagnosticServices<UID> diagnosticServices = multiDiagnosticServiceProvider.fetchOnlineDiagnosticServices(endpointsToMap(onlineNodes.keySet()))) {
        dynamicConfigServices(diagnosticServices)
            .map(Tuple2::getT2)
            .forEach(dynamicConfigService -> dynamicConfigService.setUpcomingCluster(updatedCluster));
      }
    }

    output.info("Command successful!");
  }

  /**
   * Relay and replica cannot be changed in the same command: relay nodes are not contacted to apply configuration changes,
   * they only get them by restarting and syncing from the active. Once a stripe has a replica, its other nodes restart
   * in diagnostic mode, so a relay node that missed the change would never be able to sync it.
   * Relay must be disabled on all nodes first, then replica can be set in a separate command.
   */
  private void ensureRelayIsDisabledBeforeSettingReplica(Cluster originalCluster, Cluster updatedCluster) {
    List<String> relayNodes = originalCluster.getNodes().stream().filter(DisasterRecoveryMode::isRelay).map(Node::getName).collect(toList());
    List<String> replicaNodes = updatedCluster.getNodes().stream().filter(DisasterRecoveryMode::isReplica).map(Node::getName).collect(toList());
    if (!relayNodes.isEmpty() && !replicaNodes.isEmpty()) {
      throw new IllegalStateException("Cannot set replica on nodes: " + toString(replicaNodes) + " while relay is enabled on nodes: " + toString(relayNodes) + ". " +
          "Disable relay on all nodes first, then set replica in a separate command.");
    }
  }

  /**
   * @return the names of the nodes which are replica in cluster, but not in other
   */
  private static Collection<String> replicaNodesDiff(Cluster cluster, Cluster other) {
    return cluster.getNodes().stream()
        .filter(DisasterRecoveryMode::isReplica)
        .filter(node -> other.getNode(node.getUID()).map(n -> !DisasterRecoveryMode.isReplica(n)).orElse(true))
        .map(Node::getName)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  /**
   * After setting replica on an activated cluster, the replica nodes must restart to start as replica,
   * and the other nodes of their stripes must restart to start in diagnostic mode (this is decided at startup only).
   * The nodes are the ones reporting they must be restarted (see Setting.REPLICA vetoes).
   * <p>
   * The restart is always done, even without -auto-restart, because the cluster cannot work correctly until these nodes are restarted.
   * Nodes are restarted all at once (not a rolling restart): the stripes won't have any active node anymore after the restart.
   */
  private void restartAfterReplicaSet(Cluster updatedCluster, Map<Endpoint, LogicalServerState> onlineNodes) {
    Collection<String> manualRestart = new TreeSet<>();
    Collection<Endpoint> toRestart = findNodesToRestart(onlineNodes.keySet(), manualRestart);
    warnOfflineNodes(updatedCluster, onlineNodes);
    restartForReplicaChange(toRestart, EnumSet.of(REPLICA_SUSPENDED, REPLICA, DIAGNOSTIC), manualRestart);
  }

  // TODO [REPLICA-EXTRA-NODES - to be owned by another developer]: unsetting replica does not fail over anymore
  //  (ReplicaChangeHandler was removed): the replica nodes must have been failed over to ACTIVE before, by the
  //  replica-failover command, which then unsets the replica flag. A plain "unset replica" is only accepted once the
  //  replica nodes are ACTIVE.
  //  Tests to add:
  //  - unit: unset replica on an activated replica cluster with the replica node in PASSIVE-REPLICA state fails with this message
  //  - unit: unset replica on an activated replica cluster with the replica node ACTIVE succeeds
  private void ensureReplicaNodesAreFailedOver(Map<Endpoint, LogicalServerState> onlineNodes, Collection<String> formerReplicaNodes) {
    Collection<String> notActive = new TreeSet<>(formerReplicaNodes);
    onlineNodes.forEach((endpoint, state) -> {
      if (state.isActive()) {
        notActive.remove(endpoint.getNodeName());
      }
    });
    if (!notActive.isEmpty()) {
      throw new IllegalStateException("Cannot unset replica on nodes: " + toString(notActive) + " because they are not ACTIVE. " +
          "Please use the replica-failover command to fail over the replica nodes.");
    }
  }

  /**
   * After unsetting replica on an activated replica cluster, the former replica nodes are already active
   * (they were failed over by the replica-failover command), and the other nodes of their stripes, which are in
   * diagnostic mode, must restart to join the new active as passives.
   * <p>
   * The former replica nodes are never restarted: they are the only active nodes of their stripe.
   * The other nodes of a stripe are only restarted once the former replica of the stripe is active: restarting them
   * without an active node in the stripe would make one of them become active with no data.
   * <p>
   * The restart is always done, even without -auto-restart, because the cluster cannot work correctly until these nodes are restarted.
   */
  private void restartAfterReplicaUnset(Cluster updatedCluster, Map<Endpoint, LogicalServerState> onlineNodes, Collection<String> formerReplicaNodes) {
    Collection<String> manualRestart = new TreeSet<>();
    Collection<Endpoint> toRestart = new TreeSet<>(Comparator.comparing(Endpoint::toString));

    for (Endpoint formerReplica : onlineNodes.keySet()) {
      if (!formerReplicaNodes.contains(formerReplica.getNodeName())) {
        continue;
      }
      Stripe stripe = updatedCluster.getStripeByNode(formerReplica.getNodeUID()).orElseThrow(AssertionError::new);
      List<Endpoint> peers = onlineNodes.keySet().stream()
          .filter(endpoint -> !endpoint.equals(formerReplica) && stripe.containsNode(endpoint.getNodeUID()))
          .collect(toList());
      Collection<Endpoint> peersToRestart = findNodesToRestart(peers, manualRestart);

      if (waitForActive(formerReplica)) {
        toRestart.addAll(peersToRestart);
      } else {
        peersToRestart.stream().map(Endpoint::getNodeName).forEach(manualRestart::add);
        output.warn("IMPORTANT: Node: {} did not become ACTIVE. Nodes: {} in stripe: {} were not restarted. " +
            "They must only be restarted once a node of the stripe is ACTIVE, otherwise one of them could become ACTIVE without data.", formerReplica, toString(peersToRestart), stripe.getName());
      }

      if (mustBeRestarted(formerReplica)) {
        // i.e. other settings requiring a restart were changed in the same command
        output.warn("IMPORTANT: Node: {} is now ACTIVE and has pending changes requiring a restart. " +
            "It was not restarted. Please restart it manually once the other nodes of stripe: {} are PASSIVE.", formerReplica, stripe.getName());
      }
    }

    warnOfflineNodes(updatedCluster, onlineNodes);
    restartForReplicaChange(toRestart, EnumSet.of(ACTIVE, ACTIVE_RECONNECTING, PASSIVE, PASSIVE_SUSPENDED, SYNCHRONIZING), manualRestart);
  }

  /**
   * @return the endpoints that must be restarted. Endpoints which cannot be asked are added to manualRestart.
   */
  private Collection<Endpoint> findNodesToRestart(Collection<Endpoint> endpoints, Collection<String> manualRestart) {
    Collection<Endpoint> toRestart = new TreeSet<>(Comparator.comparing(Endpoint::toString));
    for (Endpoint endpoint : endpoints) {
      try {
        if (mustBeRestarted(endpoint)) {
          toRestart.add(endpoint);
        }
      } catch (RuntimeException e) {
        LOGGER.warn("Node: {} is not reachable anymore: {}", endpoint, e.getMessage(), e);
        manualRestart.add(endpoint.getNodeName());
      }
    }
    return toRestart;
  }

  private void restartForReplicaChange(Collection<Endpoint> toRestart, Collection<LogicalServerState> acceptedStates, Collection<String> manualRestart) {
    if (!toRestart.isEmpty()) {
      output.info("Restarting nodes: {} to apply the replica change", toString(toRestart));
      try {
        restartNodes(toRestart, acceptedStates);
      } catch (RuntimeException e) {
        LOGGER.warn("Failed to restart nodes: {}: {}", toString(toRestart), e.getMessage(), e);
        toRestart.stream().map(Endpoint::getNodeName).forEach(manualRestart::add);
      }
    }
    if (!manualRestart.isEmpty()) {
      output.warn("IMPORTANT: The replica change has been applied, but nodes: {} may not have been restarted. " +
          "Please verify their state and restart them manually to apply the change.", toString(manualRestart));
    }
  }

  /**
   * Offline nodes did not receive the change and will start with their previous configuration.
   */
  private void warnOfflineNodes(Cluster updatedCluster, Map<Endpoint, LogicalServerState> onlineNodes) {
    Collection<String> offline = updatedCluster.getNodes().stream().map(Node::getName).collect(Collectors.toCollection(TreeSet::new));
    onlineNodes.keySet().stream().map(Endpoint::getNodeName).forEach(offline::remove);
    if (!offline.isEmpty()) {
      output.warn("IMPORTANT: Nodes: {} were offline and did not receive the replica change. " +
          "They will start with their previous configuration. Please detach them from the cluster.", toString(offline));
    }
  }

  private MultiSettingNomadChange getNomadChanges(Cluster cluster) {
    // MultiSettingNomadChange will apply to whole change set given by the user as an atomic operation
    return new MultiSettingNomadChange(configurations.stream()
        .map(configuration -> {
          configuration.validate(clusterState, operation);
          return SettingNomadChange.fromConfiguration(configuration, operation, cluster);
        })
        .collect(toList()));
  }

  private boolean requiresAllNodesAlive() {
    // By default, we require all nodes to be up to allow a DC change: the consensus system needs all nodes to be able to vote and prepare the change.
    // This will also solve any potential partitioned config to happen (or loosing a change if a failover happens just after),
    // in the case a nomad change is prepared at the same time a passive is synchronizing the DC configuration from active server.
    // This can be overridden by the user if he knows what he is doing with: -force
    // In any case, if the changes requires all nodes to be up (like SSL), we will require all nodes to be up even if -force is used.
    return !force || configurations.stream().map(Configuration::getSetting).anyMatch(setting -> setting.requires(CLUSTER_ONLINE));
  }

  private void rollingRestart(Cluster cluster, Map<String, Endpoint> onlineNodesToRestart) {
    // node that we cannot restart
    Collection<Endpoint> cannotRestart = new LinkedHashSet<>();

    Collection<Endpoint> attempted = rollingRestart(cluster, onlineNodesToRestart.values(), cannotRestart);

    // let's check if some nodes were not restarted because their state has changed from the last time we got their state
    attempted.stream()
        .filter(endpoint -> {
          try {
            return mustBeRestarted(endpoint);
          } catch (RuntimeException e) {
            LOGGER.warn("Node: {} is not reachable anymore: {}", endpoint, e.getMessage(), e);
            return true;
          }
        }).forEach(cannotRestart::add);

    if (!cannotRestart.isEmpty()) {
      LOGGER.warn(lineSeparator() +
          "=======================================================================================" + lineSeparator() +
          "IMPORTANT: A manual restart of nodes: " + toString(cannotRestart) + " will be required to apply the changes" + lineSeparator() +
          "=======================================================================================" + lineSeparator());
    }
  }
}
