package UESTC.module.complex.dcop;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.*;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.communication.standard.bundle.centralized.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.*;
import adf.core.component.module.complex.FireTargetAllocator;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import es.csic.iiia.bms.*;
import es.csic.iiia.bms.factors.*;
import es.csic.iiia.bms.factors.CardinalityFactor.CardinalityFunction;
import java.util.*;

/**
 * OptimumBlazeAssigner 消防车分布式目标分配器（基于 Max-Sum 算法）。
 * 物理意义：在灾难现场，全城的消防车必须经过精密统筹。
 * 通过百轮迭代通信，全局寻找让惩罚值降到最低的派车方案。
 */
public class OptimumBlazeAssigner extends FireTargetAllocator {

    // 官方消防中心实体标识。用于在地图上定位指挥部老大哥的坐标
    private final static StandardEntityURN COMMAND_NODE_URN = FIRE_STATION;

    // 官方消防车实体标识。用于去地图上抓取一线作战的消防车辆
    private final static StandardEntityURN MOBILE_UNIT_URN = FIRE_BRIGADE;

    // 算法协商最大迭代次数。也就是车与火场之间互发电报讨价还价的轮数
    private final static int CONVERGENCE_ITERATION_LIMIT = 100;

    // 建筑烧毁基础惩罚值。如果见死不救，系统将承受高达 600.0 的巨大责任分扣除
    private final static double ABSOLUTE_DESTRUCTION_COST = 600.0;

    // 虚拟闲逛与补水任务。给那些没事做或者离得太远的消防车分配的兜底任务（ID固定为-1）
    private final static EntityID STANDBY_PLACEHOLDER_ID = new EntityID(-1);

    // 最终灭火派车单。这是算法收敛后的心血，记录了哪辆车（Key）去哪个火场（Value）
    private final Map<EntityID, EntityID> optimizedTacticalMatrix = new HashMap<>();

    // DCOP 分布式因子图网络。承载着所有车辆大脑和火场呼救器的沙盘，是推演地基
    private final Map<EntityID, Factor<EntityID>> constraintOptimizationNetwork = new HashMap<>();

    // 战地局域网通信基站。挂载定制的电报派发器，用于在各个数学节点之间中转信件
    private final UESTCDataTransceiver synchronizationHub;

    // 建筑聚类分析雷达。判断哪些房子是挨在一起的，划分为同一个火灾群落
    private final Clustering spatialClusteringEngine;

    // 全城消防车作战序列。记录所有可以调遣的消防车身份证号
    private Set<EntityID> availableSuppressionFleet = new HashSet<>();

    // 极危火场清单。经过筛选后，急需派车压制的着火建筑物 ID 集合
    private final Set<EntityID> highRiskCombustionZones = new HashSet<>();

    // 燃烧殆尽或已扑灭的建筑黑名单。从汇报中得知没救了的楼，直接剔除，防浪费水
    private final Set<EntityID> obsoleteTargetRegistry = new HashSet<>();

    // 本回合存活的打卡名单。用于核实通信是否畅通，人数不对说明网络残缺
    private final Set<EntityID> activeTelemetryNodes = new HashSet<>();

    /**
     * 消防统筹指挥官构造器。在车辆打火时，把雷达和电台全部装载进大脑。
     */
    public OptimumBlazeAssigner(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager mm, DevelopData dd) {
        // 调用底层的初始化，加载世界地图等基础数据
        super(ai, wi, si, mm, dd);

        // 秘密架设一台我们专属的战地局域网通信基站
        this.synchronizationHub = new UESTCDataTransceiver();

        // 去底层模块仓库里，把那个专门分析“邻居建筑”的聚类雷达借过来
        this.spatialClusteringEngine = mm.getModule(
            "MaxSumFireTargetAllocator.Clustering", // 雷达的注册名
            "UESTC.module.algorithm.NeighborBuildings" // 雷达的真实类名
        );

        // 将借来的雷达挂载到自己的仪表盘上，确保每回合自动同步刷新
        this.registerModule(this.spatialClusteringEngine);
    }

