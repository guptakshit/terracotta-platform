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
package org.terracotta.dynamic_config.server.service;

import com.tc.classloader.BuiltinService;
import com.tc.spi.NetworkTranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.dynamic_config.api.model.Configuration;
import org.terracotta.dynamic_config.api.model.Setting;
import org.terracotta.dynamic_config.api.service.DynamicConfigService;
import org.terracotta.dynamic_config.api.service.IParameterSubstitutor;
import org.terracotta.dynamic_config.api.service.TopologyService;
import org.terracotta.dynamic_config.api.server.ConfigChangeHandler;
import org.terracotta.dynamic_config.api.server.ConfigChangeHandlerManager;
import org.terracotta.dynamic_config.api.server.DynamicConfigEventFiring;
import org.terracotta.dynamic_config.api.server.DynamicConfigEventService;
import org.terracotta.dynamic_config.api.server.DynamicConfigNomadServer;
import org.terracotta.dynamic_config.api.server.LicenseService;
import org.terracotta.dynamic_config.api.server.NomadPermissionChangeProcessor;
import org.terracotta.dynamic_config.api.server.NomadRoutingChangeProcessor;
import org.terracotta.dynamic_config.api.server.PathResolver;
import org.terracotta.dynamic_config.api.server.SelectingConfigChangeHandler;
import org.terracotta.dynamic_config.server.service.handler.ClientReconnectWindowConfigChangeHandler;
import org.terracotta.dynamic_config.server.service.handler.LoggerOverrideConfigChangeHandler;
import org.terracotta.dynamic_config.server.service.handler.NodeLogDirChangeHandler;
import org.terracotta.entity.PlatformConfiguration;
import org.terracotta.entity.ServiceConfiguration;
import org.terracotta.entity.ServiceProvider;
import org.terracotta.entity.ServiceProviderConfiguration;
import org.terracotta.nomad.server.NomadServer;
import org.terracotta.server.Server;

import java.util.Arrays;
import java.util.Collection;

import static org.terracotta.dynamic_config.api.model.Setting.CLIENT_RECONNECT_WINDOW;
import static org.terracotta.dynamic_config.api.model.Setting.CLUSTER_NAME;
import static org.terracotta.dynamic_config.api.model.Setting.CRYPTO_COMPLIANCE_LEVEL;
import static org.terracotta.dynamic_config.api.model.Setting.FAILOVER_PRIORITY;
import static org.terracotta.dynamic_config.api.model.Setting.LOCK_CONTEXT;
import static org.terracotta.dynamic_config.api.model.Setting.NODE_LOGGER_OVERRIDES;
import static org.terracotta.dynamic_config.api.model.Setting.NODE_LOG_DIR;
import static org.terracotta.dynamic_config.api.model.Setting.NODE_PUBLIC_HOSTNAME;
import static org.terracotta.dynamic_config.api.model.Setting.NODE_PUBLIC_PORT;
import static org.terracotta.dynamic_config.api.model.Setting.RELAY;
import static org.terracotta.dynamic_config.api.model.Setting.RELAY_GROUP_PORT;
import static org.terracotta.dynamic_config.api.model.Setting.RELAY_HOSTNAME;
import static org.terracotta.dynamic_config.api.model.Setting.RELAY_PORT;
import static org.terracotta.dynamic_config.api.model.Setting.REPLICA;
import static org.terracotta.dynamic_config.api.model.Setting.REPLICA_HOSTNAME;
import static org.terracotta.dynamic_config.api.model.Setting.REPLICA_PORT;
import static org.terracotta.dynamic_config.api.model.Setting.TC_PROPERTIES;
import static org.terracotta.dynamic_config.api.server.ConfigChangeHandler.accept;

@BuiltinService
public class DynamicConfigServiceProvider implements ServiceProvider {

  private static final Logger LOGGER = LoggerFactory.getLogger(DynamicConfigServiceProvider.class);

  private volatile PlatformConfiguration platformConfiguration;

