package UESTC.module.algorithm;

import adf.core.component.module.algorithm.PathPlanning;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.communication.MessageManager;
import rescuecore2.worldmodel.*;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.*;

import java.util.*;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;

public class HeuristicRouteResolver extends PathPlanning {

  /**
   * 更新信息
   */
  @Override
  public PathPlanning updateInfo(MessageManager mm) {
    // 调用父类更新逻辑
    super.updateInfo(mm);

    // 超过首次更新后直接返回
    if (this.getCountUpdateInfo() > 1) {
      return this;
    }

    // 返回当前对象
    return this;
  }

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
   * 设置目标点集合
   */
  @Override
  public PathPlanning setDestination(Collection<EntityID> destinations) {
    // 若目标集合为空则清空目标
    if (destinations == null) {
      this.destination = null;
      return this;
    }

    // 选择距离当前 agent 最近的目标点
    this.destination = chooseNearestDestination(destinations);

    // 返回当前对象
    return this;
  }

  /**
   * 从候选目标中选择最近目标
   */
  private EntityID chooseNearestDestination(Collection<EntityID> destinations) {
    // 获取当前 agent 的ID
    final EntityID me = this.agentInfo.getID();

    // 选择距离最小的目标点
    return destinations
        .stream()
        .min(comparing(id -> this.worldInfo.getDistance(id, me)))
        .orElse(null);
  }

  // 当前路径结果
  private List<EntityID> result;

  // 起点
  private EntityID from;

  // 终点
  private EntityID destination;

  /**
   * 构造函数
   */
  public HeuristicRouteResolver(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    // 调用父类构造逻辑
    super(ai, wi, si, mm, dd);
  }

  /**
   * 执行路径规划
   */
  @Override
  public PathPlanning calc() {
    // 先清空旧结果
    this.result = null;

    // 执行 A* 搜索
    this.result = HeuristicSearchBase.find(new SearchNode(this.from), this.destination);

    // 返回当前对象
    return this;
  }

  /**
   * A* 搜索节点
   */
  private class SearchNode extends HeuristicSearchBase.HeuristicNode {

    /**
     * 创建起始节点
     */
    public SearchNode(EntityID id) {
      // 初始化起始节点参数
      super(id, null, 0.0, estimateCost(id));
    }

    /**
     * 创建扩展节点
     */
    public SearchNode(EntityID id, SearchNode ancestor, double gvalue, double hvalue) {
      // 初始化扩展节点参数
      super(id, ancestor.getID(), gvalue, hvalue);
    }

    /**
     * 生成邻接节点
     */
    public SearchNode expand(EntityID next) {
      // 计算新的实际代价
      final double nextG = this.getGvalue() + stepCost(next, this);

      // 计算新的启发代价
      final double nextH = estimateCost(next);

      // 返回新的搜索节点
      return new SearchNode(next, this, nextG, nextH);
    }

    /**
     * 收集邻居节点
     */
    @Override
    public Collection<HeuristicSearchBase.HeuristicNode> gatherNeighbors() {
      // 获取当前节点的邻居实体
      final Collection<EntityID> around = collectNeighbors(this.getID());

      // 将邻居转换为搜索节点
      return around.stream().map(this::expand).collect(toList());
    }
  }

  /**
   * 计算单步代价
   */
  private double stepCost(EntityID next, HeuristicSearchBase.HeuristicNode previous) {
    // 计算两节点之间的距离
    return readDistance(next, previous.getID());
  }

  /**
   * 读取邻接区域
   */
  private Collection<EntityID> collectNeighbors(EntityID id) {
    // 获取区域实体
    final Area area = fetchArea(id);

    // 返回邻居集合
    return area.getNeighbours();
  }

  /**
   * 读取区域实体
   */
  private Area fetchArea(EntityID id) {
    // 从世界模型中读取区域
    return (Area) this.worldInfo.getEntity(id);
  }

  /**
   * 计算启发值
   */
  private double estimateCost(EntityID id) {
    // 组合基础距离与附加评价
    return this.worldInfo.getDistance(id, this.destination) + bonusScore(id) / 2;
  }

  /**
   * 计算附加评价项
   */
  private double bonusScore(EntityID id) {
    // 获取目标区域
    Area area = fetchArea(id);

    // 获取相邻区域列表
    List<EntityID> neighbors = area.getNeighbours();

    // 获取邻居数量
    int count = neighbors.size();

    // 初始化边长累积值
    double edgeSum = 0.0;

    // 统计所有相邻边长度
    for (EntityID neighbor : neighbors) {
      Edge edge = area.getEdgeTo(neighbor);
      edgeSum += Math.hypot(
          edge.getStartX() - edge.getEndX(),
          edge.getStartY() - edge.getEndY());
    }

    // 根据面积和平均边长构造评价
    return polygonArea(area) / (edgeSum / count);
  }

  /**
   * 计算区域面积
   */
  private double polygonArea(Area area) {
    // 读取顶点数组
    final int[] apexes = area.getApexList();

    // 计算顶点数量
    final int vertexCount = apexes.length / 2;

    // 至少需要三个顶点
    if (vertexCount < 3) {
      throw new IllegalArgumentException("Not enough apexes to calculate area");
    }

    // 初始化面积值
    double totalArea = 0.0;

    // 初始化前两个点
    Point2D previous = new Point2D(apexes[0], apexes[1]);
    Point2D current = new Point2D(apexes[2], apexes[3]);

    // 逐步拆分三角形并累加面积
    for (int i = 3; i < apexes.length; i += 2) {
      Point2D next = new Point2D(apexes[i], apexes[i + 1]);
      totalArea += triangleArea(previous, current, next);
      previous = current;
      current = next;
    }

    // 闭合多边形并补上最后一个三角形面积
    if (vertexCount > 1) {
      Point2D first = new Point2D(apexes[0], apexes[1]);
      totalArea += triangleArea(previous, current, first);
    }

    // 返回总面积
    return totalArea;
  }

  /**
   * 计算两个区域之间的距离
   */
  private double readDistance(EntityID id, EntityID ancestor) {
    // 从世界模型中读取距离
    return this.worldInfo.getDistance(id, ancestor);
  }

  /**
   * 计算三角形面积
   */
  private double triangleArea(Point2D a, Point2D b, Point2D c) {
    // 计算三条边长度
    double ab = pointDistance(a, b);
    double bc = pointDistance(b, c);
    double ca = pointDistance(c, a);

    // 计算半周长
    double s = (ab + bc + ca) / 2;

    // 返回海伦公式结果
    return Math.sqrt(s * (s - ab) * (s - bc) * (s - ca));
  }

  /**
   * 计算两个点之间的距离
   */
  private double pointDistance(Point2D a, Point2D b) {
    // 返回两点欧氏距离
    return Math.hypot(a.getX() - b.getX(), a.getY() - b.getY());
  }
}
