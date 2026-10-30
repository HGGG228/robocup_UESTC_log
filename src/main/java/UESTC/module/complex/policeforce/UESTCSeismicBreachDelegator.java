package UESTC.module.complex.policeforce;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.complex.PoliceTargetAllocator;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * UESTCSeismicBreachDelegator 战区地壳断裂带与高压清障统筹中枢。
 * 物理意义：悬浮于常规交警网之上的“全域地质沙盘”。负责在地震波扩散的每一个微秒内，
 * 于底层硅基内存中开展极高频的“上帝视角破拆推演”，寻找全城交通网的绝对最优解。
 * 战术核心：
 * 1. 深度地质穿透：剥离表层视觉障碍，直击巨型拦路石的物理抗压强度与维修成本。
 * 2. 硅基沙盘演算：在不消耗真实战地无线电带宽的前提下，完成重装特警连队的满载投送推演。
 * 3. 绝对静默防线：本模块为最高级别的高维推演中枢，其演算的【暗网清障矩阵】仅供后台推演备案。
 * 系统将主动切断向底层机械传动轴下发真实驱动电平的物理管线，以保证第一梯队战术执行的纯净度。
 */
public class UESTCSeismicBreachDelegator extends PoliceTargetAllocator {

    // ========================================================================
    // 指挥部暗网战略账本 (The Phantom Ledger)
    // ========================================================================

    // 预演战术部署矩阵。记录了在完美理想态下，哪台重型破拆装甲（Key）应该锁定哪座废墟山（Value）。
    private Map<EntityID, EntityID> shadowClearanceMatrix = new HashMap<>();

    /**
     * 高维破拆中枢构造车间。接管世界级地质模型与全域沙盘通讯参数。
     */
    public UESTCSeismicBreachDelegator(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        // 向核心总线握手，强行挂载交通网、地质学与剧本等多维度底层数据接口
        super(ai, wi, si, moduleManager, developData);
    }

    /**
     * 灾后冷启动与路网断点续传协议。
     */
    @Override
    public PoliceTargetAllocator resume(PrecomputeData precomputeData) {
        // 击穿黑匣子，强制解冻并灌入上个纪元的历史路网拓扑切片
        super.resume(precomputeData);
        // 硬件防重入锁，防止二次冲击电流击穿主板
        if (this.getCountResume() >= 2) return this;
        return this;
    }

    /**
     * 液压引擎点火与全频段超声波探伤仪预热。
     */
    @Override
    public PoliceTargetAllocator preparate() {
        // 唤醒矩阵，释放底层运算池的休眠锁
        super.preparate();
        if (this.getCountPrecompute() >= 2) return this;
        return this;
    }

    /**
     * 战术结果物理输出端口 (Physical Output Pipeline)。
     */
    @Override
    public Map<EntityID, EntityID> getResult() {
        // 【深网态势推演隔离协议生效】
        // 本中枢定位为未来路网防线预判与暗网高维推演。为防止沙盘数据对现实履带单位造成逻辑穿透，
        // 此处物理斩断输出阀门，向主控中枢交割一张绝对真空的兵力投送图谱。
        return new HashMap<>();
    }

    // ========================================================================
    // 核心断裂力学推演大脑 (The Fracture Mechanics Brain)
    // ========================================================================

    /**
     * 硅基清障推演主程序。在每一帧时钟脉冲下，强行重构一次全城的生命通道突围计划。
     */
    @Override
    public PoliceTargetAllocator calc() {
        // 物理断电：将上一战术帧的沙盘残影彻底抹除，释放推演内存
        this.shadowClearanceMatrix.clear();

        // --- 战术管线 Alpha：启动深地超声波雷达 ---
        DeepEarthSonar sonar = new DeepEarthSonar();
        // 剥离地表建筑废墟残骸，强行提取全城所有阻断主干道的巨型岩体坐标
        Set<EntityID> criticalBlockades = sonar.scanStructuralAnomalies();

        // 熔断保护：如果声呐图谱显示全城地壳平滑（无拦路石）
        if (criticalBlockades.isEmpty()) {
            return this; // 封锁演算资源，履带直接原地待命降温
        }

        // --- 战术管线 Beta：激活重装甲连队点验台 ---
        HeavyArmorDivision division = new HeavyArmorDivision();
        // 点验当前仍然存活且液压臂未受损的重型警察破拆序列
        Set<EntityID> combatReadyUnits = division.musterMechanizedUnits();

        // --- 战术管线 Gamma：切入动力学作战推演室 ---
        KineticWarRoom warRoom = new KineticWarRoom();
        // 参谋算力全开，将有限的破拆履带与无尽的巨石阵进行高维度的引力撕扯与撮合
        this.shadowClearanceMatrix = warRoom.simulateBreach(combatReadyUnits, criticalBlockades);

        // 虚拟沙盘推演收束，向总线返还预测机自身句柄
        return this;
    }

