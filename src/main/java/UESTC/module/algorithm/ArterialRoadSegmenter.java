package UESTC.module.algorithm;

import adf.core.component.module.algorithm.*;
import adf.core.agent.info.*;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import rescuecore2.standard.entities.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import rescuecore2.worldmodel.EntityID;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 主干道路聚类模块
 * 用于生成关键路径集合
 */
public class ArterialRoadSegmenter extends StaticClustering {

  // 路径规划模块
  private PathPlanning planner;

  // 主干道路集合
  private Set<EntityID> highwaySet = new HashSet<>();

  // 采样点数量
  private static final int SAMPLE_COUNT = 10;

  // 预计算数据键
  private static final String CACHE_KEY = "UESTC.module.algorithm.Highways.highways";

  /**
   * 返回聚类数量
   */
  @Override
  public int getClusterNumber() {
    // 当前模块固定只维护一个聚类
    return 1;
  }

  /**
   * 根据实体对象获取聚类编号
   */
  @Override
  public int getClusterIndex(StandardEntity entity) {
    // 转换为实体ID后继续判断
    return resolveMembership(entity.getID());
  }

  /**
   * 根据实体ID获取聚类编号
   */
  @Override
  public int getClusterIndex(EntityID id) {
    // 直接判断是否属于主干道路集合
    return resolveMembership(id);
  }

  /**
   * 判断实体是否属于主干道路集合
   */
  private int resolveMembership(EntityID id) {
    // 若包含在集合中则返回唯一聚类编号
    return this.highwaySet.contains(id) ? 0 : -1;
  }

  /**
   * 获取聚类中的实体ID集合
   */
  @Override
  public Collection<EntityID> getClusterEntityIDs(int i) {
    // 仅当索引为0时返回主干道路集合
    return i == 0 ? new HashSet<>(this.highwaySet) : null;
  }

  /**
   * 获取聚类中的实体集合
   */
  @Override
  public Collection<StandardEntity> getClusterEntities(int i) {
    // 先获取聚类中的实体ID集合
    Collection<EntityID> ids = this.getClusterEntityIDs(i);

    // 若不存在则返回空值
    if (ids == null) {
      return null;
    }

    // 将实体ID转换为实体对象
    return ids.stream()
        .map(this.worldInfo::getEntity)
        .collect(Collectors.toList());
  }

  /**
   * 聚类主流程
   */
  @Override
  public Clustering calc() {
    // 当前模块在运行阶段不额外执行计算
    return this;
  }

  /**
   * 信息更新阶段
   */
  @Override
  public Clustering updateInfo(MessageManager mm) {
    // 调用父类更新逻辑
    super.updateInfo(mm);

    // 控制仅在初次阶段执行
    if (this.getCountUpdateInfo() > 1) {
      return this;
    }

    // 返回当前对象
    return this;
  }

  /**
   * 构造函数
   */
  public ArterialRoadSegmenter(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    // 调用父类构造函数
    super(ai, wi, si, mm, dd);

    // 获取路径规划模块
    this.planner = mm.getModule("UESTC.Algorithm.Highways.PathPlanning");

    // 注册依赖模块
    this.registerModule(this.planner);
  }

  /**
   * 预计算阶段
   */
  @Override
  public Clustering precompute(PrecomputeData pd) {
    // 调用父类预计算逻辑
    super.precompute(pd);

    // 控制预计算只执行一次
    if (this.getCountPrecompute() > 1) {
      return this;
    }

    // 构建主干道路集合
    assembleHighwayGraph();

    // 写入预计算数据
    pd.setEntityIDList(CACHE_KEY, new ArrayList<>(this.highwaySet));

    // 返回当前对象
    return this;
  }

  /**
   * 恢复阶段
   */
  @Override
  public Clustering resume(PrecomputeData pd) {
    // 调用父类恢复逻辑
    super.resume(pd);

    // 控制恢复只执行一次
    if (this.getCountResume() > 1) {
      return this;
    }

    // 从预计算数据中恢复主干道路集合
    this.highwaySet = new HashSet<>(pd.getEntityIDList(CACHE_KEY));

    // 返回当前对象
    return this;
  }

  /**
   * 准备阶段
   */
  @Override
  public Clustering preparate() {
    // 调用父类准备逻辑
    super.preparate();

    // 控制准备只执行一次
    if (this.getCountPreparate() > 1) {
      return this;
    }

    // 返回当前对象
    return this;
  }

  /**
   * 构建主干道路集合
   */
  private void assembleHighwayGraph() {
    // 获取采样点列表
    List<EntityID> seeds = new ArrayList<>(extractSeeds(SAMPLE_COUNT));

    // 遍历所有采样点对
    for (int i = 0; i < SAMPLE_COUNT; ++i) {
      for (int j = i + 1; j < SAMPLE_COUNT; ++j) {
        // 连接两个采样点并合并路径
        appendPlannedRoute(seeds.get(i), seeds.get(j));
      }
    }
  }

  /**
   * 连接两个采样点并合并路径结果
   */
  private void appendPlannedRoute(EntityID from, EntityID to) {
    // 设置路径起点
    this.planner.setFrom(from);

    // 设置路径终点
    this.planner.setDestination(to);

    // 执行路径规划
    this.planner.calc();

    // 合并路径结果到主干道路集合
    this.highwaySet.addAll(this.planner.getResult());
  }

  /**
   * 提取采样点集合
   */
  private Set<EntityID> extractSeeds(int n) {
    // 创建结果集合
    Set<EntityID> result = new HashSet<>();

    // 获取候选实体集合
    List<StandardEntity> entities = new ArrayList<>(
        this.worldInfo.getEntitiesOfType(
            BUILDING, GAS_STATION, REFUGE,
            FIRE_STATION, AMBULANCE_CENTRE, POLICE_OFFICE));

    // 按实体ID升序排序
    entities.sort(Comparator.comparing(e -> e.getID().getValue()));

    // 获取候选实体数量
    int count = entities.size();

    // 初始化聚类输入数组
    EntityID[] idBuffer = new EntityID[count];
    double[] xBuffer = new double[count];
    double[] yBuffer = new double[count];

    // 填充坐标与实体ID数据
    for (int i = 0; i < count; i++) {
      Area area = (Area) entities.get(i);

      idBuffer[i] = area.getID();
      xBuffer[i] = area.getX();
      yBuffer[i] = area.getY();
    }

    // 创建聚类模型
    ProbabilisticSeedInitializer model = new ProbabilisticSeedInitializer(idBuffer, xBuffer, yBuffer, n);

    // 执行KMeans聚类
    model.execute(10);

    // 初始化聚类遍历索引
    int index = 0;

    // 遍历每个聚类结果
    while (index < n) {
      // 获取当前聚类中心坐标
      double centerX = model.getClusterX(index);
      double centerY = model.getClusterY(index);

      // 获取当前聚类成员集合
      Collection<EntityID> members = model.getClusterMembers(index);

      // 初始化当前聚类中最优实体
      Area best = null;
      double bestDist = Double.MAX_VALUE;

      // 遍历成员并寻找距离中心最近的实体
      for (EntityID id : members) {
        Area area = (Area) this.worldInfo.getEntity(id);

        double dx = area.getX() - centerX;
        double dy = area.getY() - centerY;
        double dist = Math.hypot(dx, dy);

        if (dist < bestDist) {
          bestDist = dist;
          best = area;
        }
      }

      // 将当前聚类代表点加入结果集合
      if (best != null) {
        result.add(best.getID());
      }

      // 移动到下一个聚类
      index++;
    }

    // 返回采样结果
    return result;
  }
}