    /**
     * 战术结果提取口。向上层模块上交本回合深思熟虑后得出的最终派车单。
     */
    @Override
    public Map<EntityID, EntityID> getResult() {
        // 直接交出我们写满决定的账本
        return this.optimizedTacticalMatrix;
    }

    /**
     * 核心火情统筹大脑运算引擎。每一秒钟都会进来执行一次全局推演。
     */
    @Override
    public FireTargetAllocator calc() {
        // 第一件事：直接撕毁上一秒钟的过期派车单，准备重新签发
        this.optimizedTacticalMatrix.clear();

        // 看看手里的作战花名册是不是空的
        if (this.availableSuppressionFleet.isEmpty()) {
            // 如果是空的，赶紧去系统数据库里把所有消防车抓取过来
            this.buildSuppressionFleet();
        }

        // 评估当前是否有必要耗费极大的 CPU 算力来进行全城统筹？
        if (!validateCommandAuthority()) {
            // 如果没资格（没建好指挥部或者时机未到），直接罢工，保持原样返回
            return this;
        }

        // --- 管线 1：战情勘察与火场初筛 ---
        // 召唤战术分析师
        TacticalAnalyzer analyzer = new TacticalAnalyzer();
        // 让分析师去找出哪些房子正处在火势蔓延的边缘
        analyzer.isolateCriticalBoundaries();

        // --- 管线 2：搭建推演沙盘 ---
        // 召唤沙盘构建工程师
        TopologyBuilder builder = new TopologyBuilder();
        // 让工程师把消防车和火场用网络连线编织起来
        builder.constructOptimizationNetwork();

        // --- 管线 3：Max-Sum 沙盘推演 ---
        // 启动百轮讨价还价循环，寻找代价最小的平衡点
        executeBeliefPropagation();

        // --- 管线 4：决断与签发 ---
        // 收集推演出来的决议，盖章印发真实的派车单
        harvestTacticalDecisions();

        // 返回当前指挥官自身，以支持链式调用
        return this;
    }

    /**
     * 战地情报汇总台。负责监听来自四面八方的电报，修正地图。
     */
    @Override
    public FireTargetAllocator updateInfo(MessageManager mm) {
        // 先让父类去处理一些基础的情报
        super.updateInfo(mm);

        // 防止单回合内被上层过度调用，导致数据重复翻倍
        if (this.getCountUpdateInfo() >= 2) return this;

        // 清空上一秒的考勤打卡记录，准备迎接本回合的新打卡
        this.activeTelemetryNodes.clear();

        // 建立情报解码器，把一堆乱码电报交给他
        TelemetryDecoder decoder = new TelemetryDecoder(mm);

        // 依次解码三种核心情报：火场温度、车辆坐标、死亡结案报告
        decoder.processStructuralDamageReports();
        decoder.processFleetPositioningSignals();
        decoder.processIncidentResolutionReports();

        // 情报处理完毕，返回自身
        return this;
    }

    // ========================================================================
    // 私有核心机制实现区
    // ========================================================================

    /**
     * 将全城消防车编入名册。
     */
    private void buildSuppressionFleet() {
        // 从世界地图里，把拥有消防车标识（FIRE_BRIGADE）的所有实体 ID 都捞出来
        Collection<EntityID> activeUnits = this.worldInfo.getEntityIDsOfType(MOBILE_UNIT_URN);
        // 装进我们自己的考勤名册里
        this.availableSuppressionFleet = new HashSet<>(activeUnits);
    }

