package org.apache.helix.controller.rebalancer.waged.constraints;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.helix.HelixRebalanceException;
import org.apache.helix.controller.rebalancer.waged.model.AssignableReplica;
import org.apache.helix.controller.rebalancer.waged.model.ClusterModel;
import org.apache.helix.controller.rebalancer.waged.model.ClusterModelTestHelper;
import org.apache.helix.controller.rebalancer.waged.model.OptimalAssignment;
import org.testng.Assert;
import org.testng.annotations.Test;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


public class TestConstraintBasedAlgorithm {

  @Test
  public void testCalculateNoValidAssignment() throws IOException {
    HardConstraint mockHardConstraint = mock(HardConstraint.class);
    SoftConstraint mockSoftConstraint = mock(SoftConstraint.class);
    when(mockHardConstraint.isAssignmentValid(any(), any(), any())).thenReturn(false);
    when(mockSoftConstraint.getAssignmentNormalizedScore(any(), any(), any())).thenReturn(1.0);
    ConstraintBasedAlgorithm algorithm =
        new ConstraintBasedAlgorithm(ImmutableList.of(mockHardConstraint),
            ImmutableMap.of(mockSoftConstraint, 1f));
    ClusterModel clusterModel = new ClusterModelTestHelper().getDefaultClusterModel();
    try {
      algorithm.calculate(clusterModel);
    } catch (HelixRebalanceException ex) {
      Assert.assertEquals(ex.getFailureType(), HelixRebalanceException.Type.FAILED_TO_CALCULATE);
    }

    verify(mockHardConstraint, times(1)).setEnableLogging(eq(true));
    verify(mockHardConstraint, times(1)).isAssignmentValid(any(), any(), any());
  }

  @Test
  public void testCalculateNoValidAssignmentFirstAndThenRecovery() throws IOException, HelixRebalanceException {
    HardConstraint mockHardConstraint = mock(HardConstraint.class);
    SoftConstraint mockSoftConstraint = mock(SoftConstraint.class);
    when(mockHardConstraint.isAssignmentValid(any(), any(), any()))
        .thenReturn(false) // hard constraint fails
        .thenReturn(true); // hard constraint recovers
    when(mockSoftConstraint.getAssignmentNormalizedScore(any(), any(), any())).thenReturn(1.0);
    ConstraintBasedAlgorithm algorithm =
        new ConstraintBasedAlgorithm(ImmutableList.of(mockHardConstraint),
            ImmutableMap.of(mockSoftConstraint, 1f));
    ClusterModel clusterModel = new ClusterModelTestHelper().getDefaultClusterModel();
    try {
      algorithm.calculate(clusterModel);
    } catch (HelixRebalanceException ex) {
      Assert.assertEquals(ex.getFailureType(), HelixRebalanceException.Type.FAILED_TO_CALCULATE);
    }

    verify(mockHardConstraint, times(1)).setEnableLogging(eq(true));
    verify(mockHardConstraint, times(1)).isAssignmentValid(any(), any(), any());

    // calling again for recovery (no exception)
    algorithm.calculate(clusterModel);
    verify(mockHardConstraint, atLeastOnce()).setEnableLogging(eq(false));
  }

  @Test
  public void testCalculateWithValidAssignment() throws IOException, HelixRebalanceException {
    HardConstraint mockHardConstraint = mock(HardConstraint.class);
    SoftConstraint mockSoftConstraint = mock(SoftConstraint.class);
    when(mockHardConstraint.isAssignmentValid(any(), any(), any())).thenReturn(true);
    when(mockSoftConstraint.getAssignmentNormalizedScore(any(), any(), any())).thenReturn(1.0);
    ConstraintBasedAlgorithm algorithm =
        new ConstraintBasedAlgorithm(ImmutableList.of(mockHardConstraint),
            ImmutableMap.of(mockSoftConstraint, 1f));
    ClusterModel clusterModel = new ClusterModelTestHelper().getDefaultClusterModel();
    OptimalAssignment optimalAssignment = algorithm.calculate(clusterModel);

    Assert.assertFalse(optimalAssignment.hasAnyFailure());
  }