  @Override
  public boolean initialize(ServiceProviderConfiguration configuration, PlatformConfiguration platformConfiguration) {
    this.platformConfiguration = platformConfiguration;

    TopologyService topologyService = findService(TopologyService.class);

    IParameterSubstitutor parameterSubstitutor = findService(IParameterSubstitutor.class);
    PathResolver pathResolver = findService(PathResolver.class);
    ConfigChangeHandlerManager configChangeHandlerManager = findService(ConfigChangeHandlerManager.class);
    Server server = findService(Server.class);

    // client-reconnect-window
    ConfigChangeHandler clientReconnectWindowHandler = new ClientReconnectWindowConfigChangeHandler();
    addToManager(configChangeHandlerManager, clientReconnectWindowHandler, CLIENT_RECONNECT_WINDOW);

    // log-dir
    ConfigChangeHandler nodeLogDirChangeHandler = new NodeLogDirChangeHandler(parameterSubstitutor, pathResolver);
    addToManager(configChangeHandlerManager, nodeLogDirChangeHandler, NODE_LOG_DIR);

    // settings applied directly without any config handler but which require a restart
    addToManager(configChangeHandlerManager, accept(), FAILOVER_PRIORITY);
    addToManager(configChangeHandlerManager, accept(), CRYPTO_COMPLIANCE_LEVEL);

    // public hostname/port
    addToManager(configChangeHandlerManager, accept(), NODE_PUBLIC_HOSTNAME);
    addToManager(configChangeHandlerManager, accept(), NODE_PUBLIC_PORT);

    // relay properties
    addToManager(configChangeHandlerManager, accept(), RELAY);
    addToManager(configChangeHandlerManager, accept(), REPLICA_HOSTNAME);
    addToManager(configChangeHandlerManager, accept(), REPLICA_PORT);

    // replica properties
    // TODO [REPLICA-EXTRA-NODES - to be owned by another developer]: ReplicaChangeHandler removed. Unsetting replica
    //  does not fail over the replica node anymore: replica-failover fails over the replica nodes from the client side
    //  (JMX call through the diagnostic port) before unsetting the replica flag. See ReplicaFailoverAction.
    //  Tests: see ReplicaFailoverAction
    //
    // OPTIONAL server-side check (not enabled): refuse to unset replica (or set replica=false) on a node which has not
    // been failed over to ACTIVE yet. The CLI already refuses it (ConfigurationMutationAction#ensureReplicaNodesAreFailedOver),
    // this would protect against any other client (older CLI, direct Nomad change, ...). It is not enabled for now because
    // the states in which a replica is allowed to be unset might change in the future (i.e. if PASSIVE-REPLICA-START cannot
    // transition to ACTIVE anymore and must be unset another way).
    //
    // Sample handler (to put in org.terracotta.dynamic_config.server.service.handler.ReplicaChangeHandler):
    //
    //   public class ReplicaChangeHandler implements ConfigChangeHandler {
    //     private final ServerJMX serverJMX;
    //
    //     public ReplicaChangeHandler(ServerJMX serverJMX) {
    //       this.serverJMX = serverJMX;
    //     }
    //
    //     @Override
    //     public void validate(NodeContext baseConfig, Configuration change) throws InvalidConfigChangeException {
    //       boolean disabling = !change.getValue().map(Boolean::parseBoolean).orElse(false);
    //       if (disabling && baseConfig.getNode().getReplica().orDefault()) {
    //         String state = serverJMX.call("DiagnosticExtensions", "getLogicalServerState", null);
    //         if (state == null || state.startsWith("Invalid JMX")) {
    //           throw new InvalidConfigChangeException("Unable to get the state of the server: " + state);
    //         }
    //         LogicalServerState logicalServerState = LogicalServerState.valueOf(state);
    //         if (logicalServerState == LogicalServerState.REPLICA || logicalServerState == LogicalServerState.REPLICA_SUSPENDED) {
    //           throw new InvalidConfigChangeException("Replica node: " + baseConfig.getNode().getName() + " is in state: " + state
    //               + ". It must be failed over to ACTIVE with the replica-failover command before unsetting replica.");
    //         }
    //       }
    //     }
    //   }
    //
    // How to integrate:
    // 1. create the class above (imports: ConfigChangeHandler, InvalidConfigChangeException, Configuration, NodeContext,
    //    LogicalServerState, ServerJMX). There is no apply(): the default one does nothing, the change is a config change only.
    // 2. replace the line below with: addToManager(configChangeHandlerManager, new ReplicaChangeHandler(server.getManagement()), REPLICA);
    // 3. in testing/entity TestServiceProvider, the fallback replica handler (accept()) can stay: the real handler is used when
    //    it is registered first, there is no ordering guarantee between the service providers.
    // 4. the handler validate() is only called on the targeted node (see ApplicabilityNomadChangeProcessor), so only the replica
    //    node checks its own state. The other nodes of the stripe (DIAGNOSTIC) are not concerned.
    // 5. repair is not impacted: validate() is only called when a change is prepared, a repair only commits or rolls back an
    //    already prepared change.
    // 6. tests to add:
    //    - unit: validate() fails for unset / set false when the server state is REPLICA or REPLICA_SUSPENDED
    //    - unit: validate() succeeds for unset / set false when ACTIVE, and for set true in any state
    //    - IT: on a replica cluster, a Nomad change unsetting replica sent directly (bypassing the CLI check) is refused at prepare
    addToManager(configChangeHandlerManager, accept(), REPLICA);
    addToManager(configChangeHandlerManager, accept(), RELAY_HOSTNAME);
    addToManager(configChangeHandlerManager, accept(), RELAY_PORT);
    addToManager(configChangeHandlerManager, accept(), RELAY_GROUP_PORT);

    // cluster name
    addToManager(configChangeHandlerManager, accept(), CLUSTER_NAME);

    // lock context
    addToManager(configChangeHandlerManager, accept(), LOCK_CONTEXT);

    // tc-logging
    LoggerOverrideConfigChangeHandler loggerOverrideConfigChangeHandler = new LoggerOverrideConfigChangeHandler(topologyService);
    addToManager(configChangeHandlerManager, loggerOverrideConfigChangeHandler, NODE_LOGGER_OVERRIDES);

    // tc-properties
    configChangeHandlerManager.set(TC_PROPERTIES, new SelectingConfigChangeHandler<String>()
        .selector(Configuration::getKey)
        .fallback(accept()));

    // initialize the config handlers that need do to something at startup
    loggerOverrideConfigChangeHandler.init();

    NomadPermissionChangeProcessor permissions = findService(NomadPermissionChangeProcessor.class);
    permissions.addCheck(new DisallowSettingChanges());
    permissions.addCheck(new ServerStateCheck(server.getManagement()));

    return true;
  }

