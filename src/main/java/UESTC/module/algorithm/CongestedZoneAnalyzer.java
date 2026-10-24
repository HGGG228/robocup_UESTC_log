package UESTC.module.algorithm;

import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.StaticClustering;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

// 声明名为 CongestedZoneAnalyzer 的公共类，继承自 StaticClustering 静态聚类基类。
public class CongestedZoneAnalyzer extends StaticClustering {

    // 声明评估特定物理位置空间拥堵状态的私有核心方法。
    private boolean evaluateSpatialCongestion(EntityID locationMarker, StandardEntityURN targetUrn, int perceptionRadius) {
        
        // 构建不可变的哈希映射表，用于存储不同实体类型的全局数量获取逻辑。
        Map<StandardEntityURN, Supplier<Long>> globalUnitDemographics = Map.of(
                // 映射救护车队枚举类型至获取对应智能体总数的供给函数。
                StandardEntityURN.AMBULANCE_TEAM, () -> (long) this.scenarioInfo.getScenarioAgentsAt(),
                // 映射消防队枚举类型至获取全局消防员总数的供给函数。
                StandardEntityURN.FIRE_BRIGADE,   () -> (long) this.scenarioInfo.getScenarioAgentsFb(),
                // 映射警察部队枚举类型至获取全局警察总数的供给函数。
                StandardEntityURN.POLICE_FORCE,   () -> (long) this.scenarioInfo.getScenarioAgentsPf()
        );

        // 从映射表中提取目标实体类型的数量供给函数指针。
        Supplier<Long> populationMetric = globalUnitDemographics.get(targetUrn);
        
        // 校验供给函数是否为空，以拦截系统不支持的实体类型。
        if (populationMetric == null) {
            // 若实体类型不受支持，则不参与拥堵计算并返回 false。
            return false;
        }
        
        // 执行供给函数，提取该特定实体类型在全局范围内的宏观活动总数。
        long macroPopulation = populationMetric.get();
        
        // 对提取到的总数进行有效性校验，防止出现零或负数的异常状态。
        if (macroPopulation <= 0) {
            // 若总数基数无效，则直接判定为非拥堵状态并返回。
            return false;
        }

        // 调用世界模型接口，检索以目标位置为圆心、感知半径为范围内的所有实体标识流。
        long microConcentration = this.worldInfo.getObjectIDsInRange(locationMarker, perceptionRadius).stream()
                // 将检索到的实体标识序列逐一映射为真实的物理实体对象。
                .map(this.worldInfo::getEntity)
                // 执行类型过滤，保留与当前分析目标实体类型相匹配的对象。
                .filter(entity -> entity.getStandardURN().equals(targetUrn)) 
                // 统计过滤后的实体总数量，计算出该区域局部的微观实体浓度。
                .count();

        // 将宏观总数除以拥堵阈值，并与微观浓度进行对比，返回判定结果。
        return (macroPopulation / CONGESTION_THRESHOLD_DIVISOR) < microConcentration;
    }
    // 空间拥堵度评估方法逻辑结束。

    // 定义全图扫描方法，用于识别特定实体类型的拥堵热点区域。
    private Collection<EntityID> scanForCongestedHotspots(StandardEntityURN requestedUrn, int radiusLimit) {
        // 初始化哈希集合，用于存储被判定为拥堵状态的物理坐标点。
        Collection<EntityID> identifiedHotspots = new HashSet<>();
        
        // 通过世界模型获取指定实体类型当前所有的活跃单位集合。
        Collection<StandardEntity> activeUnits = this.worldInfo.getEntitiesOfType(requestedUrn);

        // 遍历所有的活跃单位，逐一进行空间位置的拥堵状态审查。
        for (StandardEntity unit : activeUnits) {
            // 将标准实体对象向下转型为具备位置属性的 Human 实体。
            Human fieldAgent = (Human) unit; 
            
            // 提取该 Human 实体当前所在的物理坐标标识。
            EntityID agentPosition = fieldAgent.getPosition();

            // 检查当前坐标是否已被记录在拥堵热点集合中。
            if (identifiedHotspots.contains(agentPosition)) {
                // 若该坐标已被记录，则跳过本次计算以节约系统算力。
                continue;
            }
            // 坐标去重与防抖过滤逻辑执行完毕。

            // 声明变量，用于存储验证通过的实体分类标识。
            StandardEntityURN verifiedClassification;
            
            // 使用异常捕获机制，验证外部传入枚举参数的合法性。
            try {
                // 调用内部验证方法，确认实体类型是否为受支持的合法枚举。
                verifiedClassification = this.validateEntityClassification(requestedUrn);
            } catch (IllegalArgumentException ex) {
                // 若抛出非法参数异常，说明该实体类型不支持拥堵分析。
                continue; 
                // 捕获异常并终止当前单位的分析流程，进入下一轮循环。
            }
            // 实体类型合法性验证通过。

            // 调用核心分析方法，评估该坐标节点在感知半径内的拥堵度。
            if (this.evaluateSpatialCongestion(agentPosition, verifiedClassification, radiusLimit)) {
                // 若判定为拥堵状态，则将该坐标加入拥堵热点集合中。
                identifiedHotspots.add(agentPosition);
            }
            // 单个单位的拥堵状态测算与判定流程结束。
        }
        // 全图扫描完毕，返回包含所有热点坐标的集合。
        return identifiedHotspots;
    }
    // 全图拥堵热点扫描方法执行结束。

