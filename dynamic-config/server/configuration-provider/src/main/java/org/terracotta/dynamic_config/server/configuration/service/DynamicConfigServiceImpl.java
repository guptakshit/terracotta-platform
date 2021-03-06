/*
 * Copyright Terracotta, Inc.
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
package org.terracotta.dynamic_config.server.configuration.service;

import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.dynamic_config.api.model.Cluster;
import org.terracotta.dynamic_config.api.model.License;
import org.terracotta.dynamic_config.api.model.Node;
import org.terracotta.dynamic_config.api.model.NodeContext;
import org.terracotta.dynamic_config.api.model.nomad.DynamicConfigNomadChange;
import org.terracotta.dynamic_config.api.model.nomad.MultiSettingNomadChange;
import org.terracotta.dynamic_config.api.model.nomad.SettingNomadChange;
import org.terracotta.dynamic_config.api.service.ClusterValidator;
import org.terracotta.dynamic_config.api.service.DynamicConfigService;
import org.terracotta.dynamic_config.api.service.TopologyService;
import org.terracotta.dynamic_config.server.api.DynamicConfigEventService;
import org.terracotta.dynamic_config.server.api.DynamicConfigListener;
import org.terracotta.dynamic_config.server.api.EventRegistration;
import org.terracotta.dynamic_config.server.api.InvalidLicenseException;
import org.terracotta.dynamic_config.server.api.LicenseService;
import org.terracotta.entity.StateDumpCollector;
import org.terracotta.entity.StateDumpable;
import org.terracotta.json.Json;
import org.terracotta.nomad.messages.AcceptRejectResponse;
import org.terracotta.nomad.messages.ChangeDetails;
import org.terracotta.nomad.messages.CommitMessage;
import org.terracotta.nomad.messages.DiscoverResponse;
import org.terracotta.nomad.messages.PrepareMessage;
import org.terracotta.nomad.messages.RollbackMessage;
import org.terracotta.nomad.server.NomadChangeInfo;
import org.terracotta.nomad.server.NomadException;
import org.terracotta.server.ServerEnv;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.util.Objects.requireNonNull;
import static org.terracotta.server.StopAction.RESTART;
import static org.terracotta.server.StopAction.ZAP;

public class DynamicConfigServiceImpl implements TopologyService, DynamicConfigService, DynamicConfigEventService, DynamicConfigListener, StateDumpable {

  private static final Logger LOGGER = LoggerFactory.getLogger(DynamicConfigServiceImpl.class);
  private static final String LICENSE_FILE_NAME = "license.xml";

  private final LicenseService licenseService;
  private final NomadServerManager nomadServerManager;
  private final List<DynamicConfigListener> listeners = new CopyOnWriteArrayList<>();
  private final Path licensePath;

  private volatile NodeContext upcomingNodeContext;
  private volatile NodeContext runtimeNodeContext;
  private volatile boolean clusterActivated;

  public DynamicConfigServiceImpl(NodeContext nodeContext, LicenseService licenseService, NomadServerManager nomadServerManager) {
    this.upcomingNodeContext = requireNonNull(nodeContext);
    this.runtimeNodeContext = requireNonNull(nodeContext);
    this.licenseService = requireNonNull(licenseService);
    this.nomadServerManager = requireNonNull(nomadServerManager);
    this.licensePath = nomadServerManager.getConfigurationManager().getLicensePath().resolve(LICENSE_FILE_NAME);
    if (hasLicenseFile()) {
      validateAgainstLicense(upcomingNodeContext.getCluster());
    }
    new ClusterValidator(nodeContext.getCluster()).validate();
  }

  /**
   * called from startup manager (in case we want a pre-activated node) (and this class) to make Nomad RW.
   */
  public synchronized void activate() {
    if (isActivated()) {
      throw new AssertionError("Already activated");
    }
    LOGGER.info("Preparing activation of Node with validated topology: {}", upcomingNodeContext.getCluster().toShapeString());
    nomadServerManager.upgradeForWrite(upcomingNodeContext.getStripeId(), upcomingNodeContext.getNodeName());
    LOGGER.debug("Setting nomad writable successful");

    clusterActivated = true;
    LOGGER.info("Node activation successful");
  }

  // do not move this method up in the interface otherwise any client could access the license content through diagnostic port
  public synchronized Optional<String> getLicenseContent() {
    Path targetLicensePath = nomadServerManager.getConfigurationManager().getLicensePath().resolve(LICENSE_FILE_NAME);
    if (Files.exists(targetLicensePath)) {
      try {
        return Optional.of(new String(Files.readAllBytes(targetLicensePath), StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return Optional.empty();
  }

  @Override
  public void addStateTo(StateDumpCollector stateDumpCollector) {
    stateDumpCollector.addState("licensePath", licensePath.toString());
    stateDumpCollector.addState("hasLicenseFile", hasLicenseFile());
    stateDumpCollector.addState("configurationDir", nomadServerManager.getConfigurationManager().getConfigurationDirectory().toString());
    stateDumpCollector.addState("activated", isActivated());
    stateDumpCollector.addState("mustBeRestarted", mustBeRestarted());
    stateDumpCollector.addState("runtimeNodeContext", Json.parse(Json.toJson(getRuntimeNodeContext()), new TypeReference<Map<String, ?>>() {}));
    stateDumpCollector.addState("upcomingNodeContext", Json.parse(Json.toJson(getUpcomingNodeContext()), new TypeReference<Map<String, ?>>() {}));
    StateDumpCollector nomad = stateDumpCollector.subStateDumpCollector("Nomad");
    try {
      DiscoverResponse<NodeContext> discoverResponse = nomadServerManager.getNomadServer().discover();
      nomad.addState("mode", discoverResponse.getMode().name());
      nomad.addState("currentVersion", discoverResponse.getCurrentVersion());
      nomad.addState("highestVersion", discoverResponse.getHighestVersion());
      nomad.addState("mutativeMessageCount", discoverResponse.getMutativeMessageCount());
      nomad.addState("lastMutationUser", discoverResponse.getLastMutationUser());
      nomad.addState("lastMutationHost", discoverResponse.getLastMutationHost());
      nomad.addState("lastMutationTimestamp", discoverResponse.getLastMutationTimestamp());
      ChangeDetails<NodeContext> changeDetails = discoverResponse.getLatestChange();
      if (changeDetails != null) {
        StateDumpCollector latestChange = stateDumpCollector.subStateDumpCollector("latestChange");
        latestChange.addState("uuid", changeDetails.getChangeUuid().toString());
        latestChange.addState("state", changeDetails.getState().name());
        latestChange.addState("creationUser", changeDetails.getCreationUser());
        latestChange.addState("creationHost", changeDetails.getCreationHost());
        latestChange.addState("creationTimestamp", changeDetails.getCreationTimestamp());
        latestChange.addState("version", changeDetails.getVersion());
        latestChange.addState("summary", changeDetails.getOperation().getSummary());
      }
    } catch (NomadException e) {
      nomad.addState("error", e.getMessage());
    }
  }

  @Override
  public EventRegistration register(DynamicConfigListener listener) {
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }

  @Override
  public void onSettingChanged(SettingNomadChange change, Cluster updated) {
    if (change.canApplyAtRuntime()) {
      LOGGER.info("Configuration change: {} applied at runtime", change.getSummary());
    } else {
      LOGGER.info("Configuration change: {} will be applied after restart", change.getSummary());
    }
    // do not fire events within a synchronized block
    listeners.forEach(c -> c.onSettingChanged(change, updated));
  }

  @Override
  public void onNewConfigurationSaved(NodeContext nodeContext, Long version) {
    LOGGER.info("New configuration directory version: {} has been saved", version);
    // do not fire events within a synchronized block
    NodeContext upcoming = getUpcomingNodeContext();
    listeners.forEach(c -> c.onNewConfigurationSaved(upcoming, version));
  }

  @Override
  public void onNodeRemoval(int stripeId, Node removedNode) {
    InetSocketAddress addr = removedNode.getNodeAddress();
    LOGGER.info("Removed node: {} from stripe ID: {}", addr, stripeId);
    // do not fire events within a synchronized block
    listeners.forEach(c -> c.onNodeRemoval(stripeId, removedNode));
  }

  @Override
  public void onNodeAddition(int stripeId, Node addedNode) {
    LOGGER.info("Added node:{} to stripe ID: {}", addedNode.getNodeAddress(), stripeId);
    // do not fire events within a synchronized block
    listeners.forEach(c -> c.onNodeAddition(stripeId, addedNode));
  }

  @Override
  public void onNomadPrepare(PrepareMessage message, AcceptRejectResponse response) {
    if (response.isAccepted()) {
      LOGGER.info("Nomad change {} prepared: {}", message.getChangeUuid(), message.getChange().getSummary());
    } else {
      LOGGER.warn("Nomad change {} failed to prepare: {}", message.getChangeUuid(), response);
    }
  }

  @Override
  public void onNomadCommit(CommitMessage message, AcceptRejectResponse response, NomadChangeInfo changeInfo) {
    if (response.isAccepted()) {
      DynamicConfigNomadChange dynamicConfigNomadChange = (DynamicConfigNomadChange) changeInfo.getNomadChange();
      LOGGER.info("Nomad change {} committed: {}", message.getChangeUuid(), dynamicConfigNomadChange.getSummary());

      // extract the changes since there can be multiple settings change
      List<? extends DynamicConfigNomadChange> nomadChanges = MultiSettingNomadChange.extractChanges(dynamicConfigNomadChange);

      // the following code will be executed on all the nodes, regardless of the applicability
      // level to update the config
      synchronized (this) {
        for (DynamicConfigNomadChange nomadChange : nomadChanges) {
          // first we update the upcoming one
          Cluster upcomingCluster = nomadChange.apply(upcomingNodeContext.getCluster());
          upcomingNodeContext = upcomingNodeContext.withCluster(upcomingCluster).orElseGet(upcomingNodeContext::alone);
          // if the change can be applied at runtime, it was previously done in the config change handler.
          // so update also the runtime topology there
          if (nomadChange.canApplyAtRuntime()) {
            Cluster runtimeCluster = nomadChange.apply(runtimeNodeContext.getCluster());
            runtimeNodeContext = runtimeNodeContext.withCluster(runtimeCluster).orElseGet(runtimeNodeContext::alone);
          }
        }
      }
    } else {
      LOGGER.warn("Nomad change {} failed to commit: {}", message.getChangeUuid(), response);
    }

    listeners.forEach(c -> c.onNomadCommit(message, response, changeInfo));
  }

  @Override
  public void onNomadRollback(RollbackMessage message, AcceptRejectResponse response) {
    if (response.isAccepted()) {
      LOGGER.info("Nomad change {} rolled back", message.getChangeUuid());
    } else {
      LOGGER.warn("Nomad change {} failed to rollback: {}", message.getChangeUuid(), response);
    }
  }

  @Override
  public synchronized NodeContext getUpcomingNodeContext() {
    return upcomingNodeContext.clone();
  }

  @Override
  public synchronized NodeContext getRuntimeNodeContext() {
    return runtimeNodeContext.clone();
  }

  @Override
  public boolean isActivated() {
    return clusterActivated;
  }

  @Override
  public synchronized boolean mustBeRestarted() {
    return !runtimeNodeContext.equals(upcomingNodeContext);
  }

  @Override
  public boolean hasIncompleteChange() {
    return nomadServerManager.getNomadServer().hasIncompleteChange();
  }

  @Override
  public synchronized void setUpcomingCluster(Cluster updatedCluster) {
    if (isActivated()) {
      throw new IllegalStateException("Use Nomad instead to change the topology of activated node: " + runtimeNodeContext.getNode().getNodeAddress());
    }

    requireNonNull(updatedCluster);

    new ClusterValidator(updatedCluster).validate();

    Node oldMe = upcomingNodeContext.getNode();
    Node newMe = findMe(updatedCluster);

    if (newMe != null) {
      // we have updated the topology and I am still part of this cluster
      LOGGER.info("Set upcoming topology to: {}", updatedCluster.toShapeString());
      this.upcomingNodeContext = new NodeContext(updatedCluster, newMe.getNodeAddress());
    } else {
      // We have updated the topology and I am not part anymore of the cluster
      // So we just reset the cluster object so that this node is alone
      LOGGER.info("Node {} ({}) removed from pending topology: {}", oldMe.getNodeName(), oldMe.getNodeAddress(), updatedCluster.toShapeString());
      this.upcomingNodeContext = this.upcomingNodeContext.withOnlyNode(oldMe);
    }

    // When node is not yet activated, runtimeNodeContext == upcomingNodeContext
    this.runtimeNodeContext = upcomingNodeContext;
  }

  @Override
  public synchronized void activate(Cluster maybeUpdatedCluster, String licenseContent) {
    if (isActivated()) {
      throw new IllegalStateException("Node is already activated");
    }

    LOGGER.info("Preparing activation of cluster: {}", maybeUpdatedCluster.toShapeString());

    // validate that we are part of this cluster
    if (findMe(maybeUpdatedCluster) == null) {
      throw new IllegalArgumentException(String.format(
          "No match found for node: %s in cluster topology: %s",
          upcomingNodeContext.getNodeName(),
          maybeUpdatedCluster
      ));
    }

    this.setUpcomingCluster(maybeUpdatedCluster);
    this.installLicense(licenseContent);

    activate();
  }

  @Override
  public void reset() {
    LOGGER.info("Resetting...");
    try {
      nomadServerManager.getNomadServer().reset();
      clusterActivated = false;
      nomadServerManager.downgradeForRead();
    } catch (NomadException e) {
      throw new IllegalStateException("Unable to reset Nomad system: " + e.getMessage(), e);
    }
  }

  @Override
  public void restart(Duration delayInSeconds) {
    LOGGER.info("Will restart node in {} seconds", delayInSeconds.getSeconds());
    runAfterDelay(delayInSeconds, () -> {
      LOGGER.info("Restarting node");
      ServerEnv.getServer().stop(RESTART);
    });
  }

  @Override
  public void stop(Duration delayInSeconds) {
    LOGGER.info("Will stop node in {} seconds", delayInSeconds.getSeconds());
    runAfterDelay(delayInSeconds, () -> {
      LOGGER.info("Stopping node");
      ServerEnv.getServer().stop(ZAP);
    });
  }

  @Override
  public synchronized void upgradeLicense(String licenseContent) {
    this.installLicense(licenseContent);
  }

  @Override
  public synchronized Optional<License> getLicense() {
    return hasLicenseFile() ? Optional.of(licenseService.parse(licensePath)) : Optional.empty();
  }

  @Override
  public NomadChangeInfo[] getChangeHistory() {
    try {
      return nomadServerManager.getNomadServer().getAllNomadChanges().toArray(new NomadChangeInfo[0]);
    } catch (NomadException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public synchronized boolean validateAgainstLicense(Cluster cluster) throws InvalidLicenseException {
    if (!hasLicenseFile()) {
      LOGGER.warn("Unable to validate cluster against license: license not installed: {}", cluster.toShapeString());
      return false;
    }
    licenseService.validate(licensePath, cluster);
    LOGGER.debug("License is valid for cluster: {}", cluster.toShapeString());
    return true;
  }

  private synchronized void installLicense(String licenseContent) {
    if (licenseContent != null) {
      Path tempFile = null;
      try {
        tempFile = Files.createTempFile("terracotta-license-", ".xml");
        Files.write(tempFile, licenseContent.getBytes(StandardCharsets.UTF_8));
        licenseService.validate(tempFile, upcomingNodeContext.getCluster());
        LOGGER.info("License validated");
        LOGGER.debug("Moving license file: {} to: {}", tempFile, licensePath);
        org.terracotta.utilities.io.Files.relocate(tempFile, licensePath, StandardCopyOption.REPLACE_EXISTING);
        LOGGER.info("License installed");
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      } finally {
        if (tempFile != null) {
          try {
            org.terracotta.utilities.io.Files.deleteIfExists(tempFile);
          } catch (IOException ignored) {
          }
        }
      }
      LOGGER.info("License installation successful");

    } else {
      LOGGER.info("No license installed");
      try {
        org.terracotta.utilities.io.Files.deleteIfExists(licensePath);
      } catch (IOException e) {
        LOGGER.warn("Error deleting existing license: " + e.getMessage(), e);
      }
    }
  }

  private boolean hasLicenseFile() {
    return Files.exists(licensePath) && Files.isRegularFile(licensePath) && Files.isReadable(licensePath);
  }

  /**
   * Tries to find the node representing this process within the updated cluster.
   * <p>
   * - We cannot use the node hostname or port only, since they might have changed through a set command.
   * - We cannot use the node name and stripe ID only, since the stripe ID can have changed in the new cluster with the attach/detach commands
   * <p>
   * So we try to find the best match we can...
   */
  private synchronized Node findMe(Cluster updatedCluster) {
    final Node me = upcomingNodeContext.getNode();
    return updatedCluster.getNode(me.getNodeInternalAddress()) // important to use the internal address
        .orElseGet(() -> updatedCluster.getNode(upcomingNodeContext.getStripeId(), me.getNodeName())
            .orElse(null));
  }

  private void runAfterDelay(Duration delayInSeconds, Runnable runnable) {
    // The delay helps the caller close the connection while it's live, otherwise it gets stuck for request timeout duration
    final long millis = delayInSeconds.toMillis();
    if (millis < 1_000) {
      throw new IllegalArgumentException("Invalid delay: " + delayInSeconds.getSeconds() + " seconds");
    }
    LOGGER.info("Node will restart in: {} seconds", delayInSeconds.getSeconds());
    new Thread(getClass().getSimpleName() + "-DelayedRestart") {
      @Override
      public void run() {
        try {
          sleep(millis);
        } catch (InterruptedException e) {
          // do nothing, still try to kill server
        }
        runnable.run();
      }
    }.start();
  }
}
