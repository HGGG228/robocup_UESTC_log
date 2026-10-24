package UESTC.module.algorithm;

import adf.core.agent.info.*;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.StaticClustering;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.precompute.PrecomputeData;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.standard.entities.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import java.util.*;

/**
 * KMeans聚类模块
 * 用于将实体划分到不同聚类中
 */
public class CentroidDivisionSystem extends StaticClustering {

  // 聚类分配结果
  private Map<EntityID, Integer> assignment = new HashMap<>();

  // 聚类器
  private ProbabilisticSeedInitializer clusterer;

  // 聚类数量
  private int clusterCount = 0;

  // 当前类型
  private StandardEntityURN urn;

  /**
   * 获取聚类数量
   */
  @Override
  public int getClusterNumber() {
    // 返回当前聚类数
    return this.clusterCount;
  }

  /**
   * 根据实体获取聚类编号
   */
  @Override
  public int getClusterIndex(StandardEntity entity) {
    // 转为ID处理
    return resolve(entity.getID());
  }

  /**
   * 根据ID获取聚类编号
   */
  @Override
  public int getClusterIndex(EntityID id) {
    // 判断是否存在
    return resolve(id);
  }

  /**
   * 内部查找逻辑
   */
  private int resolve(EntityID id) {
    // 若不存在返回-1
    if (!this.assignment.containsKey(id)) {
      return -1;
    }
    // 返回分配结果
    return this.assignment.get(id);
  }

  /**
   * 获取聚类实体ID集合
   */
  @Override
  public Collection<EntityID> getClusterEntityIDs(int i) {
    // 检查范围
    if (i < 0 || i >= this.clusterCount) {
      return null;
    }
    // 返回对应成员
    return this.clusterer.getClusterMembers(i);
  }

  /**
   * 获取聚类实体集合
   */
  @Override
  public Collection<StandardEntity> getClusterEntities(int i) {

    // 获取ID集合
    Collection<EntityID> ids = this.getClusterEntityIDs(i);

    // 判空
    if (ids == null) {
      return null;
    }

    // 构建返回集合
    Collection<StandardEntity> result = new ArrayList<>(ids.size());

    // 转换为实体
    for (EntityID id : ids) {
      result.add(this.worldInfo.getEntity(id));
    }

    return result;
  }

  /**
   * 主流程
   */
  @Override
  public Clustering calc() {
    return this;
  }

  /**
   * 构造函数
   */
  public CentroidDivisionSystem(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {

    // 调用父类构造
    super(ai, wi, si, mm, dd);

    // 初始化类型
    this.urn = this.agentInfo.me().getStandardURN();
  }

  /**
   * 初始化聚类数量
   */
  private void determineClusterCount() {

    // 根据类型选择数量
    switch (this.urn) {

      case FIRE_BRIGADE:
        this.clusterCount = this.scenarioInfo.getScenarioAgentsFb();
        break;

      case POLICE_FORCE:
        this.clusterCount = this.scenarioInfo.getScenarioAgentsPf();
        break;

      case AMBULANCE_TEAM:
        this.clusterCount = this.scenarioInfo.getScenarioAgentsAt();
        break;

      default:
        this.clusterCount = 0;
    }
  }

  /**
   * 构建聚类器
   */
  private void buildModel() {

    // 获取所有区域实体
    List<Area> areas = this.worldInfo.getEntitiesOfType(
        ROAD, HYDRANT,
        BUILDING, GAS_STATION,
        REFUGE,
        POLICE_OFFICE, FIRE_STATION, AMBULANCE_CENTRE)
        .stream()
        .filter(e -> e instanceof Area)
        .map(e -> (Area) e)
        .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

    // 构建ID数组
    EntityID[] ids = areas.stream().map(Area::getID).toArray(EntityID[]::new);

    // 构建X数组
    double[] xs = areas.stream().mapToDouble(Area::getX).toArray();

    // 构建Y数组
    double[] ys = areas.stream().mapToDouble(Area::getY).toArray();

    // 初始化聚类器
    this.clusterer = new ProbabilisticSeedInitializer(ids, xs, ys, this.clusterCount);
  }

  /**
   * 执行匹配分配
   */
  private void assignClusters() {

    // 获取所有agent
    List<StandardEntity> agents = new ArrayList<>(
        this.worldInfo.getEntitiesOfType(this.urn));

    // 按ID排序
    agents.sort(Comparator.comparing(a -> a.getID().getValue()));

    // 初始化成本矩阵
    int[][] cost = new int[this.clusterCount][this.clusterCount];

    // 填充成本
    for (int i = 0; i < this.clusterCount; i++) {

      Human h = (Human) agents.get(i);

      double x = h.getX();
      double y = h.getY();

      for (int j = 0; j < this.clusterCount; j++) {

        double cx = this.clusterer.getClusterX(j);
        double cy = this.clusterer.getClusterY(j);

        cost[i][j] = (int) Math.hypot(cx - x, cy - y);
      }
    }

    // 执行匈牙利算法
    int[] match = AssignmentOptimizer.execute(cost);

    // 保存结果
    for (int i = 0; i < this.clusterCount; i++) {

      EntityID id = agents.get(i).getID();

      this.assignment.put(id, match[i]);
    }
  }

  /**
   * 预计算阶段
   */
  @Override
  public Clustering precompute(PrecomputeData pd) {

    // 调用父类
    super.precompute(pd);

    // 控制执行
    if (this.getCountPrecompute() > 1) {
      return this;
    }

    // 初始化
    determineClusterCount();

    // 构建模型
    buildModel();

    // 执行聚类
    this.clusterer.execute(20);

    // 执行匹配
    assignClusters();

    // 写入数据
    pd.setInteger(keyWithType("n"), this.clusterCount);

    for (EntityID id : this.assignment.keySet()) {

      int idx = this.assignment.get(id);

      Collection<EntityID> members = this.clusterer.getClusterMembers(idx);

      pd.setEntityIDList(keyWithType("m", idx), new ArrayList<>(members));
      pd.setEntityID(keyWithType("a", idx), id);
    }

    return this;
  }

  /**
   * 恢复阶段
   */
  @Override
  public Clustering resume(PrecomputeData pd) {

    super.resume(pd);

    if (this.getCountResume() > 1) {
      return this;
    }

    // 读取聚类数量
    this.clusterCount = pd.getInteger(keyWithType("n"));

    // 构建集合
    List<Collection<EntityID>> clusters = new ArrayList<>(this.clusterCount);

    for (int i = 0; i < this.clusterCount; i++) {

      List<EntityID> list = pd.getEntityIDList(keyWithType("m", i));
      EntityID agent = pd.getEntityID(keyWithType("a", i));

      clusters.add(list);
      this.assignment.put(agent, i);
    }

    // 重建聚类器
    this.clusterer = new ProbabilisticSeedInitializer(this.clusterCount, clusters);

    return this;
  }

  /**
   * 准备阶段
   */
  @Override
  public Clustering preparate() {

    super.preparate();

    if (this.getCountPreparate() > 1) {
      return this;
    }

    // 初始化
    determineClusterCount();

    // 构建模型
    buildModel();

    // 执行聚类
    this.clusterer.execute(20);

    // 执行匹配
    assignClusters();

    return this;
  }

  /**
   * 生成带类型的键
   */
  private String keyWithType(String key) {
    return key + "." + this.urn;
  }

  /**
   * 生成带索引的键
   */
  private String keyWithType(String key, int i) {
    return keyWithType(key) + "." + i;
  }
}