    // 定义采用 Java Switch 表达式的实体类型验证方法。
    private StandardEntityURN validateEntityClassification(StandardEntityURN inputUrn) {
        // 进行空值校验，拦截并防止传入 Null 引用。
        if (inputUrn == null) {
            // 若参数为空，则抛出非法参数异常。
            throw new IllegalArgumentException("Critical Error: Null StandardEntityURN provided.");
        }
        // 空指针拦截校验通过。
        
        // 启用 Switch 表达式，将输入的枚举值路由至对应的核验分支。
        return switch (inputUrn) {
            // 匹配并返回救护车队实体枚举类型。
            case AMBULANCE_TEAM -> StandardEntityURN.AMBULANCE_TEAM;
            // 匹配并返回消防队实体枚举类型。
            case FIRE_BRIGADE -> StandardEntityURN.FIRE_BRIGADE;
            // 匹配并返回警察部队实体枚举类型。
            case POLICE_FORCE -> StandardEntityURN.POLICE_FORCE;
            // 若输入类型未在匹配列表中，则进入默认异常处理分支。
            default -> throw new IllegalArgumentException("Unsupported StandardEntityURN classification: " + inputUrn);
            // 抛出异常以阻断不支持的实体类型造成数据污染。
        };
    }
    // 实体分类验证逻辑编写结束。

    // 重写生命周期中的核心计算调度方法。
    @Override
    public Clustering calc() {
        // 从场景配置中获取最大视野感知距离，作为拥堵探测的半径标尺。
        int perceptionLimit = this.scenarioInfo.getPerceptionLosMaxDistance();

        // 扫描消防队类型的拥堵热点，并将结果汇入总缓存集合中。
        this.congestedZonesCache.addAll(this.scanForCongestedHotspots(StandardEntityURN.FIRE_BRIGADE, perceptionLimit));
        
        // 扫描救护车队类型的拥堵热点，并持续扩充缓存集合。
        this.congestedZonesCache.addAll(this.scanForCongestedHotspots(StandardEntityURN.AMBULANCE_TEAM, perceptionLimit));
        
        // 扫描警察部队类型的拥堵热点，完成最后一轮热点数据汇聚。
        this.congestedZonesCache.addAll(this.scanForCongestedHotspots(StandardEntityURN.POLICE_FORCE, perceptionLimit));

        // 所有实体类型扫描完毕，返回当前类实例对象的引用。
        return this;
    }
    // 核心计算与调度方法执行结束。

    // 重写预计算生命周期方法，负责离线数据的计算与持久化。
    @Override
    public Clustering precompute(PrecomputeData precomputeData) {
        // 调用基类方法，完成底层预计算基础设施的初始化。
        super.precompute(precomputeData);
        
        // 校验调用次数，防止重复执行引发幂等性问题。
        if (this.getCountPrecompute() > 1) {
            // 若调用次数超过限制，则拦截该次重复的计算请求。
            return this;
        }
        // 重复调用防范机制校验完毕。

        // 校验通过，调用核心计算方法执行全图范围的拥堵分析。
        this.calc();
        
        // 将计算得出的拥堵热点集合转换为列表，并写入预计算数据对象中进行持久化。
        precomputeData.setEntityIDList(PERSISTENCE_CACHE_KEY, new ArrayList<>(this.congestedZonesCache));
        
        // 离线计算与持久化工作完成，返回实例引用。
        return this;
    }
    // 预计算生命周期方法执行结束。

