package UESTC.extaction;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import rescuecore2.worldmodel.EntityID;

/**
 * UESTCCommunalObstaclePurge 特警重型破拆终端强制对接总线。
 * 物理意义：所有挂载在警车底盘上的暴力清障模块（电锯、推土铲、牵引索），
 * 必须无条件服从本总线协议。指挥部将通过这套硬连接接口，
 * 越权接管警车底盘，实施战区扫荡与生命通道的物理开拓。
 */
public interface UESTCCommunalObstaclePurge {

    // ========================================================================
    // 战局态势感知与目标重定向通道 (Tactical Intel & Targeting)
    // ========================================================================

    /**
     * 战役清障目标强制锁定总线 (Objective Lock Override)。
     * 物理意义：上层战略指挥系统通过此通道，将必须攻克的堵点（如一堆废墟或一条主干道）
     * 的物理坐标 ID，强行灌入警车的火控与导航雷达中。
     * * @param targetCoordinates 战略级打击目标的绝对坐标点
     * @return 目标锁定成功，返回处于待命状态的执行终端本身
     */
    public ExtAction setTarget(EntityID targetCoordinates);

    /**
     * 全频段战地情报同步链路 (Battlefield Intel Synchronization)。
     * 物理意义：每一秒钟，警车必须通过此链路窃听求救电报、下载最新的路网坍塌图，
     * 并刷新内部的防卡死雷达和断头路记忆库。
     * * @param intelOfficer 战地情报官，负责统筹电报收发与频段监听
     * @return 情报解析完毕，返回状态更新后的执行终端本身
     */
    public ExtAction updateInfo(MessageManager intelOfficer);

    // ========================================================================
    // 核心物理破碎与机动推演引擎 (The Destruction Pipeline)
    // ========================================================================

    /**
     * 暴力破拆战术动作演算阵列 (Tactical Calculator)。
     * 物理意义：交火阶段的核心大脑。每秒钟只能且必须调用一次。
     * 警车在这里完成极其复杂的几何学运算：切割哪条线？撞击哪块石头？还是踩油门开过去？
     * 这是将宏观的“扫荡指令”降维打击成具体“底盘动作”的唯一引擎。
     * * @return 动作推演完毕，返回挂载了具体 Action 的执行终端本身
     */
    public ExtAction calc();

    // ========================================================================
    // 灾变各阶段生命周期管控 (Disaster Lifecycle Management)
    // ========================================================================

    /**
     * 灾前沙盘推演缓存固化协议（静默期写入阶段）。
     * 物理意义：在地震发生的最初几秒内，允许警车利用闲置算力，
     * 将极其复杂的全城路网多边形和避难所拓扑结构，暴力刻录进黑匣子缓存中。
     * * @param blackBoxData 指挥部配发的抗震固态存储器
     * @return 战术数据刻录完毕的执行终端本身
     */
    public ExtAction precompute(PrecomputeData blackBoxData);

    /**
     * 灾前沙盘推演缓存复苏协议（静默期恢复阶段）。
     * 物理意义：若警车在灾区遭遇 EMP 断电重启，或直接跳过前期侦察阶段，
     * 必须强行读取黑匣子中残留的路网数据，实现底层几何雷达的极速冷启动。
     * * @param blackBoxData 指挥部配发的抗震固态存储器
     * @return 战区记忆恢复完毕的执行终端本身
     */
    public ExtAction resume(PrecomputeData blackBoxData);

    /**
     * 硬件现场开机盲扫协议 (Cold Start Preparations)。
     * 物理意义：实战阶段开启的第一秒，强行为主板和所有传感器通电。
     * 唤醒 A* 寻路仪、人群卡死探测器等高耗能模块，确保随时可以进入战备机动状态。
     * * @return 预热完毕、引擎空转的执行终端本身
     */
    public ExtAction preparate();

}