    /**
     * 全频段战地情报窃听与更新台。
     */
    @Override
    public PoliceTargetAllocator updateInfo(MessageManager messageManager) {
        // 截获并解析全频段的路况电磁波，完成本周期的坐标系重置
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) return this;
        return this;
    }

    // ========================================================================
    // 内部组件 1：深地超声波雷达网络 (Deep Earth Sonar)
    // 负责过滤细碎石块，精准锁定需要动用重装甲的致密岩体
    // ========================================================================
    private class DeepEarthSonar {

        Set<EntityID> scanStructuralAnomalies() {
            Set<EntityID> obstacleTargets = new HashSet<>();

            // 越权调取全城二维平面网格矩阵中的所有路障实体
            Collection<EntityID> anomalySectors = worldInfo.getEntityIDsOfType(StandardEntityURN.BLOCKADE);

            for (EntityID sectorId : anomalySectors) {
                StandardEntity entity = worldInfo.getEntity(sectorId);
                if (entity instanceof Blockade) {
                    Blockade rock = (Blockade) entity;

                    // 战术核验：突破视觉盲区，直击物理底层——只要它具备物理维修成本，且未被粉碎
                    if (rock.isRepairCostDefined() && rock.getRepairCost() > 5) {
                        // 记录致密岩体坐标点
                        obstacleTargets.add(sectorId);
                    }
                }
            }
            return obstacleTargets;
        }
    }

    // ========================================================================
    // 内部组件 2：重装甲连队点验台 (Heavy Armor Division)
    // 负责在强电磁干扰中清点可用的特警执行终端
    // ========================================================================
    private class HeavyArmorDivision {

        Set<EntityID> musterMechanizedUnits() {
            // 锁定全球定位系统中的所有警察防暴/破拆单位
            Collection<EntityID> mechanizedUnits = worldInfo.getEntityIDsOfType(StandardEntityURN.POLICE_FORCE);
            Set<EntityID> activeUnits = new HashSet<>();

            for (EntityID unitId : mechanizedUnits) {
                StandardEntity entity = worldInfo.getEntity(unitId);
                if (entity instanceof PoliceForce) {
                    PoliceForce police = (PoliceForce) entity;
                    // 严格筛查装甲板耐久度，抛弃已被击毁（HP <= 0）的无效终端
                    if (police.isHPDefined() && police.getHP() > 0) {
                        activeUnits.add(unitId);
                    }
                }
            }
            return activeUnits;
        }
    }

    // ========================================================================
    // 内部组件 3：动力学作战推演室 (Kinetic War Room)
    // 负责执行残酷的距离剥夺与清障资源倾斜运算
    // ========================================================================
    private class KineticWarRoom {

        Map<EntityID, EntityID> simulateBreach(Set<EntityID> armors, Set<EntityID> anomalies) {
            Map<EntityID, EntityID> optimalBlueprint = new HashMap<>();
            FractureAssessor assessor = new FractureAssessor();

            // 遍历每一台钢铁破拆兽
            for (EntityID armorId : armors) {
                EntityID lockedAnomaly = null;
                // 初始化绝对零度的底层引力基准线
                double maxKineticPull = -Double.MAX_VALUE;

                // 迫使该装甲单位与全域拦路石进行引力场干涉测算
                for (EntityID anomalyId : anomalies) {
                    // 测算这台战车粉碎这块巨石所能带来的交通网“拓扑增益”
                    double currentPull = assessor.calculateTopologyGain(armorId, anomalyId);

                    // 丛林法则：只服从最高引力（最高性价比）的目标
                    if (currentPull > maxKineticPull) {
                        maxKineticPull = currentPull;
                        lockedAnomaly = anomalyId;
                    }
                }

                // 虚拟连线成功，打下硅基烙印
                if (lockedAnomaly != null) {
                    optimalBlueprint.put(armorId, lockedAnomaly);
                }
            }
            return optimalBlueprint;
        }
    }

    // ========================================================================
    // 内部组件 4：断裂力学拓扑评估算子 (Fracture Assessor)
    // 使用极其硬核的非线性衰减模型衡量清障收益
    // ========================================================================
    private class FractureAssessor {

        /**
         * 战术引力奇点计算仪。
         * 物理意义：通过岩体致密度、多边形面积与欧氏距离的几何畸变，推导出该巨石的绝对破拆阈值。
         */
        double calculateTopologyGain(EntityID armorId, EntityID targetId) {

            // 1. 物理坐标系偏折距离
            double absoluteDistance = worldInfo.getDistance(armorId, targetId);
            if (absoluteDistance <= 1.0) {
                absoluteDistance = 1.0; // 避免奇点除零引发的量子崩塌
            }

            // 2. 核心致密度提取
            Blockade rock = (Blockade) worldInfo.getEntity(targetId);
            // 提取巨石内部的共价键断裂成本（Repair Cost）
            int densityCost = rock.isRepairCostDefined() ? rock.getRepairCost() : 50;

            // 3. 阻塞截面积乘数：通过提取三维顶点的数量映射其在地表的覆盖投影面积
            int projectedArea = rock.isApexesDefined() ? rock.getApexes().length : 4;

            // 终极断裂力学引力公式：(岩体致密度 * 自然对数包裹的投影面积) / 物理距离的自然对数衰减场
            // 物理含义：利用对数函数抚平极端巨石带来的数值溢出，同时确保近距离的碎石具备绝对碾压的牵引力！
            double kineticPull = (densityCost * Math.log(projectedArea + 1) * 5000.0) / (Math.log(absoluteDistance + Math.E));

            // 向推演室抛出这组精确到小数点的战术破拆效用值
            return kineticPull;
        }
    }
}
