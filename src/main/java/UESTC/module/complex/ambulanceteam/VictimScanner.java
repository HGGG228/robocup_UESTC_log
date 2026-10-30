package UESTC.module.complex.ambulanceteam;

import java.util.*;
import java.util.stream.Collectors;

import UESTC.module.complex.common.ObjectScreener;
import UESTC.module.complex.common.EntrappedState;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.component.module.complex.HumanDetector;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.*;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.communication.MessageManager;
import rescuecore2.misc.Pair;
import rescuecore2.worldmodel.AbstractEntity;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.standard.entities.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

/**
 * UESTC 救护车目标探测模块
 * 负责识别、筛选并决定救护车当前应该去救援哪一个对象（平民或代理人）
 */
public class VictimScanner extends HumanDetector {

  private PathPlanning pathPlanner;      // 路径规划算法
  private Clustering clusterer;          // 聚类算法（用于区域分配）
  private Clustering stuckedHumans;      // 被困人员聚类
  private CitizenCommProtocol civMessages;  // 平民消息处理模块

  private EntityID result = null;        // 最终计算出的目标ID
  private Set<EntityID> cluster = new HashSet<>(); // 当前智能体所属的分区/簇内的实体ID

  private Set<EntityID> loadCivTask = new HashSet<>(); // 待装载/救援的平民任务列表
  private Set<EntityID> nonTarget = new HashSet<>();   // 明确不需要救援的非目标列表

  /**
   * 记录那些因为 ActionLoad（装载操作）失败而需要处理的平民
   */
  private Set<EntityID> failedTask = new HashSet<>();

  // 通信设置
  private final boolean VOICE = false; // 是否使用语音通信
  private final boolean RADIO = true;  // 是否使用无线电通信

  // 救援请求类型常量
  final private int HELP_BURIED = 5;   // 被埋压求救
  final private int HELP_BLOCKADE = 6; // 被路障困住求救

  public VictimScanner(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);

    // 从模块管理器中加载子模块
    this.pathPlanner = mm.getModule("UESTC.AT.HumanDetector.PathPlanning");
    this.registerModule(this.pathPlanner);

    this.clusterer = mm.getModule("UESTC.AT.HumanDetector.Clustering");
    this.registerModule(this.clusterer);

    this.stuckedHumans = mm.getModule("UESTC.AT.HumanDetector.StuckHumans");
    this.registerModule(this.stuckedHumans);

