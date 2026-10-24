package UESTC.module.complex.dcop;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.*;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.communication.standard.bundle.centralized.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.complex.AmbulanceTargetAllocator;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import es.csic.iiia.bms.*;
import es.csic.iiia.bms.factors.*;
import es.csic.iiia.bms.factors.CardinalityFactor.CardinalityFunction;

import java.util.*;

/**
 * 分布式医疗力量统筹调度器 (Distributed Medical Resource Allocator).
 * <p>
 * 灾害场景模型：地震导致建筑物大规模倒塌，交通网络瘫痪，城市管网损毁，次生火灾迅速蔓延。
 * 在此极端物理与通信受限的环境下，大量受害者被掩埋在废墟中。
 * 本组件基于 DCOP (Distributed Constraint Optimization Problem) 框架与 Max-Sum 信念传播算法，
 * 构建虚拟的神经网络沙盘。通过在救护车（变量节点）与受害者（因子节点）之间建立势函数（Potential Functions），
 * 模拟在废墟阻塞、通信中断的绝境下，各救援单位之间通过微弱电波进行多轮博弈与协商，
 * 最终收敛出一个全局效用最大化（损失最小化）的营救派发矩阵。
 * </p>
 */
public class OptimumMedicAssigner extends AmbulanceTargetAllocator {

    // ========================================================================
    // 环境约束与超参数定义 (Environmental Constraints & Hyperparameters)
    // ========================================================================

    /** 指挥中枢的拓扑标识。在断壁残垣中定位最高决策层。 */
    private final static StandardEntityURN HQ_IDENTIFIER = AMBULANCE_CENTRE;
    
    /** 医疗救援单元的拓扑标识。 */
    private final static StandardEntityURN MEDICAL_UNIT_URN = AMBULANCE_TEAM;
    
    /** 信念传播算法的收敛迭代阈值。在有限的通信带宽下，截断无意义的无休止协商。 */
    private final static int CONVERGENCE_ITERATIONS = 100;
    
    /** 极端伤亡惩罚基数。若调度失败导致生命流逝，全局目标函数将承受灾难性的数学惩罚。 */
    private final static double FATALITY_COST_CONSTANT = 600.0;
    
    /** 战术游荡占位符。当废墟区域已被清理或目标无法触达时，赋予空闲车辆的默认巡视状态。 */
    private final static EntityID IDLE_STATE_PLACEHOLDER = new EntityID(-1);

    // ========================================================================
    // 动态态势感知数据池 (Dynamic Situation Awareness Pool)
    // ========================================================================

    /** 最终收敛的战术部署矩阵 [车辆ID -> 目标ID] */
    private final Map<EntityID, EntityID> optimizedDispatchMatrix = new HashMap<>();
    
    /** 因子图优化网络（DCOP 拓扑图的基础设施） */
    private final Map<EntityID, Factor<EntityID>> distributedConstraintTopology = new HashMap<>();
    
    /** 克服通信中断的定制化中继基站 */
    private final UESTCDataTransceiver tacticalCommunicationHub;
    
    /** 存活且保持通信的在线节点集合 */
    private final Set<EntityID> networkActiveNodes = new HashSet<>();

    /** 可调度的医疗车队编排 */
    private Set<EntityID> operationalFleet = new HashSet<>();
    /** 急需挖掘与医疗干预的受害者危重池 */
    private final Set<EntityID> criticalPatientPool = new HashSet<>();
    /** 已确认生命体征消失或已脱险的实体黑名单 */
    private final Set<EntityID> resolvedCasualties = new HashSet<>();

    /**
     * 调度器初始化，装载态势感知传感器与战术通信中继。
     */
    public OptimumMedicAssigner(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager mm, DevelopData dd) {
        super(ai, wi, si, mm, dd);
        this.tacticalCommunicationHub = new UESTCDataTransceiver();
    }

    @Override
    public Map<EntityID, EntityID> getResult() {
        return this.optimizedDispatchMatrix;
    }

