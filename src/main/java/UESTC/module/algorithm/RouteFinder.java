package UESTC.module.algorithm;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.algorithm.StaticClustering;
import org.locationtech.jts.geom.Geometry;
import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.Refuge;
import rescuecore2.standard.entities.Road;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

// 声明高级路径规划引擎类，继承自标准路径规划组件基类。
public class RouteFinder extends PathPlanning {

    // 定义启发式搜索的内部节点模型，用于封装A*算法的图搜索状态。
    private class Node extends HeuristicSearchBase.HeuristicNode {

        // 定义单参数构造函数，专门用于初始化搜索树的起始根节点。
        public Node(EntityID identifier) {
            // 调用超类构造方法，初始化实体标识、清零实际代价并预估启发式距离。
            super(identifier, null, 0.0, estimateRemainingDistance(identifier));
        }
        // 起始节点构造方法执行结束。

        // 定义多参数构造函数，用于在搜索树扩展阶段记录节点溯源与代价信息。
        public Node(EntityID identifier, Node parentNode, double gCost, double hCost) {
            // 将父节点指针与累加的代价权重传递至底层基类进行持久化存储。
            super(identifier, parentNode.getID(), gCost, hCost);
        }
        // 中继节点构造方法执行结束。

        // 声明节点状态衍生方法，根据当前节点状态推导下一邻接节点的评估参数。
        public Node build(EntityID nextId) {
            // 计算实际移动代价，将当前基础消耗与前往邻接节点的跨越成本相加。
            double updatedGCost = this.getGvalue() + calculateTraversalCost(nextId, this);
            // 重新测算目标邻接节点到达最终目的地的启发式预估代价。
            double updatedHCost = estimateRemainingDistance(nextId);
            // 封装更新后的代价参数，实例化并返回全新的搜索节点对象。
            return new Node(nextId, this, updatedGCost, updatedHCost);
        }
        // 节点状态推导逻辑构建完毕。

        // 重写抽象方法，提供针对当前物理网格节点的邻接拓扑扩展逻辑。
        @Override
        public Collection<HeuristicSearchBase.HeuristicNode> gatherNeighbors() {
            // 依据实体标识符从环境模型中提取具体的面状物理区域实体。
            Area areaEntity = (Area) worldInfo.getEntity(this.getID());
            
            // 获取当前区域的所有邻接标识集，并转化为数据流以便执行链式操作。
            return areaEntity.getNeighbours().stream()
                    // 遍历邻接标识符，利用节点构建方法将其转化为启发式评估节点。
                    .map(neighborId -> (HeuristicSearchBase.HeuristicNode) this.build(neighborId))
                    // 终止流操作，将处理完毕的合规相邻节点收集为标准列表集合返回。
                    .toList();
        }
        // 邻接拓扑扩展收集机制执行结束。
    }

    // 定义专用数据过滤方法，从混合实体集中提取合法的道路面域对象。
    private Collection<Area> filterRoadEntities(Collection<EntityID> changedIds) {
        // 开启数据流管道，准备对传入的变动实体标识序列进行清洗。
        return changedIds.stream()
                // 依托世界模型对象，将抽象实体标识符逐一映射为实际的物理实体。
                .map(this.worldInfo::getEntity)
                // 设定严格的类型过滤条件，仅保留派生自道路基类的实体对象。
                .filter(entity -> entity instanceof Road)
                // 执行向下类型转换，将验证通过的道路实体转换为几何面域接口。
                .map(entity -> (Area) entity)
                // 汇总清洗转换后的合法道路实体，并以标准列表形式输出。
                .toList();
    }
    // 道路数据专项清洗过滤逻辑结束。

