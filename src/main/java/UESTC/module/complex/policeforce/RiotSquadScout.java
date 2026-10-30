package UESTC.module.complex.policeforce;

import adf.core.component.module.complex.RoadDetector;
import adf.core.component.module.algorithm.*;
import adf.core.component.communication.CommunicationMessage;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.*;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.standard.entities.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import rescuecore2.misc.geometry.*;
import org.locationtech.jts.geom.*;

import UESTC.module.algorithm.SpatialMorphologyCore;

import java.util.*;

import UESTC.module.util.DebugUtil;
import UESTC.module.util.AgentTelemetry;

/**
 * RiotSquadScout 警察部队战区道路侦察与除障规划雷达。
 * 物理意义：警察的主要职责是利用电锯和推土机清理道路。
 * 但是灾区道路千万条，该去哪里清？这就需要一个“雷达”来感知周围环境，
 * 将拥堵区、主干道、求救点以及自己辖区的道路进行优先级排序，从而决定最优的清障目标。
 * 战术核心：
 * 1. 优先处理辖区内的任务，如果干完了就扩张防区（网格借调）。
 * 2. 对所有待清理路段进行双重权重评估（曼哈顿直线距离 + A* 实际寻路距离）。
 * 3. 动态响应：如果别的警察被埋了，或者某地人群极其拥挤，立刻提高该地的清障优先级！
 */
public class RiotSquadScout extends RoadDetector {

  /**
   * 最终锁定的除障目标。
   * 物理意义：经过雷达千锤百炼算出来的，本回合要提着电锯去砍的那个目标区域。
   */
  private EntityID finalTargetArea = null;

  /**
   * 战区网格划分雷达。
   * 物理意义：用于明确当前警察被分配在了全城的哪一片责任网格。
   */
  private Clustering sectorDivider;

  /**
   * 主干道提取雷达。
   * 物理意义：识别出全城的交通大动脉，主干道往往拥有极高的清障优先级。
   */
  private StaticClustering mainArteryRadar;

  /**
   * 拥堵被困雷达。
   * 物理意义：监控全城哪里有人被挤得动弹不得。
   */
  private Clustering EntrappedStateCrowdRadar;

  /**
   * 交通严重拥堵区雷达。
   * 物理意义：监控哪里因为路障造成了大规模的车辆和人员堆积。
   */
  private StaticClustering congestionZoneRadar;

  /**
   * 基础直线路径规划器。
   */
  private PathPlanning basicNavigator;

  /**
   * A* 高级避障寻路引擎。
   * 物理意义：在计算距离时，不能只看直线，要看真正绕过废墟走过去有多远。
   */
  private PathPlanning advancedNavigator;

  /**
   * 难民求救电台。
   * 物理意义：监听灾区平民的哀嚎，哪里有求救声，哪里的道路可能就需要打通。
   */
  private CasualtyProfile distressSignalReceiver;

  /**
   * 任务优先级记账本。
   * 物理意义：记录了全城所有的路段（Key）及其对应的紧急程度（Value）。数字越小，越紧急！
   */
  private Map<EntityID, Integer> taskPriorityLedger = new HashMap<>();

  /**
   * 辖区待清理道路名册。
   * 物理意义：我这个警察目前负责巡视和清理的路段大名单。
   */
  private Set<EntityID> myPatrolSector = new HashSet<>();

  /**
   * 已彻底贯通的道路黑名单。
   * 物理意义：如果一条路已经被清理得干干净净了，就记在这里，以后再也不去浪费时间。
   */
  private Set<EntityID> clearedRoadLog = new HashSet<>();

  /**
   * 战友遇险拯救档案。
   * 物理意义：记录了那些被埋的警察兄弟，以及他们所在的网格防区。优先打通救他们的路！
   */
  private Map<EntityID, Collection<EntityID>> EntrappedStatePoliceSectors = new HashMap<>();

  // 固化通信频段及求救代码
  private final boolean VOICE_CHANNEL = false;
  private final boolean RADIO_CHANNEL = true;
  private final int EMERGENCY_CODE_BURIED = 5;
  private final int EMERGENCY_CODE_BLOCKADE = 6;

  /**
   * 调试工具探针。
   */
  DebugUtil tacticalLogger;

  /**
   * 警察雷达兵装配车间。
   * 物理意义：把底层的各种寻路算法和感知模块装载到警察的脑子里。
   */
  public RiotSquadScout(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);

    this.basicNavigator = mm.getModule("UESTC.PF.RoadDetector.PathPlanning");
    this.registerModule(this.basicNavigator);

    this.sectorDivider = mm.getModule("UESTC.PF.RoadDetector.Clustering");
    this.registerModule(this.sectorDivider);

    this.mainArteryRadar = mm.getModule("UESTC.PF.RoadDetector.Highways");
    this.registerModule(this.mainArteryRadar);

    this.EntrappedStateCrowdRadar = mm.getModule("UESTC.PF.RoadDetector.StuckHumans");
    this.registerModule(this.EntrappedStateCrowdRadar);

