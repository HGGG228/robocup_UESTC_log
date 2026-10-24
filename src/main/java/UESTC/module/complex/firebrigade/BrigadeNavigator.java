package UESTC.module.complex.firebrigade;

import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.communication.MessageManager;
import adf.core.component.module.complex.Search;
import adf.core.component.module.algorithm.Clustering;
import rescuecore2.standard.entities.*;
import rescuecore2.messages.Command;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.misc.geometry.*;
import java.util.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

/**
 * BrigadeNavigator 消防车音频声呐生命探测雷达。
 * 物理意义：当消防车既不用灭火，也没有接到明确的挖掘指令时，它需要进行全城网格化巡逻搜救。
 * 本类的核心战术逻辑：
 * 1. 【听声辨位】：利用底层的 AKSpeak 语音监听模块，收集附近废墟传来的“救命”声。
 * 2. 【概率声呐】：把听到的求救声数量（潜力值 potentials），挂载到附近听觉范围内的建筑物上。
 * 3. 【靶向搜索】：优先查探那些被挂载了“最多求救声”的建筑物；如果都没有声音，就查探距离最近的。
 * 4. 【网格扩张】：当本辖区（Cluster）全搜完后，自动扩张搜索范围，前往下一个网格。
 */
public class BrigadeNavigator extends Search {

  /**
   * 最终锁定的声呐追踪目标。
   * 物理意义：经过雷达运算后，本回合决定开过去一探究竟的那栋建筑物 ID。
   */
  private EntityID finalSearchTarget;

  /**
   * 搜索网格名册。
   * 物理意义：本辆消防车目前负责扫楼的所有建筑 ID 集合。
   */
  private Set<EntityID> currentPatrolZone = new HashSet<>();
  
  /**
   * 建筑物生命潜能探测档案。
   * 物理意义：记录了每栋楼的“生命潜能”。如果我听到 3 个人在喊救命，我就把这 3 个人的 ID 挂在我周围的一圈楼上。
   * 哪栋楼上挂的求救 ID 最多，哪栋楼里埋着人的概率就最大！
   */
  private Map<EntityID, Set<EntityID>> buildingLifePotentials = new HashMap<>();
  
  /**
   * 已查探废墟名册。
   * 物理意义：我已经进去看过的楼，记在黑名单里，不再去浪费时间。
   */
  private Set<EntityID> alreadySearchedBuildings = new HashSet<>();
  
  /**
   * 因断头路而被迫放弃的目标。
   * 物理意义：路被堵死过不去的地方，暂时延后查探。
   */
  private Set<EntityID> delayedUnreachableTargets = new HashSet<>();
  
  /**
   * 跨区调度惩罚系数。
   * 物理意义：由本辖区最远两端的直线距离算出。用于惩罚那些让我跑太远的任务。
   */
  private double zoneDiagonalPenalty = 0.0;
  
  /**
   * 网格划区雷达。
   */
  private Clustering zonePartitionRadar;
  
  /**
   * 道路断行探测器。
   */
  private Clustering unreachablePathRadar;
  
  /**
   * 人群拥堵探测器。
   */
  private Clustering physicalTrapRadar;

  /**
   * 搜救雷达装配车间。
   */
  public BrigadeNavigator(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);

    this.zonePartitionRadar = mm.getModule("UESTC.FB.Search.Clustering");
    this.registerModule(this.zonePartitionRadar);

    this.unreachablePathRadar = mm.getModule("UESTC.FB.Search.FailedMove");
    this.registerModule(this.unreachablePathRadar);