    // 声明通行状态评估方法，基于路障实体对目标区域的几何连通性进行重构。
    private void evaluateAreaPassability(Area mapArea) {
        // 提取并缓存当前待评估面域实体的全局唯一标识符。
        final EntityID currentAreaId = mapArea.getID();
        // 检索世界模型，获取当前区域内存在的所有路障障碍物实体。
        final Collection<Blockade> localObstacles = this.worldInfo.getBlockades(currentAreaId);
        // 调用几何处理器执行布尔差集运算，提取剔除路障后的绝对可通行二维面。
        final Geometry walkableSpace = SpatialMorphologyCore.computePassable(mapArea, localObstacles);

        // 初始化哈希集合，用于存储因路障遮挡而导致通行阻断的拓扑连接边。
        Set<DataPair<EntityID>> blockedEdges = new HashSet<>();
        // 遍历当前区域内所有生成的邻接出入口拓扑偶对，执行连通性核验。
        for (DataPair<EntityID> pairedEdge : this.generateEdgePairs(mapArea)) {
            // 检验特定连接边是否能够安全穿越当前计算得出的几何可通行空间。
            if (!this.checkEdgeClearance(mapArea, walkableSpace, pairedEdge)) {
                // 若检测到物理阻断，则将该连接边对记录至不可通行黑名单集合中。
                blockedEdges.add(pairedEdge);
            }
            // 单个连接边对的几何连通性核算完成。
        }

        // 检查当前评估区域是否完全不存在任何物理路障实体的干扰。
        if (localObstacles.isEmpty()) {
            // 若区域绝对畅通，则从全局受阻记录缓存中安全移除该区域的历史阻断信息。
            this.blockedConnectionsCache.remove(currentAreaId);
        } else {
            // 若区域内确实存在路障并对局部拓扑网格造成物理干扰。
            this.blockedConnectionsCache.put(currentAreaId, blockedEdges);
            // 将更新后的受阻边对集合覆盖写入全局通行黑名单字典中。
        }
        // 特定面域通行状态重构与全局缓存同步结束。
    }

    // 定义拓扑关系生成器，构建特定区域内各邻接节点两两配对的连通映射。
    private Set<DataPair<EntityID>> generateEdgePairs(Area mapArea) {
        // 提取当前面域实体所直接接壤的全部外部节点标识集合。
        List<EntityID> adjacentList = mapArea.getNeighbours();
        // 初始化去重集合，用于存储无序且唯一的内部拓扑联通元组。
        Set<DataPair<EntityID>> edgeSet = new HashSet<>();
        // 预先缓存邻接节点总数，优化双重循环遍历过程中的性能消耗。
        int adjSize = adjacentList.size();

        // 启动外层遍历循环，设定内部连通线段的初始端点锚位。
        for (int indexA = 0; indexA < adjSize; indexA++) {
            // 启动内层嵌套循环，匹配不重复的终端目标以确立双向连接。
            for (int indexB = indexA + 1; indexB < adjSize; indexB++) {
                // 将抽取的两端孤立标识封装为标准的二元偶对，并加入拓扑关系集。
                edgeSet.add(new DataPair<>(adjacentList.get(indexA), adjacentList.get(indexB)));
            }
            // 单节点的内部组合遍历映射完成。
        }

        // 检测当前分析的区域是否为智能体自身当前驻留的物理位置。
        if (mapArea.getID().equals(this.agentInfo.getPosition())) {
            // 针对智能体驻留地，补充生成自原点辐射至各接壤边界的单向拓扑边。
            adjacentList.forEach(adjNode -> edgeSet.add(new DataPair<>(adjNode, null)));
        }

        // 返回组装完毕的区域内部通道映射集，完成拓扑边对的生成逻辑。
        return edgeSet;
    }

    // 声明几何间隙验证方法，核算特定连接边是否被路障多边形物理切断。
    private boolean checkEdgeClearance(Area areaContext, Geometry walkableSpace, DataPair<EntityID> edgePair) {
        // 构建基于智能体当前绝对二维坐标的零长度参考向量线段。
        Line2D selfLine = new Line2D(this.fetchSelfCoordinates(), this.fetchSelfCoordinates());

        // 提取起始端边缘线参数，若存在空值则降级回退采用自身参考基点。
        Line2D lineSource = (edgePair.elem1 == null) ? selfLine : areaContext.getEdgeTo(edgePair.elem1).getLine();
        // 提取终止端边缘线参数，执行相同的空指针替代与降级容错操作。
        Line2D lineTarget = (edgePair.elem2 == null) ? selfLine : areaContext.getEdgeTo(edgePair.elem2).getLine();

        // 委托底层几何处理器，验证跨越线段是否完全包裹于可导航布尔面之内。
        return SpatialMorphologyCore.validate(walkableSpace, lineSource, lineTarget);
    }

