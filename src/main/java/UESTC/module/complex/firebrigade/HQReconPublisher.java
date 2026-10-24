package UESTC.module.complex.firebrigade;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.complex.BuildingDetector;
import rescuecore2.worldmodel.EntityID;

public interface HQReconPublisher {
    
    public BuildingDetector precompute(PrecomputeData precomputeData);

    public BuildingDetector resume(PrecomputeData precomputeData);

    public BuildingDetector preparate();

    public EntityID getTarget();

    public BuildingDetector updateInfo(MessageManager messageManager);

    public BuildingDetector calc();

} 
