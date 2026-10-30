package UESTC.module.algorithm;

import UESTC.module.util.AgentTelemetry;
import adf.core.component.module.algorithm.*;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.communication.MessageManager;
import rescuecore2.worldmodel.*;
import rescuecore2.standard.entities.*;
import rescuecore2.misc.geometry.*;
import org.locationtech.jts.geom.*;

import java.util.*;
import java.util.stream.*;

import static java.util.stream.Collectors.*;

public class NavigableWayConstructor extends PathPlanning {

  /**
   * 返回路径结果
   */
  @Override
  public List<EntityID> getResult() {
    // 返回当前路径结果
    return this.result;
  }

  /**
   * 设置起点
   */
  @Override
  public PathPlanning setFrom(EntityID id) {
    // 保存起点
    this.from = id;

    // 返回当前对象
    return this;
  }

  /**
   * 收集发生变化的道路区域
   */
  private Collection<Area> collectChangedRoads(Collection<EntityID> ids) {
    // 将变化实体转换为道路区域集合
    return ids.stream()
        .map(this.worldInfo::getEntity)
        .filter(Road.class::isInstance)
        .map(Road.class::cast)
        .collect(toList());
  }

  /**
   * 更新信息
   */
  @Override
  public PathPlanning updateInfo(MessageManager mm) {
    // 调用父类更新逻辑
    super.updateInfo(mm);

    // 仅在第一次更新时处理
    if (this.getCountUpdateInfo() > 1) {
      return this;
    }

    // 读取变化实体集合
    final Set<EntityID> changed = this.worldInfo.getChanged().getChangedEntities();

    // 逐个同步道路边状态
    for (Area area : this.collectChangedRoads(changed)) {
      this.syncBlockedEdges(area);
    }

    // 返回当前对象
    return this;
  }

  /**
   * 判断区域是否属于高速通道
   */
  private boolean belongsToHighway(EntityID id) {
    // 获取当前时间
    final int time = this.agentInfo.getTime();

    // 初始阶段不启用高速判断
    if (time < 30) {
      return false;
    }

    // 根据聚类模块判断是否属于高速通道
    return this.highways.getClusterIndex(id) >= 0;
  }

  // 当前规划结果
  private List<EntityID> result;

  // 起点实体
  private EntityID from;

  // 终点实体
  private EntityID destination;

  // 无效边记录表
  private Map<EntityID, Set<DataPair<EntityID>>> invalidz = new HashMap<>();

  // 无效边惩罚系数
  private static final double INVALID_PENALTY = 15.0;

  // 高速通道模块
  private StaticClustering highways;

  // 移动失败模块
  private Clustering failedMove;

  // 被困行人模块
  private Clustering stuckedHumans;

  /**
   * 构造函数
   */
  public NavigableWayConstructor(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    // 调用父类构造逻辑
    super(ai, wi, si, mm, dd);

    // 获取高速通道模块
    this.highways = mm.getModule("UESTC.Algorithm.NavigableWayConstructor.Highways");

    // 注册高速通道模块
    this.registerModule(this.highways);

    // 获取移动失败模块
    this.failedMove = mm.getModule("UESTC.Algorithm.NavigableWayConstructor.FailedMove");

    // 注册移动失败模块
    this.registerModule(this.failedMove);

    // 获取被困行人模块
    this.stuckedHumans = mm.getModule("UESTC.Algorithm.NavigableWayConstructor.StuckHumans");

    // 注册被困行人模块
    this.registerModule(this.stuckedHumans);
  }

  /**
   * 计算启发值
   */
  private double estimate(EntityID id) {
    // 返回当前节点到目标节点的估计距离
    return this.worldInfo.getDistance(id, this.destination);
  }

  /**
   * 设置目标集合
   */
  @Override
  public PathPlanning setDestination(Collection<EntityID> destinations) {
    // 若目标集合为空则清空目标
    if (destinations == null) {
      this.destination = null;
      return this;
    }

    // 初始化最近目标
    EntityID nearest = null;

    // 初始化最短距离
    int best = Integer.MAX_VALUE;

    // 获取自身ID
    final EntityID me = this.agentInfo.getID();

    // 遍历所有目标点
    for (EntityID id : destinations) {
      // 读取到目标的距离
      int d = this.worldInfo.getDistance(id, me);

      // 更新最近目标
      if (nearest == null || d < best) {
        nearest = id;
        best = d;
      }
    }

    // 保存最终目标
    this.destination = nearest;

    // 返回当前对象
    return this;
  }

  /**
   * 计算路径规划
   */
  @Override
  public PathPlanning calc() {
    long started = System.nanoTime();
    // 清空旧结果
    this.result = null;

    // 执行 A* 搜索
    this.result = HeuristicSearchBase.find(new SearchNode(this.from), this.destination);
    AgentTelemetry.path(this.agentInfo, this.worldInfo, "NavigableWayConstructor",
        this.from, this.destination, this.result, System.nanoTime() - started);

    // 返回当前对象
    return this;
  }

  /**
   * 获取当前位置点
   */
  private Point2D readCurrentPoint() {
    // 读取横坐标
    final double x = this.agentInfo.getX();

    // 读取纵坐标
    final double y = this.agentInfo.getY();

    // 构造当前位置点
    return new Point2D(x, y);
  }

