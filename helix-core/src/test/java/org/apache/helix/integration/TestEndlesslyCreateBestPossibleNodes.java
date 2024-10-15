package org.apache.helix.integration;

import java.util.ArrayList;
import java.util.List;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.TestHelper;
import org.apache.helix.common.ZkTestBase;
import org.apache.helix.controller.rebalancer.strategy.CrushEdRebalanceStrategy;
import org.apache.helix.integration.manager.ClusterControllerManager;
import org.apache.helix.integration.manager.MockParticipantManager;
import org.apache.helix.manager.zk.ZKHelixDataAccessor;
import org.apache.helix.model.ClusterConfig;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.ResourceConfig;
import org.apache.helix.tools.ClusterVerifiers.BestPossibleExternalViewVerifier;
import org.apache.helix.tools.ClusterVerifiers.StrictMatchExternalViewVerifier;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;


public class TestEndlesslyCreateBestPossibleNodes extends ZkTestBase {

  private static String CLUSTER_NAME = TestHelper.getTestClassName() + "_cluster";
  private static int PARTICIPANT_COUNT = 6;
  private static int RESOURCE_COUNT = 6;
  private static int PARTITION_COUNT = 1;
  private static int REPLICA_COUNT = PARTICIPANT_COUNT;
  private static int MIN_ACTIVE_REPLICAS = 3;
  private static List<MockParticipantManager> _participants = new ArrayList<>();
  private static List<String> _resourceNames = new ArrayList<>();
  private static ClusterControllerManager _controller;
  private HelixDataAccessor _dataAccessor;
  private int testIteration = 0;
  BestPossibleExternalViewVerifier _bestPossibleClusterVerifier;
  StrictMatchExternalViewVerifier _clusterVerifier;


  @BeforeClass
  public void beforeClass() {
    System.out.println("Start test: " + TestHelper.getTestClassName());

  }

  @BeforeMethod
  public void beforeMethod() {
    _resourceNames.clear();
    for (MockParticipantManager participant : _participants) {
      dropParticipant(participant);
    }
    if (_controller != null) {
      _controller.syncStop();
    }
    _participants.clear();

    CLUSTER_NAME = TestHelper.getTestClassName() + "_cluster_" + testIteration++;
    _gSetupTool.addCluster(CLUSTER_NAME, true);

    _dataAccessor = new ZKHelixDataAccessor(CLUSTER_NAME, _baseAccessor);
    ClusterConfig clusterConfig = _dataAccessor.getProperty(_dataAccessor.keyBuilder().clusterConfig());
    clusterConfig.setPersistBestPossibleAssignment(true);
    _dataAccessor.updateProperty(_dataAccessor.keyBuilder().clusterConfig(), clusterConfig);

    for (int i = 0; i < PARTICIPANT_COUNT; i++) {
      addParticipant("localhost_" + i);
    }

    String controllerName = CONTROLLER_PREFIX + "_0";
    _controller = new ClusterControllerManager(ZK_ADDR, CLUSTER_NAME, controllerName);
    _controller.syncStart();

    _bestPossibleClusterVerifier = new BestPossibleExternalViewVerifier.Builder(CLUSTER_NAME).setZkAddr(ZK_ADDR)
        .setWaitTillVerify(TestHelper.DEFAULT_REBALANCE_PROCESSING_WAIT_TIME)
        .build();
    _clusterVerifier = new StrictMatchExternalViewVerifier.Builder(CLUSTER_NAME).setZkAddr(ZK_ADDR)
        .setDeactivatedNodeAwareness(true)
        .setWaitTillVerify(TestHelper.DEFAULT_REBALANCE_PROCESSING_WAIT_TIME)
        .build();
  }

