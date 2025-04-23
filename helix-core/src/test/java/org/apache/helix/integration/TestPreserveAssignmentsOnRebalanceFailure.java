package org.apache.helix.integration;

import java.util.ArrayList;
import java.util.List;
import org.apache.helix.ConfigAccessor;
import org.apache.helix.TestHelper;
import org.apache.helix.common.ZkTestBase;
import org.apache.helix.controller.rebalancer.DelayedAutoRebalancer;
import org.apache.helix.controller.rebalancer.strategy.CrushEdRebalanceStrategy;
import org.apache.helix.integration.manager.ClusterControllerManager;
import org.apache.helix.integration.manager.MockParticipantManager;
import org.apache.helix.model.ClusterConfig;
import org.apache.helix.model.ExternalView;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.InstanceConfig;
import org.apache.helix.tools.ClusterVerifiers.BestPossibleExternalViewVerifier;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;


public class TestPreserveAssignmentsOnRebalanceFailure extends ZkTestBase {

  public static String CLUSTER_NAME = TestHelper.getTestClassName() + "_cluster";
  public static int PARTICIPANT_COUNT = 3;
  public static List<MockParticipantManager> _participants = new ArrayList<>();
  public static ClusterControllerManager _controller;
  public static ConfigAccessor _configAccessor;
  public static BestPossibleExternalViewVerifier _verifier;

  @BeforeClass
  public void setup() {
    System.out.println("Start test " + TestHelper.getTestClassName());
    _configAccessor = new ConfigAccessor(_gZkClient);
    _gSetupTool.addCluster(CLUSTER_NAME, true);
    for (int i = 0; i < PARTICIPANT_COUNT; i++) {
      String instanceName = "localhost_" + i;
      addParticipant(CLUSTER_NAME, instanceName);
      InstanceConfig instanceConfig = _configAccessor.getInstanceConfig(CLUSTER_NAME, instanceName);
      instanceConfig.setDomain("zone=zone" + i);
      _configAccessor.setInstanceConfig(CLUSTER_NAME, instanceName, instanceConfig);
    }

    // Enable topology aware rebalance and set expcted topology
    ClusterConfig clusterConfig = _configAccessor.getClusterConfig(CLUSTER_NAME);
    clusterConfig.setFaultZoneType("zone");
    clusterConfig.setTopology("/zone");
    clusterConfig.setTopologyAwareEnabled(true);
    _configAccessor.setClusterConfig(CLUSTER_NAME, clusterConfig);

    String controllerName = CONTROLLER_PREFIX + "_0";
    _controller = new ClusterControllerManager(ZK_ADDR, CLUSTER_NAME, controllerName);
    _controller.syncStart();

    _verifier =
        new BestPossibleExternalViewVerifier.Builder(CLUSTER_NAME).setZkAddr(ZK_ADDR)
            .setWaitTillVerify(TestHelper.DEFAULT_REBALANCE_PROCESSING_WAIT_TIME).build();
  }

  // This test verifies that when a mapping cannot be generated for a resource (failureResources in
  // BestPossibleStateCalcStage), the replicas are not dropped and that the new idealState falls back to the currentStates
  @Test
  public void testPreserveAssignmentsOnRebalanceFailure() {
    System.out.println("Start test: " + TestHelper.getTestClassName() + "." + TestHelper.getTestMethodName());

    // Create a CRUSHED resource
    int numPartition = 3;
    String firstDB = "firstDB";
    _gSetupTool.addResourceToCluster(CLUSTER_NAME, firstDB, numPartition, "LeaderStandby",
        IdealState.RebalanceMode.FULL_AUTO.name(), CrushEdRebalanceStrategy.class.getName());
    IdealState idealStateOne =
        _gSetupTool.getClusterManagementTool().getResourceIdealState(CLUSTER_NAME, firstDB);
    idealStateOne.setMinActiveReplicas(2);
    idealStateOne.setRebalancerClassName(DelayedAutoRebalancer.class.getName());
    _gSetupTool.getClusterManagementTool().setResourceIdealState(CLUSTER_NAME, firstDB, idealStateOne);
    _gSetupTool.rebalanceStorageCluster(CLUSTER_NAME, firstDB, 3);

    // Wait for cluster to converge and take a snapshot of the ExternalView
    Assert.assertTrue(_verifier.verifyByPolling());
    ExternalView oldEV = _gSetupTool.getClusterManagementTool().getResourceExternalView(CLUSTER_NAME, firstDB);

    // Add an instance with no domain set to the cluster, this will cause the topology aware assignment to fail
    String badInstance = "bad_instance";
    _gSetupTool.addInstanceToCluster(CLUSTER_NAME, badInstance);

    // Wait for cluster to converge
    Assert.assertTrue(_verifier.verifyByPolling());

    // Check that the new EV (after bad instance added) is the same as the old EV (before bad instance added)
    ExternalView newEV = _gSetupTool.getClusterManagementTool().getResourceExternalView(CLUSTER_NAME, firstDB);
    Assert.assertEquals(oldEV, newEV);
    System.out.println("End test: " + TestHelper.getTestClassName() + "." + TestHelper.getTestMethodName());
  }
}