    // 定义提取智能体自身实时二维坐标位点的基础访问方法。
    private Point2D fetchSelfCoordinates() {
        // 从环境信息中提取横纵分离坐标，封装为标准的二维矢量点对象返回。
        return new Point2D(this.agentInfo.getX(), this.agentInfo.getY());
    }

    // 声明受阻状态判定方法，验证特定双节点网络连接是否处于物理断绝状态。
    private boolean isConnectionBlocked(EntityID areaId, EntityID nodeFrom, EntityID nodeTo) {
        // 从全局阻塞缓存字典中，提取当前区域所属的历史不可通行边界记录集。
        Set<DataPair<EntityID>> invalidSet = this.blockedConnectionsCache.get(areaId);
        // 执行空值安全校验，排查目标区域是否存在相关阻塞记录。
        if (invalidSet == null) {
            // 若无阻塞记录，则默认赋予该拓扑连接畅通的合法性断言。
            return false;
        }
        // 采用双向核查机制，验证给定的出入偶对是否命中了受阻黑名单记录。
        return invalidSet.contains(new DataPair<>(nodeFrom, nodeTo)) || 
               invalidSet.contains(new DataPair<>(nodeTo, nodeFrom));
    }

    // 定义基于启发式历史节点的重载判定方法，用以解构封装层的网络连通度。
    private boolean isConnectionBlocked(EntityID targetId, HeuristicSearchBase.HeuristicNode ancestorNode) {
        // 提取父代节点层级溯源链标识，递交至底层受阻评估函数实施甄别。
        return this.isConnectionBlocked(ancestorNode.getID(), ancestorNode.getAncestor(), targetId);
    }

    // 声明路网层级优先权鉴别机制，判定目标节点是否享有干线畅通权重。
    private boolean isPriorityRoute(EntityID areaId) {
        // 综合考量仿真时间阈值及高速管网集群的从属状态，输出布尔鉴别结果。
        return this.agentInfo.getTime() >= 30 && this.fastRoutesCluster.getClusterIndex(areaId) >= 0;
    }

    // 定义单步移动实际耗散代价（G-Cost）的动态量化加权核算方法。
    private double calculateTraversalCost(EntityID targetId, HeuristicSearchBase.HeuristicNode ancestorNode) {
        // 测量节点两端的绝对欧氏直线物理距离，将其作为基础移动衡量标尺。
        double physicalDist = this.worldInfo.getDistance(targetId, ancestorNode.getID());
        // 结合路权优先级判定结果，动态赋予干线路段与普通街区的地形阻力乘数。
        double terrainWeight = this.isPriorityRoute(ancestorNode.getID()) ? 1.0 : 3.0;
        
        // 执行基础代价核算，合并物理距离与地形阻力因子推导出初始阶段消耗值。
        double finalCost = physicalDist * terrainWeight;

        // 最终裁定路径阻断状态，对受阻路段施加重度障碍惩罚加权后返回运算结果。
        return this.isConnectionBlocked(targetId, ancestorNode) ? (finalCost * OBSTACLE_PENALTY_WEIGHT) : finalCost;
    }

    // 声明启发式预估代价（H-Cost）测算算子，输出当前至终点的偏差估值。
    private double estimateRemainingDistance(EntityID nodeId) {
        // 依据全局坐标系统，测定并返回测试节点至最终标靶的最短标称直线距离。
        return this.worldInfo.getDistance(nodeId, this.goalNodeId);
    }

    // 实现标准化的数据输出接口，向外层架构披露运算完毕的最优路径规划表。
    @Override
    public List<EntityID> getResult() {
        // 返回经由搜索探索引擎推演所生成的实体序列链表。
        return this.computedRoute;
    }

