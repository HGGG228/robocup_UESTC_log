package UESTC.module.algorithm;

import adf.core.component.module.algorithm.*;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.communication.MessageManager;
import rescuecore2.worldmodel.*;
import rescuecore2.standard.entities.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import rescuecore2.misc.geometry.*;
import java.awt.geom.Ellipse2D;
import java.util.*;
import java.util.stream.*;

public class PeopleConstraintDetector extends DynamicClustering {

  /**
   * 获取聚类中的实体集合
   */
  @Override
  public Collection<StandardEntity> getClusterEntities(int i) {
    // 先获取实体ID集合
    Collection<EntityID> ids = this.getClusterEntityIDs(i);

    // 若结果为空则返回空集合
    if (Objects.isNull(ids) || ids.isEmpty()) {
      return Collections.emptyList();
    }

    // 将实体ID转换为实体对象
    return ids.stream()
        .map(this.worldInfo::getEntity)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  /**
   * 获取聚类编号
   */
  @Override
  public int getClusterIndex(EntityID id) {
    // 若目标存在于被困集合则返回唯一聚类编号
    return this.stuckedHumans.contains(id) ? 0 : -1;
  }

  // 被困人类集合
  private Set<EntityID> stuckedHumans = new HashSet<>();

  // 当前帧更新过的人类集合
  private Set<EntityID> updates = new HashSet<>();

  // 当前帧无法继续跟踪的人类集合
  private Set<EntityID> cannotTracks = new HashSet<>();

  /**
   * 获取聚类数量
   */
  @Override
  public int getClusterNumber() {
    // 当前模块固定维护一个聚类
    return 1;
  }

  /**
   * 计算被困状态
   */
  @Override
  public Clustering calc() {
    // 遍历所有待更新的人类
    for (EntityID id : this.updates) {
      // 读取对应人类实体
      final Human human = (Human) this.worldInfo.getEntity(id);

      // 根据判定结果更新被困集合
      if (this.detectStucked(human)) {
        this.stuckedHumans.add(id);
      } else {
        this.stuckedHumans.remove(id);
      }
    }

    // 移除无法继续跟踪的人类
    this.stuckedHumans.removeAll(this.cannotTracks);

    // 清空本轮更新记录
    this.updates.clear();

    // 返回当前对象
    return this;
  }

  /**
   * 根据实体对象获取聚类编号
   */
  @Override
  public int getClusterIndex(StandardEntity entity) {
    // 转换为实体ID继续处理
    return this.getClusterIndex(entity.getID());
  }

  /**
   * 获取聚类中的实体ID集合
   */
  @Override
  public Collection<EntityID> getClusterEntityIDs(int i) {
    // 仅索引为0时返回被困集合副本
    return i == 0 ? new HashSet<>(this.stuckedHumans) : null;
  }

  // agent 的判定半径
  private static final double AGENT_RADIUS = 500.0;

  // 平民的判定半径
  private static final double CIVILIAN_RADIUS = 300.0;

  /**
   * 将人类位置转换为二维点
   */
  public static Point2D getPoint(Human human) {
    // 读取横坐标
    final double x = human.getX();

    // 读取纵坐标
    final double y = human.getY();

    // 构造点对象
    return new Point2D(x, y);
  }

  /**
   * 将圆形占用区域转换为 AWT 图形区域
   */
  public static java.awt.geom.Area makeAWTArea(Point2D p, double rad) {
    // 计算直径
    final double d = rad * 2.0;

    // 计算左上角横坐标
    final double x = p.getX() - rad;

    // 计算左上角纵坐标
    final double y = p.getY() - rad;

    // 构造圆形区域
    return new java.awt.geom.Area(new Ellipse2D.Double(x, y, d, d));
  }

  /**
   * 更新信息
   */
  @Override
  public Clustering updateInfo(MessageManager mm) {
    // 调用父类更新逻辑
    super.updateInfo(mm);

    // 第二次及以后更新直接返回
    if (this.getCountUpdateInfo() >= 2) {
      return this;
    }

    // 记录发生变化的人类
    this.captureChangedHumans();

    // 记录当前位置中无法继续跟踪的人类
    this.markUntrackableHumans();

    // 返回当前对象
    return this;
  }

  /**
   * 构造函数
   */
  public PeopleConstraintDetector(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    // 调用父类构造逻辑
    super(ai, wi, si, mm, dd);
  }

  /**
   * 标记发生变化的人类
   */
  private void captureChangedHumans() {
    // 读取变化集合
    final ChangeSet changes = this.worldInfo.getChanged();

    // 遍历所有变化实体
    for (EntityID id : changes.getChangedEntities()) {
      // 读取实体对象
      StandardEntity entity = this.worldInfo.getEntity(id);

      // 仅处理人类实体
      if (entity instanceof Human) {
        this.updates.add(id);
        this.cannotTracks.remove(id);
      }
    }
  }

  /**
   * 标记当前位置中无法跟踪的人类
   */
  private void markUntrackableHumans() {
    // 读取当前 agent 所在位置
    final EntityID position = this.agentInfo.getPosition();

    // 读取变化集合
    final ChangeSet changes = this.worldInfo.getChanged();

    // 读取所有人类实体ID
    final Collection<EntityID> humans = this.worldInfo.getEntityIDsOfType(
        FIRE_BRIGADE, AMBULANCE_TEAM, POLICE_FORCE, CIVILIAN);

    // 遍历所有人类实体
    for (EntityID human : humans) {
      // 读取人类对象
      final Human entity = (Human) this.worldInfo.getEntity(human);

      // 位置未定义则跳过
      if (!entity.isPositionDefined()) {
        continue;
      }

      // 不在当前位置则跳过
      if (!entity.getPosition().equals(position)) {
        continue;
      }

      // 已在变化集合中则跳过
      if (changes.getChangedEntities().contains(human)) {
        continue;
      }

      // 记录为无法跟踪
      this.cannotTracks.add(human);
    }
  }

  /**
   * 判断人类是否被困
   */
  private boolean detectStucked(Human human) {
    // 读取人类所在位置
    final EntityID position = human.getPosition();

    // 读取对应实体
    final StandardEntity entity = this.worldInfo.getEntity(position);

    // 仅区域类型可继续判断
    if (!Area.class.isInstance(entity)) {
      return false;
    }

    // 转换为区域对象
    final Area area = (Area) entity;

    // 未定义障碍物则直接返回
    if (!area.isBlockadesDefined()) {
      return false;
    }

    // 构建障碍区域
    final Optional<java.awt.geom.Area> obstacle = this.buildObstacle(area);

    // 无障碍区域则返回
    if (!obstacle.isPresent()) {
      return false;
    }

    // 读取人类当前位置
    final Point2D point = getPoint(human);

    // 根据类型选择判定半径
    final double rad = human.getStandardURN() == CIVILIAN
        ? CIVILIAN_RADIUS
        : AGENT_RADIUS;

    // 构造占用区域
    final java.awt.geom.Area shape = makeAWTArea(point, rad);

    // 计算与障碍区域的交集
    shape.intersect(obstacle.get());

    // 根据交集是否为空判断是否被困
    return !shape.isEmpty();
  }

  /**
   * 构建区域内障碍物联合图形
   */
  private Optional<java.awt.geom.Area> buildObstacle(Area area) {
    // 将所有障碍物形状合并为一个区域
    return area.getBlockades()
        .stream()
        .map(this.worldInfo::getEntity)
        .map(Blockade.class::cast)
        .map(Blockade::getShape)
        .map(java.awt.geom.Area::new)
        .reduce((acc, v) -> {
          acc.add(v);
          return acc;
        });
  }
}