    /**
     * 验证我自己是不是最高指挥官（老大哥）。
     */
    private boolean validateCommandAuthority() {
        // 纪律检查：警察局、消防局、医院，这三个大本营必须都存在（大于0）
        if (this.scenarioInfo.getScenarioAgentsFs() <= 0 ||
            this.scenarioInfo.getScenarioAgentsPo() <= 0 ||
            this.scenarioInfo.getScenarioAgentsAc() <= 0) {
            // 少一个都不算是完善的联合指挥网，放弃统筹
            return false;
        }

        // 通信检查：今天真实发报打卡的车辆数，必须和名册上应到的人数一致
        if (this.activeTelemetryNodes.size() != this.availableSuppressionFleet.size()) {
            // 人数不对说明有人掉线或者电台坏了，算出来也不准，放弃统筹
            return false;
        }

        // 寻找指挥权：把全城建好的消防指挥中心 ID 全找出来
        Collection<EntityID> commandNodes = this.worldInfo.getEntityIDsOfType(COMMAND_NODE_URN);
        // 要是一个中心都没有，那直接没戏
        if (commandNodes.isEmpty()) return false;

        // 默认先设一个无穷大的 ID 作为擂主
        int rootNodeValue = Integer.MAX_VALUE;
        // 遍历所有的指挥中心，通过比对官方编号打擂台
        for (EntityID nodeId : commandNodes) {
            // 谁的编号更小，谁就晋升为主将
            rootNodeValue = Math.min(rootNodeValue, nodeId.getValue());
        }

        // 获取当前是比赛的第几秒（回合数）
        int currentStep = this.agentInfo.getTime();
        // 获取官方规定的“初始混乱发呆期”要持续多久
        int activationThreshold = this.scenarioInfo.getKernelAgentsIgnoreuntil();

        // 终极判断：一要度过发呆期，二要我自己的 ID 刚好等于那个最小的主将 ID！
        return (currentStep >= activationThreshold) && (this.agentInfo.getID().getValue() == rootNodeValue);
    }

    /**
     * 启动百轮推演。
     */
    private void executeBeliefPropagation() {
        // 把沙盘上所有的数学节点（包含了人和楼）全都抓在手里
        Collection<Factor<EntityID>> computationalNodes = this.constraintOptimizationNetwork.values();

        // 开启残酷的百轮循环，少一轮可能都不收敛
        for (int iter = 0; iter < CONVERGENCE_ITERATION_LIMIT; ++iter) {

            // 让沙盘上的每一个节点进行头脑风暴，产生这一轮的报价单
            for (Factor<EntityID> node : computationalNodes) {
                // 运行核心计算逻辑
                node.run();
            }

            // 头脑风暴完毕后，让基站统一按响电铃，下发所有人缓存的电报
            this.synchronizationHub.execute(this.constraintOptimizationNetwork);
        }
    }

    /**
     * 摘取胜利果实，颁发派车单。
     */
    private void harvestTacticalDecisions() {
        // 弄个计数器，看看有几辆车去干实事了
        int successfulDeployments = 0;

        // 挨个点名花名册上的消防车
        for (EntityID unitId : this.availableSuppressionFleet) {

            // 把这辆车在沙盘里的数学大脑给挖出来
            Factor<EntityID> cognitiveNode = this.constraintOptimizationNetwork.get(unitId);

            // 开颅手术刀！扒掉外壳，露出最里面专门负责选择的 Selector 核心
            SelectorFactor<EntityID> coreLogic = (SelectorFactor<EntityID>) ((ProxyFactor<EntityID>) cognitiveNode).getInnerFactor();

            // 逼问大脑：“说！经过一百轮砍价，你最终选了哪个火场？！”
            EntityID designatedTarget = coreLogic.select();

            // 如果它支支吾吾说选了虚拟闲逛任务
            if (designatedTarget.equals(STANDBY_PLACEHOLDER_ID)) {
                // 那就在派车单上把它设为空（放任自流）
                designatedTarget = null;
            }

            // 盖下大印，把决断结果写入官方账本
            this.optimizedTacticalMatrix.put(unitId, designatedTarget);

            // 如果分配了真实任务，有效派发数加一
            if (designatedTarget != null) {
                successfulDeployments++;
            }
        }
        // 在后台悄悄打印一句战报，方便调试查阅
        System.out.println("FIRE ALLOCATOR -> 成功分派灭火任务的消防车数量: " + successfulDeployments);
    }

