package UESTC.module.complex.firebrigade;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.complex.Search;
import rescuecore2.worldmodel.EntityID;

public interface HQPathPublisher {

    public Search preparate();

    public Search resume(PrecomputeData pd);

    public EntityID getTarget();

    public Search updateInfo(MessageManager mm);

    public Search calc();
}
