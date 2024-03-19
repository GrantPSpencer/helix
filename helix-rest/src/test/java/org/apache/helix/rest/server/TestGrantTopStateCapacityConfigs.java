package org.apache.helix.rest.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import org.apache.helix.ConfigAccessor;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.TestHelper;
import org.apache.helix.controller.rebalancer.waged.WagedRebalancer;
import org.apache.helix.integration.manager.ClusterControllerManager;
import org.apache.helix.integration.manager.MockParticipantManager;
import org.apache.helix.manager.zk.ZKHelixDataAccessor;
import org.apache.helix.model.ClusterConfig;
import org.apache.helix.model.CurrentState;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.InstanceConfig;
import org.apache.helix.tools.ClusterVerifiers.BestPossibleExternalViewVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class TestGrantTopStateCapacityConfigs extends AbstractTestClass {
  private static final Logger LOG = LoggerFactory.getLogger(TestHelper.class);

  private static final int RESOURCE_COUNT = 20;
  private static final int PARTITIONS_COUNT = 1;
  private static final int REPLICAS_COUNT = 3;
  private static final int MIN_ACTIVE_REPLICAS = 2;
  private static final String INSTANCE_CAPACITY_KEY = "partition";
  private static final int DEFAULT_INSTANCE_CAPACITY = 5000;
  private static final int DEFAULT_PARTITION_WEIGHT = 1;
  private static final int DEFAULT_INSTANCE_COUNT = 3;
  private static final String STATE_MODEL_DEF_REF = "LeaderStandby";
  private static final String TOP_STATE_NAME = "LEADER";

  private static final String INSTANCE_NAME_PREFIX = "localhost_";
  private static final int INSTANCE_START_PORT = 12918;
  private static final String CLUSTER_NAME = "TopStateCapacityConfigsTestCluster";
  private static ClusterControllerManager _controller;
  private static HelixDataAccessor _helixDataAccessor;
  private static ConfigAccessor _configAccessor;
  private static BestPossibleExternalViewVerifier _clusterVerifier;
  private static List<MockParticipantManager> _participants = new ArrayList<>();
  private static List<String> _resources = new ArrayList<>();

  @BeforeMethod
  public void beforeTest() {
    System.out.println("Start setup:" + TestHelper.getTestMethodName());
    // Create test cluster
    _gSetupTool.addCluster(CLUSTER_NAME, true);

    // Setup cluster configs
    _configAccessor = new ConfigAccessor(_gZkClient);
    ClusterConfig clusterConfig = _configAccessor.getClusterConfig(CLUSTER_NAME);
    clusterConfig.setPersistBestPossibleAssignment(true);
    _configAccessor.setClusterConfig(CLUSTER_NAME, clusterConfig);
    _controller = startController(CLUSTER_NAME);

    // Create HelixDataAccessor
    _helixDataAccessor = new ZKHelixDataAccessor(CLUSTER_NAME, _baseAccessor);

    // Create cluster verifier
    _clusterVerifier = new BestPossibleExternalViewVerifier.Builder(CLUSTER_NAME).setZkAddr(ZK_ADDR)
        .setResources(new HashSet<>(_resources))
        .setWaitTillVerify(TestHelper.DEFAULT_REBALANCE_PROCESSING_WAIT_TIME).build();

    Assert.assertTrue(_clusterVerifier.verifyByPolling());

    // Add and start instances to cluster
    for (int i = 0; i < DEFAULT_INSTANCE_COUNT; i++) {
      String instanceName = INSTANCE_NAME_PREFIX + (INSTANCE_START_PORT + i);
      InstanceConfig instanceConfig = new InstanceConfig(instanceName);
      instanceConfig.setInstanceEnabled(true);
      _gSetupTool.getClusterManagementTool().addInstance(CLUSTER_NAME, instanceConfig);
      MockParticipantManager participant =
          new MockParticipantManager(ZK_ADDR, CLUSTER_NAME, instanceName);
      participant.syncStart();
      _participants.add(participant);
    }

    System.out.println("End setup:" + TestHelper.getTestMethodName());
  }

  @AfterMethod
  public void afterTest() throws Exception {
    System.out.println("Start teardown:" + TestHelper.getTestMethodName());

    // Drop all resources
    for (String resource : _resources) {
      _gSetupTool.dropResourceFromCluster(CLUSTER_NAME, resource);
    }
    _resources.clear();

    // Stop and remove all instances
    for (MockParticipantManager participant : _participants) {
      participant.syncStop();
      InstanceConfig instanceConfig = _helixDataAccessor.getProperty(
          _helixDataAccessor.keyBuilder().instanceConfig(participant.getInstanceName()));
      if (instanceConfig != null) {
        _gSetupTool.getClusterManagementTool().dropInstance(CLUSTER_NAME, instanceConfig);
      }
    }
    _participants.clear();

    // Stop controller
    _controller.syncStop();

    // Drop cluster
    _gSetupTool.deleteCluster(CLUSTER_NAME);

    System.out.println("End teardown:" + TestHelper.getTestMethodName());
  }


  @Test
  public void testNoCapacityDefined() throws Exception {
    // Create 10 WAGED resources
    String wagedResourcePrefix = "TEST_WAGED_DB_";
    for (int i = 0; i < RESOURCE_COUNT; i++) {
      createWagedResource(wagedResourcePrefix + i, PARTITIONS_COUNT, MIN_ACTIVE_REPLICAS, 100000L);
    }
    // No capacity is defined
    Assert.assertTrue(_clusterVerifier.verifyByPolling());
    System.out.println("--- Before Change ---");
    printTopStateCountMap();
  }

  @Test
  public void testYesCapacityDefined() throws Exception {
    // Create WAGED resources
    String wagedResourcePrefix = "TEST_WAGED_DB_";

    ClusterConfig clusterConfig = _configAccessor.getClusterConfig(CLUSTER_NAME);
    clusterConfig.setDefaultInstanceCapacityMap(
        Collections.singletonMap(INSTANCE_CAPACITY_KEY, DEFAULT_INSTANCE_CAPACITY));
    clusterConfig.setDefaultPartitionWeightMap(Collections.singletonMap(INSTANCE_CAPACITY_KEY,
        DEFAULT_PARTITION_WEIGHT));
    clusterConfig.setInstanceCapacityKeys(ImmutableList.of(INSTANCE_CAPACITY_KEY));
    _configAccessor.setClusterConfig(CLUSTER_NAME, clusterConfig);

    for (int i = 0; i < RESOURCE_COUNT; i++) {
      createWagedResource(wagedResourcePrefix + i, PARTITIONS_COUNT, MIN_ACTIVE_REPLICAS, 100000L);
    }
    // Define default instance capacity, default partition weight, and capacity keys

    // MapFields will have:
    //     "DEFAULT_INSTANCE_CAPACITY_MAP": {
    //       "partition": "5000"
    //     },
    //     "DEFAULT_PARTITION_WEIGHT_MAP": {
    //       "partition": "1"
    //     },

    // ListFields will have:
    //     "INSTANCE_CAPACITY_KEYS": [
    //       "partition"
    //     ]



    // Wait for cluster to converge then check top state distribution
    Assert.assertTrue(_clusterVerifier.verifyByPolling());
    System.out.println("--- After Change ---");
    printTopStateCountMap();
  }


  private void printTopStateCountMap() {
    Map<String, Integer> topStateCountMap = new HashMap<>();
    for (MockParticipantManager participant : _participants) {
      topStateCountMap.put(participant.getInstanceName(), 0);
      List<String> assignedResources = _helixDataAccessor.getChildNames(_helixDataAccessor
          .keyBuilder().currentStates(participant.getInstanceName(), participant.getSessionId()));
      for (String resource : assignedResources) {
        CurrentState currentState = _helixDataAccessor.getProperty(_helixDataAccessor.keyBuilder()
            .currentState(participant.getInstanceName(), participant.getSessionId(), resource));

        for (String state : currentState.getPartitionStateMap().values()) {
          if (TOP_STATE_NAME.equals(state)) {
            topStateCountMap.put(participant.getInstanceName(), topStateCountMap.
                getOrDefault(participant.getInstanceName(), 0) + 1);
          }
        }
      }
    }

    for (Map.Entry<String, Integer> entry : topStateCountMap.entrySet()) {
      System.out.println("Participant: " + entry.getKey() + ", Top State Count: " + entry.getValue());
    }
  }

  private void createWagedResource(String db, int numPartition, int minActiveReplica, long delay)
      throws IOException {
    _gSetupTool.addResourceToCluster(CLUSTER_NAME, db, numPartition, STATE_MODEL_DEF_REF,
        IdealState.RebalanceMode.FULL_AUTO + "", null);
    _resources.add(db);

    IdealState idealState =
        _gSetupTool.getClusterManagementTool().getResourceIdealState(CLUSTER_NAME, db);
    idealState.setMinActiveReplicas(minActiveReplica);
    idealState.setDelayRebalanceEnabled(true);
    idealState.setRebalanceDelay(delay);
    idealState.setRebalancerClassName(WagedRebalancer.class.getName());
    _gSetupTool.getClusterManagementTool().setResourceIdealState(CLUSTER_NAME, db, idealState);
    _gSetupTool.rebalanceResource(CLUSTER_NAME, db, REPLICAS_COUNT);
    Assert.assertTrue(_clusterVerifier.verifyByPolling());
  }
}
