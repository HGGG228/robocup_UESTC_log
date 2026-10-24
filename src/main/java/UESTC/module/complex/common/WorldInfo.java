package UESTC.module.complex.common;

import adf.core.agent.info.AgentInfo;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;

/**
 * 动态环境实体查询构建器 (Dynamic Environment Entity Query Builder)。
 * <p>
 * 灾害模拟环境（RoboCup Rescue）中的状态是高度动态的。底层提供的地图数据源在处理复杂条件过滤时
 * 略显不足。本组件采用建造者模式（Builder Pattern），提供一套流式接口（Fluent Interface），
 * 允许调用方通过组合条件（如指定实体类别 URN）来构造特定的查询上下文。
 * 其核心目标是从当前仿真周期的全量实体中，提取出发生状态变动的特定实体子集，以优化决策模块的计算开销。
 * </p>
 */
public class WorldInfo {

    // ========================================================================
    // 上下文依赖注入区 (Context Dependencies)
    // ========================================================================

    /** 智能体自身的局部状态档案 */
    private final AgentInfo localAgentProfile;
    
    /** 底层全局地图数据存储库 */
    private final adf.core.agent.info.WorldInfo globalMapRepository;

    // ========================================================================
    // 动态配置参数区 (Dynamic Configurations)
    // ========================================================================

    /** 查询容量限制（目前处于预留状态，待后续扩展） */
    private int capacityLimit;
    
    /** 目标实体的统一资源名称 (URN) 分类 */
    private StandardEntityURN searchCriteriaURN;

    /**
     * 受保护的构造函数。要求必须注入全局地图上下文和本地智能体上下文。
     *
     * @param mapRepo 全局世界模型仓库
     * @param agentProfile 智能体自身状态描述
     */
    private WorldInfo(adf.core.agent.info.WorldInfo mapRepo, AgentInfo agentProfile) {
        this.localAgentProfile = agentProfile;
        this.globalMapRepository = mapRepo;
    }

    /**
     * 实例化入口。
     *
     * @param wi 核心世界模型
     * @param ai 代理当前状态
     * @return 配置好基础依赖的查询器实例
     */
    public static WorldInfo build(adf.core.agent.info.WorldInfo wi, AgentInfo ai) {
        return new WorldInfo(wi, ai);
    }

    /**
     * 设定最大检索数量阈值。
     *
     * @param limit 期望返回的结果集最大容量
     * @return 当前 Builder 实例
     */
    public WorldInfo number(int limit) {
        this.capacityLimit = limit;
        return this;
    }

    /**
     * 指定检索的目标实体类别枚举。
     *
     * @param criteria 目标实体的 URN（例如：CIVILIAN, BUILDING）
     * @return 当前 Builder 实例
     */
    public WorldInfo type(StandardEntityURN criteria) {
        this.searchCriteriaURN = criteria;
        return this;
    }

    /**
     * 触发最终的查询计算逻辑。
     * <p>
     * 内部利用 {@link FilterEngine} 对指定 URN 的实体集合与当前时间步发生变动的实体集合
     * 取交集，从而筛选出活跃的特定目标。
     * </p>
     * * @return 符合组合条件的实体 ID 集合 (Set)
     */
    public Set<EntityID> getEntitieIDs() {
        // 将具体的过滤逻辑委托给内部处理机执行
        FilterEngine engine = new FilterEngine();
        return engine.executeIntersection();
    }

    /**
     * 占位方法。用于兼容某些特定的接口契约或反射调用。
     * * @return 恒为空的实体集合
     */
    public Set<Entity> getEntities() {
        return new HashSet<>();
    }

    // ========================================================================
    // 内部处理机 (Internal Processor)
    // ========================================================================

    /**
     * 内部过滤器引擎。
     * 负责隔离集合的遍历和交集计算逻辑，增强代码的层级。
     */
    private class FilterEngine {

        /**
         * 执行核心的求交集操作。
         *
         * @return 过滤后的 ID 集合
         */
        protected Set<EntityID> executeIntersection() {
            Set<EntityID> resultSet = new HashSet<>();

            // 获取当前轮回中所有状态发生了改变的实体集合
            Set<EntityID> dynamicEntitiesInCurrentCycle = globalMapRepository.getChanged().getChangedEntities();
            
            // 获取指定 URN 类型的所有实体（注意：此处安全地使用 Collection 接收，避免强转 Set 异常）
            Collection<EntityID> entitiesMatchingURN = globalMapRepository.getEntityIDsOfType(searchCriteriaURN);

            // 遍历目标类型的集合，若该实体存在于当前周期的变动名单中，则加入结果集
            for (EntityID idToInspect : entitiesMatchingURN) {
                if (dynamicEntitiesInCurrentCycle.contains(idToInspect)) {
                    resultSet.add(idToInspect);
                }
            }

            return resultSet;
        }
    }
}