    // 覆写配置接口规范，赋予外部调度器重置导航启程原点坐标的权限。
    @Override
    public PathPlanning setFrom(EntityID startId) {
        // 将输入的实体标识参数固化赋予引擎内部的起始基准常量字段。
        this.startNodeId = startId;
        // 返回当前操作实例自身，以维持构造型设计模式下的链式调用协议。
        return this;
    }

    // 覆写目标配置接口，支持在候选集合中依据特定策略择取最优收敛节点。
    @Override
    public PathPlanning setDestination(Collection<EntityID> destinations) {
        // 建立前置防御检验逻辑，排查输入参数集是否存在空指针或数据真空状态。
        if (destinations == null || destinations.isEmpty()) {
            // 若校验判定输入无效，则同步重置内部终端导向锚点，规避非法寻径。
            this.goalNodeId = null;
            // 触发安全短路机制，中断无意义分析任务并跳出当前方法栈执行流。
            return this;
        }
        // 终点输入源合法性验证与异常阻断执行完毕。

        // 提取当前智能体的实体唯一标识符，作为后续空间度量计算的基准参考原点。
        final EntityID agentId = this.agentInfo.getID();

        // 开启数据校验流，探测传入的候选集是否具备纯粹的避难所设施属性集合。
        boolean pureRefugesMode = destinations.stream()
                // 将抽象的实体标识符数据解包转换为具有完整属性状态的场景实体模型。
                .map(this.worldInfo::getEntity)
                // 采用严格匹配谓词，断言全量待定目标点是否均隶属于避难所派生类模型。
                .allMatch(entity -> entity instanceof Refuge);

        // 声明对象引用变量，用于缓存依据容量及距离综合排位决出的最优避难所设施。
        EntityID optimalRefuge = null;
        // 基于纯避难所模式判定结果，执行对应的高级避险疏散节点优选子程序。
        if (pureRefugesMode) {
            // 将避难所候选集导入过滤流，准备实施基于动态容量指标的筛选淘汰机制。
            optimalRefuge = destinations.stream()
                    // 设立匿名过滤函数，对每个避难所实体的负载接纳状态执行逐项质检考核。
                    .filter(candidateId -> {
                        // 执行类型转换操作，将泛型实体复原为具备容量存取接口的避难所对象。
                        Refuge ref = (Refuge) this.worldInfo.getEntity(candidateId);
                        // 通过对比最大床位承载上限与当前已用份额，判定其是否具备收容余量。
                        return ref.getBedCapacity() > ref.getOccupiedBeds();
                    })
                    // 调用空间距离比较器，在满足容量约束的候选集中择取物理位置最为接近的目标。
                    .min(Comparator.comparingInt(candidateId -> this.worldInfo.getDistance(agentId, candidateId)))
                    // 提供终端流安全兜底策略，若不存在合规避难所则赋予空值引用结束处理。
                    .orElse(null);
        }

        // 处理备用模式逻辑，当避险条件不成立或目标资源匮乏时启用通用距离降维择优。
        if (optimalRefuge == null) {
            // 重新面向全量备选资料库开启数据流比对，实施基于物理测距的降级寻优。
            this.goalNodeId = destinations.stream()
                    // 直接依照与当前智能体的欧式直线距离进行升序排列，提取最近点位标识。
                    .min(Comparator.comparingInt(candidateId -> this.worldInfo.getDistance(candidateId, agentId)))
                    // 设置终极容错机制，防范全集检索后的游离崩溃隐患。
                    .orElse(null);
        }

        // 目的地优选与状态锁定机制妥当收尾，交付配置更新完毕的规划器对象句柄。
        return this;
    }

