package com.linkedin.helix;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.linkedin.helix.DataAccessor;
import com.linkedin.helix.Mocks.MockHelixTaskExecutor;
import com.linkedin.helix.NotificationContext;
import com.linkedin.helix.PropertyType;
import com.linkedin.helix.Mocks.MockManager;
import com.linkedin.helix.Mocks.MockStateModel;
import com.linkedin.helix.messaging.handling.AsyncCallbackService;
import com.linkedin.helix.messaging.handling.HelixStateTransitionHandler;
import com.linkedin.helix.model.Message;
import com.linkedin.helix.model.StateModelDefinition;
import com.linkedin.helix.model.Message.MessageType;
import com.linkedin.helix.tools.StateModelConfigGenerator;

public class TestHelixTaskExecutor
{

  @Test ()
  public void testCMTaskExecutor() throws Exception
  {
    System.out.println("START TestCMTaskExecutor");
    String msgId = "TestMessageId";
    Message message = new Message(MessageType.TASK_REPLY,msgId);

    message.setMsgId(msgId);
    message.setSrcName("cm-instance-0");
    message.setTgtName("cm-instance-1");
    message.setTgtSessionId("1234");
    message.setFromState("Offline");
    message.setToState("Slave");
    message.setStateUnitKey("Teststateunitkey");
    message.setStateUnitGroup("Teststateunitkey");
    message.setStateModelDef("MasterSlave");
   
    MockManager manager = new MockManager("clusterName");
    DataAccessor accessor = manager.getDataAccessor();
    StateModelConfigGenerator generator = new StateModelConfigGenerator();
    StateModelDefinition stateModelDef = new StateModelDefinition(generator.generateConfigForMasterSlave());
    accessor.setProperty(PropertyType.STATEMODELDEFS, stateModelDef, "MasterSlave");
    
    MockHelixTaskExecutor executor = new MockHelixTaskExecutor();
    MockStateModel stateModel = new MockStateModel();
    NotificationContext context;
    executor.registerMessageHandlerFactory(
        MessageType.TASK_REPLY.toString(), new AsyncCallbackService());
    String clusterName =" testcluster";
    context = new NotificationContext(manager);
    HelixStateTransitionHandler handler = new HelixStateTransitionHandler(stateModel, message, context);

    executor.scheduleTask(message, handler, context);
    while (!executor.isDone(msgId))
    {
      Thread.sleep(500);
    }
    AssertJUnit.assertTrue(stateModel.stateModelInvoked);
    System.out.println("END TestCMTaskExecutor");
  }

}