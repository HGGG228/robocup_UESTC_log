package UESTC.module.complex.firebrigade;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.complex.FireTargetAllocator;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * UESTCPreemptiveThermalAllocator 战区全域热力态势预演中枢（零维统筹版）。
 * 物理意义：悬浮于物理消防防线之上的“上帝之眼”。负责在灾难爆发的每一个瞬间，
 * 于底层硅基内存中开展极高频的“上帝视角排兵布阵”，测算全城火情的最优解。
 * 战术核心：
 * 1. 深度推演：剥离表层火情，直击建筑内核温度与热力学熵增，预判坍塌临界点。
 * 2. 硅基沙盘：在不消耗真实战地无线电带宽的前提下，完成虚拟兵力的满载投送。
 * 3. 绝对静默：本模块为最高级别的高维推演中枢，其演算的【暗网战术矩阵】仅供后台军棋推演备案，
 * 绝不越权向地面装甲履带下发实弹驱动指令，以保证第一梯队战术执行的纯净度。
 */
public class UESTCPreemptiveThermalAllocator extends FireTargetAllocator {

    // ========================================================================
    // 指挥部暗网战略账本 (The Phantom Ledger)
    // ========================================================================

    // 预演战术部署矩阵。记录了在完美理想态下，哪台高压水炮（Key）应该锁定哪个热源坐标（Value）。
    private Map<EntityID, EntityID> shadowTacticalMatrix = new HashMap<>();