    // 覆写生命周期更新钩子接口，拦截并解析底层系统广播的环境特征变动数据报。
    @Override
    public PathPlanning updateInfo(MessageManager mm) {
        // 上抛调度执行权至父类架构，完成通用模块层级的例行数据同步与状态归档。
        super.updateInfo(mm);
        // 构建防重入并发检测阀门，审查当前执行帧内是否遭遇了多线程的冗余唤醒。
        if (this.getCountUpdateInfo() > 1) {
            // 遭遇过载调用时强制执行资源保护，切断后续重型图论刷新计算调用链。
            return this;
        }

        // 访问系统变动对象注册表，检索截获所有在本轮仿真周期发生物理变异的实体群落。
        Set<EntityID> changedEntities = this.worldInfo.getChanged().getChangedEntities();

        // 过筛提取变动名录中的路面设施，对其开展逐一迭代的局部通行拓扑重建核验。
        this.filterRoadEntities(changedEntities).forEach(this::evaluateAreaPassability);

        // 拓扑图谱动态修正编修流程终了，返还当前对象上下文操作引用。
        return this;
    }

    // 显式声明全局路线演算的执行中枢通道，激活底层启发式图搜索运转引擎。
    @Override
    public PathPlanning calc() {
        // 装填最新状态的起止标靶并推入 A* 处理器，挂载所得序列至计算结果缓冲池。
        this.computedRoute = HeuristicSearchBase.find(new Node(this.startNodeId), this.goalNodeId);
        // 阶段性全图规划任务解算落地，反馈包含路线产出成果的任务对象实例。
        return this;
    }

    // 制订高级规划模块的总控装配构造器，接纳系统层初始化指派下发的多源环境参数。
    public RouteFinder(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager mm, DevelopData dd) {
        // 移交仿真核心运行上下文至超类缓存寄存，奠定内部运算机制的资源支持基准。
        super(ai, wi, si, mm, dd);

        // 依据框架硬编码寻址规则，精确定向拉取负责宏观高速路权分析的聚类功能组件。
        this.fastRoutesCluster = mm.getModule("UESTC.Algorithm.RouteFinder.ArterialRoadSegmenter");
        // 向组件总控制台提交报备记录，将此快线解析算子正式编入当前体系协同调度网络。
        this.registerModule(this.fastRoutesCluster);

        // 持续导入外部微件资源，定位并剥离专职处理单位拥堵停滞评估模型的异构服务板。
        this.stuckAgentsCluster = mm.getModule("UESTC.Algorithm.RouteFinder.MotionAnomalyInspector");
        // 完成驻停状态评估板的内部系统槽位嵌合，确保该微件跟随主程序生命周期同步。
        this.registerModule(this.stuckAgentsCluster);

        // 翻查并接入负责监测评估受困平民热力学分布阻隔图层数据的拓展分析组件。
        this.immobileCiviliansCluster = mm.getModule("UESTC.Algorithm.RouteFinder.PeopleConstraintDetector");
        // 完成全部异源协程分析前哨的配置挂载，宣告依赖总线拼接组装阶段圆满竣工。
        this.registerModule(this.immobileCiviliansCluster);
    }

    // 声明不可变的重磅乘数常量，用以对遭遇物理斩断破坏的死胡同拓扑连接施以极端惩罚。
    private static final double OBSTACLE_PENALTY_WEIGHT = 15.0;

    // 声明私有动态序列缓冲容器，专供承载接纳经图搜索算法测算完毕所析出的完整导航序列。
    private List<EntityID> computedRoute;
    // 声明专置标量字段寄存槽，持久化锁定记录下达推进任务指派阶段所赋予的原始发车网格。
    private EntityID startNodeId;
    // 配置终期导向坐标属性探测位，借以收敛明确当前全局搜索框架最终需锚定逼近的方位指向。
    private EntityID goalNodeId;

    // 实例化黑名单字典树空间，长效记录维系各区块内遭受断崖式物理阻隔的废弃出入口图对。
    private final Map<EntityID, Set<DataPair<EntityID>>> blockedConnectionsCache = new HashMap<>();

    // 固化关联外接支援实例指针句柄，统筹调用管辖主干大动脉连通分析数据的聚类外援处理器。
    private final StaticClustering fastRoutesCluster;
    // 固化外延协程接口绑定对象，专门用以引入处理大面积停滞单位负面路权影响的分析模型图。
    private final Clustering stuckAgentsCluster;
    // 限定指向专用静态组件地址索引，参考纳入特大遇险民众集聚地带衍生的动态阻隔限位分析层。
    private final Clustering immobileCiviliansCluster;

}
