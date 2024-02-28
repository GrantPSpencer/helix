package org.apache.helix.rest.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.apache.helix.ConfigAccessor;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.TestHelper;
import org.apache.helix.controller.rebalancer.DelayedAutoRebalancer;
import org.apache.helix.controller.rebalancer.strategy.CrushEdRebalanceStrategy;
import org.apache.helix.integration.manager.ClusterControllerManager;
import org.apache.helix.integration.manager.MockParticipantManager;
import org.apache.helix.manager.zk.ZKHelixDataAccessor;
import org.apache.helix.model.ClusterConfig;
import org.apache.helix.model.CurrentState;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.InstanceConfig;
import org.apache.helix.model.ResourceConfig;
import org.apache.helix.tools.ClusterVerifiers.BestPossibleExternalViewVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class TestCrushedFaultZonesGspencer extends AbstractTestClass {
  private static final Logger LOG = LoggerFactory.getLogger(TestHelper.class);

  private static final int REPLICA_COUNT = 3;
  private static final int RESOURCE_COUNT = 20;
  private static final int PARTITION_COUNT = 20;
  private static final int MIN_ACTIVE_REPLICAS = 2;
  private static final String INSTANCE_NAME_PREFIX = "localhost_";
  private static final int INSTANCE_START_PORT = 12918;
  private static final String CLUSTER_NAME = "CrushedFaultZoneTestCluster";
  private static ClusterControllerManager _controller;
  private static HelixDataAccessor _helixDataAccessor;
  private static ConfigAccessor _configAccessor;
  private static BestPossibleExternalViewVerifier _clusterVerifier;
  private static List<MockParticipantManager> _participants = new ArrayList<>();
  private static List<String> _resources = new ArrayList<>();
  private static Map<String, String> _instanceToFaultZoneMap = new HashMap<>();
  private static Map<String, List<String>> _faultZoneInstanceMap = new HashMap<>();
  private static List<MockParticipantManager> _participantsToDrain = new ArrayList<>();

  @BeforeMethod
  public void beforeTest() {
    try {
      System.out.println("Start setup:" + TestHelper.getTestMethodName());
      // Create test cluster
      _gSetupTool.addCluster(CLUSTER_NAME, true);

      // Setup cluster configs
      _configAccessor = new ConfigAccessor(_gZkClient);
      ClusterConfig clusterConfig = _configAccessor.getClusterConfig(CLUSTER_NAME);
      clusterConfig.setPersistBestPossibleAssignment(true);
      clusterConfig.setTopology("/zone/instance");
      clusterConfig.setTopologyAwareEnabled(true);
      clusterConfig.setFaultZoneType("zone");
      clusterConfig.setRebalanceDelayTime(30000);
      _configAccessor.setClusterConfig(CLUSTER_NAME, clusterConfig);
      _controller = startController(CLUSTER_NAME);

      // Create HelixDataAccessor
      _helixDataAccessor = new ZKHelixDataAccessor(CLUSTER_NAME, _baseAccessor);

      // Create cluster verifier
      _clusterVerifier =
          new BestPossibleExternalViewVerifier.Builder(CLUSTER_NAME).setZkAddr(ZK_ADDR).setResources(new HashSet<>(_resources))
              .setWaitTillVerify(TestHelper.DEFAULT_REBALANCE_PROCESSING_WAIT_TIME).build();

      Assert.assertTrue(_clusterVerifier.verifyByPolling());

      // 10 fault zones of 2 instances
      for (int i = 0; i < 10; i++) {
        String zoneID = "zone_" + i;
        // create instances
        for (int j = 1; j < 3; j++) {
          String instanceName = INSTANCE_NAME_PREFIX + (INSTANCE_START_PORT + String.valueOf(i) + j);
          InstanceConfig instanceConfig = new InstanceConfig(instanceName);
          instanceConfig.setInstanceEnabled(true);
          // Participant_R_10
          String instanceID = String.format("Participant_%s_%s", i, j * 10);
          // zone=zone_R,instance=Participant_R_10
          instanceConfig.setDomain(String.format("zone=%s,instance=%s", zoneID, instanceID));
          _gSetupTool.getClusterManagementTool().addInstance(CLUSTER_NAME, instanceConfig);
          MockParticipantManager participant =
              new MockParticipantManager(ZK_ADDR, CLUSTER_NAME, instanceName);
          participant.syncStart();
          _participants.add(participant);
          _faultZoneInstanceMap.computeIfAbsent(zoneID, key -> new ArrayList<>()).add(instanceName);
          _instanceToFaultZoneMap.put(instanceName, zoneID);
        }
      }
      System.out.println("finished adding 2 instance fault zones");

      // // 2 fault zones of 1 instances
      // for (int i = 10; i < 12; i++) {
      //   String zoneID = "zone_" + i;
      //   // create instances
      //   for (int j = 1; j < 2; j++) {
      //     String instanceName = INSTANCE_NAME_PREFIX + (INSTANCE_START_PORT + String.valueOf(i) + j);
      //     InstanceConfig instanceConfig = new InstanceConfig(instanceName);
      //     instanceConfig.setInstanceEnabled(true);
      //     // Participant_R_10
      //     String instanceID = String.format("Participant_%s_%s", i, j * 10);
      //     // zone=zone_R,instance=Participant_R_10
      //     instanceConfig.setDomain(String.format("zone=%s,instance=%s", zoneID, instanceID));
      //     _gSetupTool.getClusterManagementTool().addInstance(CLUSTER_NAME, instanceConfig);
      //     MockParticipantManager participant =
      //         new MockParticipantManager(ZK_ADDR, CLUSTER_NAME, instanceName);
      //     participant.syncStart();
      //     _participants.add(participant);
      //     _faultZoneInstanceMap.computeIfAbsent(zoneID, key -> new ArrayList<>()).add(instanceName);
      //     _instanceToFaultZoneMap.put(instanceName, zoneID);
      //   }
      // }
      // System.out.println("finished adding 1 instance fault zones");


      // 2 fault zones of 8 instances
      for (int i = 12; i < 14; i++) {
        String zoneID = "zone_" + i;
        // create instances
        for (int j = 1; j < 9; j++) {
          String instanceName = INSTANCE_NAME_PREFIX + (INSTANCE_START_PORT + String.valueOf(i) + j);
          InstanceConfig instanceConfig = new InstanceConfig(instanceName);
          instanceConfig.setInstanceEnabled(true);
          // Participant_R_10
          String instanceID = String.format("Participant_%s_%s", i, j * 10);
          // zone=zone_R,instance=Participant_R_10
          instanceConfig.setDomain(String.format("zone=%s,instance=%s", zoneID, instanceID));
          _gSetupTool.getClusterManagementTool().addInstance(CLUSTER_NAME, instanceConfig);
          MockParticipantManager participant =
              new MockParticipantManager(ZK_ADDR, CLUSTER_NAME, instanceName);
          participant.syncStart();
          _participants.add(participant);
          _instanceToFaultZoneMap.put(instanceName, zoneID);
          // if (i > 13) {
          //   _participantsToDrain.add(participant);
          // } else {
          _faultZoneInstanceMap.computeIfAbsent(zoneID, key -> new ArrayList<>()).add(instanceName);
          // }
        }
      }

      System.out.println("finished adding 8 instance fault zones");


      // 1 fault zones of 1 instances
      for (int i = 10; i < 11; i++) {
        String zoneID = "zone_" + i;
        // create instances
        for (int j = 1; j < 2; j++) {
          String instanceName = INSTANCE_NAME_PREFIX + (INSTANCE_START_PORT + String.valueOf(i) + j);
          InstanceConfig instanceConfig = new InstanceConfig(instanceName);
          instanceConfig.setInstanceEnabled(true);
          // Participant_R_10
          String instanceID = String.format("Participant_%s_%s", i, j * 10);
          // zone=zone_R,instance=Participant_R_10
          instanceConfig.setDomain(String.format("zone=%s,instance=%s", zoneID, instanceID));
          _gSetupTool.getClusterManagementTool().addInstance(CLUSTER_NAME, instanceConfig);
          MockParticipantManager participant =
              new MockParticipantManager(ZK_ADDR, CLUSTER_NAME, instanceName);
          participant.syncStart();
          _participants.add(participant);
          _faultZoneInstanceMap.computeIfAbsent(zoneID, key -> new ArrayList<>()).add(instanceName);
          _instanceToFaultZoneMap.put(instanceName, zoneID);
        }
      }

      System.out.println("finished adding 1 instance fault zones");


      // // add 6 different 0 weight nodes to one of the fault zones
      // for (int i = 0; i < 6; i++) {
      //   String instanceName = INSTANCE_NAME_PREFIX + "badweight_" + i;
      //   InstanceConfig instanceConfig = new InstanceConfig(instanceName);
      //   instanceConfig.setInstanceEnabled(true);
      //   String instanceID = String.format("Participant_badweight_%s", i);
      //   String zoneID = "zone_8";
      //   instanceConfig.setDomain(String.format("zone=%s,instance=%s", zoneID, instanceID));
      //   instanceConfig.setWeight(0);
      //   _gSetupTool.getClusterManagementTool().addInstance(CLUSTER_NAME, instanceConfig);
      //   MockParticipantManager participant =
      //       new MockParticipantManager(ZK_ADDR, CLUSTER_NAME, instanceName);
      //   participant.syncStart();
      //   _participants.add(participant);
      //   _faultZoneInstanceMap.computeIfAbsent(zoneID, key -> new ArrayList<>()).add(instanceName);
      //   _instanceToFaultZoneMap.put(instanceName, zoneID);
      // }
      //
      // System.out.println("Finished adding 0 weight nodes");


      System.out.println("End setup:" + TestHelper.getTestMethodName());
    } catch (Exception e) {
      System.out.println(e.getMessage());
    }
  }

  @AfterMethod
  public void afterTest () throws Exception {
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
  public void testDistribution() throws Exception {
    System.out.println("Start test :" + TestHelper.getTestMethodName());
    // Resource count = 20
    // partition count = 20
    // replicas = 3
    // total replica count = (20*20*3) = 1200

    System.out.println("Starting creating resources.");
    String crushedResourcePrefix = "TEST_CRUSHED_DB_";
    for (int i = 0; i < 1; i++) {
      createCrushedResource(crushedResourcePrefix + i, 1200, MIN_ACTIVE_REPLICA, 3000L);
      // if (i % 1 == 0) {
      //   Assert.assertTrue(_clusterVerifier.verifyByPolling());
      // }
    }
    System.out.println("Finished creating resources.");

    // Wait for cluster to converge after adding resources
    Assert.assertTrue(_clusterVerifier.verifyByPolling());

    // for (int i = 0; i < 2; i++) {
    //   MockParticipantManager participant = _participants.get((int) (Math.random() * _participants.size()));
    //   participant.syncStop();
    //   System.out.println("Killed participant: " + participant.getInstanceName() + " was in fault zone " + _instanceToFaultZoneMap.get(participant.getInstanceName()));
    // }
    // Thread.sleep(4000L);
    // Assert.assertTrue(_clusterVerifier.verifyByPolling());

    // enter mm
    _gSetupTool.getClusterManagementTool().enableMaintenanceMode(CLUSTER_NAME, true, "entering MM");
    for (MockParticipantManager participant : _participantsToDrain) {
      // InstanceConfig instanceConfig = new InstanceConfig(instanceName);
      // instanceConfig.setInstanceEnabled(true);
      // Participant_R_10

      InstanceConfig instanceConfig = _helixDataAccessor.getProperty(_helixDataAccessor.keyBuilder().instanceConfig(participant.getInstanceName()));
      instanceConfig.setWeight(0);
      _helixDataAccessor.setProperty(_helixDataAccessor.keyBuilder().instanceConfig(participant.getInstanceName()), instanceConfig);
    }


    // exit mm
    _gSetupTool.getClusterManagementTool().enableMaintenanceMode(CLUSTER_NAME, false, "exiting MM");
    Assert.assertTrue(_clusterVerifier.verifyByPolling());



    // Get instance --> assignment distribution
    Map<String, Integer> instanceToAssignmentCount = new HashMap<>();
    Map<String, Integer> faultZonetoAssignmmentCount = new HashMap<>();
    for (MockParticipantManager participant : _participants) {
      if (!participant.isConnected()) {
        _faultZoneInstanceMap.get(_instanceToFaultZoneMap.get(participant.getInstanceName())).remove(participant.getInstanceName());
        _instanceToFaultZoneMap.remove(participant.getInstanceName());
        continue;
      }
      List<String> assignedResources = _helixDataAccessor.getChildNames(_helixDataAccessor
          .keyBuilder().currentStates(participant.getInstanceName(), participant.getSessionId()));
      instanceToAssignmentCount.put(participant.getInstanceName(), 0);
      String instanceFaultZone = _instanceToFaultZoneMap.get(participant.getInstanceName());
      for (String resource : assignedResources) {
        CurrentState currentState = _helixDataAccessor.getProperty(_helixDataAccessor.keyBuilder()
            .currentState(participant.getInstanceName(), participant.getSessionId(), resource));
        instanceToAssignmentCount.put(participant.getInstanceName(),
            instanceToAssignmentCount.get(participant.getInstanceName()) + currentState.getPartitionStateMap().size());
        // System.out.println(String.format("count for faultzone %s is: %s", instanceFaultZone, faultZonetoAssignmmentCount.get(instanceFaultZone)));
        faultZonetoAssignmmentCount.put(instanceFaultZone,
            faultZonetoAssignmmentCount.getOrDefault(instanceFaultZone, 0) + currentState.getPartitionStateMap().size());
        // System.out.println(String.format("adding size: %s", currentState.getPartitionStateMap().size()));
        // System.out.println(String.format("count after add for faultzone %s is: %s", instanceFaultZone, faultZonetoAssignmmentCount.get(instanceFaultZone)));
      }
    }

    System.out.println("--- faultZoneInstance Map start --");
    for (Map.Entry<String, List<String>> entry : _faultZoneInstanceMap.entrySet()) {
      System.out.println(entry.getKey() + ": " + entry.getValue().toString());
    }
    System.out.println("--- faultZoneInstance Map end --");

    System.out.println("--- instanceToAssignmentCount Map start --");
    for (Map.Entry<String, Integer> entry : instanceToAssignmentCount.entrySet()) {
      System.out.println(entry.getKey() + ": " + entry.getValue());
    }
    System.out.println("--- instanceToAssignmentCount Map end --");

    System.out.println("--- faultZonetoAssignmmentCount Map start --");
    for (Map.Entry<String, Integer> entry : faultZonetoAssignmmentCount.entrySet()) {
      System.out.println("Total = " + entry.getKey() + ": " + entry.getValue());
      System.out.println("Averaged = " + entry.getKey() + ": " + (entry.getValue() / _faultZoneInstanceMap.get(entry.getKey()).size()));
    }
    System.out.println("--- faultZonetoAssignmmentCount Map end --");

    System.out.println("End test :" + TestHelper.getTestMethodName());
  }


  private void createCrushedResource(String db, int numPartition, int minActiveReplica, long delay) {
    _gSetupTool.addResourceToCluster(CLUSTER_NAME, db, numPartition, "LeaderStandby",
        IdealState.RebalanceMode.FULL_AUTO + "", null);
    _resources.add(db);

    IdealState idealState =
        _gSetupTool.getClusterManagementTool().getResourceIdealState(CLUSTER_NAME, db);
    idealState.setMinActiveReplicas(minActiveReplica);
    idealState.setDelayRebalanceEnabled(true);
    idealState.setRebalanceDelay(delay);
    idealState.setRebalancerClassName(DelayedAutoRebalancer.class.getName());
    idealState.setRebalanceStrategy(CrushEdRebalanceStrategy.class.getName());
    _gSetupTool.getClusterManagementTool().setResourceIdealState(CLUSTER_NAME, db, idealState);

    ResourceConfig resourceConfig = new ResourceConfig(db);
    _configAccessor.setResourceConfig(CLUSTER_NAME, db, resourceConfig);
    _gSetupTool.rebalanceResource(CLUSTER_NAME, db, REPLICA_COUNT);
  }
}