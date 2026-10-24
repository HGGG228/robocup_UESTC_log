package UESTC.module.algorithm;

import rescuecore2.worldmodel.EntityID;
import java.util.*;

/**
 * KMeans++ 聚类模块
 * 用于根据目标点坐标生成聚类结果
 */
public class ProbabilisticSeedInitializer {

  /**
   * 执行聚类过程
   */
  public void execute(int rep) {
    // 初始化聚类中心
    this.result = createInitialClusters(this.targets, this.xs, this.ys, this.n);

    // 按给定轮数执行迭代
    for (int round = 0; round < rep; ++round) {
      // 清空全部聚类成员
      clearAllClusters();

      // 重新分配全部目标点
      assignAllTargets();

      // 刷新全部聚类中心
      updateAllCenters();
    }
  }

  /**
   * 返回聚类数量
   */
  public int getClusterNumber() {
    // 返回当前聚类总数
    return this.result.length;
  }

  /**
   * 返回指定聚类中心的 X 坐标
   */
  public double getClusterX(int i) {
    // 获取目标聚类中心横坐标
    return this.result[i].readCenterX();
  }

  /**
   * 返回指定聚类中心的 Y 坐标
   */
  public double getClusterY(int i) {
    // 获取目标聚类中心纵坐标
    return this.result[i].readCenterY();
  }

  /**
   * 返回指定聚类中的成员集合
   */
  public Collection<EntityID> getClusterMembers(int i) {
    // 返回目标聚类的成员列表
    return this.result[i].exportMembers();
  }

  /**
   * 清空所有聚类成员
   */
  private void clearAllClusters() {
    // 遍历全部聚类对象
    for (Cluster cluster : this.result) {
      // 清空当前聚类成员
      cluster.eraseMembers();
    }
  }

  /**
   * 为全部目标重新分配聚类
   */
  private void assignAllTargets() {
    // 遍历全部目标点
    for (int i = 0; i < this.targets.length; ++i) {
      // 将当前目标分配到最近聚类
      pushToNearestCluster(this.result, this.targets[i], this.xs[i], this.ys[i]);
    }
  }

  /**
   * 更新所有聚类中心
   */
  private void updateAllCenters() {
    // 遍历全部聚类
    for (Cluster cluster : this.result) {
      // 更新当前聚类中心
      cluster.recomputeCenter();
    }
  }

  // 目标实体数组
  private EntityID[] targets;

  // 横坐标数组
  private double[] xs;

  // 纵坐标数组
  private double[] ys;

  // 聚类结果数组
  private Cluster[] result;

  // 聚类数量
  private int n;

  // 固定随机种子
  private static final int COMMON_SEED = 123456789;

  /**
   * 使用目标点数组构造聚类器
   */
  public ProbabilisticSeedInitializer(EntityID[] targets, double[] xs, double[] ys, int n) {
    // 保存目标实体
    this.targets = targets;

    // 保存横坐标数组
    this.xs = xs;

    // 保存纵坐标数组
    this.ys = ys;

    // 保存聚类数量
    this.n = n;
  }

  /**
   * 使用已有成员集合构造聚类器
   */
  public ProbabilisticSeedInitializer(int n, List<Collection<EntityID>> memberz) {
    // 初始化结果数组
    this.result = new Cluster[n];

    // 保存聚类数量
    this.n = n;

    // 根据已有成员恢复聚类
    for (int i = 0; i < n; ++i) {
      Collection<EntityID> members = memberz.get(i);
      this.result[i] = new Cluster(members);
    }
  }

  /**
   * 聚类内部类
   * 用于保存聚类成员和中心坐标
   */
  private static class Cluster {

    // 聚类中心 X 坐标
    private double cx = 0.0;

    // 聚类中心 Y 坐标
    private double cy = 0.0;

    // 成员集合
    private Collection<EntityID> members = new LinkedList<>();

    // 横坐标累加值
    private double sumx = 0.0;

    // 纵坐标累加值
    private double sumy = 0.0;

    /**
     * 默认构造函数
     */
    public Cluster() {
    }

    /**
     * 使用已有成员集合构造聚类
     */
    public Cluster(Collection<EntityID> members) {
      // 保存成员集合
      this.members = members;
    }

    /**
     * 记录一个新成员
     */
    public void recordMember(EntityID id, double x, double y) {
      // 将成员加入集合
      this.members.add(id);

      // 累加横坐标
      this.sumx += x;

      // 累加纵坐标
      this.sumy += y;
    }

    /**
     * 返回成员集合副本
     */
    public Collection<EntityID> exportMembers() {
      // 返回成员列表拷贝
      return new ArrayList<>(this.members);
    }

    /**
     * 清空全部成员
     */
    public void eraseMembers() {
      // 清空成员集合
      this.members.clear();

      // 重置横坐标和
      this.sumx = 0.0;

      // 重置纵坐标和
      this.sumy = 0.0;
    }