    /**
     * 执行核心的时序调度管道 (Execution Pipeline).
     * <p>在火势蔓延和建筑物二次坍塌的每一秒内，重新评估全局资源分配最优解。</p>
     */
    @Override
    public AmbulanceTargetAllocator calc() {
        this.optimizedDispatchMatrix.clear();
        
        if (this.operationalFleet.isEmpty()) {
            this.buildOperationalFleet();
        }
        
        // 评估指挥链完整性，若网络孤立则退化为单机自主决策
        if (!verifyCommandChainIntegrity()) {
            return this;
        }

        // 管道 1：情报解析与目标立案
        this.compileCriticalTargets();
        
        // 管道 2：构建多边博弈沙盘
        FactorGraphBuilder graphBuilder = new FactorGraphBuilder();
        graphBuilder.constructTopology();
        
        // 管道 3：启动 Max-Sum 分布式信念传播
        this.executeBeliefPropagation();
        
        // 管道 4：决策收割与下发
        this.harvestDecisions();

        return this;
    }

    /**
     * 战术级信息碎片解析中枢 (Tactical Intelligence Parsing).
     * 从损毁的通信设施中捕捉微弱的求救信号与友军位置通报。
     */
    @Override
    public AmbulanceTargetAllocator updateInfo(MessageManager mm) {
        super.updateInfo(mm);
        if (this.getCountUpdateInfo() >= 2) return this;

        this.networkActiveNodes.clear();
        IntelligenceDecoder decoder = new IntelligenceDecoder(mm);
        
        // 分门别类解析来自各个维度的残存电文
        decoder.decodeCivilianDistressSignals();
        decoder.decodeFireBrigadeReconnaissance();
        decoder.decodePoliceRouteClearance();
        decoder.decodeAmbulanceStatusReports();
        decoder.decodeFinalityReports();

        return this;
    }

    // ========================================================================
    // 私有核心机制实现区 (Private Core Mechanisms)
    // ========================================================================

    private void buildOperationalFleet() {
        this.operationalFleet = new HashSet<>(this.worldInfo.getEntityIDsOfType(MEDICAL_UNIT_URN));
    }

    private boolean verifyCommandChainIntegrity() {
        if (this.scenarioInfo.getScenarioAgentsFs() <= 0 || 
            this.scenarioInfo.getScenarioAgentsPo() <= 0 || 
            this.scenarioInfo.getScenarioAgentsAc() <= 0) {
            return false;
        }
        if (this.networkActiveNodes.size() != this.operationalFleet.size()) {
            return false;
        }

        Collection<EntityID> hqEntities = this.worldInfo.getEntityIDsOfType(HQ_IDENTIFIER);
        if (hqEntities.isEmpty()) return false;

        int primaryHqId = Integer.MAX_VALUE;
        for (EntityID hq : hqEntities) {
            primaryHqId = Math.min(primaryHqId, hq.getValue());
        }

        return this.agentInfo.getTime() >= this.scenarioInfo.getKernelAgentsIgnoreuntil() 
               && this.agentInfo.getID().getValue() == primaryHqId;
    }

    private void compileCriticalTargets() {
        this.criticalPatientPool.clear();
        Collection<StandardEntity> globalPopulation = this.worldInfo.getEntitiesOfType(CIVILIAN);

        for (StandardEntity entity : globalPopulation) {
            if (!(entity instanceof Human)) continue;
            Human casualty = (Human) entity;

            if (casualty.isPositionDefined()) {
                StandardEntity environment = this.worldInfo.getEntity(casualty.getPosition());
                // 仅筛选被困于坍塌废墟内且持续流血的实体
                if (environment.getStandardURN() == BUILDING && casualty.isDamageDefined() && casualty.getDamage() > 0) {
                    this.criticalPatientPool.add(casualty.getID());
                }
            }
        }
        this.criticalPatientPool.removeAll(this.resolvedCasualties);
        this.criticalPatientPool.add(IDLE_STATE_PLACEHOLDER);
    }