  @Test
  public void testCalculateScoreDeterminism() throws IOException, HelixRebalanceException {
    HardConstraint mockHardConstraint = mock(HardConstraint.class);
    SoftConstraint mockSoftConstraint = mock(SoftConstraint.class);
    when(mockHardConstraint.isAssignmentValid(any(), any(), any())).thenReturn(true);
    when(mockSoftConstraint.getAssignmentNormalizedScore(any(), any(), any())).thenReturn(1.0);
    ConstraintBasedAlgorithm algorithm =
        new ConstraintBasedAlgorithm(ImmutableList.of(mockHardConstraint),
            ImmutableMap.of(mockSoftConstraint, 1f));
    ClusterModel clusterModel = new ClusterModelTestHelper().getMultiNodeClusterModel();
    OptimalAssignment optimalAssignment = algorithm.calculate(clusterModel);

    optimalAssignment.getOptimalResourceAssignment().values().forEach(
        resourceAssignment -> resourceAssignment.getMappedPartitions().forEach(partition -> {
          Assert.assertEquals(resourceAssignment.getReplicaMap(partition).keySet().size(), 1);
          Assert.assertTrue(resourceAssignment.getReplicaMap(partition)
              .containsKey(ClusterModelTestHelper.TEST_INSTANCE_ID_1));
        }));
  }

  // Add capacity related hard/soft constraint to test sorting algorithm in ConstraintBasedAlgorithm.
  @Test
  public void testSortingByResourceCapacity() throws IOException, HelixRebalanceException {
    HardConstraint nodeCapacityConstraint = new NodeCapacityConstraint();
    SoftConstraint soft1 = new MaxCapacityUsageInstanceConstraint();
    SoftConstraint soft2 = new InstancePartitionsCountConstraint();
    ConstraintBasedAlgorithm algorithm =
        new ConstraintBasedAlgorithm(ImmutableList.of(nodeCapacityConstraint),
            ImmutableMap.of(soft1, 1f, soft2, 1f));
    ClusterModel clusterModel = new ClusterModelTestHelper().getMultiNodeClusterModel();
    OptimalAssignment optimalAssignment = algorithm.calculate(clusterModel);

    Assert.assertFalse(optimalAssignment.hasAnyFailure());
  }

  // Add neg test for error handling in ConstraintBasedAlgorithm replica sorting.
  @Test
  public void testSortingEarlyQuitLackCapacity() throws IOException, HelixRebalanceException {
    HardConstraint nodeCapacityConstraint = new NodeCapacityConstraint();
    SoftConstraint soft1 = new MaxCapacityUsageInstanceConstraint();
    SoftConstraint soft2 = new InstancePartitionsCountConstraint();
    ConstraintBasedAlgorithm algorithm =
        new ConstraintBasedAlgorithm(ImmutableList.of(nodeCapacityConstraint),
            ImmutableMap.of(soft1, 1f, soft2, 1f));
    ClusterModel clusterModel =
        new ClusterModelTestHelper().getMultiNodeClusterModelNegativeSetup();
    try {
      OptimalAssignment optimalAssignment = algorithm.calculate(clusterModel);
    } catch (HelixRebalanceException ex) {
      Assert.assertEquals(ex.getFailureType(), HelixRebalanceException.Type.FAILED_TO_CALCULATE);
      Assert.assertEquals(ex.getMessage(),
          "The cluster does not have enough item1 capacity for all partitions.  Failure Type: FAILED_TO_CALCULATE");
    }
  }

  @Test
  public void testCalculateWithInvalidAssignmentForNodeCapacity() throws IOException {
    HardConstraint nodeCapacityConstraint = new NodeCapacityConstraint();
    SoftConstraint soft1 = new MaxCapacityUsageInstanceConstraint();
    SoftConstraint soft2 = new InstancePartitionsCountConstraint();
    ConstraintBasedAlgorithm algorithm =
        new ConstraintBasedAlgorithm(ImmutableList.of(nodeCapacityConstraint),
            ImmutableMap.of(soft1, 1f, soft2, 1f));
    ClusterModel clusterModel = new ClusterModelTestHelper().getMultiNodeClusterModel();
    // increase the ask capacity of item 3, which will trigger the capacity constraint to fail.
    Map<String, Set<AssignableReplica>> assignableReplicaMap = new HashMap<>(clusterModel.getAssignableReplicaMap());
    Set<AssignableReplica> resourceAssignableReplicas = assignableReplicaMap.get("Resource3");
    AssignableReplica replica = resourceAssignableReplicas.iterator().next();
    replica.getCapacity().put("item3", 40); // available: 30, requested: 40.

    try {
      algorithm.calculate(clusterModel);
    } catch (HelixRebalanceException ex) {
      Assert.assertEquals(ex.getFailureType(), HelixRebalanceException.Type.FAILED_TO_CALCULATE);
    }
  }
}