    /**
     * 重新计算中心位置
     */
    public void recomputeCenter() {
      // 若没有成员则不更新
      if (this.members.isEmpty()) {
        return;
      }

      // 根据均值更新横坐标
      this.cx = this.sumx / this.members.size();

      // 根据均值更新纵坐标
      this.cy = this.sumy / this.members.size();
    }

    /**
     * 返回中心 X 坐标
     */
    public double readCenterX() {
      // 返回横坐标
      return this.cx;
    }

    /**
     * 返回中心 Y 坐标
     */
    public double readCenterY() {
      // 返回纵坐标
      return this.cy;
    }
  }

  /**
   * 初始化聚类中心
   */
  private static Cluster[] createInitialClusters(
      EntityID[] targets, double[] xs, double[] ys, int n) {

    // 创建空聚类数组
    Cluster[] clusters = createBlankClusters(n);

    // 初始化随机生成器
    Random random = new Random(COMMON_SEED);

    // 随机选取第一个中心点
    int first = random.nextInt(targets.length);

    // 将第一个点加入首个聚类
    clusters[0].recordMember(targets[first], xs[first], ys[first]);

    // 更新首个聚类中心
    clusters[0].recomputeCenter();

    // 创建已选标记数组
    boolean[] selected = new boolean[targets.length];

    // 标记第一个中心已被使用
    selected[first] = true;

    // 逐个生成其余中心
    for (int i = 0; i < n - 1; ++i) {
      // 计算当前采样权重
      double[] weights = createWeightArray(targets, xs, ys, clusters[i], selected);

      // 抽取新的中心索引
      int chosen = chooseIndex(weights, selected, random);

      // 将新点加入下一个聚类
      clusters[i + 1].recordMember(targets[chosen], xs[chosen], ys[chosen]);

      // 更新下一个聚类中心
      clusters[i + 1].recomputeCenter();

      // 标记该点已使用
      selected[chosen] = true;
    }

    // 返回初始化完成的聚类数组
    return clusters;
  }

  /**
   * 创建空聚类数组
   */
  private static Cluster[] createBlankClusters(int n) {
    // 初始化聚类数组
    Cluster[] clusters = new Cluster[n];

    // 逐个创建空聚类对象
    for (int i = 0; i < n; ++i) {
      clusters[i] = new Cluster();
    }

    // 返回聚类数组
    return clusters;
  }

  /**
   * 构造当前轮次的权重数组
   */
  private static double[] createWeightArray(
      EntityID[] targets, double[] xs, double[] ys, Cluster anchor, boolean[] selected) {

    // 创建权重数组
    double[] weights = new double[targets.length];

    // 初始化权重总和
    double total = 0.0;

    // 获取当前中心坐标
    double cx = anchor.readCenterX();
    double cy = anchor.readCenterY();

    // 计算每个候选点的距离权重
    for (int j = 0; j < targets.length; ++j) {
      // 已选中的点直接跳过
      if (selected[j]) {
        continue;
      }

      // 计算当前点到中心的距离
      double d = Math.hypot(xs[j] - cx, ys[j] - cy);

      // 保存当前权重
      weights[j] = d;

      // 累加权重总和
      total += d;
    }

    // 执行归一化
    for (int j = 0; j < targets.length; ++j) {
      weights[j] /= total;
    }

    // 返回权重数组
    return weights;
  }

  /**
   * 按概率选择索引
   */
  private static int chooseIndex(double[] weights, boolean[] selected, Random random) {
    // 生成随机概率值
    double p = random.nextDouble();

    // 初始化累计概率
    double acc = 0.0;

    // 遍历所有候选点
    for (int j = 0; j < weights.length; ++j) {
      // 已选点跳过
      if (selected[j]) {
        continue;
      }

      // 累加当前权重
      acc += weights[j];

      // 命中当前区间则返回
      if (p <= acc) {
        return j;
      }
    }

    // 返回兜底索引
    return 0;
  }

  /**
   * 将单个目标放入最近聚类
   */
  private static void pushToNearestCluster(Cluster[] clusters, EntityID id, double x, double y) {
    // 初始化最近聚类
    Cluster nearest = null;

    // 初始化最近距离
    double bestDistance = Double.MAX_VALUE;

    // 遍历全部聚类中心
    for (Cluster cluster : clusters) {
      // 计算当前距离
      double d = Math.hypot(cluster.readCenterX() - x, cluster.readCenterY() - y);

      // 更新最近聚类
      if (d < bestDistance) {
        bestDistance = d;
        nearest = cluster;
      }
    }

    // 若不存在最近聚类则抛出异常
    if (nearest == null) {
      throw new IllegalArgumentException("No cluster found");
    }

    // 将当前目标加入最近聚类
    nearest.recordMember(id, x, y);
  }
}