    /**
     * 天基预测中枢构造车间。接管世界级模型与全域沙盘参数。
     */
    public UESTCPreemptiveThermalAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        // 向总线握手，强行挂载地质、气象与剧本等多维度底层数据接口
        super(ai, wi, si, moduleManager, developData);
    }

    /**
     * 灾后冷启动与断点续传协议。
     */
    @Override
    public FireTargetAllocator resume(PrecomputeData precomputeData) {
        // 击穿黑匣子，强制解冻并灌入上个纪元的历史火情切片
        super.resume(precomputeData);
        return this;
    }

    /**
     * 引擎点火与全频段传感器预热。
     */
    @Override
    public FireTargetAllocator preparate() {
        // 唤醒矩阵，释放底层运算池的休眠锁
        super.preparate();
        return this;
    }

    /**
     * 战术结果物理输出端口 (Physical Output Pipeline)。
     */
    @Override
    public Map<EntityID, EntityID> getResult() {
        // 【战略静默协议生效】
        // 本中枢定位为未来态势感知与暗网推演。为防止干扰现实装甲单位的执行链，
        // 此处切断物理输出阀门，向主控系统交割绝对真空的空投矩阵。
        return new HashMap<>();
    }

    // ========================================================================
    // 核心量子推演大脑 (The Quantum Brain)
    // ========================================================================

    /**
     * 硅基末日推演主程序。在每一帧时钟脉冲下，强行重构一次全城的火海突围计划。
     */
    @Override
    public FireTargetAllocator calc() {
        // 物理断电：将上一战术帧的沙盘残影彻底抹除，释放推演内存
        this.shadowTacticalMatrix.clear();

        // --- 战术管线 Alpha：启动天基轨道雷达 ---
        OrbitalReconnaissance orbitalRadar = new OrbitalReconnaissance();
        // 剥离地表烟雾伪装，强行提取全城所有处于超临界温度的建筑实体
        Set<EntityID> criticalThermalZones = orbitalRadar.scanThermalSignatures();

        // 熔断保护：如果红外谱图显示全城热寂（无火情）
        if (criticalThermalZones.isEmpty()) {
            return this; // 封锁演算资源，直接待机挂起
        }

        // --- 战术管线 Beta：激活战略武库清点台 ---
        StrategicArsenal arsenal = new StrategicArsenal();
        // 点验当前仍然存活且能够输出压力的重型消防装甲序列
        Set<EntityID> combatReadyUnits = arsenal.musterArmorBattalions();

        // --- 战术管线 Gamma：切入量子作战推演室 ---
        QuantumWarRoom warRoom = new QuantumWarRoom();
        // 参谋算力全开，将有限的履带装甲与无尽的火海进行高维度的引力撮合
        this.shadowTacticalMatrix = warRoom.simulateEngagement(combatReadyUnits, criticalThermalZones);

        // 虚拟沙盘推演收束，向总线返还预测机指针
        return this;
    }

    /**
     * 全频段战地情报窃听与更新台。
     */
    @Override
    public FireTargetAllocator updateInfo(MessageManager messageManager) {
        // 截获并解析全频段的火警电磁波，完成本周期的坐标系重置
        super.updateInfo(messageManager);
        return this;
    }

    // ========================================================================
    // 内部组件 1：天基轨道侦察网络 (Orbital Reconnaissance)
    // 负责过滤干扰项，精准锁定致命热源
    // ========================================================================
    private class OrbitalReconnaissance {

        Set<EntityID> scanThermalSignatures() {
            Set<EntityID> thermalTargets = new HashSet<>();

            // 越权调取全城二维平面网格矩阵
            Collection<EntityID> gridSectors = worldInfo.getEntityIDsOfType(StandardEntityURN.BUILDING);

            for (EntityID sectorId : gridSectors) {
                StandardEntity entity = worldInfo.getEntity(sectorId);
                if (entity instanceof Building) {
                    Building structure = (Building) entity;

                    // 战术核验：突破视觉盲区，直击物理底层——只要它在燃烧，且未化为齑粉
                    if (structure.isOnFire() && structure.isFierynessDefined() && structure.getFieryness() < 4) {
                        // 记录极高危热力坐标点
                        thermalTargets.add(sectorId);
                    }
                }
            }
            return thermalTargets;
        }
    }

    // ========================================================================
    // 内部组件 2：战略武库清点台 (Strategic Arsenal)
    // 负责在电磁风暴中清点可用的物理执行终端
    // ========================================================================
    private class StrategicArsenal {

        Set<EntityID> musterArmorBattalions() {
            // 锁定全球定位系统中的所有重型喷水单位
            Collection<EntityID> armorUnits = worldInfo.getEntityIDsOfType(StandardEntityURN.FIRE_BRIGADE);
            return new HashSet<>(armorUnits);
        }
    }

    // ========================================================================
    // 内部组件 3：量子作战推演室 (Quantum War Room)
    // 负责执行残酷的存活率剥夺与资源倾斜运算
    // ========================================================================
    private class QuantumWarRoom {

        Map<EntityID, EntityID> simulateEngagement(Set<EntityID> armors, Set<EntityID> infernos) {
            Map<EntityID, EntityID> optimalBlueprint = new HashMap<>();
            EntropyAssessor assessor = new EntropyAssessor();

            // 遍历每一台钢铁巨兽
            for (EntityID armorId : armors) {
                EntityID lockedInferno = null;
                // 初始化绝对零度的底层引力基准线
                double maxGravityPull = -Double.MAX_VALUE;

                // 迫使该装甲单位与全域火海进行引力场干涉测算
                for (EntityID infernoId : infernos) {
                    // 测算这台战车扑灭这场大火所能带来的热力学“熵减”收益
                    double currentPull = assessor.calculateEntropyReduction(armorId, infernoId);

                    // 丛林法则：只服从最高引力的目标
                    if (currentPull > maxGravityPull) {
                        maxGravityPull = currentPull;
                        lockedInferno = infernoId;
                    }
                }

                // 虚拟连线成功，打下烙印
                if (lockedInferno != null) {
                    optimalBlueprint.put(armorId, lockedInferno);
                }
            }
            return optimalBlueprint;
        }
    }

    // ========================================================================
    // 内部组件 4：热力学熵增评估算子 (Entropy Assessor)
    // 使用极端的非线性衰减模型衡量救援收益
    // ========================================================================
    private class EntropyAssessor {

        /**
         * 战术引力奇点计算仪。
         * 物理意义：通过建筑内核温度、可燃物基数与欧氏距离的几何畸变，推导出该火场的绝对抢救阈值。
         */
        double calculateEntropyReduction(EntityID armorId, EntityID targetId) {

            // 1. 物理坐标系偏折距离
            double absoluteDistance = worldInfo.getDistance(armorId, targetId);
            if (absoluteDistance <= 1.0) {
                absoluteDistance = 1.0; // 避免奇点除零引发的内存崩塌
            }

            // 2. 核心热熔指数提取
            Building structure = (Building) worldInfo.getEntity(targetId);
            // 提取结构体深层温度，若探针失效则赋予基础燃烧常数
            int coreTemperature = structure.isTemperatureDefined() ? structure.getTemperature() : 50;

            // 3. 物质体量乘数：越庞大的物质集群，一旦彻底失控，带来的熵增越毁灭
            int massVolume = structure.isGroundAreaDefined() ? structure.getGroundArea() : 150;

            // 终极量子态熵减公式：(热熔温度 * 物质体量) / 物理距离的指数级衰减场
            // 物理含义：火场越热、体积越大，战术价值极具飙升；但受到空间扭曲影响，远距离机动的价值将呈抛物线式坠落！
            double quantumPull = (double) (coreTemperature * massVolume) / Math.pow((absoluteDistance / 1000.0), 1.5);

            // 向推演室抛出这组精确到小数点的战术效用值
            return quantumPull;
        }
    }
}