    this.physicalTrapRadar = mm.getModule("UESTC.FB.Search.StuckHumans");
    this.registerModule(this.physicalTrapRadar);
  }

  /**
   * 战术结果提取接口。
   */
  @Override
  public EntityID getTarget() {
    return this.finalSearchTarget;
  }

  /**
   * 核心目标决断大脑。
   * 物理意义：每回合看一眼待查探清单。如果快查完了，就扩大防区。
   * 然后从清单里挑出一个最值得去的目标。
   */
  @Override
  public Search calc() {
    // 战术扩张：如果我的地盘快转完了（超过 90% 都看过了），向雷达申请扩大管辖范围
    if (this.evaluateIfExpansionNeeded()) {
      this.expandPatrolZone();
    }

    // 拷贝一份当前管辖名单，作为候选池
    Set<EntityID> activeCandidates = new HashSet<>(this.currentPatrolZone);
    // 划掉那些已经看过的地方
    activeCandidates.removeAll(this.alreadySearchedBuildings);
    
    // 如果候选池空了，无事可做
    if (activeCandidates.isEmpty()) {
      this.finalSearchTarget = null;
      return this;
    }

    EntityID optimalTarget = null;
    int maxPotentialScore = -1;
    double minDistanceScore = Double.MAX_VALUE;

    // 打擂台算法寻找最优目标
    for (EntityID candidateId : activeCandidates) {
      // 评估指标 1：这栋楼里挂了多少个人的呼救声？
      Set<EntityID> potentialsForThisBuilding = this.buildingLifePotentials.get(candidateId);
      int currentPotentialScore = (potentialsForThisBuilding == null) ? 0 : potentialsForThisBuilding.size();

      // 评估指标 2：跑过去需要多远？如果曾经试图过去但路断了（被 delayed），加上极其高昂的对角线惩罚路费！
      double currentDistanceScore = this.worldInfo.getDistance(candidateId, this.agentInfo.getID());
      if (this.delayedUnreachableTargets.contains(candidateId)) {
        currentDistanceScore += this.zoneDiagonalPenalty;
      }

      // 核心排序逻辑：呼救声越多的越优先；呼救声一样多时，距离越近越优先。
      boolean isBetterPotential = (currentPotentialScore > maxPotentialScore);
      boolean isSamePotentialButCloser = (currentPotentialScore == maxPotentialScore) && (currentDistanceScore < minDistanceScore);

      if (isBetterPotential || isSamePotentialButCloser) {
        maxPotentialScore = currentPotentialScore;
        minDistanceScore = currentDistanceScore;
        optimalTarget = candidateId;
      }
    }

    this.finalSearchTarget = optimalTarget;
    return this;
  }

  /**
   * 开局首次划定防区。
   */
  private void initCluster() {
    this.zonePartitionRadar.calc();

    final EntityID mySelfId = this.agentInfo.getID();
    final int myAssignedZoneIndex = this.zonePartitionRadar.getClusterIndex(mySelfId);
    
    Collection<EntityID> rawZoneEntities = this.zonePartitionRadar.getClusterEntityIDs(myAssignedZoneIndex);
    Collection<EntityID> purifiedBuildings = this.purifyBuildingsOnly(rawZoneEntities);

    this.currentPatrolZone.addAll(purifiedBuildings);
    // 计算出本防区的地理跨度，作为以后的“长途跋涉惩罚金”
    this.zoneDiagonalPenalty = this.computeZoneDiagonalSpan();
  }

  /**
   * 战术防区扩张器。
   * 物理意义：活干完了，就跨区接手别的网格。按索引顺序（i*n）去拿其他网格的任务。
   */
  private void expandPatrolZone() {
    final EntityID mySelfId = this.agentInfo.getID();

    final int totalZoneCount = this.zonePartitionRadar.getClusterNumber();
    final int myOriginalZoneIndex = this.zonePartitionRadar.getClusterIndex(mySelfId);

    final int oldSize = this.currentPatrolZone.size();
    
    for (int step = 1; step < totalZoneCount; ++step) {
      // 如果加完之后尺寸没变（说明刚才那个网格是空的或者全查过了），继续往下一个网格借活
      if (this.currentPatrolZone.size() > oldSize) {
        break;
      }
      
      int nextZoneIndex = myOriginalZoneIndex + step * totalZoneCount;
      Collection<EntityID> newRawEntities = this.zonePartitionRadar.getClusterEntityIDs(nextZoneIndex);
      Collection<EntityID> newBuildings = this.purifyBuildingsOnly(newRawEntities);
      
      this.currentPatrolZone.addAll(newBuildings);
    }
  }

  /**
   * 防区扩张阈值评估。
   * 物理意义：当我的责任田里，有 90% 的楼都已经查探过了，就可以开始接别区的活了。
   */
  private boolean evaluateIfExpansionNeeded() {
    return this.alreadySearchedBuildings.size() >= this.currentPatrolZone.size() * 0.9;
  }

  /**
   * 纯净楼宇过滤器。
   * 物理意义：雷达划分的网格里有路也有楼。搜救只需要搜楼，把路过滤掉。
   */
  private Set<EntityID> purifyBuildingsOnly(Collection<EntityID> mixedCollection) {
    Set<EntityID> cleanBuildingSet = new HashSet<>();
    if (mixedCollection == null) {
      return cleanBuildingSet;
    }

    for (EntityID entityId : mixedCollection) {
      StandardEntity rawEntity = this.worldInfo.getEntity(entityId);
      if (rawEntity instanceof Building) {
        cleanBuildingSet.add(rawEntity.getID());
      }
    }
    return cleanBuildingSet;
  }

  /**
   * 战地情报汇总更新。
   * 物理意义：每秒处理自己的移动状态、听觉信息和视觉信息，更新数据库。
   */
  @Override
  public Search updateInfo(MessageManager mm) {
    super.updateInfo(mm);
    if (this.getCountUpdateInfo() > 1) {
      return this;
    }

    // 冷启动
    if (this.currentPatrolZone.isEmpty()) {
      this.initCluster();
    }

    // 困境检讨：如果刚才选的地方过不去，且我自己没被埋（确实是路断了），把那个地方拉入延后处理名单
    Human mySelfBody = (Human) this.agentInfo.me();
    boolean amINotBuried = !mySelfBody.isBuriednessDefined() || mySelfBody.getBuriedness() == 0;
    
    if (this.isUnreachable() && amINotBuried) {
      if (this.finalSearchTarget != null) {
        this.delayedUnreachableTargets.add(this.finalSearchTarget);
      }
    }

    // 核心情报 1：听觉雷达映射（把声音投射到建筑物上）
    this.mapAcousticSignalsToBuildings();
    
    // 核心情报 2：视觉检阅与档案清洗
    this.logVisuallySearchedBuildings();
    this.ignoreUntaskableBuildings();
    this.ignoreDiscoveredCivilians();
    this.ignoreRescuedCivilian();
    
    // 如果全城都被我看光了，清空档案准备从头再巡逻一遍
    if (this.evaluateIfCityIsFullySearched()) {
      this.alreadySearchedBuildings.clear();
    }

    return this;
  }

  /**
   * 听觉雷达潜力映射仪。
   * 物理意义：人类的耳朵无法精确定位。如果我听见有人在 30 米内喊救命，
   * 那我就把我周围 30 米内所有的楼都记上一笔（增加潜力值）。
   */
  private void mapAcousticSignalsToBuildings() {
    Set<EntityID> buildingsInEarshot = this.findBuildingsInAcousticRange();
    Set<EntityID> criesForHelpIds = this.listenToCivilianCries();

    for (EntityID bldId : buildingsInEarshot) {
      Set<EntityID> potentialRecord = this.buildingLifePotentials.get(bldId);
      if (potentialRecord == null) {
        potentialRecord = new HashSet<>();
      }
      potentialRecord.addAll(criesForHelpIds);
      this.buildingLifePotentials.put(bldId, potentialRecord);
    }
  }

  /**
   * 声呐有效听觉圈扫描器。
   * 物理意义：去底层配置文件里查询人类嗓门的传播距离（通常是 30 米），然后把这 30 米内的楼全找出来。
   */
  private Set<EntityID> findBuildingsInAcousticRange() {
    int acousticRadius = this.scenarioInfo.getRawConfig().getIntValue("comms.channels.0.range");
    EntityID mySelfId = this.agentInfo.getID();
    
    // 底层引擎返回的是实体对象，此处修正为兼容的遍历逻辑
    Collection<StandardEntity> rawEntitiesInRange = this.worldInfo.getObjectsInRange(mySelfId, acousticRadius);
    Set<EntityID> idsInRange = new HashSet<>();
    
    for (StandardEntity entity : rawEntitiesInRange) {
      idsInRange.add(entity.getID());
    }
    
    return this.purifyBuildingsOnly(idsInRange);
  }

  /**
   * 绝望呼救声剥离器。
   * 物理意义：监听全网语音频道。听到大喊大叫的，必须排除掉那些自己人的喊声（警察、消防、医生），
   * 剩下的纯粹就是平民求救声了。
   */
  private Set<EntityID> listenToCivilianCries() {
    Set<EntityID> officialAgents = new HashSet<>(this.worldInfo.getEntityIDsOfType(
        FIRE_BRIGADE, FIRE_STATION,
        AMBULANCE_TEAM, AMBULANCE_CENTRE,
        POLICE_FORCE, POLICE_OFFICE));

    Set<EntityID> distressedCivilianIds = new HashSet<>();
    Collection<Command> heardSignals = this.agentInfo.getHeard();

    for (Command signal : heardSignals) {
      if (signal instanceof AKSpeak) {
        AKSpeak voiceSignal = (AKSpeak) signal;
        // 0 频道是人类物理说话的语音频道
        if (voiceSignal.getChannel() == 0) {
          EntityID speakerId = voiceSignal.getAgentID();
          // 如果说话的不是官方人员，那就是平民！
          if (!officialAgents.contains(speakerId)) {
            distressedCivilianIds.add(speakerId);
          }
        }
      }
    }
    return distressedCivilianIds;
  }

  /**
   * 视觉检阅雷达。
   * 物理意义：记录自己走过的路，只要被我的双眼（或者感知识别线）确切看过的楼，
   * 就拉入已搜查黑名单。并且，如果一栋楼我彻底看过了，那这栋楼上的“潜在呼救声”就可以清空了。
   */
  private void logVisuallySearchedBuildings() {
    EntityID myFootprint = this.agentInfo.getPosition();
    if (this.worldInfo.getEntity(myFootprint) instanceof Building) {
      this.alreadySearchedBuildings.add(myFootprint);
    }

    Set<EntityID> visuallyChangedEntities = this.worldInfo.getChanged().getChangedEntities();
    for (EntityID cId : visuallyChangedEntities) {
      StandardEntity rawEntity = this.worldInfo.getEntity(cId);
      if (rawEntity instanceof Building) {
        Building bld = (Building) rawEntity;
        // 如果这栋楼的每个角落都被我看清了（无视觉死角）
        if (this.isVisuallyCleared(bld)) {
          this.alreadySearchedBuildings.add(bld.getID());
        }
      }
    }

    // 从潜力声呐板上，撕掉那些已经被彻底看清底细的楼
    for (EntityID searchedId : this.alreadySearchedBuildings) {
      this.buildingLifePotentials.remove(searchedId);
    }
  }

  /**
   * 视觉死角穿透检测算法。
   * 物理意义：光距离近没用，如果这栋楼被其他墙壁挡住了，就不算看清。
   * 发射一条射线，看射线是否被楼体边缘打断。
   */
  private boolean isVisuallyCleared(Building targetBuilding) {
    int maxSightDistance = this.scenarioInfo.getPerceptionLosMaxDistance();
    Line2D sightLine = new Line2D(
        this.getMyCoordinates(),
        new Point2D(targetBuilding.getX(), targetBuilding.getY()));

    // 如果距离超出了最大视距的 80%，不算看清（模糊）
    if (sightLine.getDirection().getLength() >= maxSightDistance * 0.8) {
      return false;
    }
    
    // 检查视线是否与这栋楼的外墙边框发生碰撞
    for (Edge structuralEdge : targetBuilding.getEdges()) {
      // 只有实心墙（不可穿透）才会挡视线
      if (structuralEdge.isPassable() == false) {
        Point2D collisionPoint = GeometryTools2D.getSegmentIntersectionPoint(sightLine, structuralEdge.getLine());
        if (collisionPoint != null) {
          // 视线成功打在了外墙上，说明确实看清了
          return true;
        }
      }
    }
    return false;
  }

  /**
   * 无搜救价值废墟剔除器。
   * 物理意义：如果一栋楼没塌（破坏度 == 0），那里面的人大摇大摆就走出来了，
   * 不需要我去搜救！直接拉入已搜查黑名单。
   */
  private void ignoreUntaskableBuildings() {
    Set<EntityID> changedEntities = this.worldInfo.getChanged().getChangedEntities();
    
    for (EntityID cId : changedEntities) {
      StandardEntity rawEntity = this.worldInfo.getEntity(cId);
      if (rawEntity instanceof Building) {
        Building bld = (Building) rawEntity;
        if (bld.isBrokennessDefined() && bld.getBrokenness() == 0) {
          this.alreadySearchedBuildings.add(bld.getID());
        }
      }
    }
    for (EntityID searchedId : this.alreadySearchedBuildings) {
      this.buildingLifePotentials.remove(searchedId);
    }
  }

  /**
   * 提取自身二维坐标点。
   */
  private Point2D getMyCoordinates() {
    return new Point2D(this.agentInfo.getX(), this.agentInfo.getY());
  }

  /**
   * 已发现遇难者声源解除器。
   * 物理意义：如果某个平民已经被我【亲眼看到】了，那么刚才在这栋楼上记下的他的“呼救潜能点”，
   * 就可以划掉了，因为人已经找到了。
   */
  private void ignoreDiscoveredCivilians() {
    Set<EntityID> changedEntities = this.worldInfo.getChanged().getChangedEntities();
    Set<EntityID> visibleCivilianIds = new HashSet<>();

    for (EntityID cId : changedEntities) {
      StandardEntity rawEntity = this.worldInfo.getEntity(cId);
      if (rawEntity instanceof Civilian) {
        visibleCivilianIds.add(rawEntity.getID());
      }
    }

    for (Set<EntityID> potentialList : this.buildingLifePotentials.values()) {
      potentialList.removeAll(visibleCivilianIds);
    }
  }

  /**
   * 已获救平民声源解除器。
   * 物理意义：如果我看到某个平民不仅还活着，而且根本没被埋（被挖出来了），
   * 那他的求救档案就彻底销毁。
   */
  private void ignoreRescuedCivilian() {
    Set<EntityID> changedEntities = this.worldInfo.getChanged().getChangedEntities();
    Set<EntityID> safeCivilianIds = new HashSet<>();

    for (EntityID cId : changedEntities) {
      StandardEntity rawEntity = this.worldInfo.getEntity(cId);
      if (rawEntity instanceof Civilian) {
        Civilian civ = (Civilian) rawEntity;
        if (civ.isBuriednessDefined() && civ.getBuriedness() <= 0) {
          safeCivilianIds.add(civ.getID());
        }
      }
    }

    for (Set<EntityID> potentialList : this.buildingLifePotentials.values()) {
      potentialList.removeAll(safeCivilianIds);
    }
  }

  /**
   * 交通瘫痪状态鉴定器。
   */
  private boolean isUnreachable() {
    final EntityID myId = this.agentInfo.getID();
    final StandardEntityURN myType = this.agentInfo.me().getStandardURN();

    if (this.finalSearchTarget == null) {
      return false;
    }
    // 警察能伐木开路，永不瘫痪
    if (myType == POLICE_FORCE) {
      return false;
    }

    this.unreachablePathRadar.calc();
    this.physicalTrapRadar.calc();

    final boolean isRoadBlocked = this.unreachablePathRadar.getClusterIndex(myId) >= 0;
    final boolean isBodyStuck = this.physicalTrapRadar.getClusterIndex(myId) >= 0;
    
    return isBodyStuck || isRoadBlocked;
  }

  /**
   * 战区对角线极值测算器。
   * 物理意义：抽出这片网格里所有楼的最左、最右、最上、最下坐标。
   * 利用勾股定理算出这片区域横跨多远。用于在跨区调度时惩罚路费。
   */
  private double computeZoneDiagonalSpan() {
    int minX = Integer.MAX_VALUE;
    int minY = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int maxY = Integer.MIN_VALUE;

    boolean hasBuilding = false;

    for (EntityID elementId : this.currentPatrolZone) {
      StandardEntity rawEntity = this.worldInfo.getEntity(elementId);
      if (rawEntity instanceof Building) {
        Building bld = (Building) rawEntity;
        hasBuilding = true;
        int bldX = bld.getX();
        int bldY = bld.getY();

        if (bldX < minX) minX = bldX;
        if (bldY < minY) minY = bldY;
        if (bldX > maxX) maxX = bldX;
        if (bldY > maxY) maxY = bldY;
      }
    }

    if (!hasBuilding) {
      return 0.0;
    }

    return Math.hypot(maxX - minX, maxY - minY);
  }

  /**
   * 全城扫荡清零核查器。
   * 物理意义：当我看过的非安全屋建筑总数，等于全城非安全屋建筑总数时，
   * 宣告全城一轮扫荡完毕！清空档案，开始二刷。
   */
  private boolean evaluateIfCityIsFullySearched() {
    Collection<StandardEntity> allBuildingEntities = this.worldInfo.getEntitiesOfType(BUILDING);
    Collection<StandardEntity> allRefugeEntities = this.worldInfo.getEntitiesOfType(REFUGE);

    Set<EntityID> generalBuildings = new HashSet<>();
    for (StandardEntity e : allBuildingEntities) {
      generalBuildings.add(e.getID());
    }

    Set<EntityID> safeRefuges = new HashSet<>();
    for (StandardEntity e : allRefugeEntities) {
      safeRefuges.add(e.getID());
    }

    // 剔除医院避难所
    generalBuildings.removeAll(safeRefuges);

    // 算算我看过了几栋危楼
    long validReachedCount = 0;
    for (EntityID reachedId : this.alreadySearchedBuildings) {
      if (!safeRefuges.contains(reachedId)) {
        validReachedCount++;
      }
    }

    return validReachedCount == generalBuildings.size();
  }
}
