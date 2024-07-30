package org.apache.helix.util;

import org.apache.helix.HelixConstants;
import org.apache.helix.PropertyPathBuilder;
import org.apache.helix.SystemPropertyKeys;
import org.apache.helix.TestHelper;
import org.apache.helix.common.ZkTestBase;
import org.apache.helix.integration.manager.MockParticipantManager;
import org.apache.helix.messaging.handling.HelixStateTransitionHandler;
import org.apache.helix.model.Message;
import org.apache.helix.zookeeper.datamodel.ZNRecord;
import org.apache.helix.zookeeper.zkclient.exception.ZkException;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;


public class TestStatusUpdateUtilDisabled extends ZkTestBase {
  private String clusterName = TestHelper.getTestClassName();
  private int n = 1;
  private Message message = new Message(Message.MessageType.STATE_TRANSITION, "Some unique id");
  private MockParticipantManager[] participants = new MockParticipantManager[n];


  // static void setFinalStatic(Field field, Object newValue) throws Exception {
  //   field.setAccessible(true);
  //
  //   Field modifiersField = Field.class.getDeclaredField("modifiers");
  //   modifiersField.setAccessible(true);
  //   modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
  //
  //   field.set(null, newValue);
  // }

  @AfterClass
  public void afterClass() {
    for (int i = 0; i < n; i++) {
      participants[i].syncStop();
    }
  }

  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    TestHelper.setupCluster(clusterName, ZK_ADDR, 12918, // participant port
        "localhost", // participant name prefix
        "TestDB", // resource name prefix
        1, // resources
        1, // partitions per resource
        n, // number of nodes
        1, // replicas
        "MasterSlave", true);

    for (int i = 0; i < n; i++) {
      String instanceName = "localhost_" + (12918 + i);
      participants[i] = new MockParticipantManager(ZK_ADDR, clusterName, instanceName);
      participants[i].syncStart();
    }

    message.setSrcName("cm-instance-0");
    message.setTgtSessionId(participants[0].getSessionId());
    message.setFromState("Offline");
    message.setToState("Slave");
    message.setPartitionName("TestDB_0");
    message.setMsgId("Some unique message id");
    message.setResourceName("TestDB");
    message.setTgtName("localhost_12918");
    message.setStateModelDef("MasterSlave");
    message.setStateModelFactoryName(HelixConstants.DEFAULT_STATE_MODEL_FACTORY);
  }

  @Test
  public void testDisableErrorLogByDefault() throws Exception {
    StatusUpdateUtil statusUpdateUtil = new StatusUpdateUtil();
    System.out.println("Error log enabled is: " + statusUpdateUtil.ERROR_LOG_TO_ZK_ENABLED);

    Exception e = new RuntimeException("test exception");
    statusUpdateUtil.logError(message, HelixStateTransitionHandler.class, e, "test status update", participants[0]);

    // assert by default, not logged to Zookeeper
    String errPath = PropertyPathBuilder.instanceError(clusterName, "localhost_12918", participants[0].getSessionId(), "TestDB",
            "TestDB_0");
    try {
      ZNRecord error = _gZkClient.readData(errPath);
      Assert.fail("not expecting being able to send error logs to ZK by default.");
    } catch (ZkException zke) {
      // expected
    }
  }
}
