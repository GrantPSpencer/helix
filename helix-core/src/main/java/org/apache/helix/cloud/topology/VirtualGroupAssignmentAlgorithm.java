package org.apache.helix.cloud.topology;

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

import java.util.Map;
import java.util.Set;


public interface VirtualGroupAssignmentAlgorithm {
  /**
   * Compute the assignment for each virtual topology group.
   *
   * @param numGroups number of the virtual groups
   * @param virtualGroupName virtual group name
   * @param zoneMapping current zone mapping from zoneId to instanceIds
   * @param virtualGroupToInstancesMap  current virtual group mapping from virtual group Id to instancesIds
   * @return the assignment as mapping from virtual group ID to instanceIds
   */
  default Map<String, Set<String>> computeAssignment(int numGroups, String virtualGroupName,
      Map<String, Set<String>> zoneMapping, Map<String, Set<String>> virtualGroupToInstancesMap) {
    return computeAssignment(numGroups, virtualGroupName, zoneMapping);
  }

  /**
   * Compute the assignment for each virtual topology group.
   *
   * @param numGroups number of the virtual groups
   * @param virtualGroupName virtual group name
   * @param zoneMapping current zone mapping from zoneId to instanceIds
   * @return the assignment as mapping from virtual group ID to instanceIds
   */
  @Deprecated
  Map<String, Set<String>> computeAssignment(int numGroups, String virtualGroupName,
      Map<String, Set<String>> zoneMapping);
}
