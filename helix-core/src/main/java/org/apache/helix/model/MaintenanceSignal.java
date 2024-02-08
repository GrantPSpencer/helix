package org.apache.helix.model;

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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.helix.HelixException;
import org.apache.helix.HelixProperty;
import org.apache.helix.zookeeper.datamodel.ZNRecord;

/**
 * A ZNode that signals that the cluster is in maintenance mode.
 */
// This class needs to support having multiple reasons. All the operations should be here for handling adding new signals, removing old ones, swapping out the old client reasons, etc.
public class MaintenanceSignal extends HelixProperty {
  public static int UPDATE_SIGNAL_RETRY_LIMIT = 5;
  private ObjectMapper _objectMapper = new ObjectMapper();

  public class Signal {
    private final Map<String, String> _map;
    public Signal(Map<String, String> map) {
      _map = map;
    }

    /**
     * Returns auto-trigger reason.
     * @return AutoTriggerReason.NOT_APPLICABLE if it was not triggered automatically
     */
    public AutoTriggerReason getAutoTriggerReason() {
      try {
        return AutoTriggerReason
            .valueOf(_map.get(MaintenanceSignalProperty.AUTO_TRIGGER_REASON.name()));
      } catch (Exception e) {
        return AutoTriggerReason.NOT_APPLICABLE;
      }
    }

    /**
     * Returns reason why the cluster is in maintenance mode
     * @return null if field does not exist
     */
    public String getReason() {
      return _map.get(MaintenanceSignalProperty.REASON.name());
    }

    /**
     * Returns last modified time.
     * TODO: Consider using modifiedTime in ZK Stat object.
     * @return -1 if the field does not exist.
     */
    public long getTimestamp() {
      long value = -1;
      String valueStr = _map.get(MaintenanceSignalProperty.TIMESTAMP.name());
      if (valueStr != null) {
        try {
          value = Long.parseLong(valueStr);
        } catch (NumberFormatException ignored) {}
      }
      return value;
    }

    /**
     * Returns triggering entity.
     * @return TriggeringEntity.UNKNOWN if the field does not exist.
     */
    public TriggeringEntity getTriggeringEntity() {
      try {
        return TriggeringEntity
            .valueOf(_map.get(MaintenanceSignalProperty.TRIGGERED_BY.name()));
      } catch (Exception e) {
        return TriggeringEntity.UNKNOWN;
      }
    }

    public String get(String key) {
      return _map.get(key);
    }
  }

  /**
   * Pre-defined fields set by Helix Controller only.
   */
  public enum MaintenanceSignalProperty {
    // This should be maintenanceSignalProperty
    REASON,
    REASONS,
    // This should be signalProperty
    TRIGGERED_BY,
    TIMESTAMP,
    AUTO_TRIGGER_REASON
  }

  /**
   * Possible values for TRIGGERED_BY field in MaintenanceSignal.
   */
  public enum TriggeringEntity {
    CONTROLLER,
    USER, // manually triggered by user
    UNKNOWN
  }

  /**
   * Reason for the maintenance mode being triggered automatically. This will allow checking more
   * efficient because it will check against the exact condition for which the cluster entered
   * maintenance mode. This field does not apply when triggered manually.
   */
  public enum AutoTriggerReason {
    MAX_OFFLINE_INSTANCES_EXCEEDED,
    MAX_PARTITION_PER_INSTANCE_EXCEEDED,
    NOT_APPLICABLE // Not triggered automatically or automatically exiting maintenance mode
  }

  public MaintenanceSignal(String id) {
    super(id);
  }

  public MaintenanceSignal(ZNRecord record) {
    super(record);
  }

  public List<String> getReasonsSerialized() {
    return _record.getListField(MaintenanceSignalProperty.REASONS.name());
  }