    this.congestionZoneRadar = mm.getModule("UESTC.PF.RoadDetector.OvercrowdingArea");
    this.registerModule(this.congestionZoneRadar);

    this.advancedNavigator = mm.getModule("UESTC.PF.RoadDetector.OptimalTrajectorySolver");
    this.registerModule(this.advancedNavigator);

    this.distressSignalReceiver = mm.getModule("UESTC.PF.RoadDetector.CitizenCommProtocol");
    this.registerModule(this.distressSignalReceiver);

    this.tacticalLogger = new DebugUtil(this.agentInfo, this.worldInfo, this.scenarioInfo);
  }

  /**
   * 核心除障目标决断大脑。
   * 物理意义：每秒钟根据账本上的任务，结合路程远近，挑出一个最优的地点开电锯。
   */
  @Override
  public RoadDetector calc() {
    AgentTelemetry.event(this.agentInfo, "calc_start", "RiotSquadScout",
        "pending_roads", this.taskPriorityLedger.size());
    this.distressSignalReceiver.calc();
    this.finalTargetArea = null;

    // 如果处于比赛初期的指令静默期，则原地待命
    if (this.isUnderIdleDiscipline()) {
      AgentTelemetry.event(this.agentInfo, "target_select", "RiotSquadScout",
          "reason", "idle", "target", null);
      return this;
    }

    // 提取账本里所有还没有被清理干净的有效任务
    List<EntityID> validCandidates = new ArrayList<>();
    for (EntityID taskId : this.taskPriorityLedger.keySet()) {
      if (this.clearedRoadLog.contains(taskId) == false) {
        validCandidates.add(taskId);
      }
    }

    // 第一重排序：按紧急程度（优先级数值越小越好），再按物理直线距离
    Collections.sort(validCandidates, new PrimaryTaskComparator());

    // 掐尖机制：只保留排在最前面的 5 个极品任务，减少后续 A* 寻路的算力开销
    int limitCap = Math.min(5, validCandidates.size());
    List<EntityID> eliteTop5Tasks = new ArrayList<>(validCandidates.subList(0, limitCap));

    // 第二重排序：从这 5 个精英任务里，用真实的 A* 寻路距离再次打擂台，挑出最终冠军
    if (eliteTop5Tasks.isEmpty() == false) {
      this.finalTargetArea = Collections.min(eliteTop5Tasks, new SecondaryTaskComparator());
    } else {
      this.finalTargetArea = null;
    }

    // 如果转了一圈发现根本没有目标，说明我负责的辖区可能已经干净了！
    if (this.finalTargetArea == null) {
      int previousSectorSize = this.myPatrolSector.size();

      // 战术扩张：去接管别人的网格！
      this.expandPatrolSector();
      // 根据新扩张的网格，重新把里面的道路加入账本
      this.refreshLedgerWithSectorData();
      this.refreshLedgerWithCongestionData(1);

      // 如果网格确实变大了，说明拿到了新任务，立刻递归重新算一次！
      if (previousSectorSize != this.myPatrolSector.size()) {
        return this.calc();
      }
    }

    AgentTelemetry.event(this.agentInfo, "target_select", "RiotSquadScout",
        "candidates", validCandidates.size(), "target", this.finalTargetArea);
    return this;
  }

  /**
   * 一级目标过滤器（直线距离）。
   * 物理意义：主要看账本优先级，优先级相同的情况下，看直线曼哈顿距离。
   */
  private class PrimaryTaskComparator implements Comparator<EntityID> {
    @Override
    public int compare(EntityID idAlpha, EntityID idBeta) {
      EntityID mySelfId = agentInfo.getID();

      // 比对紧急程度（数值越小优先级越高）
      int priorityA = taskPriorityLedger.get(idAlpha);
      int priorityB = taskPriorityLedger.get(idBeta);
      int priorityComparison = Integer.compare(priorityA, priorityB);

      if (priorityComparison != 0) {
        return priorityComparison;
      }

      // 紧急程度相同时，比对直线距离
      double distA = worldInfo.getDistance(mySelfId, idAlpha);
      double distB = worldInfo.getDistance(mySelfId, idBeta);
      return Double.compare(distA, distB);
    }
  }

  /**
   * 二级目标过滤器（A* 寻路距离）。
   * 物理意义：在精英名单中，动用高级导航仪计算真实要走多远，选最省油的那个。
   */
  private class SecondaryTaskComparator implements Comparator<EntityID> {
    @Override
    public int compare(EntityID idAlpha, EntityID idBeta) {
      EntityID myLocation = agentInfo.getPosition();

      int priorityA = taskPriorityLedger.get(idAlpha);
      int priorityB = taskPriorityLedger.get(idBeta);
      int priorityComparison = Integer.compare(priorityA, priorityB);

      if (priorityComparison != 0) {
        return priorityComparison;
      }

      double realDistA = calculateNavigatedDistance(myLocation, idAlpha);
      double realDistB = calculateNavigatedDistance(myLocation, idBeta);
      return Double.compare(realDistA, realDistB);
    }
  }

  /**
   * 真实寻路里程计。
   * 物理意义：让导航仪算一算，沿着马路绕过废墟走到目的地，一共需要走多少距离。
   */
  private double calculateNavigatedDistance(EntityID origin, EntityID destination) {
    this.basicNavigator.setFrom(origin);
    this.basicNavigator.setDestination(destination);
    this.basicNavigator.calc();

    List<EntityID> routedPath = this.basicNavigator.getResult();
    if (routedPath == null || routedPath.isEmpty()) {
      return Double.MAX_VALUE; // 死路一条，距离无限远
    }

    int pathNodeCount = routedPath.size();
    double totalTravelDistance = 0.0;

    for (int step = 1; step < pathNodeCount; ++step) {
      totalTravelDistance += this.worldInfo.getDistance(routedPath.get(step - 1), routedPath.get(step));
    }
    return totalTravelDistance;
  }

  /**
   * 任务账本登录员。
   * 物理意义：将某个道路加入待清理账本。如果它已经在账本里了，就保留更紧急的那个优先级。
   */
  private void assignTaskPriority(EntityID taskArea, int urgencyLevel) {
    int existingUrgency = this.taskPriorityLedger.getOrDefault(taskArea, Integer.MAX_VALUE);
    this.taskPriorityLedger.put(taskArea, Math.min(existingUrgency, urgencyLevel));
  }

  /**
   * 战术结果提取口。
   */
  @Override
  public EntityID getTarget() {
    AgentTelemetry.decision(this.agentInfo, this.worldInfo, "RiotSquadScout", this.finalTargetArea,
        "taskCount", this.taskPriorityLedger.size(),
        "targetPriority", this.taskPriorityLedger.get(this.finalTargetArea),
        "patrolSectorSize", this.myPatrolSector.size(),
        "clearedRoadCount", this.clearedRoadLog.size(),
        "trappedPoliceSectorCount", this.EntrappedStatePoliceSectors.size());
    return this.finalTargetArea;
  }

  /**
   * 战地情报与状态更新台。
   * 物理意义：每秒钟看一眼四周，看看路是不是清完了，同时更新任务账本。
   */
  @Override
  public RoadDetector updateInfo(MessageManager mm) {
    super.updateInfo(mm);
    if (this.getCountUpdateInfo() > 1) {
      return this;
    }

    // 物理检查：看一眼我脚底下这条路
    StandardEntity myFootprint = this.worldInfo.getPosition(this.agentInfo.getID());
    if (myFootprint instanceof Road) {
      Area currentRoadArea = (Area) myFootprint;
      // 如果我脚底下的路干干净净（没有碎石），立刻把它从账本里划掉
      if (currentRoadArea.getBlockades() == null || currentRoadArea.getBlockades().isEmpty()) {
        this.taskPriorityLedger.remove(myFootprint.getID());
      }
    }

    // 冷启动：如果刚开局还没划分防区，立即划分并登账
    if (this.myPatrolSector.isEmpty()) {
      this.initPatrolSector();
      this.refreshLedgerWithSectorData();
      this.refreshLedgerWithCongestionData(1);
    }

    // 处理复杂的战损和路况情报
    this.processBuriedPoliceIntel();
    this.markClearedRoadsBySight();
    this.processUrgentClearanceRequests();
    this.processNeighboringBlockades();

    // 收发电报
    this.receiveRadioMessages(mm);
    this.broadcastEmergencySignals(mm);
    AgentTelemetry.event(this.agentInfo, "update_summary", "RiotSquadScout",
        "sector_roads", this.myPatrolSector.size(), "pending_roads", this.taskPriorityLedger.size());

    return this;
  }

  /**
   * 防区网格首次认领。
   */
  private void initPatrolSector() {
    this.sectorDivider.calc();
    final EntityID mySelfId = this.agentInfo.getID();

    int myAssignedSectorIndex = this.sectorDivider.getClusterIndex(mySelfId);
    Collection<EntityID> zoneEntities = this.sectorDivider.getClusterEntityIDs(myAssignedSectorIndex);
    this.myPatrolSector.addAll(zoneEntities);
  }

  /**
   * 防区网格战术扩张。
   * 物理意义：干完活了，接管别的区域。
   */
  private void expandPatrolSector() {
    final EntityID mySelfId = this.agentInfo.getID();
    final int totalSectors = this.sectorDivider.getClusterNumber();
    final int myOriginalIndex = this.sectorDivider.getClusterIndex(mySelfId);

    final int previousSize = this.myPatrolSector.size();

    for (int step = 1; step < totalSectors && previousSize == this.myPatrolSector.size(); ++step) {
      Collection<EntityID> newZoneEntities = this.sectorDivider.getClusterEntityIDs(myOriginalIndex + step * totalSectors);
      this.myPatrolSector.addAll(newZoneEntities);
    }
  }

  /**
   * 将辖区大名单拆解并登录到战术账本中。
   * 物理意义：区分普通道路、房屋、主干道，赋予它们不同的优先级。
   */
  private void refreshLedgerWithSectorData() {
    this.assignPriorityToBaseSector(8);           // 兜底任务（优先级极低）
    this.assignPriorityToBuildings(7);            // 房屋入口（优先级低）
    this.assignPriorityToMainArteries(6);         // 交通主干道（优先级中等）
    this.assignPriorityToOccupiedBuildings(5);    // 有同行待着的楼（优先挖他们出来）
    this.assignPriorityToSafeHavens(0);           // 医院和避难所入口（最高优先级，生命通道！）
  }

  private void assignPriorityToBaseSector(int priorityLevel) {
    for (EntityID sectorId : this.myPatrolSector) {
      this.assignTaskPriority(sectorId, priorityLevel);
    }
  }

  private void assignPriorityToBuildings(int priorityLevel) {
    for (EntityID sectorId : this.myPatrolSector) {
      StandardEntity baseEntity = this.worldInfo.getEntity(sectorId);
      if (baseEntity instanceof Building) {
        this.assignTaskPriority(sectorId, priorityLevel);
      }
    }
  }

  private void assignPriorityToMainArteries(int priorityLevel) {
    Collection<EntityID> highwayList = this.mainArteryRadar.getClusterEntityIDs(0);
    for (EntityID hId : highwayList) {
      if (this.myPatrolSector.contains(hId)) {
        StandardEntity rawEntity = this.worldInfo.getEntity(hId);
        if (rawEntity instanceof Road) {
          this.assignTaskPriority(hId, priorityLevel);
        }
      }
    }
  }

  private void assignPriorityToOccupiedBuildings(int priorityLevel) {
    EntityID mySelfId = this.agentInfo.getID();
    // 修复：使用返回集合的 EntityID 类型，而不是尝试强转
    Collection<EntityID> allHeroIds = this.worldInfo.getEntityIDsOfType(FIRE_BRIGADE, POLICE_FORCE, AMBULANCE_TEAM);

    for (EntityID heroId : allHeroIds) {
      if (!heroId.equals(mySelfId)) {
        StandardEntity hisFootprint = this.worldInfo.getPosition(1, heroId);
        if (hisFootprint instanceof Building && this.myPatrolSector.contains(hisFootprint.getID())) {
          this.assignTaskPriority(hisFootprint.getID(), priorityLevel);
        }
      }
    }
  }

  private void assignPriorityToSafeHavens(int priorityLevel) {
    for (EntityID sectorId : this.myPatrolSector) {
      StandardEntity baseEntity = this.worldInfo.getEntity(sectorId);
      if (baseEntity instanceof Refuge) {
        this.assignTaskPriority(sectorId, priorityLevel);
      }
    }
  }

  /**
   * 将高危拥堵区登录账本。
   * 物理意义：如果雷达发现某片区域发生了大规模踩踏或车辆堵塞，立刻派出警力用 A* 寻路杀过去开路！
   */
  private void refreshLedgerWithCongestionData(int priorityLevel) {
    EntityID mySelfId = this.agentInfo.getID();
    EntityID myLocation = this.agentInfo.getPosition();

    int myCongestionZoneIndex = this.congestionZoneRadar.getClusterIndex(mySelfId);
    Collection<EntityID> congestedAreas = this.congestionZoneRadar.getClusterEntityIDs(myCongestionZoneIndex);

    for (EntityID crisisAreaId : congestedAreas) {
      if (this.shouldISuperviseCongestionZone(crisisAreaId)) {
        Collection<EntityID> rescuePath = this.advancedNavigator.getResult(myLocation, crisisAreaId);
        if (rescuePath != null) {
          for (EntityID pathNodeId : rescuePath) {
            if (!this.clearedRoadLog.contains(pathNodeId)) {
              this.assignTaskPriority(pathNodeId, priorityLevel);
            }
          }
        }
      }
    }
  }

  /**
   * 已贯通道路视觉清洗仪。
   * 物理意义：利用肉眼确认刚才走过的路是不是已经彻底畅通了，如果是，拉入黑名单并广度优先清洗相连区域。
   */
  private void markClearedRoadsBySight() {
    Set<EntityID> changedEntities = this.worldInfo.getChanged().getChangedEntities();
    EntityID myFootprintId = this.agentInfo.getPosition();

    // 焦点映射：记录视野内那些“确诊已经被彻底清理干净”的区域
    Map<EntityID, EntityID> clearedFocusMap = new HashMap<>();

    for (EntityID cId : changedEntities) {
      StandardEntity rawEntity = this.worldInfo.getEntity(cId);
      if (!(rawEntity instanceof Area)) {
        continue;
      }

      // 门槛推导：如果看清楚的是一栋楼，那它代表的目标可能是这栋楼的大门路口
      EntityID targetValueId = cId;
      if (!cId.equals(myFootprintId) && rawEntity instanceof Building) {
        EntityID entranceId = this.traceBuildingEntrance(cId);
        if (entranceId != null) {
          targetValueId = entranceId;
        }
      }

      // 利用高级几何引擎判断：这块地到底能不能通车？
      if (this.isAreaFullyPassable(targetValueId)) {
        clearedFocusMap.put(cId, targetValueId);
      }
    }

    // 广度优先漫游：顺着我站的地方往外推，把连着的一片干净路全标记出来
    Set<EntityID> accessibleScope = new HashSet<>();
    Queue<EntityID> propagationQueue = new LinkedList<>();
    propagationQueue.add(myFootprintId);

    while (!propagationQueue.isEmpty()) {
      EntityID currentId = propagationQueue.remove();

      if (!clearedFocusMap.containsKey(currentId)) {
        continue;
      }
      if (accessibleScope.contains(currentId)) {
        continue;
      }

      accessibleScope.add(currentId);
      Area currentArea = (Area) this.worldInfo.getEntity(currentId);
      for (EntityID neighborId : currentArea.getNeighbours()) {
        propagationQueue.add(neighborId);
      }
    }

    // 结算：将所有在连通范围内、且确诊无障碍的区域，打上“已完工”的烙印
    for (Map.Entry<EntityID, EntityID> focusEntry : clearedFocusMap.entrySet()) {
      EntityID trueTargetId = focusEntry.getValue();
      if (accessibleScope.contains(trueTargetId)) {
        this.clearedRoadLog.add(trueTargetId);
      }
    }
  }

  /**
   * 建筑物大门反溯仪。
   * 物理意义：通过寻路引擎倒推，看进入这栋楼之前，最后踩的是哪一条马路。
   */
  private EntityID traceBuildingEntrance(EntityID buildingId) {
    EntityID myLocation = this.agentInfo.getPosition();

    this.basicNavigator.setFrom(myLocation);
    this.basicNavigator.setDestination(buildingId);
    this.basicNavigator.calc();

    List<EntityID> routedPath = this.basicNavigator.getResult();
    if (routedPath == null) routedPath = new ArrayList<>();

    // 强制把起点塞进去
    if (routedPath.isEmpty() || !routedPath.get(0).equals(myLocation)) {
      routedPath.add(0, myLocation);
    }

    // 倒序检查路径，碰到的最后一条马路就是入口
    for (int idx = routedPath.size() - 1; idx >= 0; --idx) {
      EntityID pathNodeId = routedPath.get(idx);
      StandardEntity pathEntity = this.worldInfo.getEntity(pathNodeId);
      if (pathEntity instanceof Road) {
        return pathNodeId;
      }
    }
    return null;
  }

  /**
   * 车辆通行物理半径。
   */
  private static final double VEHICLE_PASSABLE_RADIUS = 500.0;

  /**
   * 几何引擎道路贯通鉴定器。
   * 物理意义：利用 JTS 库，从底层顶点多边形级别，死抠这条路上剩下的碎石会不会卡住轮胎。
   */
  private boolean isAreaFullyPassable(EntityID areaId) {
    if (areaId == null) {
      return false;
    }

    Area targetArea = (Area) this.worldInfo.getEntity(areaId);
    // 如果压根没有石头，那绝对通畅
    if (!targetArea.isBlockadesDefined()) {
      return true;
    }

    // 提取所有石头模型
    List<Blockade> rubbleList = new ArrayList<>();
    for (EntityID bId : targetArea.getBlockades()) {
      Blockade rubble = (Blockade) this.worldInfo.getEntity(bId);
      rubbleList.add(rubble);
    }

    // 交给 JTS 生成剩余的可通行几何面
    Geometry passableSurface = SpatialMorphologyCore.computePassable(targetArea, rubbleList);

    // 提取区域的所有虚线边界（比如路口连线）
    List<Line2D> virtualEdges = new ArrayList<>();
    for (Edge borderEdge : targetArea.getEdges()) {
      if (borderEdge.isPassable()) {
        virtualEdges.add(borderEdge.getLine());
      }
    }

    int edgeCount = virtualEdges.size();
    // 极其硬核的遍历：看任意两个出入口之间的距离，以及它们在可通行面内是否被彻底阻断
    for (int idx1 = 0; idx1 < edgeCount; ++idx1) {
      for (int idx2 = idx1 + 1; idx2 < edgeCount; ++idx2) {
        Line2D lineAlpha = virtualEdges.get(idx1);
        Line2D lineBeta = virtualEdges.get(idx2);

        // 如果这个出入口太窄，连一辆车都过不去，直接不予考虑
        if (lineAlpha.getDirection().getLength() < VEHICLE_PASSABLE_RADIUS * 2) {
          continue;
        }
        if (lineBeta.getDirection().getLength() < VEHICLE_PASSABLE_RADIUS * 2) {
          continue;
        }

        // 如果这两个出入口之间的通路，被剩下的碎石切断了，判定为不通畅
        if (!SpatialMorphologyCore.validate(passableSurface, lineAlpha, lineBeta)) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * 拥堵求救信号提权器。
   * 物理意义：如果我看到有人被堵在路上了（非警察、非平民，也就是消防和救护车被堵），
   * 立刻将他们脚下的路赋予极高优先级（2），赶紧去开路。
   */
  private void processUrgentClearanceRequests() {
    Set<EntityID> changedEntities = this.worldInfo.getChanged().getChangedEntities();

    this.EntrappedStateCrowdRadar.calc();
    Collection<EntityID> stuckVictimIds = this.EntrappedStateCrowdRadar.getClusterEntityIDs(0);

    for (EntityID cId : changedEntities) {
      if (!stuckVictimIds.contains(cId)) {
        continue;
      }

      StandardEntity rawEntity = this.worldInfo.getEntity(cId);
      if (!(rawEntity instanceof Human)) {
        continue;
      }
      // 警察自己能砍树，平民不用车，所以只管被堵的救护车和消防车
      if (rawEntity.getStandardURN() == POLICE_FORCE || rawEntity.getStandardURN() == CIVILIAN) {
        continue;
      }

      Human EntrappedStateColleague = (Human) rawEntity;
      EntityID colleagueLocation = EntrappedStateColleague.getPosition();

      // 提权登账，同时从完工黑名单里拉出来（可能又塌方了）
      this.assignTaskPriority(colleagueLocation, 2);
      this.clearedRoadLog.remove(colleagueLocation);
    }
  }

  /**
   * 邻近路障视野蔓延器。
   * 物理意义：只要我看到路边有人（特别是平民或伤员），我就顺便把他旁边有石头的区域提权（2），
   * 方便后续救护车能畅通无阻地开过去接他。
   */
  private void processNeighboringBlockades() {
    Set<EntityID> changedEntities = this.worldInfo.getChanged().getChangedEntities();
    Set<Human> sensitiveTargets = new HashSet<>();

    for (EntityID cId : changedEntities) {
      StandardEntity rawEntity = this.worldInfo.getEntity(cId);
      if (rawEntity instanceof Human) {
        Human hBody = (Human) rawEntity;
        StandardEntity footprint = this.worldInfo.getPosition(hBody);

        // 排除掉待在救护车里的人，和警察自己
        if (!(footprint instanceof AmbulanceTeam) && !(hBody instanceof PoliceForce)) {
          sensitiveTargets.add(hBody);
        }
      }
    }

    for (Human targetHuman : sensitiveTargets) {
      StandardEntity targetFootprint = this.worldInfo.getPosition(targetHuman);
      if (targetFootprint instanceof Area) {
        Area targetArea = (Area) targetFootprint;

        for (EntityID neighborAreaId : targetArea.getNeighbours()) {
          StandardEntity nEntity = this.worldInfo.getEntity(neighborAreaId);
          if (nEntity instanceof Area) {
            Area nArea = (Area) nEntity;

            boolean isNotClearedYet = !this.clearedRoadLog.contains(neighborAreaId);
            boolean hasRubble = nArea.isBlockadesDefined();

            // 如果旁边这条路有石头且还没扫干净，提权！
            if (isNotClearedYet && hasRubble) {
              this.assignTaskPriority(neighborAreaId, 2);
            }
          }
        }
      }
    }
  }

  /**
   * 开局静默期判断。
   */
  private boolean isUnderIdleDiscipline() {
    final int currentTime = this.agentInfo.getTime();
    final int disciplineThreshold = this.scenarioInfo.getKernelAgentsIgnoreuntil();
    return currentTime < disciplineThreshold;
  }

  /**
   * 战地电报监听器。
   */
  private void receiveRadioMessages(MessageManager mm) {
    List<CommunicationMessage> ambTelegrams = mm.getReceivedMessageList(MessageAmbulanceTeam.class);
    for (CommunicationMessage msg : ambTelegrams) {
      this.decodeEmergencyTelegram((MessageAmbulanceTeam) msg);
    }

    List<CommunicationMessage> fbTelegrams = mm.getReceivedMessageList(MessageFireBrigade.class);
    for (CommunicationMessage msg : fbTelegrams) {
      this.decodeEmergencyTelegram((MessageFireBrigade) msg);
    }
  }

  /**
   * 消防电报解密器。
   * 物理意义：如果消防车大喊“我路被堵了！”，且他离我比较近或者就在我的辖区里，
   * 立刻将那个地点设置为最高清障优先级（0）。
   */
  private void decodeEmergencyTelegram(MessageFireBrigade message) {
    EntityID senderId = message.getSenderID();
    EntityID crisisLocationId = message.getTargetID();

    if (message.getAction() != EMERGENCY_CODE_BLOCKADE) return;
    if (!message.isTargetDefined()) return;

    double signalRadius = this.scenarioInfo.getRawConfig().getIntValue("comms.channels.0.range");
    boolean isNearby = this.worldInfo.getDistance(this.agentInfo.getID(), senderId) <= signalRadius;

    if (isNearby) {
      this.assignTaskPriority(crisisLocationId, 0);
      this.clearedRoadLog.remove(crisisLocationId);
    }

    if (this.myPatrolSector.contains(crisisLocationId)) {
      this.assignTaskPriority(crisisLocationId, 0);
      this.clearedRoadLog.remove(crisisLocationId);
    }
  }

  /**
   * 救护电报解密器。
   */
  private void decodeEmergencyTelegram(MessageAmbulanceTeam message) {
    EntityID senderId = message.getSenderID();
    EntityID crisisLocationId = message.getTargetID();

    if (message.getAction() != EMERGENCY_CODE_BLOCKADE) return;
    if (!message.isTargetDefined()) return;

    double signalRadius = this.scenarioInfo.getRawConfig().getIntValue("comms.channels.0.range");
    boolean isNearby = this.worldInfo.getDistance(this.agentInfo.getID(), senderId) <= signalRadius;

    if (isNearby) {
      this.assignTaskPriority(crisisLocationId, 0);
      this.clearedRoadLog.remove(crisisLocationId);
    }

    if (this.myPatrolSector.contains(crisisLocationId)) {
      this.assignTaskPriority(crisisLocationId, 0);
      this.clearedRoadLog.remove(crisisLocationId);
    }
  }

  /**
   * 被埋警报发报机。
   * 物理意义：如果我自己都被埋在废墟里了，拼命发信号摇人。
   */
  private void broadcastEmergencySignals(MessageManager mm) {
    PoliceForce mySelfEntity = (PoliceForce) this.worldInfo.getEntity(this.agentInfo.getID());
    if (this.isMyselfBuried()) {
      mm.addMessage(new MessagePoliceForce(RADIO_CHANNEL, mySelfEntity, EMERGENCY_CODE_BURIED, this.agentInfo.getID()));
      mm.addMessage(new MessagePoliceForce(VOICE_CHANNEL, mySelfEntity, EMERGENCY_CODE_BURIED, this.agentInfo.getID()));
      return;
    }
  }

  /**
   * 自我被埋状态鉴定。
   */
  private boolean isMyselfBuried() {
    Human myBody = (Human) this.worldInfo.getEntity(this.agentInfo.getID());
    if (!(this.agentInfo.getPositionArea() instanceof Building)) {
      return false;
    }
    if (!myBody.isBuriednessDefined()) {
      return false;
    }
    return myBody.getBuriedness() != 0;
  }

  // 动态辖区覆盖半径常量计算
  private final Double CITY_MAX_SPAN = Math.max(this.worldInfo.getBounds().getMaxX(), this.worldInfo.getBounds().getMaxY());
  private final Double CITY_GRID_SIDES = this.scenarioInfo.getScenarioAgentsPf() != 0 ? Math.floor(Math.sqrt(this.scenarioInfo.getScenarioAgentsPf())) : 1;
  private final Double TACTICAL_INCLUDE_RADIUS = CITY_MAX_SPAN / CITY_GRID_SIDES;

  /**
   * 被埋警察跨区接管判定。
   * 物理意义：如果有同行被埋了，判断他是不是离我的防区够近，如果够近，我就把他的防区一并接管过来！
   */
  private boolean evaluateAdoptingBuriedColleagueSector(PoliceForce EntrappedStateColleague) {
    Integer mySectorIndex = this.sectorDivider.getClusterIndex(this.agentInfo.getID());
    Collection<EntityID> mySectorNodes = this.sectorDivider.getClusterEntityIDs(mySectorIndex);
    EntityID mySectorAnchor = null;
    int minMyId = Integer.MAX_VALUE;
    for (EntityID id : mySectorNodes) {
      if (id.getValue() < minMyId) {
        minMyId = id.getValue();
        mySectorAnchor = id;
      }
    }
    if (mySectorAnchor == null) return false;

    Integer hisSectorIndex = this.sectorDivider.getClusterIndex(EntrappedStateColleague.getID());
    Collection<EntityID> hisSectorNodes = this.sectorDivider.getClusterEntityIDs(hisSectorIndex);
    EntityID hisSectorAnchor = null;
    int minHisId = Integer.MAX_VALUE;
    for (EntityID id : hisSectorNodes) {
      if (id.getValue() < minHisId) {
        minHisId = id.getValue();
        hisSectorAnchor = id;
      }
    }
    if (hisSectorAnchor == null) return false;

    return this.worldInfo.getDistance(mySectorAnchor, hisSectorAnchor) < TACTICAL_INCLUDE_RADIUS;
  }

  /**
   * 战友阵亡防区接管情报网。
   * 物理意义：每秒看看有没有警察同行被埋了。如果他被埋了，我就接管他的辖区；如果他被挖出来了，我再把辖区还给他。
   */
  private void processBuriedPoliceIntel() {
    EntityID myLocation = this.agentInfo.getPosition();
    Set<EntityID> changedEntities = this.worldInfo.getChanged().getChangedEntities();
    Set<PoliceForce> newlyUpdatedPolice = new HashSet<>();

    for (EntityID cId : changedEntities) {
      StandardEntity rawEntity = this.worldInfo.getEntity(cId);
      if (rawEntity instanceof PoliceForce) {
        PoliceForce pf = (PoliceForce) rawEntity;
        if (pf.isBuriednessDefined()) {
          newlyUpdatedPolice.add(pf);
        }
      }
    }

    for (PoliceForce colleagueObj : newlyUpdatedPolice) {
      EntityID colleagueId = colleagueObj.getID();
      if (colleagueId.equals(this.agentInfo.getID())) {
        continue;
      }

      // 喜讯：他被挖出来了
      if (colleagueObj.getBuriedness() == 0) {
        if (this.EntrappedStatePoliceSectors.containsKey(colleagueId)) {
          this.myPatrolSector.removeAll(this.EntrappedStatePoliceSectors.get(colleagueId));
        }
        this.EntrappedStatePoliceSectors.remove(colleagueId);
      }
      // 噩耗：他被埋了，且我之前还没接管他的地盘
      else if (!this.EntrappedStatePoliceSectors.containsKey(colleagueId)) {
        if (this.evaluateAdoptingBuriedColleagueSector(colleagueObj)) {
          // 接管他的防区
          this.absorbColleagueSector(colleagueId);

          // 并为他原来负责的拥堵区立刻派发寻路清障任务（提权1级）
          Integer hisCongestionIndex = this.congestionZoneRadar.getClusterIndex(colleagueId);
          Collection<EntityID> crisisAreas = this.congestionZoneRadar.getClusterEntityIDs(hisCongestionIndex);

          // 手动排序
          List<EntityID> sortedCrisisAreas = new ArrayList<>(crisisAreas);
          Collections.sort(sortedCrisisAreas, new Comparator<EntityID>(){
            public int compare(EntityID a, EntityID b) {
              return Integer.compare(a.getValue(), b.getValue());
            }
          });

          for (EntityID cAreaId : sortedCrisisAreas) {
            if (this.shouldISuperviseCongestionZone(cAreaId, colleagueId)) {
              Collection<EntityID> rescuePath = this.advancedNavigator.getResult(myLocation, cAreaId);
              if (rescuePath != null) {
                for (EntityID pathNodeId : rescuePath) {
                  if (!this.clearedRoadLog.contains(pathNodeId)) {
                    this.assignTaskPriority(pathNodeId, 1);
                  }
                }
              }
            }
          }
        } else {
          // 如果离得太远管不了，就记个空账
          this.EntrappedStatePoliceSectors.put(colleagueId, new HashSet<>(0));
        }
      }
    }
  }

  /**
   * 跨区吸纳执行器。
   */
  private void absorbColleagueSector(EntityID colleagueId) {
    int sectorIdx = this.sectorDivider.getClusterIndex(colleagueId);
    Collection<EntityID> hisEntities = this.sectorDivider.getClusterEntityIDs(sectorIdx);
    this.EntrappedStatePoliceSectors.put(colleagueId, hisEntities);
    this.myPatrolSector.addAll(hisEntities);
  }

  /**
   * 自身拥堵防区督办鉴定。
   * 物理意义：判断某个发生拥堵的地方，该不该我来管？
   */
  private boolean shouldISuperviseCongestionZone(EntityID congestionAreaId) {
    if (this.clearedRoadLog.contains(congestionAreaId)) {
      return false;
    }

    int mySectorIndex = this.sectorDivider.getClusterIndex(this.agentInfo.getID());
    Collection<EntityID> mySectorNodes = this.sectorDivider.getClusterEntityIDs(mySectorIndex);
    EntityID mySectorAnchor = null;
    int minId = Integer.MAX_VALUE;
    for (EntityID id : mySectorNodes) {
      if (id.getValue() < minId) {
        minId = id.getValue();
        mySectorAnchor = id;
      }
    }
    if (mySectorAnchor == null) return false;

    return this.worldInfo.getDistance(mySectorAnchor, congestionAreaId) < TACTICAL_INCLUDE_RADIUS;
  }

  /**
   * 代管拥堵防区督办鉴定。
   * 物理意义：判断代为接管的拥堵地点，是否在合理的辐射范围内。
   */
  private boolean shouldISuperviseCongestionZone(EntityID congestionAreaId, EntityID colleagueId) {
    if (this.clearedRoadLog.contains(congestionAreaId)) {
      return false;
    }

    int hisSectorIndex = this.sectorDivider.getClusterIndex(colleagueId);
    Collection<EntityID> hisSectorNodes = this.sectorDivider.getClusterEntityIDs(hisSectorIndex);
    EntityID hisSectorAnchor = null;
    int minId = Integer.MAX_VALUE;
    for (EntityID id : hisSectorNodes) {
      if (id.getValue() < minId) {
        minId = id.getValue();
        hisSectorAnchor = id;
      }
    }
    if (hisSectorAnchor == null) return false;

    return this.worldInfo.getDistance(hisSectorAnchor, congestionAreaId) < TACTICAL_INCLUDE_RADIUS;
  }
}
