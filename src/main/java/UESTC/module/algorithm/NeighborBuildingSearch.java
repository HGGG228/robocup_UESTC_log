package UESTC.module.algorithm;

import adf.core.component.module.algorithm.*;
import adf.core.agent.info.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import rescuecore2.standard.entities.*;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;

import java.awt.Rectangle;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 建筑邻接关系探测器
 * 主要职责是分析静态地图，将物理位置上靠近的建筑物进行聚类分组。
 */
public class NeighborBuildingSearch extends StaticClustering {

    // ---------------------------------------------------------
    // 数据查询层接口
    // ---------------------------------------------------------

    @Override
    public int getClusterNumber() {
        // 直接返回映射表中记录的独立聚类总数
        return this.buildingNeighborsMap.size(); 
    }

    @Override
    public int getClusterIndex(StandardEntity entity) {
        // 将实体对象转化为实体 ID，委托给另一个重载方法处理
        return this.getClusterIndex(entity.getID()); 
    }

    @Override
    public int getClusterIndex(EntityID id) {
        // 在本模块中，建筑 ID 的内部整型值直接充当其聚类索引
        return id.getValue(); 
    }

    @Override
    public Collection<StandardEntity> getClusterEntities(int clusterIdx) {
        // 先调用内部方法，获取当前索引名下的所有相邻建筑 ID
        Collection<EntityID> groupMembers = this.getClusterEntityIDs(clusterIdx);
        
        // 增加空值拦截，防止后续 Stream 操作触发空指针异常
        if (groupMembers == null || groupMembers.isEmpty()) {
            // 如果没找到邻居，返回一个不可变的空集合
            return Collections.emptyList();
        }

        // 开启 Stream 流，准备将抽象 ID 转化为物理实体
        return groupMembers.stream()
                // 利用世界模型接口，将 ID 逐一映射为真实的实体对象
                .map(this.worldInfo::getEntity)
                // 剔除映射失败的 null 值，保证返回集合的纯净度
                .filter(Objects::nonNull)
                // 收集过滤后的流数据，打包为 List 返回
                .toList(); 
    }

    @Override
    public Collection<EntityID> getClusterEntityIDs(int clusterIdx) {
        // 将传入的虚拟索引重组为 EntityID，从映射表中快速提取邻居集合
        return this.buildingNeighborsMap.get(new EntityID(clusterIdx));
    }


    // ---------------------------------------------------------
    // 生命周期与状态控制
    // ---------------------------------------------------------

    @Override
    public Clustering preparate() {
        // 调用父类的准备方法，完成基础环境注入
        super.preparate();
        // 限制方法执行频率，防止单周期内进行多余的重复计算
        if (this.getCountPreparate() < 2) {
            // 满足触发条件，执行核心的拓扑网络计算
            this.calc();
        }
        // 返回实例对象本身，以支持链式调用规范
        return this;
    }

    @Override
    public Clustering resume(PrecomputeData precomputeData) {
        // 执行父类的状态恢复逻辑，读取离线缓存
        super.resume(precomputeData);
        // 通过计数器拦截，避免不必要的地形重构计算
        if (this.getCountResume() < 2) {
            // 触发动态计算，更新实时的建筑邻接关系
            this.calc();
        }
        // 操作完成，返回聚类器自身指针
        return this;
    }

    @Override
    public Clustering precompute(PrecomputeData pd) {
        // 预计算阶段无特殊动作，直接透传给父类处理
        super.precompute(pd);
        // 顺势返回当前对象引用
        return this;
    }


    // ---------------------------------------------------------
    // 核心算法：生成拓扑关系
    // ---------------------------------------------------------

    @Override
    public Clustering calc() {
        // 启动全局流操作，开始筛查地图上的建筑目标
        Set<EntityID> allBuildingIdentifiers = this.worldInfo.getAllEntities().stream()
                // 严格过滤，仅允许 Building 类型的实体通过
                .filter(entity -> entity instanceof Building)
                // 将符合条件的建筑实体降维至 EntityID
                .map(Entity::getID)
                // 将全图的合法建筑 ID 收集到一个无重复的 Set 中
                .collect(Collectors.toSet());

        // 清理旧的缓存数据，为新一周期的拓扑图谱腾出内存
        this.buildingNeighborsMap.clear();

        // 遍历整个建筑 ID 库，将其逐一视作探测基点
        allBuildingIdentifiers.forEach(centerBuildingId -> {
            // 驱动私有探针方法，扫描当前中心建筑辐射圈内的邻居
            Set<EntityID> buildingsInVicinity = this.fetchBuildingsInVicinity(centerBuildingId, allBuildingIdentifiers);
            // 将中心点与其扫描到的邻接子集绑定，写入全局状态表
            this.buildingNeighborsMap.put(centerBuildingId, buildingsInVicinity);
        });

        // 所有的拓扑网络交织完毕，向调度系统返回计算完成的模块
        return this;
    }


    // ---------------------------------------------------------
    // 私有辅助计算逻辑
    // ---------------------------------------------------------

    private Set<EntityID> fetchBuildingsInVicinity(EntityID centerBuildingId, Set<EntityID> allBuildingIdentifiers) {
        // 提取中心建筑在二维平面上的绝对包围盒
        Rectangle boundingBox = this.extractBoundingBox(centerBuildingId);
        
        // 提取矩形宽高的最大值，乘以扩散系数，作为范围搜索的阈值半径
        int radiusLimit = (int) (Math.max(boundingBox.getWidth(), boundingBox.getHeight()) * PROXIMITY_MULTIPLIER);

        // 调用引擎底层的范围检索功能，提取物理半径内现存的所有对象 ID
        return this.worldInfo.getObjectIDsInRange(centerBuildingId, radiusLimit).stream()
                // 使用白名单进行双重校验，彻底拦截范围内的非建筑实体（如车辆、人）
                .filter(allBuildingIdentifiers::contains)
                // 最终结果凝结为 Set 集合，交付给外层循环处理
                .collect(Collectors.toSet());
    }

    private Rectangle extractBoundingBox(EntityID id) {
        // 将原始 ID 对应的实体强制断言为具有面属性的 Area 类型
        Area spatialArea = (Area) this.worldInfo.getEntity(id);
        // 从实体的基础几何属性中，剥离出其外接的 AWT 矩形边界
        return spatialArea.getShape().getBounds();
    }


    // ---------------------------------------------------------
    // 构造器与环境配置参数
    // ---------------------------------------------------------

    public NeighborBuildingSearch(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager mm, DevelopData dd) {
        // 向基类传递仿真平台下发的核心运行上下文
        super(ai, wi, si, mm, dd);
    }

    // 拓扑范围扩散乘数：数值越大，判定两建筑相邻的标准越宽松
    private static final double PROXIMITY_MULTIPLIER = 1.5;

    // 核心状态字典：持久化存储“中心建筑”与“紧邻建筑群”的无向图映射
    private final Map<EntityID, Set<EntityID>> buildingNeighborsMap = new HashMap<>();

}