    // 重写恢复生命周期方法，负责热启动时的数据反序列化与状态恢复。
    @Override
    public Clustering resume(PrecomputeData precomputeData) {
        // 调用基类方法，执行底层的通用数据恢复逻辑。
        super.resume(precomputeData);
        
        // 执行调用次数校验，设置防重复执行的安全屏障。
        if (this.getCountResume() > 1) {
            // 若检测到重复调用，则直接返回当前实例对象。
            return this;
        }
        // 重复调用校验完毕。
        
        // 使用预设的缓存键名，从持久化数据中提取上一周期的拥堵坐标列表。
        List<EntityID> recoveredHotspots = precomputeData.getEntityIDList(PERSISTENCE_CACHE_KEY);
        
        // 对提取的数据进行非空校验，以确保数据的有效性。
        if (recoveredHotspots != null) {
            // 若数据有效，则将其全量添加至当前的拥堵区域缓存集合中。
            this.congestedZonesCache.addAll(recoveredHotspots);
        }
        // 数据的反序列化与状态重建流程结束。
        
        // 模块状态恢复完成，返回实例引用。
        return this;
    }
    // 恢复生命周期方法执行结束。

    // 重写准备期生命周期方法，用于处理无离线数据的实时计算场景。
    @Override
    public Clustering preparate() {
        // 调用基类方法，执行常规的准备与环境初始化。
        super.preparate();
        
        // 设置执行频次校验，避免在单周期内过度消耗计算资源。
        if (this.getCountPreparate() > 1) {
            // 若调用频次超限，则直接返回当前实例句柄。
            return this;
        }
        // 执行频次合规性检测通过。

        // 调用核心计算方法进行实时拥堵分析。
        this.calc();
        
        // 全局分析计算完毕。
        return this;
    }
    // 准备生命周期方法执行结束。

    // 实现标准化聚类接口，返回聚类划分的总数量。
    @Override
    public int getClusterNumber() {
        // 返回预设的单例聚类容量常量。
        return TOTAL_CLUSTER_CAPACITY;
    }
    // 聚类数量获取接口实现结束。

    // 实现标准化接口，通过物理实体对象获取其对应的聚类索引。
    @Override
    public int getClusterIndex(StandardEntity standardEntity) {
        // 因采用单例聚类模式，统一返回默认的 0 号聚类索引。
        return SINGLETON_CLUSTER_INDEX;
    }
    // 实体索引查询接口实现结束。

    // 实现重载接口，通过底层 EntityID 获取其对应的聚类索引。
    @Override
    public int getClusterIndex(EntityID entityID) {
        
        return SINGLETON_CLUSTER_INDEX;
    }
    // 标识符索引查询接口实现结束。

    // 实现接口，获取指定聚类索引下的所有实体标识符成员。
    @Override
    public Collection<EntityID> getClusterEntityIDs(int index) {
        // 忽略传入索引，直接返回全局唯一的拥堵区域缓存集合。
        return this.congestedZonesCache;
    }
    // 实体标识符集合获取接口实现结束。

    // 实现接口，获取指定聚类索引下的所有实际物理实体对象。
    @Override
    public Collection<StandardEntity> getClusterEntities(int index) {
        // 将缓存的拥堵热点集合转换为数据流以便进行映射处理。
        return this.congestedZonesCache.stream()
                // 将抽象的实体标识符通过世界模型映射为具体的 StandardEntity 实体。
                .map(this.worldInfo::getEntity)
                // 将映射后的物理实体收集至全新的哈希集合中进行去重并返回。
                .collect(Collectors.toCollection(HashSet::new));
    }
    // 物理实体集合获取接口实现结束。

    // 构造函数，接收并初始化外部传入的基础运行上下文参数。
    public CongestedZoneAnalyzer(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager mm, DevelopData dd) {
        // 调用父类构造器，完成基础依赖组件的深层注入与初始化。
        super(ai, wi, si, mm, dd);
        
        // 触发一次己方阵营类型的获取方法，以唤醒并初始化底层的相关状态机制。
        this.agentInfo.me().getStandardURN();
    }
    // 拥堵区聚类分析器实例化装配完成。

    // 声明静态常量，表示单例聚类模式下的默认聚类索引。
    private static final int SINGLETON_CLUSTER_INDEX = 0;
    
    // 声明静态常量，限定当前聚类体系的总容量为 1。
    private static final int TOTAL_CLUSTER_CAPACITY = 1;
    
    // 声明静态常量，作为评估拥挤阈值时的人口比例除数。
    private static final int CONGESTION_THRESHOLD_DIVISOR = 2;
    
    // 声明静态常量，用作持久化读写离线缓存数据的唯一键名。
    private static final String PERSISTENCE_CACHE_KEY = "UESTC.module.algorithm.OvercrowdingArea.c";

    // 声明私有集合，用于在内存中缓存判定为拥堵的实体坐标标识。
    private final Collection<EntityID> congestedZonesCache = new HashSet<>();

}
