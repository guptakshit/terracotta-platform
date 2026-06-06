package org.terracotta.dynamic_config.server.service.handler;

import org.terracotta.diagnostic.model.LogicalServerState;
import org.terracotta.dynamic_config.api.model.Configuration;
import org.terracotta.dynamic_config.api.model.NodeContext;
import org.terracotta.dynamic_config.api.server.ConfigChangeHandler;
import org.terracotta.dynamic_config.api.server.InvalidConfigChangeException;
import org.terracotta.nomad.server.NomadException;
import org.terracotta.server.Server;

import java.util.Optional;

public class ReplicaChangeHandler implements ConfigChangeHandler {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReplicaChangeHandler.class);
  private final Server server;

  public ReplicaChangeHandler(Server server) {
    this.server = server;
  }

  @Override
  public void validate(NodeContext nodeContext, Configuration change) throws InvalidConfigChangeException {
//    try {
//      LogicalServerState logicalServerState = getLogicalServerState();
//      if (logicalServerState != LogicalServerState.REPLICA) {
////        if the state is not replica we don't transition that means one of the node rejected the change
////        and that means if replica-start is stuck they're stuck then forever
//        throw new InvalidConfigChangeException("Replica state is not REPLICA");
//      }
//    } catch (NomadException e) {
//      throw new InvalidConfigChangeException("invalid state", e.getCause());
//    }
////    Optional<String> value = change.getValue();
////    if (value.isEmpty()) {
////      throw new InvalidConfigChangeException("Replica change value is empty");
////    }
  }

  private LogicalServerState getLogicalServerState() throws NomadException {
    try {
      return LogicalServerState.valueOf(validate(
        "DiagnosticExtensions", "getLogicalServerState",
        server.getManagement().call("DiagnosticExtensions", "getLogicalServerState", null)));
    } catch (RuntimeException e) {
      throw new NomadException(e.getMessage(), e);
    }
  }

  private static String validate(String mBean, String method, String value) {
    if (value == null || value.startsWith("Invalid JMX")) {
      throw new IllegalStateException("mBean call '" + mBean + "#" + method + "' error: " + value);
    }
    return value;
  }

  @Override
  public void apply(Configuration change) {
//    System.out.println("**********************************");
//    log.warn("**************************************");
//    Optional<String> value = change.getValue();
//    System.out.println("value is: " + value);
//    Optional<String> value1 = change.getValue();
//    log.warn("value is: " + value1);
//    if (value.isEmpty()) {
//      System.out.println("executing the mbean call");
//      boolean failover = server.replicaFailoverToActive();
//      System.out.println("finished executing the mbean call, result: " + failover);
//    }
  }
}
