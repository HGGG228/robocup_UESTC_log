package UESTC.extaction;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import rescuecore2.worldmodel.EntityID;

/**
 * WaterCannonBroadcaster 重型消防装备战术执行总线 (Tactical Execution Bus)。
 * 物理意义：凡是挂载在消防车底盘上的武器系统（水炮、履带、雷达），
 * 都必须严格对接本总线协议。指挥部通过该协议强行接管车辆底盘，
 * 并在不同灾难阶段（静默期、冷启动、实战交火）对车辆进行生命周期管理。
 */
public interface WaterCannonBroadcaster {

    // ========================================================================
    // 核心交火与火力投送枢纽 (The Combat Pipeline)
    // ========================================================================

    /**
     * 战术中枢动作推演引擎 (Action Calculator)。
     * 物理意义：在实战交火阶段，车载电脑每秒钟必须且只能调用一次该接口。
     * 它负责根据雷达扫到的残值、自身血量、水箱压力，算出一笔账：
     * 这秒钟是踩油门撞过去、原地喷水、还是立刻掉头去医院？
     * * @return 返回演算完毕、准备向服务器发送动作请求的执行器实体本身
     */
    public ExtAction calc();

    // ========================================================================
    // 情报下发与坐标注入通道 (Intelligence & Target Injection)
    // ========================================================================

    /**
     * 战役目标强制锁定接口 (Target Lock Override)。
     * 物理意义：上层战略指挥官（Allocator 或 Explorer）通过此通道，
     * 将本回合需要重点打击的火灾建筑 ID，强行刻录在消防车的火控雷达上。
     * * @param targetCoordinates 长官下发的绝对坐标/建筑物实体ID
     * @return 返回雷达已锁定的执行器实体本身
     */
    public ExtAction setTarget(EntityID targetCoordinates);

    /**
     * 战地全频段情报同步接口 (Intel Synchronization)。
     * 物理意义：每秒钟强制接收指挥部发来的最新路况电报和火情通报。
     * 负责将队友求救信号、路障倒塌信息更新到车载动态黑名单中，修正 A* 寻路权重。
     * * @param commsOfficer 战地通讯兵，负责递送电报与频道窃听
     * @return 返回情报刷新完毕的执行器实体本身
     */
    public ExtAction updateInfo(MessageManager commsOfficer);

    // ========================================================================
    // 硬件预热与系统生命周期管理 (Lifecycle Management)
    // ========================================================================

    /**
     * 硬件现场开机预热协议 (Cold Start Preparations)。
     * 物理意义：比赛哨声吹响的第一秒（0 时刻），强行通电，
     * 唤醒所有底层导航仪、防卡死传感器进行盲扫和内存分配，确保发车不熄火。
     * * @return 返回预热完毕、引擎怠速的执行器实体本身
     */
    public ExtAction preparate();

    /**
     * 灾前沙盘推演缓存读取协议（断点恢复阶段）。
     * 物理意义：当系统遭遇崩溃重启，或跳过静默期直接空降灾区时，
     * 强行撬开黑匣子，读取上一场存下来的全城路网距离数据，实现极速冷启动。
     * * @param blackBoxData 指挥部提供的固态黑匣子存储器
     * @return 返回恢复战地记忆的执行器实体本身
     */
    public ExtAction resume(PrecomputeData blackBoxData);

    /**
     * 灾前沙盘推演缓存写入协议（静默断点阶段）。
     * 物理意义：在灾难发生前几秒的静默期，允许车载电脑超频运转，
     * 提前把极其消耗算力的全城路网结构、安全屋拓扑图刻录进黑匣子里。
     * * @param blackBoxData 指挥部提供的固态黑匣子存储器
     * @return 返回刻录完毕的执行器实体本身
     */
    public ExtAction precompute(PrecomputeData blackBoxData);

}
