package UESTC.module.algorithm;

import adf.core.component.module.algorithm.*;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.misc.geometry.*;

import java.util.*;

import static java.util.stream.Collectors.*;

public class MotionAnomalyInspector extends DynamicClustering {

  // 是否判定为移动失败
  private boolean failed = false;

  // 是否已经执行过本轮计算
  private boolean called = false;

  // 历史位置记录
  private List<Point2D> history = new LinkedList<>();

  // 距离判定阈值
  private static final double AGENT_RADIUS = 500.0;

  /**
   * 获取聚类中的实体ID集合
   */
  @Override
  public Collection<EntityID> getClusterEntityIDs(int i) {
    // 获取当前 agent 的ID
    final EntityID me = this.agentInfo.getID();

    // 若属于失败聚类则返回单元素集合
    return i == 0 && this.failed ? Collections.singleton(me) : null;
  }

  /**
   * 构造函数
   */
  public MotionAnomalyInspector(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    // 调用父类构造逻辑
    super(ai, wi, si, mm, dd);
  }

  /**
   * 判断是否处于等待阶段
   */
  private boolean shouldWait() {
    // 获取当前时间
    final int time = this.agentInfo.getTime();

    // 获取忽略截止时间
    final int ignored = this.scenarioInfo.getKernelAgentsIgnoreuntil();

    // 判断当前是否仍需等待
    return time <= ignored;
  }

  /**
   * 根据实体对象获取聚类编号
   */
  @Override
  public int getClusterIndex(StandardEntity entity) {
    // 转换为实体ID继续判断
    return this.getClusterIndex(entity.getID());
  }

  /**
   * 获取当前坐标点
   */
  private Point2D obtainCurrentPoint() {
    // 获取横坐标
    final double x = this.agentInfo.getX();

    // 获取纵坐标
    final double y = this.agentInfo.getY();

    // 构造点对象
    return new Point2D(x, y);
  }

  /**
   * 执行聚类计算
   */
  @Override
  public Clustering calc() {
    // 若本轮已执行或仍在等待阶段则直接返回
    if (this.called || this.shouldWait()) {
      return this;
    }

    // 标记本轮已执行
    this.called = true;

    // 更新移动失败状态
    this.failed = this.detectFailure();

    // 返回当前对象
    return this;
  }

  /**
   * 获取聚类实体集合
   */
  @Override
  public Collection<StandardEntity> getClusterEntities(int i) {
    // 获取实体ID集合
    final Collection<EntityID> ids = this.getClusterEntityIDs(i);

    // 若集合不存在则返回空值
    if (ids == null) {
      return null;
    }

    // 将ID转换为实体对象
    return ids.stream().map(this.worldInfo::getEntity).collect(toList());
  }

  /**
   * 判断当前位置是否已经变化
   */
  private boolean hasShifted(ActionMove move) {
    // 获取当前位置实体ID
    final EntityID position = this.agentInfo.getPosition();

    // 复制移动路径
    final List<EntityID> path = new ArrayList<>(move.getPath());

    // 判断路径首点是否仍为当前位置
    return !path.isEmpty() && !position.equals(path.get(0));
  }

  /**
   * 根据实体ID获取聚类编号
   */
  @Override
  public int getClusterIndex(EntityID id) {
    // 获取当前 agent 的ID
    final EntityID me = this.agentInfo.getID();

    // 仅当自身失败时返回聚类编号
    return me.equals(id) && this.failed ? 0 : -1;
  }

  /**
   * 更新信息
   */
  @Override
  public Clustering updateInfo(MessageManager mm) {
    // 调用父类更新逻辑
    super.updateInfo(mm);

    // 仅在首次更新时继续处理
    if (this.getCountUpdateInfo() > 1) {
      return this;
    }

    // 将当前位置加入历史记录头部
    this.history.add(0, this.obtainCurrentPoint());

    // 仅保留最近两个位置点
    while (this.history.size() > 2) {
      this.history.remove(2);
    }

    // 重置调用标记
    this.called = false;

    // 返回当前对象
    return this;
  }

  /**
   * 获取聚类数量
   */
  @Override
  public int getClusterNumber() {
    // 若失败则存在一个聚类
    return this.failed ? 1 : 0;
  }

  /**
   * 获取移动目标点
   */
  private Point2D obtainTargetPoint(ActionMove move) {
    // 获取目标横坐标
    final double x = move.getPosX();

    // 获取目标纵坐标
    final double y = move.getPosY();

    // 构造点对象
    return new Point2D(x, y);
  }

  /**
   * 判断本轮移动是否失败
   */
  private boolean detectFailure() {
    // 获取当前时间
    final int currentTime = this.agentInfo.getTime();

    // 获取上一个时刻执行的动作
    final Action previousAction = this.agentInfo.getExecutedAction(currentTime - 1);

    // 仅对移动动作进行判断
    if (!(previousAction instanceof ActionMove)) {
      return false;
    }

    // 转换为移动动作
    ActionMove move = (ActionMove) previousAction;

    // 若位置已经发生变化则视为未失败
    if (this.hasShifted(move)) {
      return false;
    }

    // 获取最近两次历史位置
    final Point2D currentPoint = this.history.get(0);
    final Point2D previousPoint = this.history.get(1);

    // 初始化到目标点的距离
    double distanceToTarget = Double.POSITIVE_INFINITY;

    // 若使用目标点则计算当前位置到目标点的距离
    if (move.getUsePosition()) {
      Point2D targetPoint = this.obtainTargetPoint(move);
      distanceToTarget = GeometryTools2D.getDistance(currentPoint, targetPoint);
    }

    // 计算两次历史位置之间的位移
    double displacement = GeometryTools2D.getDistance(currentPoint, previousPoint);

    // 根据条件判断是否移动失败
    return distanceToTarget > AGENT_RADIUS && displacement <= AGENT_RADIUS * 6;
  }
}