  public List<Signal> getReasons() {
    return getReasonsSerialized().stream()
        .map(jsonString -> {
          try {
            return new Signal(_objectMapper.readValue(jsonString, new TypeReference<Map<String, String>>() {}));
          } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize maintenanceReason object in listField", e);
          }
        }).collect(Collectors.toList());
  }

  public void addMaintenanceReason(String reason, Long timestamp, TriggeringEntity triggeringEntity,
      Map<String, String> customFields) throws IOException {
    // check if last reason in listFields == current simpleFields reason
      // if no, create maintenanceObject from simpleFields and add to listFields
    // write maintenanceReason to simple fields
    // add maintenanceReason object to simpleFields
    // attempt to write to ZK with expected version.... how many retries? (this logic should be done outside)


    checkAndStoreSimpleFieldReason();

    // Add new reason to both simpleField and ListField
    writeMaintenanceReasonToSimpleFields(reason, timestamp, triggeringEntity, customFields);
    getReasonsSerialized().add(_objectMapper.writeValueAsString(createMaintenanceReasonObject(
        reason, timestamp, triggeringEntity, customFields).toString()));
  }

  public void removeMaintenanceReason(String reason) throws IOException {
    // Find the reason in listField that matches (does order matter?)
    // If there is no matched object, then throw an error
    // If it is at the end of the list, check if the object's reason == the simpleField reason
    // if no, then do nothing (simpleField was most recently set by old client) --> TODO: MAYBE WE SHOULD DO OP BECAUSE CHECKING IF MAINTENANCESGINAL EMPTY WILL BE EASIER THEN
    // if yes, then take the reasonObject at index-1 and write it to the simpleFields
    // Remove that object from the listField

    checkAndStoreSimpleFieldReason();

    List<Signal> maintenanceReasons = getReasons();
    if (maintenanceReasons.isEmpty()) {
      throw new HelixException(String.format(
          "Attempted to remove maintenance reason %s but current reasons list is empty", reason));
    }

    // TODO: change back to foreach
    int matchedMaintenanceReasonIndex = -1;
    for (int i = 0; i < maintenanceReasons.size(); i++) {
      String currMaintenanceReason = maintenanceReasons.get(i).getReason();
      if (reason.equals(currMaintenanceReason)) {
        matchedMaintenanceReasonIndex = i;
        break;
      }
    }

    if (matchedMaintenanceReasonIndex == -1) {
      throw new HelixException(
          String.format("Attempted to remove maintenance reason %s did not exist", reason));
    }

    getReasonsSerialized().remove(matchedMaintenanceReasonIndex);

    checkAndStoreSimpleFieldReason();
  }

  private void checkAndStoreSimpleFieldReason() throws IOException {
    List<String> maintenanceReasons = getReasonsSerialized();
    // Backwards compatibility check, as old clients do not write maintenance reason to listField
    // Most recent won't be in list if it's from old client, need to add to the list before adding new reason
    if (!maintenanceReasons.isEmpty() &&
        !maintenanceReasons.get(maintenanceReasons.size()-1).equals(
            _record.getSimpleField(MaintenanceSignalProperty.REASON.name()))) {
      // Try to write maintenance object currently in simpleFields to listFields
      getReasonsSerialized().add(_objectMapper.
          writeValueAsString(_record.getSimpleFields()));

    }
  }

  private void writeMaintenanceReasonToSimpleFields(String reason, Long timestamp,
      TriggeringEntity triggeringEntity, Map<String, String> customFields) {
    _record.setSimpleField(MaintenanceSignalProperty.REASON.name(), reason);
    _record.setLongField(MaintenanceSignalProperty.TIMESTAMP.name(), timestamp);
    _record.setSimpleField(MaintenanceSignalProperty.TRIGGERED_BY.name(), triggeringEntity.name());
    customFields.forEach(_record.getSimpleFields()::putIfAbsent);
  }

  private Map<String, String> createMaintenanceReasonObject(String reason, Long timestamp,
      TriggeringEntity triggeringEntity, Map<String, String> customFields) {
    Map<String, String> maintenanceReason = new HashMap<>();
    maintenanceReason.put(MaintenanceSignalProperty.REASON.name(), reason);
    maintenanceReason.put(MaintenanceSignalProperty.TIMESTAMP.name(), Long.toString(timestamp));
    maintenanceReason.put(MaintenanceSignalProperty.TRIGGERED_BY.name(), triggeringEntity.name());
    customFields.forEach(maintenanceReason::putIfAbsent);

    return maintenanceReason;
  }



}