    // ========================================================================
    // 内部组件：战术分析师 (Tactical Analyzer)
    // ========================================================================
    private class TacticalAnalyzer {

        /**
         * 筛选出处于蔓延边界的危险房屋。
         */
        void isolateCriticalBoundaries() {
            // 擦黑板，清空旧的清单
            highRiskCombustionZones.clear();

            // 从世界地图里倒出全城所有的建筑物（里面好坏掺杂）
            Collection<StandardEntity> infrastructure = worldInfo.getEntitiesOfType(BUILDING);

            // 挨个验明正身
            for (StandardEntity struct : infrastructure) {
                // 强制转为 Building 对象
                Building bld = (Building) struct;

                // 双重安检：你着火了吗？且你处在火烧连营的边缘线上吗？
                if (bld.isOnFire() && detectCombustionBoundary(bld)) {
                    // 如果满足，立刻拉入极危抢救名单
                    highRiskCombustionZones.add(bld.getID());
                }
            }

            // 对对账：如果有楼已经确认被烧塌了，或者被别的车灭了，从抢救单里划掉
            highRiskCombustionZones.removeAll(obsoleteTargetRegistry);

            // 强行往清单底端塞入一个“-1”的虚拟闲逛任务，给无处可去的车垫底
            highRiskCombustionZones.add(STANDBY_PLACEHOLDER_ID);
        }

        /**
         * 探测一栋楼到底是不是火灾防线。
         */
        private boolean detectCombustionBoundary(Building focusNode) {
            // 去聚类雷达那问一声：“我这栋楼属于几号火灾群落？”
            int spatialClusterIndex = spatialClusteringEngine.getClusterIndex(focusNode);

            // 把跟我同属一个群落的“难兄难弟”全拉出来
            Collection<StandardEntity> adjacentTopology = spatialClusteringEngine.getClusterEntities(spatialClusterIndex);

            // 挨个敲邻居的门
            for (StandardEntity neighbor : adjacentTopology) {
                // 如果邻居是个正儿八经的房子
                if (neighbor instanceof Building) {
                    Building adjacentBld = (Building) neighbor;

                    // 战术核心：邻居没着火（Fieryness未定义，或者明确为0完好）
                    boolean isStructurallySafe = !adjacentBld.isFierynessDefined() || adjacentBld.getFieryness() == 0;

                    // 只要发现哪怕一个安全的邻居！
                    if (isStructurallySafe) {
                        // 就说明火烧到我这里停止了，我就是阻断火势的物理防线！返回真！
                        return true;
                    }
                }
            }
            // 邻居全都烧成了灰，说明我处于火海正中央，救不活了，放弃我吧
            return false;
        }
    }

    // ========================================================================
    // 内部组件：拓扑构建师 (Topology Builder)
    // ========================================================================
    private class TopologyBuilder {

        /**
         * 执行造网工程。
         */
        void constructOptimizationNetwork() {
            // 第一步：捏出所有消防车的大脑
            instantiateFleetAgents();
            // 第二步：装配所有火场的呼救器
            instantiateCombustionConstraints();
            // 第三步：牵线搭桥
            weaveConstraintEdges();
        }

        /**
         * 捏造消防车大脑。
         */
        private void instantiateFleetAgents() {
            // 遍历花名册上的每一辆消防车
            for (EntityID unitId : availableSuppressionFleet) {

                // 造一个非常死板的“唯一选择器”，规定这辆车只能去一个地方
                Factor<EntityID> rootSelector = new ChoiceHeuristic<>();

                // 在选择器外面包一层“计分天平”，让它学会权衡远近的代价
                WeightingFactor<EntityID> agentWrapper = new WeightingFactor<>(rootSelector);

                // 给大脑下死命令：你的唯一目标就是把路程惩罚降到最低 (Minimize)
                agentWrapper.setMaxOperator(new Minimize());

                // 给大脑贴上专属于这辆消防车的车牌号
                agentWrapper.setIdentity(unitId);

                // 给大脑插上无线电天线，准备随时发报
                agentWrapper.setCommunicationAdapter(synchronizationHub);

                // 把造好的大脑丢进网络沙盘里养着
                constraintOptimizationNetwork.put(unitId, agentWrapper);
            }
        }

