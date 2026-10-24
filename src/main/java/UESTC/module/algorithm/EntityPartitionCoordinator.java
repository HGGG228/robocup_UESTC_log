package UESTC.module.algorithm;

import adf.core.component.module.algorithm.*;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.agent.communication.MessageManager;
import rescuecore2.worldmodel.*;
import rescuecore2.standard.entities.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import java.util.*;
import java.util.stream.*;

/**
 * 分组聚类模块
 * 按顺序将实体均衡分配到多个分组中
 */
public class EntityPartitionCoordinator extends DynamicClustering {

  // 当前分组数量
  private int groupNum;

  // 存储分组结果
  private List<List<EntityID>> groups;

  /**
   * 构造函数
   */
  public EntityPartitionCoordinator(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                  ModuleManager mm, DevelopData dd) {

    // 初始化父类
    super(ai, wi, si, mm, dd);

    // 初始化分组容器
    this.groups = new ArrayList<>();
  }

  /**
   * 返回分组数量
   */
  @Override
  public int getClusterNumber() {
    return this.groupNum;
  }

  /**
   * 根据实体获取分组编号
   */
  @Override
  public int getClusterIndex(StandardEntity entity) {
    return locateIndex(entity.getID());
  }

  /**
   * 根据ID查找分组编号
   */
  @Override
  public int getClusterIndex(EntityID id) {
    return locateIndex(id);
  }

  /**
   * 实际查找分组位置
   */
  private int locateIndex(EntityID id) {

    // 默认未找到
    int res = -1;

    // 遍历分组
    for (List<EntityID> g : this.groups) {
      if (g.contains(id)) {
        res = this.groups.indexOf(g);
      }
    }

    // 返回结果
    return res;
  }

  /**
   * 获取分组ID集合
   */
  @Override
  public Collection<EntityID> getClusterEntityIDs(int i) {

    // 判断索引是否合法
    if (i < 0 || i >= this.groupNum) {
      return null;
    }

    // 返回分组
    return this.groups.get(i);
  }

  /**
   * 获取分组实体集合
   */
  @Override
  public Collection<StandardEntity> getClusterEntities(int i) {

    // 检查索引
    if (i < 0 || i >= this.groupNum) {
      throw new IndexOutOfBoundsException("Cluster index out of bounds: " + i);
    }

    // 转换为实体
    return this.groups.get(i)
        .stream()
        .map(this.worldInfo::getEntity)
        .collect(Collectors.toList());
  }

  /**
   * 执行聚类
   */
  @Override
  public Clustering calc() {

    // 获取当前类型
    StandardEntityURN type = this.agentInfo.me().getStandardURN();

    // 构建数据集合
    List<EntityID> agentList = collectSorted(type);
    List<EntityID> buildingList = collectSorted(BUILDING);

    // 初始化分组
    prepareContainers(agentList);

    // 执行分配
    dispatch(agentList, buildingList);

    return this;
  }

  /**
   * 收集并排序实体ID
   */
  private List<EntityID> collectSorted(StandardEntityURN urn) {

    // 获取并排序
    return this.worldInfo.getEntityIDsOfType(urn)
        .stream()
        .sorted(Comparator.comparing(EntityID::getValue))
        .collect(Collectors.toList());
  }

  /**
   * 初始化分组容器
   */
  private void prepareContainers(Collection<EntityID> agents) {

    // 清空旧数据
    this.groups.clear();

    // 计算分组数
    this.groupNum = calcGroupSize(agents.size());

    // 创建分组
    for (int i = 0; i < this.groupNum; i++) {
      this.groups.add(new ArrayList<>());
    }
  }

  /**
   * 执行轮转分配
   */
  private void dispatch(List<EntityID> agents, List<EntityID> buildings) {

    // 指针
    int cursor = 0;

    // 循环分配
    while (!(agents.isEmpty() && buildings.isEmpty())) {

      // 控制范围
      cursor = cursor % this.groupNum;

      // 填充数据
      fill(cursor, agents, buildings);

      // 移动指针
      cursor++;
    }
  }

  /**
   * 向分组中填充数据
   */
  private void fill(int index, List<EntityID> agents, List<EntityID> buildings) {

    // 获取分组
    List<EntityID> target = this.groups.get(index);

    // 加入agent
    if (!agents.isEmpty()) {
      target.add(agents.remove(0));
    }

    // 加入building
    if (!buildings.isEmpty()) {
      target.add(buildings.remove(0));
    }
  }

  /**
   * 根据数量计算分组规模
   */
  private int calcGroupSize(int count) {

    // 转换为double
    double size = count;

    // 返回计算结果
    return (int) Math.ceil(Math.sqrt(size) / 2.0);
  }

  /**
   * 信息更新
   */
  @Override
  public Clustering updateInfo(MessageManager mm) {

    // 调用父类逻辑
    super.updateInfo(mm);

    // 控制执行次数
    if (this.getCountUpdateInfo() > 1) {
      return this;
    }

    return this;
  }

  /**
   * 预计算
   */
  @Override
  public Clustering precompute(PrecomputeData pd) {
    super.precompute(pd);
    return this;
  }

  /**
   * 恢复
   */
  @Override
  public Clustering resume(PrecomputeData pd) {
    super.resume(pd);
    return this;
  }

  /**
   * 准备
   */
  @Override
  public Clustering preparate() {
    super.preparate();
    return this;
  }
}
