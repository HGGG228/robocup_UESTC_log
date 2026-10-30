package UESTC.extaction;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import rescuecore2.worldmodel.EntityID;

/**
 * UESTCcleaning 战地清扫与医疗静默冗余接口 (Phantom Reserve Bus)。

 */
public interface UESTCcleaning {

    // ========================================================================
    //
    // ========================================================================

    /**
     * 战术目标静默锁定测试接口。
     * @param targetCoordinates 战略级打击目标的绝对坐标点
     * @return 默认返回空或由后续扩展实现
     */
    public ExtAction setTarget(EntityID targetCoordinates);

    /**
     * 频段监听伪装链路。
     * 实际作用：提供一个合法的战地情报监听方法签名以备不时之需。
     * @param intelOfficer 战地情报官
     * @return 默认返回空或由后续扩展实现
     */
    public ExtAction updateInfo(MessageManager intelOfficer);

    // ========================================================================
    // 备用机动推演引擎 (Standby Pipeline)
    // ========================================================================

    /**
     * 冗余推演引擎占位符。
     * @return 默认返回空
     */
    public ExtAction calc();

    // ========================================================================
    // 生命周期静默管控 (Silent Lifecycle Management)
    // ========================================================================

    /**
     * 占位符缓存固化（休眠态）。
     * @param blackBoxData 指挥部配发的抗震固态存储器
     * @return 返回终端本身
     */
    public ExtAction precompute(PrecomputeData blackBoxData);

    /**
     * 占位符缓存复苏（休眠态）。
     * @param blackBoxData 指挥部配发的抗震固态存储器
     * @return 返回终端本身
     */
    public ExtAction resume(PrecomputeData blackBoxData);

    /**
     * 硬件盲扫预热占位符。
     * @return 返回终端本身
     */
    public ExtAction preparate();

}