        /**
         * 装配火场呼救器。
         */
        private void instantiateCombustionConstraints() {
            // 请出冷酷无情的惩罚计算仪
            final CostEvaluator evaluator = new CostEvaluator();

            // 遍历抢救名单上的每一个火场
            for (final EntityID zoneId : highRiskCombustionZones) {

                // 造一个“容量因子”，因为它是个胃口很大的火场，需要很多车来填
                CardinalityFactor<EntityID> capacityConstraint = new SetSizeMultiplier<>();

                // 注入灵魂：这是一个匿名动态函数，随时计算“如果来了N辆车，惩罚还剩多少”
                capacityConstraint.setFunction(new CardinalityFunction() {
                    @Override
                    public double getCost(int allocatedUnits) {
                        // 委托给计算仪去算这笔复杂的糊涂账
                        return evaluator.evaluateThermalSuppressionCost(zoneId, allocatedUnits);
                    }
                });

                // 同样下达命令：你要让大火造成的毁灭惩罚降到最低 (Minimize)
                capacityConstraint.setMaxOperator(new Minimize());

                // 贴上这栋楼特有的门牌号
                capacityConstraint.setIdentity(zoneId);

                // 同样插上天线
                capacityConstraint.setCommunicationAdapter(synchronizationHub);

                // 扔进沙盘
                constraintOptimizationNetwork.put(zoneId, capacityConstraint);
            }
        }

        /**
         * 把车和火场用线连起来。
         */
        private void weaveConstraintEdges() {
            final CostEvaluator evaluator = new CostEvaluator();

            // 让所有的车，挨个去跟所有的火场打招呼
            for (EntityID unitId : availableSuppressionFleet) {
                for (EntityID zoneId : highRiskCombustionZones) {

                    // 揪出车的大脑
                    WeightingFactor<EntityID> agentNode = (WeightingFactor<EntityID>) constraintOptimizationNetwork.get(unitId);
                    // 揪出火的呼救器
                    Factor<EntityID> constraintNode = constraintOptimizationNetwork.get(zoneId);

                    // 双方互加微信好友（双向连接）
                    agentNode.addNeighbor(zoneId);
                    constraintNode.addNeighbor(unitId);

                    // 算一下从这辆车开到这个火场，路上得耗费多少油钱（路费惩罚）
                    double navigationFriction = evaluator.evaluateNavigationFriction(unitId, zoneId);

                    // 把路费惩罚当作一条初始的刻痕，印在两人之间的连线上
                    agentNode.setPotential(zoneId, navigationFriction);
                }
            }
        }
    }

    // ========================================================================
    // 内部组件：惩罚评估引擎 (Cost Evaluator)
    // ========================================================================
    private class CostEvaluator {

        /**
         * 算油钱（距离惩罚）。
         */
        double evaluateNavigationFriction(EntityID origin, EntityID destination) {
            // 免罚金牌：如果目标是兜底的闲逛任务，不管离多远，都不收路费惩罚
            if (destination.equals(STANDBY_PLACEHOLDER_ID)) return 0.0;

            // 拉起皮尺，量出车和火灾现场的物理直线距离
            double physicalDistance = worldInfo.getDistance(origin, destination);

            // 除以一个官方设定的魔数 42000，把几十万的距离数值缩小，强行跟毁灭惩罚拉到同一个量级
            return physicalDistance / (42000.0 / 1.5);
        }