  @Test
  public void testCrushedToWage() throws Exception {
    for (int i = 0; i < RESOURCE_COUNT; i++) {
      String resourceName = "testResource_" + i;
      _gSetupTool.addResourceToCluster(CLUSTER_NAME, resourceName, PARTITION_COUNT, "LeaderStandby",
          IdealState.RebalanceMode.SEMI_AUTO.name(), null);
      IdealState is = _gSetupTool.getClusterManagementTool().getResourceIdealState(CLUSTER_NAME, resourceName);
      is.setRebalanceStrategy(CrushEdRebalanceStrategy.class.getName());
      is.setReplicas(Integer.toString(REPLICA_COUNT));
      is.setMinActiveReplicas(MIN_ACTIVE_REPLICAS);
      is.setPreferenceList(resourceName + "_0", new ArrayList<>(_gSetupTool.getClusterManagementTool().getInstancesInCluster(CLUSTER_NAME)));
      _gSetupTool.getClusterManagementTool().setResourceIdealState(CLUSTER_NAME, resourceName, is);
      _resourceNames.add(resourceName);
    }

    // Set HELIX_DISABLED_PARTITION for first resource's partition on all instances
    IdealState resourceToDisableIS = _gSetupTool.getClusterManagementTool().getResourceIdealState(CLUSTER_NAME, _resourceNames.get(0));
    String resourcetoDisableName = resourceToDisableIS.getResourceName();

    List<String> partitionsToDisable = new ArrayList<>(resourceToDisableIS.getPartitionSet());
    for (MockParticipantManager participant : _participants) {
      _gSetupTool.getClusterManagementTool().enablePartition(false, CLUSTER_NAME, participant.getInstanceName(),
          resourcetoDisableName, partitionsToDisable);
    }

    // Let cluster converge on CRUSHED assignments
    Assert.assertTrue(_bestPossibleClusterVerifier.verifyByPolling());

    _gSetupTool.getClusterManagementTool().manuallyEnableMaintenanceMode(CLUSTER_NAME, true, "Test", null);

    // Switch all resources to waged and full auto
    for (String resource : _gSetupTool.getClusterManagementTool().getResourcesInCluster(CLUSTER_NAME)) {
      IdealState is = _gSetupTool.getClusterManagementTool().getResourceIdealState(CLUSTER_NAME, resource);
      is.setRebalanceMode(IdealState.RebalanceMode.FULL_AUTO);
      is.setRebalancerClassName("org.apache.helix.controller.rebalancer.waged.WagedRebalancer");
      _gSetupTool.getClusterManagementTool().setResourceIdealState(CLUSTER_NAME, resource, is);
    }

    _gSetupTool.getClusterManagementTool().manuallyEnableMaintenanceMode(CLUSTER_NAME, false, "Test", null);


    // FLIP THIS TO TRUE TO MONITOR THE BEHAVIOR
    boolean endlesslyLoop = false;
    System.out.println("starting endless loop: " + endlesslyLoop);
    while (endlesslyLoop) {}
  }

  // Rebalance will fail for this test but it will not endlessly create znodes. Showing that the bug is specific to some
  // conditions caused during switch from CRUSHED --> WAGED.. This was an attempt to recreate the scenario where all nodes were in offline state
  // in current state before disabling the partition on each node. Currently, I think the reason the previos test causes the endless node creation behavior
  // is because it is continuously calculating a new WAGED assignment. The result of calculateAssignment excludes the disabled partition and that is persisted to in memory store.
  // When we then try to get the
  @Test
  public void testNewWagededResources() throws Exception {
    // enter MM while we create resources
    _gSetupTool.getClusterManagementTool().manuallyEnableMaintenanceMode(CLUSTER_NAME, true, "Test", null);
    for (int i = 0; i < RESOURCE_COUNT; i++) {
      String resourceName = "testResource_" + i;
      createResourceWithWagedRebalance(CLUSTER_NAME, resourceName, "LeaderStandby", PARTITION_COUNT, REPLICA_COUNT, MIN_ACTIVE_REPLICAS);
      _resourceNames.add(resourceName);
    }

    IdealState resourceToDisableIS = _gSetupTool.getClusterManagementTool().getResourceIdealState(CLUSTER_NAME, _resourceNames.get(0));
    String resourcetoDisableName = resourceToDisableIS.getResourceName();
    _gSetupTool.getClusterManagementTool().manuallyEnableMaintenanceMode(CLUSTER_NAME, false, "Test", null);
    Assert.assertTrue(_bestPossibleClusterVerifier.verifyByPolling());

    _gSetupTool.getClusterManagementTool().enableResource(CLUSTER_NAME, resourcetoDisableName, false);
    Assert.assertTrue(_bestPossibleClusterVerifier.verifyByPolling());

    System.out.println("disabling partitions");
    List<String> partitionsToDisable = new ArrayList<>(resourceToDisableIS.getPartitionSet());
    for (MockParticipantManager participant : _participants) {
      _gSetupTool.getClusterManagementTool().enablePartition(false, CLUSTER_NAME, participant.getInstanceName(),
          resourcetoDisableName, partitionsToDisable);
    }
    _gSetupTool.getClusterManagementTool().enableResource(CLUSTER_NAME, resourcetoDisableName, true);

    // FLIP THIS TO TRUE TO MONITOR THE BEHAVIOR
    boolean endlesslyLoop = false;
    System.out.println("starting endless loop: " + endlesslyLoop);
    while (endlesslyLoop) {}
  }

  public MockParticipantManager addParticipant(String instanceName) {
    _gSetupTool.addInstanceToCluster(CLUSTER_NAME, instanceName);
    MockParticipantManager participant = new MockParticipantManager(ZK_ADDR, CLUSTER_NAME, instanceName);
    participant.syncStart();
    _participants.add(participant);
    return participant;
  }

  public void dropParticipant(MockParticipantManager participantToDrop) {
    participantToDrop.syncStop();
    _gSetupTool.getClusterManagementTool().dropInstance(CLUSTER_NAME,
        _gSetupTool.getClusterManagementTool().getInstanceConfig(CLUSTER_NAME, participantToDrop.getInstanceName()));
  }
}
