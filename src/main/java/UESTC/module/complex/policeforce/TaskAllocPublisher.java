package UESTC.module.complex.policeforce;

import java.util.Map;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.complex.PoliceTargetAllocator;
import rescuecore2.worldmodel.EntityID;

public interface TaskAllocPublisher {

    public PoliceTargetAllocator resume(PrecomputeData precomputeData);

    public PoliceTargetAllocator preparate();

    public Map<EntityID, EntityID> getResult();

    public PoliceTargetAllocator calc();

    public PoliceTargetAllocator updateInfo(MessageManager messageManager);
    
}