    this.civMessages = mm.getModule("UESTC.AT.HumanDetector.CitizenCommProtocol");
    this.registerModule(this.civMessages);
  }

  @Override
  public EntityID getTarget() {
    return this.result;
  }

  /**
   * 更新模块信息：每一周期执行，负责处理消息、更新任务状态
   */
  @Override
  public HumanDetector updateInfo(MessageManager mm) {
    super.updateInfo(mm);
    if (this.getCountUpdateInfo() > 1) {
      return this;
    }

    ensureClusterInitialized(); // 确保聚类信息已初始化
    this.receiveMessage(mm);    // 接收并处理平民消息
    this.sendMessage(mm);       // 发送求救消息（如果自己被困）
    this.updateTask();          // 刷新任务状态，清理已死亡或已救助的目标

    return this;
  }

  /**
   * 核心计算逻辑：决定当前周期的救援目标
   */
  @Override
  public HumanDetector calc() {
    this.civMessages.calc();

    EntityID target = selectTarget();
    if (target != null) {
      this.result = target;
    }

    return this;
  }

  /**
   * 优先级选择目标逻辑
   */
  private EntityID selectTarget() {
    // 优先级 1: 如果车上已经载了人，则目标就是这个人（送往避难所）
    EntityID target = this.findResultFromOnboard();
    if (target != null) {
      return target;
    }

    // 优先级 2: 寻找任务列表中距离最近且不在避难所的平民
    target = this.findNearestNonRefugeEntity();
    if (target != null) {
      return target;
    }

    // 优先级 3: 寻找视野范围内可以直接移动/救援的平民
    return this.findMovableCivilian();
  }

  private void ensureClusterInitialized() {
    if (this.cluster.isEmpty()) {
      this.initCluster();
    }
  }

  private EntityID findResultFromOnboard() {
    final Human onboard = this.agentInfo.someoneOnBoard();
    return onboard != null ? onboard.getID() : null;
  }

  /**
   * 遍历当前任务列表，返回距离最近的目标
   */
  private EntityID findNearestNonRefugeEntity() {
    double minDistance = Double.MAX_VALUE;
    EntityID nearestTaskID = null;

    for (EntityID e : this.loadCivTask) {
      if (this.isRefuge(e)) {
        continue;
      }

      final double distance = this.calculateDistance(this.worldInfo.getLocation(e));
      if (distance < minDistance) {
        minDistance = distance;
        nearestTaskID = e;
      }
    }

    return nearestTaskID;
  }

  /**
   * 查找本周期视野内变化的平民，并判断是否需要装载
   */
  private EntityID findMovableCivilian() {
    final Set<EntityID> changedEntities = this.worldInfo.getChanged().getChangedEntities();

    for (EntityID e : changedEntities) {
      final StandardEntity se = this.worldInfo.getEntity(e);
      if (!(se instanceof Civilian)) {
        continue;
      }

      final Civilian c = (Civilian) se;
      if (this.isMovableCivilian(c)) {
        this.result = c.getID();
        return this.result;
      }
    }

    return null;
  }

  /**
   * 判断平民是否可以作为救援目标：不在避难所且符合装载条件
   */
  private boolean isMovableCivilian(Civilian civilian) {
    return !this.isRefuge(civilian) && this.loadTarget(civilian.getID());
  }

  private boolean isRefuge(EntityID entityID) {
    return this.worldInfo.getPosition(entityID).getStandardURN().equals(REFUGE);
  }

  private boolean isRefuge(Civilian civilian) {
    return this.worldInfo.getPosition(civilian).getStandardURN().equals(REFUGE);
  }

  /**
   * 计算曼哈顿距离
   */
  private double calculateDistance(Pair<Integer, Integer> location) {
    return Math.abs(this.agentInfo.getX() - location.first())
        + Math.abs(this.agentInfo.getY() - location.second());
  }

  /**
   * 如果救护车自己被埋或被路障困住，向外界发送求救信号
   */
  private void sendMessage(MessageManager mm) {
    final AmbulanceTeam me = (AmbulanceTeam) this.worldInfo.getEntity(this.agentInfo.getID());

    if (this.isAgentEntrappedState()) {
      this.sendHelpMessage(mm, me, HELP_BLOCKADE, this.agentInfo.getPosition());
      return;
    }

    if (this.isMyselfBuried()) {
      this.sendHelpMessage(mm, me, HELP_BURIED, this.agentInfo.getID());
    }
  }

  /**
   * 检测自己是否被路障包围导致无法移动
   */
  private boolean isAgentEntrappedState() {
    final ObjectScreener filter = new ObjectScreener(this.worldInfo, this.agentInfo);
    final Set<Blockade> blockades = filter.getBlockades();
    final EntrappedState Stucked = new EntrappedState(blockades, this.agentInfo.getX(), this.agentInfo.getY());
    return Stucked.isEntrappedState();
  }

  private void sendHelpMessage(MessageManager mm, AmbulanceTeam me, int helpType, EntityID targetID) {
    mm.addMessage(new MessageAmbulanceTeam(RADIO, me, helpType, targetID));
    mm.addMessage(new MessageAmbulanceTeam(VOICE, me, helpType, targetID));
  }

  private boolean isMyselfBuried() {
    Human me = (Human) this.worldInfo.getEntity(this.agentInfo.getID());
    if (!(this.agentInfo.getPositionArea() instanceof Building)) {
      return false;
    }
    if (!me.isBuriednessDefined()) {
      return false;
    }
    return me.getBuriedness() != 0;
  }

  /**
   * 初始化聚类：确定自己负责的区域，只关注该区域内的实体
   */
  private void initCluster() {
    this.clusterer.calc();
    final EntityID me = this.agentInfo.getID();
    final int index = this.clusterer.getClusterIndex(me);
    final Collection<EntityID> ids = this.clusterer.getClusterEntityIDs(index);
    this.cluster.addAll(ids);
  }

  /**
   * 判断目标是否满足“待救助”条件：
   * HP > 0 (活着), Buriedness == 0 (已被挖出), Damage > 0 (受伤需要送医)
   */
  private boolean loadTarget(EntityID e) {
    final Human h = (Human) this.worldInfo.getEntity(e);
    if (!(h instanceof Civilian)) {
      return false;
    }
    final int hp = h.getHP();
    final int buried = h.getBuriedness();
    final int damage = h.getDamage();

    return hp > 0 && buried == 0 && damage > 0;
  }

  /**
   * 处理接收到的平民消息，并将其加入任务列表
   */
  private void receiveMessage(MessageManager mm) {
    final List<CommunicationMessage> cmdAmb = mm.getReceivedMessageList(MessageCivilian.class);
    for (CommunicationMessage tmp : cmdAmb) {
      this.handleMessage((MessageCivilian) tmp);
    }
  }

  private void handleMessage(MessageCivilian msg) {
    final EntityID targetID = msg.getAgentID();
    if (this.loadTarget(targetID)) {
      this.addLoadCivTask(targetID);
      return;
    }
    this.removeLoadCivTaskAndMarkNonTarget(targetID);
  }

  private void addLoadCivTask(EntityID targetID) {
    if (!this.loadCivTask.contains(targetID)) {
      this.loadCivTask.add(targetID);
    }
  }

  private void removeLoadCivTaskAndMarkNonTarget(EntityID targetID) {
    this.loadCivTask.remove(targetID);
    if (!this.nonTarget.contains(targetID)) {
      this.nonTarget.add(targetID);
    }
  }

  /**
   * 刷新任务列表：清理失效、死亡或已获救的目标
   */
  private void updateTask() {
    final Set<EntityID> changedCivilianEntityIDs = this.getChangedCivilianEntityIDs();

    this.collectFailedTasks(changedCivilianEntityIDs);
    this.removeFailedTasksFromTaskSets();
    this.moveFailedTasksToRefuge(); // 逻辑修正：将失败任务视为已处理
    this.refreshLoadCivTask();
    this.refreshNonTarget();
  }

  private Set<EntityID> getChangedCivilianEntityIDs() {
    return this.worldInfo.getChanged().getChangedEntities().stream()
        .map(this.worldInfo::getEntity)
        .filter(Civilian.class::isInstance)
        .map(AbstractEntity::getID)
        .collect(Collectors.toSet());
  }

  /**
   * 识别失败任务：如果救护车和平民在同一位置，但平民状态没更新（没上车），视为失败
   */
  private void collectFailedTasks(Set<EntityID> changedCivilianEntityIDs) {
    this.failedTask.addAll(
        this.loadCivTask.stream()
            .map(this.worldInfo::getEntity)
            .map(Civilian.class::cast)
            .filter(c -> this.isFailedLoadTask(c, changedCivilianEntityIDs))
            .map(Civilian::getID)
            .collect(Collectors.toSet())
    );
  }

  private boolean isFailedLoadTask(Civilian c, Set<EntityID> changedCivilianEntityIDs) {
    return this.agentInfo.getPosition().equals(c.getPosition())
        && !changedCivilianEntityIDs.contains(c.getID());
  }

  private void removeFailedTasksFromTaskSets() {
    this.loadCivTask.removeAll(this.failedTask);
    this.nonTarget.removeAll(this.failedTask);
  }

  /**
   * 这里的逻辑比较特殊：将 ActionLoad 失败的目标坐标强制“修改”为避难所坐标
   * 通常用于内部状态的一致性维护
   */
  private void moveFailedTasksToRefuge() {
    final Refuge anyRefuge = this.getAnyRefuge();

    this.failedTask.stream()
        .map(this.worldInfo::getEntity)
        .map(Civilian.class::cast)
        .forEach(c -> {
          c.setPosition(anyRefuge.getID());
          c.setX(anyRefuge.getX());
          c.setY(anyRefuge.getY());
        });
  }

  private Refuge getAnyRefuge() {
    return this.worldInfo.getEntityIDsOfType(REFUGE).stream()
        .findAny()
        .map(this.worldInfo::getEntity)
        .map(Refuge.class::cast)
        .get();
  }

  /**
   * 检查当前救援列表，如果目标已康复或死亡，移入 nonTarget
   */
  private void refreshLoadCivTask() {
    Iterator<EntityID> i = this.loadCivTask.iterator();
    while (i.hasNext()) {
      final EntityID e = i.next();
      if (!this.loadTarget(e)) {
        this.nonTarget.add(e);
        i.remove();
      }
    }
  }

  /**
   * 检查非目标列表，如果目标状态恶化（受伤且被挖出），重新移回救援列表
   */
  private void refreshNonTarget() {
    Iterator<EntityID> i = this.nonTarget.iterator();
    while (i.hasNext()) {
      final EntityID e = i.next();
      if (this.loadTarget(e)) {
        this.loadCivTask.add(e);
        i.remove();
      }
    }
  }
}