    private void executeBeliefPropagation() {
        Collection<Factor<EntityID>> allNodes = this.distributedConstraintTopology.values();
        for (int iter = 0; iter < CONVERGENCE_ITERATIONS; ++iter) {
            for (Factor<EntityID> node : allNodes) {
                node.run(); // 节点内部状态演算
            }
            this.tacticalCommunicationHub.execute(this.distributedConstraintTopology); // 广播信念矩阵
        }
    }

    private void harvestDecisions() {
        long effectiveMissions = 0;
        for (EntityID unitId : this.operationalFleet) {
            Factor<EntityID> cognitiveNode = this.distributedConstraintTopology.get(unitId);
            SelectorFactor<EntityID> decisionCore = (SelectorFactor<EntityID>) ((ProxyFactor<EntityID>) cognitiveNode).getInnerFactor();
            EntityID assignedTarget = decisionCore.select();

            if (assignedTarget.equals(IDLE_STATE_PLACEHOLDER)) {
                assignedTarget = null;
            }
            this.optimizedDispatchMatrix.put(unitId, assignedTarget);
            if (assignedTarget != null) effectiveMissions++;
        }
        System.out.println("[DCOP Optimizer] 收敛完毕，有效投入废墟抢险的车辆编队数: " + effectiveMissions);
    }

    // ========================================================================
    // 内部类：拓扑网络工程师 (Factor Graph Builder)
    // ========================================================================
    private class FactorGraphBuilder {
        
        void constructTopology() {
            deployVariableNodes();
            deployFactorNodes();
            establishConstraintEdges();
        }

        private void deployVariableNodes() {
            for (EntityID unitId : operationalFleet) {
                Factor<EntityID> exclusiveEngine = new ChoiceHeuristic<>();
                WeightingFactor<EntityID> cognitiveAgent = new WeightingFactor<>(exclusiveEngine);
                cognitiveAgent.setMaxOperator(new Minimize());
                cognitiveAgent.setIdentity(unitId);
                cognitiveAgent.setCommunicationAdapter(tacticalCommunicationHub);
                distributedConstraintTopology.put(unitId, cognitiveAgent);
            }
        }

        private void deployFactorNodes() {
            // 实例化代价评估器
            final CostEvaluator evaluator = new CostEvaluator();
            
            for (final EntityID targetId : criticalPatientPool) {
                CardinalityFactor<EntityID> survivalConstraint = new SetSizeMultiplier<>();
                survivalConstraint.setFunction(new CardinalityFunction() {
                    @Override
                    public double getCost(int deployedUnits) {
                        return evaluator.evaluateExtricationCost(targetId, deployedUnits);
                    }
                });
                survivalConstraint.setMaxOperator(new Minimize());
                survivalConstraint.setIdentity(targetId);
                survivalConstraint.setCommunicationAdapter(tacticalCommunicationHub);
                distributedConstraintTopology.put(targetId, survivalConstraint);
            }
        }

        private void establishConstraintEdges() {
            final CostEvaluator evaluator = new CostEvaluator();
            
            for (EntityID unitId : operationalFleet) {
                List<EntityID> proximityQueue = new ArrayList<>(criticalPatientPool);
                proximityQueue.sort(new Comparator<EntityID>() {
                    @Override
                    public int compare(EntityID t1, EntityID t2) {
                        if (t1.equals(IDLE_STATE_PLACEHOLDER)) return -1;
                        if (t2.equals(IDLE_STATE_PLACEHOLDER)) return 1;
                        return Double.compare(worldInfo.getDistance(t1, unitId), worldInfo.getDistance(t2, unitId));
                    }
                });

                for (EntityID targetId : proximityQueue) {
                    WeightingFactor<EntityID> vNode = (WeightingFactor<EntityID>) distributedConstraintTopology.get(unitId);
                    Factor<EntityID> fNode = distributedConstraintTopology.get(targetId);
                    
                    vNode.addNeighbor(targetId);
                    fNode.addNeighbor(unitId);
                    
                    double debrisNavigationPenalty = evaluator.evaluateNavigationCost(unitId, targetId);
                    vNode.setPotential(targetId, debrisNavigationPenalty);
                }
            }
        }
    }

