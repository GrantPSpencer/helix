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


  @BeforeClass
  public void beforeClass() {
    System.out.println("Start test: " + TestHelper.getTestClassName());
    _gSetupTool.addCluster(CLUSTER_NAME, true);

    _dataAccessor = new ZKHelixDataAccessor(CLUSTER_NAME, _baseAccessor);
    ClusterConfig clusterConfig = _dataAccessor.getProperty(_dataAccessor.keyBuilder().clusterConfig());
    clusterConfig.setPersistBestPossibleAssignment(true);
    _dataAccessor.updateProperty(_dataAccessor.keyBuilder().clusterConfig(), clusterConfig);

    String controllerName = CONTROLLER_PREFIX + "_0";
    _controller = new ClusterControllerManager(ZK_ADDR, CLUSTER_NAME, controllerName);
    _controller.syncStart();
  }

  @BeforeMethod
  public void beforeMethod() {
    for (MockParticipantManager participant : _participants) {
      dropParticipant(participant);
    }
    _participants.clear();
    for (String resourceName : _resourceNames) {
      _gSetupTool.getClusterManagementTool().dropResource(CLUSTER_NAME, resourceName);
    }
    _resourceNames.clear();

    _gSetupTool.getClusterManagementTool().addCluster(CLUSTER_NAME, true);

    for (int i = 0; i < PARTICIPANT_COUNT; i++) {
      addParticipant("localhost_" + i);
    }
  }

  @Test
  public void testCrushedToWage() throws Exception {

    // enter MM while we create resources
    _gSetupTool.getClusterManagementTool().manuallyEnableMaintenanceMode(CLUSTER_NAME, true, "ADMIN", null);

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

    // Exit MM and let controller make assignments based off CRUSHED calculation
    _gSetupTool.getClusterManagementTool().manuallyEnableMaintenanceMode(CLUSTER_NAME, false, "ADMIN", null);

    List<String> partitionsToDisable = new ArrayList<>(resourceToDisableIS.getPartitionSet());
    for (MockParticipantManager participant : _participants) {
      _gSetupTool.getClusterManagementTool().enablePartition(false, CLUSTER_NAME, participant.getInstanceName(),
          resourcetoDisableName, partitionsToDisable);
    }

    // Switch all resources to waged and full auto
    for (String resource : _gSetupTool.getClusterManagementTool().getResourcesInCluster(CLUSTER_NAME)) {
      IdealState is = _gSetupTool.getClusterManagementTool().getResourceIdealState(CLUSTER_NAME, resource);
      is.setRebalanceMode(IdealState.RebalanceMode.FULL_AUTO);
      is.setRebalancerClassName("org.apache.helix.controller.rebalancer.waged.WagedRebalancer");
      _gSetupTool.getClusterManagementTool().setResourceIdealState(CLUSTER_NAME, resource, is);
    }

    // FLIP THIS TO TRUE TO MONITOR THE BEHAVIOR
    boolean endlesslyLoop = false;
    System.out.println("starting endless loop: " + endlesslyLoop);
    while (endlesslyLoop) {}
  }

  @Test
  public void testNewWagededResources() throws Exception {

    // enter MM while we create resources
    _gSetupTool.getClusterManagementTool().manuallyEnableMaintenanceMode(CLUSTER_NAME, true, "ADMIN", null);

    for (int i = 0; i < RESOURCE_COUNT; i++) {
      String resourceName = "testResource_" + i;
      _gSetupTool.addResourceToCluster(CLUSTER_NAME, resourceName, PARTITION_COUNT, "LeaderStandby",
          IdealState.RebalanceMode.FULL_AUTO.name(), null);
      IdealState is = _gSetupTool.getClusterManagementTool().getResourceIdealState(CLUSTER_NAME, resourceName);
      is.setReplicas(Integer.toString(REPLICA_COUNT));
      is.setMinActiveReplicas(MIN_ACTIVE_REPLICAS);
      is.setRebalancerClassName("org.apache.helix.controller.rebalancer.waged.WagedRebalancer");
      _gSetupTool.getClusterManagementTool().setResourceIdealState(CLUSTER_NAME, resourceName, is);
      _resourceNames.add(resourceName);
    }

    _gSetupTool.getClusterManagementTool().manuallyEnableMaintenanceMode(CLUSTER_NAME, false, "ADMIN", null);
    while(true) {

    }

//    IdealState resourceToDisableIS = _gSetupTool.getClusterManagementTool().getResourceIdealState(CLUSTER_NAME, _resourceNames.get(0));
//    String resourcetoDisableName = resourceToDisableIS.getResourceName();
//    List<String> partitionsToDisable = new ArrayList<>(resourceToDisableIS.getPartitionSet());
//    for (MockParticipantManager participant : _participants) {
//      _gSetupTool.getClusterManagementTool().enablePartition(false, CLUSTER_NAME, participant.getInstanceName(),
//          resourcetoDisableName, partitionsToDisable);
//    }
//
//    // FLIP THIS TO TRUE TO ENDLESSLY LOOP AND MONITOR THE BEHAVIOR
//    boolean endlesslyLoop = true;
//    System.out.println("starting endless loop: " + endlesslyLoop);
//    while (endlesslyLoop) {}
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