        /**
         * 算大楼毁灭的代价（容量惩罚）。
         */
        double evaluateThermalSuppressionCost(EntityID zoneId, int deployedForce) {
            // 闲逛任务永远不造成毁灭惩罚
            if (zoneId.equals(STANDBY_PLACEHOLDER_ID)) return 0.0;

            // 破窗效应：如果对着火的楼不管不顾（派去的车为0）
            if (deployedForce == 0) {
                // 直接给予 600 分的无情抹杀惩罚
                return ABSOLUTE_DESTRUCTION_COST;
            }

            // 把这栋可怜的楼找出来
            Building structure = (Building) worldInfo.getEntity(zoneId);

            // 扒开看看它现在烧到什么程度了（1冒烟，2小火，3大火）
            int thermalIntensity = structure.getFieryness();

            // 估算一下它肚子里的可燃物体积（占地面积 * 楼层 * 3米层高）
            int structuralVolume = structure.getGroundArea() * structure.getFloors() * 3;

            // 热力学公式：用火势级别的平方乘体积，除以500，估算需要几车水才能镇住它
            double theoreticalForceRequirement = (Math.pow(thermalIntensity, 2) * structuralVolume) / 500.0;

            // 舍入法兜底：哪怕只要半车水，咱们也得完整地派一辆车过去（向上取整）
            double baselineForceRequirement = Math.ceil(theoreticalForceRequirement);

            // 算一下救援饱和度：实际派去的车，占据了理论需要车辆的百分之多少？
            double forceSaturationIndex = Math.min((double) deployedForce, baselineForceRequirement) / baselineForceRequirement;

            // 抛物线模型：车来的越少，惩罚越靠近600；一旦车管够了，惩罚呈指数级下降归零
            return ABSOLUTE_DESTRUCTION_COST * (1.0 - Math.pow(forceSaturationIndex, 2.0));
        }
    }

    // ========================================================================
    // 内部组件：遥测信号解码器 (Telemetry Decoder)
    // ========================================================================
    private class TelemetryDecoder {
        // 专门接收电报的邮递员
        private final MessageManager messageManager;

        // 构造器：上班时领走自己的邮递员
        TelemetryDecoder(MessageManager manager) {
            this.messageManager = manager;
        }

        /**
         * 处理房屋着火电报。
         */
        void processStructuralDamageReports() {
            // 从邮包里掏出所有写着 MessageBuilding 的信件
            for (CommunicationMessage payload : messageManager.getReceivedMessageList(MessageBuilding.class)) {
                // 强转成具体信件类型
                MessageBuilding dataPacket = (MessageBuilding) payload;
                // 用信里的温度数据，强行去世界地图上修改这栋楼的参数
                MessageUtil.reflectMessage(worldInfo, dataPacket);
            }
        }

        /**
         * 处理消防车同行定位电报。
         */
        void processFleetPositioningSignals() {
            // 掏出所有的 MessageFireBrigade 信件
            for (CommunicationMessage payload : messageManager.getReceivedMessageList(MessageFireBrigade.class)) {
                MessageFireBrigade dataPacket = (MessageFireBrigade) payload;
                // 更新同行在地图上的最新身体状况
                MessageUtil.reflectMessage(worldInfo, dataPacket);

                // 提取发信人（这辆消防车）的身份证号
                EntityID unitIdentifier = dataPacket.getAgentID();
                // 找到发信人本尊
                Human physicalEntity = (Human) worldInfo.getEntity(unitIdentifier);

                // 为了防止它上一秒的位置产生缓存导致寻路死机
                if (physicalEntity != null) {
                    // 把它的旧坐标残影无情抹除掉
                    physicalEntity.undefineX();
                    physicalEntity.undefineY();
                }
                // 将这辆乖乖发报的消防车登记在“今日考勤表”上
                activeTelemetryNodes.add(unitIdentifier);
            }
        }

        /**
         * 处理大楼死讯/火灭报告。
         */
        void processIncidentResolutionReports() {
            // 掏出最终的 MessageReport 结案陈词
            for (CommunicationMessage payload : messageManager.getReceivedMessageList(MessageReport.class)) {
                MessageReport dataPacket = (MessageReport) payload;
                // 如果这份报告明确指出了当事楼的 ID
                if (dataPacket.isFromIDDefined()) {
                    // 将其拉入黑名单，永远不再过问它的生死
                    obsoleteTargetRegistry.add(dataPacket.getFromID());
                }
            }
        }
    }
}