  /**
   * 同步道路中的无效边信息
   */
  private void syncBlockedEdges(Area area) {
    // 读取区域ID
    final EntityID id = area.getID();

    // 读取障碍物集合
    final Collection<Blockade> blockades = this.worldInfo.getBlockades(id);

    // 计算可通行区域
    final Geometry passable = SpatialMorphologyCore.computePassable(area, blockades);

    // 构造无效边集合
    final Set<DataPair<EntityID>> invalidEdges = this.enumerateEdges(area)
        .stream()
        .filter(edge -> !this.canPass(area, passable, edge))
        .collect(toSet());

    // 更新无效边记录
    this.invalidz.put(id, invalidEdges);

    // 若无障碍物则移除记录
    if (blockades.isEmpty()) {
      this.invalidz.remove(id);
    }
  }

  /**
   * 枚举区域中的候选边
   */
  private Set<DataPair<EntityID>> enumerateEdges(Area area) {
    // 读取邻居集合
    final List<EntityID> neighbors = area.getNeighbours();

    // 读取邻居数量
    final int n = neighbors.size();

    // 读取当前区域ID
    final EntityID id = area.getID();

    // 判断是否为当前位置
    final boolean isCurrentPosition = id.equals(this.agentInfo.getPosition());

    // 初始化边集合
    Set<DataPair<EntityID>> edges = new HashSet<>();

    // 生成邻居两两之间的边
    for (int i = 0; i < n; ++i) {
      for (int j = i + 1; j < n; ++j) {
        edges.add(new DataPair<>(neighbors.get(i), neighbors.get(j)));
      }
    }

    // 若为当前位置则补充与当前位置相关的边
    if (isCurrentPosition) {
      for (EntityID neighbor : neighbors) {
        edges.add(new DataPair<>(neighbor, null));
      }
    }

    // 返回边集合
    return edges;
  }

  /**
   * 验证边是否可通行
   */
  private boolean canPass(Area area, Geometry passable, DataPair<EntityID> edge) {
    // 构造当前位置线段
    final Line2D currentLine = new Line2D(this.readCurrentPoint(), this.readCurrentPoint());

    // 构造第一条边线段
    final Line2D line1 = edge.elem1 == null ? currentLine : area.getEdgeTo(edge.elem1).getLine();

    // 构造第二条边线段
    final Line2D line2 = edge.elem2 == null ? currentLine : area.getEdgeTo(edge.elem2).getLine();

    // 调用几何模块验证通行性
    return SpatialMorphologyCore.validate(passable, line1, line2);
  }

  /**
   * 判断边是否被标记为无效
   */
  private boolean isForbidden(EntityID areaId, EntityID prev, EntityID next) {
    // 若区域不存在记录则视为有效
    if (!this.invalidz.containsKey(areaId)) {
      return false;
    }

    // 读取无效边集合
    final Set<DataPair<EntityID>> invalids = this.invalidz.get(areaId);

    // 判断正向边是否无效
    if (invalids.contains(new DataPair<>(prev, next))) {
      return true;
    }

    // 判断反向边是否无效
    if (invalids.contains(new DataPair<>(next, prev))) {
      return true;
    }

    // 默认有效
    return false;
  }

  /**
   * 判断启发节点到目标边是否无效
   */
  private boolean isForbidden(EntityID next, HeuristicSearchBase.HeuristicNode ancestor) {
    // 读取当前节点ID
    final EntityID current = ancestor.getID();

    // 读取祖先节点ID
    final EntityID prev = ancestor.getAncestor();

    // 调用无效边判断
    return this.isForbidden(current, prev, next);
  }

  /**
   * 读取邻居集合
   */
  private Collection<EntityID> loadNeighbors(EntityID id) {
    // 获取区域实体
    final Area area = (Area) this.worldInfo.getEntity(id);

    // 返回邻居集合
    return area.getNeighbours();
  }

  /**
   * 读取两节点间距离
   */
  private double loadDistance(EntityID id, EntityID ancestor) {
    // 从世界模型中获取距离
    return this.worldInfo.getDistance(id, ancestor);
  }

  /**
   * 计算实际代价
   */
  private double movementCost(EntityID id, HeuristicSearchBase.HeuristicNode ancestor) {
    // 计算基础距离
    double d = loadDistance(id, ancestor.getID());

    // 计算无效边惩罚
    double penalty = isForbidden(id, ancestor) ? INVALID_PENALTY : 1.0;

    // 若上一节点位于高速通道则使用基础倍率
    if (belongsToHighway(ancestor.getID())) {
      return d * penalty;
    }

    // 否则放大非高速通道成本
    return d * 3.0 * penalty;
  }

  /**
   * 搜索节点定义
   */
  private class SearchNode extends HeuristicSearchBase.HeuristicNode {

    /**
     * 创建根节点
     */
    public SearchNode(EntityID id) {
      // 初始化根节点参数
      super(id, null, 0.0, estimate(id));
    }

    /**
     * 创建扩展节点
     */
    public SearchNode(EntityID id, SearchNode ancestor, double gvalue, double hvalue) {
      // 初始化扩展节点参数
      super(id, ancestor.getID(), gvalue, hvalue);
    }

    /**
     * 构建下一个节点
     */
    public SearchNode next(EntityID id) {
      // 计算新的实际代价
      final double gvalue = this.getGvalue() + movementCost(id, this);

      // 计算新的启发代价
      final double hvalue = estimate(id);

      // 返回新的搜索节点
      return new SearchNode(id, this, gvalue, hvalue);
    }

    /**
     * 收集邻居节点
     */
    @Override
    public Collection<HeuristicSearchBase.HeuristicNode> gatherNeighbors() {
      // 获取当前节点的邻居实体
      final Collection<EntityID> neighbors = loadNeighbors(this.getID());

      // 转换为搜索节点集合
      return neighbors.stream().map(this::next).collect(toList());
    }
  }
}