    // ========================================================================
    // 内部类：数学惩罚计算引擎 (Cost Evaluator)
    // 修复：移除 static，使其可以访问外部类实例的 worldInfo
    // ========================================================================
    private class CostEvaluator {
        
        double evaluateNavigationCost(EntityID unit, EntityID target) {
            if (target.equals(IDLE_STATE_PLACEHOLDER)) return 0.0;
            // 现在可以正常访问外部类的 worldInfo 了
            return worldInfo.getDistance(unit, target) / (42000.0 / 1.5);
        }

        double evaluateExtricationCost(EntityID target, int deployedUnits) {
            if (target.equals(IDLE_STATE_PLACEHOLDER)) return 0.0;
            if (deployedUnits == 0) return FATALITY_COST_CONSTANT;

            // 现在可以正常访问外部类的 worldInfo 了
            Civilian casualty = (Civilian) worldInfo.getEntity(target);
            double survivalWindow = (double) casualty.getHP() / casualty.getDamage();
            int rubbleBurden = casualty.isBuriednessDefined() ? casualty.getBuriedness() : 0;
            
            double theoreticalWorkforce = (double) rubbleBurden / survivalWindow;
            double baselineRequirement = Math.ceil(theoreticalWorkforce + 1.0);
            double resourceSaturation = Math.min(deployedUnits, baselineRequirement) / baselineRequirement;

            // 基于时间窗口与挖掘难度构建的二次衰减死亡风险模型
            return FATALITY_COST_CONSTANT * (1.0 - Math.pow(resourceSaturation, 2.0));
        }
    }

    // ========================================================================
    // 内部类：战地电报解码器 (Intelligence Decoder)
    // 修复：恢复了 MessageUtil.reflectMessage 之前的显式强制类型转换
    // ========================================================================
    private class IntelligenceDecoder {
        private final MessageManager mm;

        IntelligenceDecoder(MessageManager mm) { this.mm = mm; }

        private void obliterateSpatialCache(EntityID id) {
            Human entity = (Human) worldInfo.getEntity(id);
            if (entity != null) {
                entity.undefineX();
                entity.undefineY();
            }
        }

        void decodeCivilianDistressSignals() {
            for (CommunicationMessage msg : mm.getReceivedMessageList(MessageCivilian.class)) {
                MessageCivilian mail = (MessageCivilian) msg; // 修复：显式强转
                MessageUtil.reflectMessage(worldInfo, mail);
                obliterateSpatialCache(mail.getAgentID());
            }
        }

        void decodeFireBrigadeReconnaissance() {
            for (CommunicationMessage msg : mm.getReceivedMessageList(MessageFireBrigade.class)) {
                MessageFireBrigade mail = (MessageFireBrigade) msg; // 修复：显式强转
                MessageUtil.reflectMessage(worldInfo, mail);
                obliterateSpatialCache(mail.getAgentID());
            }
        }

        void decodePoliceRouteClearance() {
            for (CommunicationMessage msg : mm.getReceivedMessageList(MessagePoliceForce.class)) {
                MessagePoliceForce mail = (MessagePoliceForce) msg; // 修复：显式强转
                MessageUtil.reflectMessage(worldInfo, mail);
                obliterateSpatialCache(mail.getAgentID());
            }
        }

        void decodeAmbulanceStatusReports() {
            for (CommunicationMessage msg : mm.getReceivedMessageList(MessageAmbulanceTeam.class)) {
                MessageAmbulanceTeam mail = (MessageAmbulanceTeam) msg; // 修复：显式强转
                MessageUtil.reflectMessage(worldInfo, mail);
                obliterateSpatialCache(mail.getAgentID());
            }
        }

        void decodeFinalityReports() {
            for (CommunicationMessage msg : mm.getReceivedMessageList(MessageReport.class)) {
                MessageReport report = (MessageReport) msg; // 已经是显式强转了，保留
                if (report.isFromIDDefined()) {
                    resolvedCasualties.add(report.getFromID());
                }
            }
        }
    }
}
