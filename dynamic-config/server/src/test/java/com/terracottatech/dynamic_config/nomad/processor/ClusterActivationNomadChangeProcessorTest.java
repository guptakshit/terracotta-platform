/*
 * Copyright (c) 2011-2019 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA, and/or its subsidiaries and/or its affiliates and/or their licensors.
 * Use, reproduction, transfer, publication or disclosure is prohibited except as specifically provided for in your License Agreement with Software AG.
 */
package com.terracottatech.dynamic_config.nomad.processor;

import com.terracottatech.dynamic_config.model.Cluster;
import com.terracottatech.dynamic_config.model.Node;
import com.terracottatech.dynamic_config.model.NodeContext;
import com.terracottatech.dynamic_config.model.Stripe;
import com.terracottatech.dynamic_config.nomad.ClusterActivationNomadChange;
import com.terracottatech.nomad.server.NomadException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;

@RunWith(MockitoJUnitRunner.class)
public class ClusterActivationNomadChangeProcessorTest {

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  private ClusterActivationNomadChangeProcessor processor;
  private NodeContext topology = new NodeContext(new Cluster("bar", new Stripe(Node.newDefaultNode("foo", "localhost"))), 1, "foo");

  @Before
  public void setUp() {
    processor = new ClusterActivationNomadChangeProcessor(1, "foo", topology.getCluster());
  }

  @Test
  public void testGetConfigWithChange() throws Exception {
    ClusterActivationNomadChange change = new ClusterActivationNomadChange(topology.getCluster());

    NodeContext configWithChange = processor.tryApply(null, change);

    assertThat(configWithChange, notNullValue());
  }

  @Test
  public void testCanApplyWithNonNullBaseConfig() throws Exception {
    ClusterActivationNomadChange change = new ClusterActivationNomadChange(new Cluster("cluster"));
    NodeContext topology = new NodeContext(new Cluster(new Stripe(Node.newDefaultNode("foo", "localhost"))), 1, "foo");

    expectedException.expect(NomadException.class);
    expectedException.expectMessage("Existing config must be null. Found: " + topology);

    processor.tryApply(topology, change);
  }
}