  @Override
  public <T> T getService(long consumerID, ServiceConfiguration<T> configuration) {
    if (configuration.getServiceType() == NetworkTranslator.class) {
      return configuration.getServiceType().cast(new DynamicConfigNetworkTranslator(findService(TopologyService.class)));
    }
    return findService(configuration.getServiceType());
  }

  @Override
  public Collection<Class<?>> getProvidedServiceTypes() {
    return Arrays.asList(
        IParameterSubstitutor.class,
        ConfigChangeHandlerManager.class,
        DynamicConfigEventService.class,
        TopologyService.class,
        DynamicConfigService.class,
        DynamicConfigEventFiring.class,
        NomadServer.class,
        DynamicConfigNomadServer.class,
        NomadRoutingChangeProcessor.class,
        NomadPermissionChangeProcessor.class,
        LicenseService.class,
        PathResolver.class,
        Server.class,
        NetworkTranslator.class
    );
  }

  @Override
  public void prepareForSynchronization() {
    // no-op
  }

  private void addToManager(ConfigChangeHandlerManager manager, ConfigChangeHandler configChangeHandler, Setting setting) {
    ConfigChangeHandler old = manager.set(setting, configChangeHandler);
    if (old != null) {
      LOGGER.warn("Default dynamic configuration change handler {} for setting {} has been override by {}", configChangeHandler, setting, old);
    }
  }

  private <T> T findService(Class<T> type) {
    if (!getProvidedServiceTypes().contains(type)) {
      throw new IllegalArgumentException(String.valueOf(type));
    }
    final Collection<T> services = platformConfiguration.getExtendedConfiguration(type);
    if (services.isEmpty()) {
      throw new IllegalArgumentException("No instance of service " + type + " found");
    }

    if (services.size() == 1) {
      T instance = services.iterator().next();
      if (instance == null) {
        throw new IllegalArgumentException("Instance of service " + type + " found to be null");
      }
      return instance;
    }
    throw new IllegalArgumentException("Multiple instances of service " + type + " found");
  }
}
