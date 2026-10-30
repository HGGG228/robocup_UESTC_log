package UESTC.module.complex.firebrigade;

import static java.util.Comparator.comparing;
import static rescuecore2.standard.entities.StandardEntityURN.*;

import UESTC.module.complex.common.ObjectScreener;
import UESTC.module.complex.common.SurvivalChronicle;
import UESTC.module.complex.common.EntrappedState;
import UESTC.module.util.AgentTelemetry;
import adf.core.agent.action.Action;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.complex.HumanDetector;
import java.util.*;
import rescuecore2.misc.Pair;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

/**
 * BrigadeScout 消防队生命探测与救援统筹雷达。
 * 物理意义：消防车的主要任务是灭火，但在火灾现场或地震废墟中，如果发现有平民或战友被埋，
 * 它们也肩负着挖掘和救援的副业。
 * 本类的核心战术逻辑：
 * 1. 优先救战友（同阵营的消防、警察、救护），因为战友获救后能产生更大的救援放大效应。
 * 2. 对平民进行“伤情与寿命（SurvivalChronicle）”评估，结合挖掘难度和物理距离，计算出最具性价比的救援目标。
 * 3. 协同挖掘：如果一个人被埋得很深，一辆车挖不出来，系统会呼叫附近防区的多辆车一起挖（上限为 3 辆）。
 */
public class BrigadeScout extends HumanDetector {

  /**
   * 物理意义：经过雷达扫描和伤情评估后，决定下车去用铁锹挖的那个人。
   */
  private EntityID finalRescueTarget = null;

  /**
   * 物理意义：用于明确当前消防车被分配在了哪一片责任区。
   */
  private Clustering sectorDividerRadar;

  /**
   * 物理意义：记录了归属本车负责的所有建筑和道路 ID。
   */
  private Set<EntityID> mySectorEntities = new HashSet<>();

  /**
   */
  private final Random stochasticEngine = new Random();

  /**
   * 物理意义：专门用于监听平民发来的痛苦哀嚎和求救信号。
   */
  private final CasualtyProfile distressSignalReceiver;

  // 固化通信频段标志（语音大喊 / 无线电）
  private final boolean VOICE_CHANNEL = false;
  private final boolean RADIO_CHANNEL = true;

  // 固化紧急求救代码
  private final int EMERGENCY_CODE_BURIED = 5;
  private final int EMERGENCY_CODE_BLOCKADE = 6;

  /**
   * 物理意义：如果我自己都被埋在废墟里了，我就无法去救别人，只能发信号等别人来救。
   */
  private boolean isMyselfNeedingRescue = false;

  // 各种任务类型的分类垃圾桶
  private Set<EntityID> civilianRescueQueue = new HashSet<>();
  private Set<EntityID> teammateRescueQueue = new HashSet<>();
  private Set<EntityID> civilianLoadQueue = new HashSet<>();
  private Set<EntityID> blacklistedAvoidTargets = new HashSet<>();
  private Set<EntityID> nonViableTargets = new HashSet<>();
  private Set<EntityID> broadcastedTargets = new HashSet<>();

  /**
   */
  private Pair<Integer, Integer> sectorGeographicCenter = null;

  /**
   */
  private Set<EntityID> jurisdictionalEntities = new HashSet<>();

  /**
   * 物理意义：记录最近 10 个周期内，各个地区汇聚的消防车数量，用于防扎堆判断。
   */
  private int[] truckDensityHistory = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
  private final int HISTORY_WINDOW_SIZE = truckDensityHistory.length;

  /**
   * 物理意义：一个坑周围最多只能站 3 个人挖，再多就施展不开了。
   */
  private final int MAX_COOPERATIVE_DIGGERS = 3;

  /**
   * buriedness < 26 的消防车优先于平民处理。
   */
  private static final int LIGHT_FIRE_BRIGADE_BURIEDNESS_THRESHOLD = 26;

  /**
   */
  private Clustering unreachablePathRadar;

  /**
   */
  private Clustering physicalTrapRadar;

  /**
   */
  public BrigadeScout(
      AgentInfo ai, WorldInfo wi,
      ScenarioInfo si, ModuleManager mm, DevelopData dd) {

    super(ai, wi, si, mm, dd);

    this.sectorDividerRadar = mm.getModule("UESTC.FB.HumanDetector.Clustering");
    this.registerModule(this.sectorDividerRadar);

    this.distressSignalReceiver = mm.getModule("UESTC.FB.HumanDetector.CitizenCommProtocol");
    this.registerModule(this.distressSignalReceiver);

    this.unreachablePathRadar = mm.getModule("UESTC.FB.Search.FailedMove");
    this.registerModule(this.unreachablePathRadar);

    this.physicalTrapRadar = mm.getModule("UESTC.FB.Search.StuckHumans");
    this.registerModule(this.physicalTrapRadar);
  }

  /**
   */
  @Override
  public EntityID getTarget() {
    AgentTelemetry.decision(this.agentInfo, this.worldInfo, "BrigadeScout", this.finalRescueTarget,
        "civilianCandidates", this.civilianRescueQueue.size(),
        "teammateCandidates", this.teammateRescueQueue.size(),
        "loadCandidates", this.civilianLoadQueue.size(),
        "sectorSize", this.mySectorEntities.size(),
        "jurisdictionSize", this.jurisdictionalEntities.size(),
        "blacklistSize", this.blacklistedAvoidTargets.size(),
        "nonViableCount", this.nonViableTargets.size(),
        "selfNeedsRescue", this.isMyselfNeedingRescue);
    return this.finalRescueTarget;
  }

  /**
   * 物理意义：每秒钟接收求救电报，初始化自身责任区，并清理无效任务。
   */
  @Override
  public HumanDetector updateInfo(MessageManager mm) {
    super.updateInfo(mm);

    // 防范单回合内的重复更新
    if (this.getCountUpdateInfo() > 1) {
      return this;
    }

    // 使用自身 ID 和时间戳合成绝对随机的种子
    long dynamicSeed = this.agentInfo.getID().getValue() + this.agentInfo.getTime();
    this.stochasticEngine.setSeed(dynamicSeed);

    // 如果还没有划分责任区，立即向雷达索要防区地图
    if (this.mySectorEntities.isEmpty()) {
      this.initializeSectorData();
    }

    // 收发电报，更新周围的求救呼声
    this.processIncomingTelegrams(mm);
    this.emitDistressSignalsIfNeeded(mm);

    // 洗牌任务列表，清理死者或已获救者
    this.purgeInvalidTasks();
    AgentTelemetry.event(this.agentInfo, "update_summary", "BrigadeScout",
        "civilianQueue", this.civilianRescueQueue.size(),
        "teammateQueue", this.teammateRescueQueue.size());
    return this;
  }

  /**
   * 物理意义：每秒钟环顾四周，看看有没有需要挖的人。优先挖同僚，其次评估平民性价比。
   * 重构说明：彻底去除了所有的 Java 8 Stream 操作，全部回归底层稳健的实体遍历。
   */
  @Override
  public HumanDetector calc() {
    AgentTelemetry.decision(this.agentInfo, this.worldInfo, "BrigadeScout", this.finalRescueTarget,
        "phase", "calc_start");
    // 唤醒求救信号处理器
    this.distressSignalReceiver.calc();

    final EntityID mySelfId = this.agentInfo.getID();

    // 伤情自检：我被埋了吗？如果我被埋了，直接放弃思考，等待救援
    this.isMyselfNeedingRescue = this.evaluateRescueNecessity(mySelfId);
    if (this.isMyselfNeedingRescue) {
      return logCalcResult("buried");
    }

    // 获取本回合视野内发生了状态变动的实体
    Set<EntityID> visuallyChangedEntities = this.worldInfo.getChanged().getChangedEntities();
    visuallyChangedEntities.add(mySelfId);

    // 目标合法性复核：如果我之前锁定的目标不值得救了，或者他就在我眼前（状态刷新了），立刻将目标清空重选
    boolean isTargetStillValid = this.evaluateRescueNecessity(this.finalRescueTarget);
    if (!isTargetStillValid || visuallyChangedEntities.contains(this.finalRescueTarget)) {
      this.finalRescueTarget = null;
    }

    // 建立各类人员的位置分布档案库
    Map<EntityID, Set<EntityID>> civilianDistribution = new HashMap<>();
    Map<EntityID, Set<EntityID>> ambulanceDistribution = new HashMap<>();
    Map<EntityID, Set<EntityID>> fireTruckDistribution = new HashMap<>();

    // 战友被埋专门名单
    Set<EntityID> EntrappedStateAmbulances = new HashSet<>();
    Set<EntityID> EntrappedStateFireTrucks = new HashSet<>();
    Set<EntityID> EntrappedStatePolice = new HashSet<>();

    // 遍历视野中所有变动的实体，进行检伤分类
    for (EntityID currentEntityId : visuallyChangedEntities) {
      StandardEntity rawEntity = this.worldInfo.getEntity(currentEntityId);
      // 只关心人类（车也是广义的人类载体）
      if (!(rawEntity instanceof Human)) {
        continue;
      }

      StandardEntity physicalLocation = this.worldInfo.getPosition(currentEntityId);
      // 如果人已经待在避难所（医院）里了，那是绝对安全的，不用我们管
      if (physicalLocation == null || physicalLocation.getStandardURN().equals(REFUGE)) {
        continue;
      }

      // 评估这个人是否需要挖掘，以及是否需要被装载（救护车专属任务，此处仅作记录）
      boolean needsDigging = this.evaluateRescueNecessity(currentEntityId);
      boolean needsLoading = this.evaluateLoadNecessity(currentEntityId);

      StandardEntityURN entityType = rawEntity.getStandardURN();
      EntityID locationId = physicalLocation.getID();

      // 分兵种建立伤情档案
      if (entityType.equals(AMBULANCE_TEAM)) {
        if (needsDigging) {
          EntrappedStateAmbulances.add(currentEntityId);
        } else {
          // 没被埋的救护车，记录其位置，方便后续联合行动评估
          Set<EntityID> squad = ambulanceDistribution.getOrDefault(locationId, new HashSet<>());
          squad.add(currentEntityId);
          ambulanceDistribution.put(locationId, squad);
        }
      } else if (entityType.equals(FIRE_BRIGADE)) {
        if (needsDigging) {
          EntrappedStateFireTrucks.add(currentEntityId);
        } else {
          Set<EntityID> squad = fireTruckDistribution.getOrDefault(locationId, new HashSet<>());
          squad.add(currentEntityId);
          fireTruckDistribution.put(locationId, squad);
        }
      } else if (entityType.equals(POLICE_FORCE)) {
        if (needsDigging) {
          EntrappedStatePolice.add(currentEntityId);
        }
      } else if (entityType.equals(CIVILIAN)) {
        if (needsDigging) {
          Set<EntityID> squad = civilianDistribution.getOrDefault(locationId, new HashSet<>());
          squad.add(currentEntityId);
          civilianDistribution.put(locationId, squad);
        } else if (needsLoading) {
          this.civilianLoadQueue.add(currentEntityId);
        }
      }
    }

    // --- 优先级 1：先救轻度掩埋消防车 ---
    // 只抢救 buriedness < 26 的消防车。
    // 这样可以快速释放一个可继续搜索/挖人的消防车，
    // 但不会因为重度掩埋战友而压过平民救援。
    this.finalRescueTarget = this.findShallowestBuriedAgentBelowDepth(
        EntrappedStateFireTrucks,
        LIGHT_FIRE_BRIGADE_BURIEDNESS_THRESHOLD
    );
    if (this.finalRescueTarget != null) return logCalcResult("buried-fire-brigade");

    // --- 统计滑动窗口 ---
    int visibleFireTrucksCount = 0;
    for (Set<EntityID> squad : fireTruckDistribution.values()) {
      visibleFireTrucksCount += squad.size();
    }
    int historyCursor = this.agentInfo.getTime() % HISTORY_WINDOW_SIZE;
    this.truckDensityHistory[historyCursor] = visibleFireTrucksCount;

    // 如果视野里根本没有被埋的平民，先从平民电报里找；
    // 平民电报也没有，再进入最后的战友兜底逻辑。
    if (civilianDistribution.isEmpty()) {
      this.finalRescueTarget = this.pickBestCivilianTaskFromTelegrams();
      if (this.finalRescueTarget != null) {
        return logCalcResult("civilian-message");
      }

      this.finalRescueTarget = this.pickLateTeammateRescueTarget(
          EntrappedStateFireTrucks,
          EntrappedStateAmbulances,
          EntrappedStatePolice
      );
      if (this.finalRescueTarget != null) {
        return logCalcResult("teammate");
      }

      this.finalRescueTarget = this.pickBestLateTeammateTaskFromTelegrams();
      return logCalcResult("teammate-message-or-idle");
    }

    // --- 平民挖掘分配逻辑 ---
    final StandardEntity myFootprint = this.worldInfo.getPosition(mySelfId);
    if (myFootprint == null) {
      this.finalRescueTarget = null;
      return logCalcResult("position-unavailable");
    }
    final EntityID myLocationId = myFootprint.getID();

    // 1. 脚底下逻辑：如果我脚底下就有被埋的平民，优先挖脚底下的！
    Set<EntityID> victimsUnderMyFeet = civilianDistribution.get(myLocationId);
    if (victimsUnderMyFeet != null) {
      Set<EntityID> fireTrucksHere = fireTruckDistribution.get(myLocationId);
      int diggerCount = 0;

      int localWorkforce = 1;
      if (fireTrucksHere != null) {
        localWorkforce = fireTrucksHere.size() + 1;
      }

      // 遍历脚底下的遇难者：不再按 ID，改为按“能救活、时间紧、埋得浅”排序
      for (EntityID victimId : this.sortCivilianVictimsByPriority(victimsUnderMyFeet, localWorkforce, true)) {
        // 判断这个坑周围站的人是不是已经满了，我还能不能挤进去挖？
        if (this.canIJoinTheDigging(fireTrucksHere, diggerCount, victimId)) {
          this.finalRescueTarget = victimId;
          return logCalcResult("local-civilian");
        } else {
          // 如果人满了，我挤不进去，就换一个策略
          if (fireTrucksHere == null) {
            if (this.canIJoinTheDigging(fireTruckDistribution.get(myLocationId), visibleFireTrucksCount, victimId)) {
              this.finalRescueTarget = victimId;
              return logCalcResult("local-civilian");
            }
          } else {
            // 这个人身边人满了，标记为暂不处理
            this.nonViableTargets.add(victimId);
          }
        }
        diggerCount++;
      }
      civilianDistribution.remove(myLocationId); // 脚底下的处理完了
    }

    // 2. 远处平民逻辑：脚底下没有，或者挤不进去，就去周围别的建筑挖
    for (Map.Entry<EntityID, Set<EntityID>> locationEntry : civilianDistribution.entrySet()) {
      Set<EntityID> trucksAtThatLocation = fireTruckDistribution.get(locationEntry.getKey());
      int expectedTruckCount = (trucksAtThatLocation == null) ? 1 : trucksAtThatLocation.size() + 1;
      int diggingOrder = 0;

      int effectiveTruckCount = Math.max(expectedTruckCount, this.getHistoricalMaxTruckCount());

      for (EntityID victimId : this.sortCivilianVictimsByPriority(locationEntry.getValue(), effectiveTruckCount, false)) {
        // 如果他是个黑名单死结，跳过
        if (this.blacklistedAvoidTargets.contains(victimId)) {
          continue;
        }

        // 核心评估：结合派过去的车辆数，算算这个人还能不能救得活？
        if (this.isSalvable(victimId, effectiveTruckCount)) {
          // 救得活，且我能挤进去挖
          if (this.canIJoinTheDigging(trucksAtThatLocation, diggingOrder, victimId)) {
            this.finalRescueTarget = victimId;
            return logCalcResult("visible-civilian");
          } else {
            this.blacklistedAvoidTargets.add(victimId);
          }
          diggingOrder++;
        }
      }
    }

    // 如果转了一大圈还是没找到视野内平民，先尝试平民电报任务。
    if (this.finalRescueTarget == null) {
      this.finalRescueTarget = this.pickBestCivilianTaskFromTelegrams();
    }

    // 平民任务也没有，再执行最后兜底：被埋消防车 -> 被埋救护车 -> 被埋警察。
    if (this.finalRescueTarget == null) {
      this.finalRescueTarget = this.pickLateTeammateRescueTarget(
          EntrappedStateFireTrucks,
          EntrappedStateAmbulances,
          EntrappedStatePolice
      );
    }

    // 视野里没有合适战友时，再从战友电报里按同样顺序兜底。
    if (this.finalRescueTarget == null) {
      this.finalRescueTarget = this.pickBestLateTeammateTaskFromTelegrams();
    }
    return logCalcResult("fallback-or-idle");
  }

  private HumanDetector logCalcResult(String reason) {
    AgentTelemetry.event(this.agentInfo, "target_select", "BrigadeScout",
        "reason", reason, "target", this.finalRescueTarget);
    return this;
  }

  /**
   * 物理意义：将底层雷达划分给我的那片防区，记录到大脑备忘录里。
   */
  private void initializeSectorData() {
    this.sectorDividerRadar.calc();
    final EntityID myId = this.agentInfo.getID();
    final int sectorId = this.sectorDividerRadar.getClusterIndex(myId);

    if (sectorId < 0) {
      return;
    }
    Collection<EntityID> assignedEntities = this.sectorDividerRadar.getClusterEntityIDs(sectorId);
    if (assignedEntities == null || assignedEntities.isEmpty()) {
      return;
    }
    this.mySectorEntities.addAll(assignedEntities);

    this.jurisdictionalEntities = new HashSet<>(assignedEntities);
    this.calculateSectorCenter();
  }

  /**
   * 物理意义：如果我被困住了，立刻用嗓子喊（附近听得见）和用电台发（全城听得见），请求别的车来救我。
   */
  private void emitDistressSignalsIfNeeded(MessageManager mm) {
    StandardEntity self = this.worldInfo.getEntity(this.agentInfo.getID());
    if (!(self instanceof FireBrigade)) {
      return;
    }
    FireBrigade mySelfEntity = (FireBrigade) self;
    if (!mySelfEntity.isPositionDefined() || !mySelfEntity.isXDefined() || !mySelfEntity.isYDefined()) {
      return;
    }
    ObjectScreener visualFilter = new ObjectScreener(this.worldInfo, this.agentInfo);
    Set<Blockade> visibleRubble = visualFilter.getBlockades();

    // 物理诊断：用我目前的坐标去跟周围的碎石多边形发生碰撞测试，看看我是否被卡死了
    EntrappedState mobilityDiagnostic = new EntrappedState(visibleRubble, this.agentInfo.getX(), this.agentInfo.getY());
    EntityID myLocationId = this.agentInfo.getPosition();

    // 困境 1：路被堵死了过不去（请求警察来锯木头）
    boolean isBlocked = mobilityDiagnostic.isEntrappedState() || (this.isUnreachable() && !this.checkIfBuriedInRubble());
    if (isBlocked) {
      mm.addMessage(new MessageFireBrigade(RADIO_CHANNEL, mySelfEntity, EMERGENCY_CODE_BLOCKADE, myLocationId));
      mm.addMessage(new MessageFireBrigade(VOICE_CHANNEL, mySelfEntity, EMERGENCY_CODE_BLOCKADE, myLocationId));
      return;
    }

    // 困境 2：房子塌了把我埋了（请求同行来挖我）
    if (this.checkIfBuriedInRubble()) {
      mm.addMessage(new MessageFireBrigade(RADIO_CHANNEL, mySelfEntity, EMERGENCY_CODE_BURIED, this.agentInfo.getID()));
      mm.addMessage(new MessageFireBrigade(VOICE_CHANNEL, mySelfEntity, EMERGENCY_CODE_BURIED, this.agentInfo.getID()));
      return;
    }

    // 日常报告：如果我上一秒执行了“救援平民”的动作，立刻向外通报进度
    if (this.agentInfo.getTime() > 1) {
      Action pastAction = this.agentInfo.getExecutedAction(this.agentInfo.getTime() - 1);
      if (pastAction != null && pastAction.toString().contains("ActionRescue")) {
        StandardEntity rescueTargetEntity = this.worldInfo.getEntity(this.finalRescueTarget);
        if (rescueTargetEntity instanceof Civilian) {
          Civilian rescuedCiv = (Civilian) rescueTargetEntity;
          mm.addMessage(new MessageCivilian(VOICE_CHANNEL, rescuedCiv));
          mm.addMessage(new MessageCivilian(RADIO_CHANNEL, rescuedCiv));
        }
      }
    }
  }

  /**
   * 物理意义：查验自身档案，看看有没有被压在房子底下。
   */
  private boolean checkIfBuriedInRubble() {
    StandardEntity self = this.worldInfo.getEntity(this.agentInfo.getID());
    if (!(self instanceof Human)) {
      return false;
    }
    Human myBody = (Human) self;

    // 只有在建筑物里才可能被埋
    if (!(this.agentInfo.getPositionArea() instanceof Building)) {
      return false;
    }
    // 数据缺失时默认安全
    if (!myBody.isBuriednessDefined()) {
      return false;
    }
    // 埋藏深度大于 0 视为被埋
    return myBody.getBuriedness() > 0;
  }

  /**
   * 物理意义：算出我这个辖区的最中心坐标在哪，方便以后判断任务是不是“偏离防区太远”。
   */
  private void calculateSectorCenter() {
    double totalX = 0.0;
    double totalY = 0.0;

    int elementCount = 0;
    for (EntityID elementId : this.jurisdictionalEntities) {
      Pair<Integer, Integer> coords = this.worldInfo.getLocation(elementId);
      if (coords == null || coords.first() == null || coords.second() == null) {
        continue;
      }
      totalX += coords.first();
      totalY += coords.second();
      elementCount++;
    }

    this.sectorGeographicCenter = elementCount == 0 ? null
        : new Pair<>((int) (totalX / elementCount), (int) (totalY / elementCount));
  }

  /**
   * 物理意义：判断这个人到底需不需要挥舞铁锹去挖。只要他还活着，且身上压了石头，就需要。
   */
  private boolean evaluateRescueNecessity(EntityID suspectId) {
    if (suspectId == null) {
      return false;
    }
    StandardEntity rawEntity = this.worldInfo.getEntity(suspectId);
    if (!(rawEntity instanceof Human)) {
      return false;
    }
    Human victim = (Human) rawEntity;
    return victim.isHPDefined() && victim.isBuriednessDefined()
        && victim.getHP() > 0 && victim.getBuriedness() > 0;
  }

  /**
   * 物理意义：判断这个人需不需要被抬上救护车。只有没被埋、活着、且受伤了的平民才需要。
   */
  private boolean evaluateLoadNecessity(EntityID suspectId) {
    if (suspectId == null) {
      return false;
    }
    StandardEntity rawEntity = this.worldInfo.getEntity(suspectId);
    if (!(rawEntity instanceof Civilian)) {
      return false;
    }
    Civilian victim = (Civilian) rawEntity;
    return victim.isHPDefined() && victim.isBuriednessDefined() && victim.isDamageDefined()
        && victim.getHP() > 0 && victim.getBuriedness() == 0 && victim.getDamage() > 0;
  }

  /**
   * 物理意义：打乱系统默认的迭代顺序，将实体按照身份证号从小到大严格排序。
   * 确保所有的车面对相同的乱局时，做出的检伤顺序是一模一样的，方便统筹配合。
   */
  private Set<EntityID> sortEntitiesById(Set<EntityID> chaosSet) {
    if (chaosSet == null || chaosSet.isEmpty()) {
      return new LinkedHashSet<>();
    }

    int[] rawIds = new int[chaosSet.size()];
    int cursor = 0;
    for (EntityID e : chaosSet) {
      rawIds[cursor++] = e.getValue();
    }

    Arrays.sort(rawIds);

    Set<EntityID> orderedSet = new LinkedHashSet<>();
    for (int idVal : rawIds) {
      orderedSet.add(new EntityID(idVal));
    }
    return orderedSet;
  }

  /**
   * 旧逻辑是按 EntityID 排序；这里改为按“可救性、紧急度、埋压深度、距离”排序。
   * 返回顺序：越靠前越优先救。
   */
  private List<EntityID> sortCivilianVictimsByPriority(Collection<EntityID> victims,
                                                       int assignedWorkforce,
                                                       boolean underMyFeet) {
    List<EntityID> orderedVictims = new ArrayList<>();

    if (victims == null || victims.isEmpty()) {
      return orderedVictims;
    }

    for (EntityID victimId : victims) {
      if (victimId == null) {
        continue;
      }

      StandardEntity rawEntity = this.worldInfo.getEntity(victimId);
      if (!(rawEntity instanceof Civilian)) {
        continue;
      }

      Civilian civilian = (Civilian) rawEntity;
      if (!civilian.isHPDefined() || !civilian.isBuriednessDefined() || !civilian.isDamageDefined()) {
        continue;
      }

      if (civilian.getHP() <= 0 || civilian.getBuriedness() <= 0) {
        continue;
      }

      orderedVictims.add(victimId);
    }

    final int workforce = Math.max(1, assignedWorkforce);
    final boolean localTarget = underMyFeet;

    Collections.sort(orderedVictims, new Comparator<EntityID>() {
      @Override
      public int compare(EntityID left, EntityID right) {
        double leftScore = evaluateCivilianPriorityScore(left, workforce, localTarget);
        double rightScore = evaluateCivilianPriorityScore(right, workforce, localTarget);

        int scoreCompare = Double.compare(leftScore, rightScore);
        if (scoreCompare != 0) {
          return scoreCompare;
        }

        return Integer.compare(left.getValue(), right.getValue());
      }
    });

    return orderedVictims;
  }

  /**
   * 分数越小，优先级越高。
   */
  private double evaluateCivilianPriorityScore(EntityID victimId,
                                               int assignedWorkforce,
                                               boolean underMyFeet) {
    StandardEntity rawEntity = this.worldInfo.getEntity(victimId);
    if (!(rawEntity instanceof Civilian)) {
      return Double.MAX_VALUE;
    }

    Civilian civilian = (Civilian) rawEntity;
    if (!civilian.isHPDefined() || !civilian.isBuriednessDefined() || !civilian.isDamageDefined()) {
      return Double.MAX_VALUE;
    }

    if (civilian.getHP() <= 0 || civilian.getBuriedness() <= 0) {
      return Double.MAX_VALUE;
    }

    int workforce = Math.max(1, assignedWorkforce);
    SurvivalChronicle medicalAnalyzer = new SurvivalChronicle(civilian);
    int lifeExpectancy = medicalAnalyzer.getLifeXpectancy();
    int digTime = this.calculateDiggingTime(victimId, workforce);
    int rescueMargin = lifeExpectancy - digTime;

    // 救不活的目标放到最后；仍保留兜底，不让排序阶段直接崩掉。
    if (rescueMargin <= 0) {
      return 1_000_000_000.0 + civilian.getBuriedness() * 1000.0 + victimId.getValue();
    }

    double distanceCost = 0.0;
    if (!underMyFeet) {
      distanceCost = this.worldInfo.getDistance(this.agentInfo.getID(), victimId) / 1000.0;
    }

    /*
     * 评分含义：
     * 1. rescueMargin 越小，说明越危险但还能救，优先级越高；
     * 2. buriedness 越小，越容易挖出来，优先级越高；
     * 3. 非脚下目标额外考虑距离，避免跑太远；
     * 4. 最后加一个很小的 ID 项，保证排序稳定。
     */
    return rescueMargin * 10.0
        + civilian.getBuriedness() * 2.0
        + distanceCost
        + victimId.getValue() * 0.000001;
  }

  /**
   * 物理意义：如果我看到一堆人正在挖一个坑，我判断一下，以我这辆车的资历（ID大小），
   * 我能不能挤进这个挖掘圈？如果人太多了我就不凑热闹了。
   */
  private boolean canIJoinTheDigging(Set<EntityID> diggerSquad, int effortMultiplier) {
    if (diggerSquad == null) {
      return true; // 没人挖，我当然能加入
    }

    diggerSquad.add(this.agentInfo.getID());

    // 计算坑位上限（基础上限 + 补偿机制）
    int diggerLimit = Math.min(diggerSquad.size(), this.getDynamicDiggerLimit() + this.getDynamicDiggerLimit() * effortMultiplier);

    // 按照资历（ID大小）排座次
    int[] sortedIds = new int[diggerSquad.size()];
    int cursor = 0;
    for (EntityID e : diggerSquad) {
      sortedIds[cursor++] = e.getValue();
    }
    Arrays.sort(sortedIds);

    int myOwnId = this.agentInfo.getID().getValue();
    // 看看在前 limit 个名额里，有没有我？有我说明我挤进去了
    for (int idx = 0; idx < diggerLimit; ++idx) {
      if (sortedIds[idx] == myOwnId) {
        return true;
      }
    }
    return false;
  }

  /**
   * 物理意义：根据特定目标所需的精准人数上限，来判断我能不能挤进去。
   */
  private boolean canIJoinTheDigging(Set<EntityID> diggerSquad, int effortMultiplier, EntityID targetVictimId) {
    if (diggerSquad == null) {
      return true;
    }

    diggerSquad.add(this.agentInfo.getID());

    int requiredForce = this.getPreciseDiggerLimit(targetVictimId) + this.getPreciseDiggerLimit(targetVictimId) * effortMultiplier;
    int diggerLimit = Math.min(diggerSquad.size(), requiredForce);

    int[] sortedIds = new int[diggerSquad.size()];
    int cursor = 0;
    for (EntityID e : diggerSquad) {
      sortedIds[cursor++] = e.getValue();
    }
    Arrays.sort(sortedIds);

    int myOwnId = this.agentInfo.getID().getValue();
    for (int idx = 0; idx < diggerLimit; ++idx) {
      if (sortedIds[idx] == myOwnId) {
        return true;
      }
    }
    return false;
  }

  /**
   */
  private int getDynamicDiggerLimit() {
    return MAX_COOPERATIVE_DIGGERS + (int) (this.agentInfo.getTime() * 0.01);
  }

  /**
   */
  private int getPreciseDiggerLimit(EntityID victimId) {
    return this.calculateRequiredWorkforce(victimId) + (int) (this.agentInfo.getTime() * 0.01);
  }

  /**
   * 物理意义：在一堆被压住的战友里，找那个埋得“最浅”的。
   * 因为埋得最浅最容易挖出来，挖出来之后他就能立刻帮忙去挖别人，性价比无敌！
   * 重构说明：去 Stream 化，手写寻找最小值。
   */
  private EntityID findShallowestBuriedAgent(Set<EntityID> EntrappedStateAgents) {
    Human easiestTarget = null;
    int shallowestDepth = Integer.MAX_VALUE;

    if (EntrappedStateAgents == null) {
      return null;
    }
    for (EntityID suspectId : EntrappedStateAgents) {
      StandardEntity rawEntity = this.worldInfo.getEntity(suspectId);
      if (rawEntity instanceof Human) {
        Human humanVictim = (Human) rawEntity;
        // 必须还活着
        if (humanVictim.isHPDefined() && humanVictim.isBuriednessDefined()
            && humanVictim.getHP() > 0 && humanVictim.getBuriedness() > 0) {
          int currentDepth = humanVictim.getBuriedness();
          // 打擂台，寻找埋藏值最小的战友
          if (currentDepth < shallowestDepth) {
            shallowestDepth = currentDepth;
            easiestTarget = humanVictim;
          }
        }
      }
    }

    if (easiestTarget != null) {
        return easiestTarget.getID();
    }
    return null;
  }


  /**
   * 轻度掩埋战友筛选器。
   * 只选择 buriedness < depthThreshold 的目标，并在其中选择 buriedness 最小者。
   */
  private EntityID findShallowestBuriedAgentBelowDepth(Set<EntityID> buriedAgents, int depthThreshold) {
    Human easiestTarget = null;
    int shallowestDepth = Integer.MAX_VALUE;

    if (buriedAgents == null || buriedAgents.isEmpty()) {
      return null;
    }

    for (EntityID suspectId : buriedAgents) {
      StandardEntity rawEntity = this.worldInfo.getEntity(suspectId);
      if (!(rawEntity instanceof Human)) {
        continue;
      }

      Human humanVictim = (Human) rawEntity;
      if (!humanVictim.isHPDefined() || !humanVictim.isBuriednessDefined()) {
        continue;
      }

      int currentDepth = humanVictim.getBuriedness();
      if (humanVictim.getHP() > 0 && currentDepth > 0 && currentDepth < depthThreshold) {
        if (currentDepth < shallowestDepth) {
          shallowestDepth = currentDepth;
          easiestTarget = humanVictim;
        }
      }
    }

    return easiestTarget == null ? null : easiestTarget.getID();
  }

  /**
   * 最后兜底的战友救援顺序：消防车 -> 救护车 -> 警察。
   * 这里不再限制 buriedness < 26，属于平民任务之后的兜底救援。
   */
  private EntityID pickLateTeammateRescueTarget(Set<EntityID> buriedFireTrucks,
                                                Set<EntityID> buriedAmbulances,
                                                Set<EntityID> buriedPoliceForces) {
    EntityID targetId = this.findShallowestBuriedAgent(buriedFireTrucks);
    if (targetId != null) {
      return targetId;
    }

    targetId = this.findShallowestBuriedAgent(buriedAmbulances);
    if (targetId != null) {
      return targetId;
    }

    return this.findShallowestBuriedAgent(buriedPoliceForces);
  }

  /**
   * 伤者存活率死刑宣判器。
   * 物理意义：通过医学公式（寿命期望值 - 挖掘耗时），判定这个人去救他还有没有意义？
   * 如果还没挖出来他就得死，那就放弃他，把宝贵的兵力留给能活下来的人。
   */
  private boolean isSalvable(EntityID victimId, int assignedWorkforce) {
    if (!this.evaluateRescueNecessity(victimId)) {
      return false;
    }
    Human humanBody = (Human) this.worldInfo.getEntity(victimId);

    if (humanBody instanceof Civilian) {
      if (!humanBody.isDamageDefined()) {
        return false;
      }
      SurvivalChronicle medicalAnalyzer = new SurvivalChronicle(humanBody);
      int estimatedSurvivalChronicle = medicalAnalyzer.getLifeXpectancy();
      int estimatedDigTime = this.calculateDiggingTime(victimId, assignedWorkforce);

      // 如果寿命期望大于挖掘时间，说明能赶在死前挖出来
      return (estimatedSurvivalChronicle - estimatedDigTime > 0);
    }
    // 对于非平民（战友），无条件认为值得救
    return true;
  }

  /**
   */
  private int calculateDiggingTime(EntityID victimId, int assignedWorkforce) {
    Human victim = (Human) this.worldInfo.getEntity(victimId);
    int workforce = Math.max(1, assignedWorkforce);
    // 向上取整，避免 buriedness 小于人数时被估成 0 回合。
    return (int) Math.ceil((double) victim.getBuriedness() / workforce);
  }

  /**
   * 物理意义：为了赶在这个人咽气之前把他挖出来，理论上最少需要派几辆车来挖？
   */
  private int calculateRequiredWorkforce(EntityID victimId) {
    Human victim = (Human) this.worldInfo.getEntity(victimId);
    SurvivalChronicle medicalAnalyzer = new SurvivalChronicle(victim);

    int depth = victim.getBuriedness();
    int SurvivalChronicle = medicalAnalyzer.getLifeXpectancy();

    int requiredForce = 1;
    // 模拟推演：派1个人行不行？派2个人行不行？一直加人，直到挖掘耗时小于他剩余的寿命
    while (SurvivalChronicle - depth / requiredForce <= 0) {
      ++requiredForce;
    }
    return requiredForce + 1; // 增加冗余系数，确保稳妥
  }

  /**
   * 物理意义：看看最近一段时间，这个地区最多曾汇聚过多少辆车。
   */
  private int getHistoricalMaxTruckCount() {
    int absoluteMax = 0;
    for (int count : this.truckDensityHistory) {
      if (count > absoluteMax) {
        absoluteMax = count;
      }
    }
    return absoluteMax;
  }

  /**
   */
  private void interpretTelegram(MessageCivilian msg) {
    EntityID callerId = msg.getAgentID();
    if (this.evaluateRescueNecessity(callerId)) {
      this.civilianRescueQueue.add(callerId);
    } else {
      this.nonViableTargets.add(callerId);
      this.civilianRescueQueue.remove(callerId);
    }
  }

  /**
   */
  private void interpretTelegram(MessageFireBrigade msg) {
    EntityID callerId = msg.getAgentID();
    if (msg.getAction() != EMERGENCY_CODE_BURIED) return;

    if (this.evaluateRescueNecessity(callerId)) {
      this.teammateRescueQueue.add(callerId);
      this.nonViableTargets.remove(callerId);
    } else {
      this.teammateRescueQueue.remove(callerId);
    }
  }

  /**
   */
  private void interpretTelegram(MessageAmbulanceTeam msg) {
    EntityID callerId = msg.getAgentID();
    if (msg.getAction() != EMERGENCY_CODE_BURIED) return;

    if (this.evaluateRescueNecessity(callerId)) {
      this.teammateRescueQueue.add(callerId);
      this.nonViableTargets.remove(callerId);
    } else {
      this.teammateRescueQueue.remove(callerId);
    }
  }

  /**
   */
  private void interpretTelegram(MessagePoliceForce msg) {
    EntityID callerId = msg.getAgentID();
    if (msg.getAction() != EMERGENCY_CODE_BURIED) return;

    if (this.evaluateRescueNecessity(callerId)) {
      this.teammateRescueQueue.add(callerId);
      this.nonViableTargets.remove(callerId);
    } else {
      this.teammateRescueQueue.remove(callerId);
    }
  }

  /**
   */
  private void processIncomingTelegrams(MessageManager mm) {
    List<CommunicationMessage> civMails = mm.getReceivedMessageList(MessageCivilian.class);
    for (CommunicationMessage raw : civMails) {
      this.interpretTelegram((MessageCivilian) raw);
    }

    List<CommunicationMessage> ambMails = mm.getReceivedMessageList(MessageAmbulanceTeam.class);
    for (CommunicationMessage raw : ambMails) {
      this.interpretTelegram((MessageAmbulanceTeam) raw);
    }

    List<CommunicationMessage> fbMails = mm.getReceivedMessageList(MessageFireBrigade.class);
    for (CommunicationMessage raw : fbMails) {
      this.interpretTelegram((MessageFireBrigade) raw);
    }

    List<CommunicationMessage> pfMails = mm.getReceivedMessageList(MessagePoliceForce.class);
    for (CommunicationMessage raw : pfMails) {
      this.interpretTelegram((MessagePoliceForce) raw);
    }
  }

  /**
   * 物理意义：电报听得太多，有的任务可能已经过期了。定期洗牌，把死了的、救活了的划掉。
   */
  private void purgeInvalidTasks() {
    Iterator<EntityID> iterator = this.civilianRescueQueue.iterator();
    while (iterator.hasNext()) {
      EntityID targetId = iterator.next();
      if (!this.evaluateRescueNecessity(targetId)) {
        this.nonViableTargets.add(targetId);
        iterator.remove();
      }
    }

    iterator = this.teammateRescueQueue.iterator();
    while (iterator.hasNext()) {
      EntityID targetId = iterator.next();
      if (!this.evaluateRescueNecessity(targetId)) {
        this.nonViableTargets.add(targetId);
        iterator.remove();
      }
    }

    iterator = this.civilianLoadQueue.iterator();
    while (iterator.hasNext()) {
      EntityID targetId = iterator.next();
      if (!this.evaluateLoadNecessity(targetId)) {
        this.nonViableTargets.add(targetId);
        iterator.remove();
      }
    }

    this.civilianRescueQueue.removeAll(this.blacklistedAvoidTargets);
    this.civilianRescueQueue.removeAll(this.nonViableTargets);
    this.teammateRescueQueue.removeAll(this.nonViableTargets);

    iterator = this.blacklistedAvoidTargets.iterator();
    while (iterator.hasNext()) {
      EntityID targetId = iterator.next();
      if (!this.evaluateRescueNecessity(targetId)) {
        this.nonViableTargets.add(targetId);
        iterator.remove();
      }
    }

    iterator = this.broadcastedTargets.iterator();
    while (iterator.hasNext()) {
      EntityID targetId = iterator.next();
      if (!this.evaluateRescueNecessity(targetId)) {
        this.nonViableTargets.add(targetId);
        iterator.remove();
      }
    }

    if (this.teammateRescueQueue.isEmpty()) {
      this.blacklistedAvoidTargets.clear();
    }
  }

  /**
   * 物理意义：当手头有很多求救名单时，算一笔经济账：
   * 成本 = 物理距离的平方根 + (寿命 - 受伤加重率) + 跨区调度惩罚。
   * 成本最低的那个，就是最优目标！
   */
  private EntityID calculateBestTaskByCost(Set<EntityID> candidateQueue) {
    EntityID optimumTarget = null;
    double lowestCalculatedCost = Double.MAX_VALUE;
    Pair<Integer, Integer> ownCoords = this.worldInfo.getLocation(this.agentInfo.getID());
    if (ownCoords == null || ownCoords.first() == null || ownCoords.second() == null) {
      return null;
    }

    for (EntityID suspectId : candidateQueue) {
      StandardEntity candidate = this.worldInfo.getEntity(suspectId);
      if (!(candidate instanceof Human)) {
        continue;
      }
      Human victimBody = (Human) candidate;
      if (!victimBody.isPositionDefined() || !victimBody.isHPDefined()
          || !victimBody.isBuriednessDefined() || !victimBody.isDamageDefined()) {
        continue;
      }
      Pair<Integer, Integer> victimCoords = this.worldInfo.getLocation(suspectId);
      if (victimCoords == null || victimCoords.first() == null || victimCoords.second() == null) {
        continue;
      }

      // 跨区调度惩罚：如果这个人不在我的防区里，距离我防区中心越远，惩罚越大
      int outOfBoundsPenalty = 0;
      if (!this.jurisdictionalEntities.contains(victimBody.getPosition()) && this.sectorGeographicCenter != null) {
          outOfBoundsPenalty = Math.abs(this.sectorGeographicCenter.first() - victimCoords.first())
                             + Math.abs(this.sectorGeographicCenter.second() - victimCoords.second());
      }

      // 物理油耗成本：曼哈顿距离
      double physicalDistance = Math.abs(ownCoords.first() - victimCoords.first())
                              + Math.abs(ownCoords.second() - victimCoords.second());
      double geographicCost = Math.sqrt(physicalDistance);

      // 生命医学成本：如果他寿命很短，或者伤得极重埋得极深，医疗成本急剧飙升
      SurvivalChronicle medicalAnalyzer = new SurvivalChronicle(victimBody);
      double medicalCost = medicalAnalyzer.getLifeXpectancy() - (victimBody.getDamage() * victimBody.getBuriedness());

      // 总代价汇总
      double totalComprehensiveCost = geographicCost + medicalCost + outOfBoundsPenalty;

      // 打擂台，寻找代价最小的黄金目标
      if (lowestCalculatedCost > totalComprehensiveCost) {
        lowestCalculatedCost = totalComprehensiveCost;
        optimumTarget = suspectId;
      }
    }
    return optimumTarget;
  }

  /**
   * 物理意义：如果在视野里没找到人挖，去听电报机，挑一个性价比最高的电报任务过去。
   */
  /**
   * 平民电报备用任务抽取器。
   * 现在平民优先于重度被埋战友，因此这里单独先取平民电报任务。
   */
  private EntityID pickBestCivilianTaskFromTelegrams() {
    if (!this.civilianRescueQueue.isEmpty()) {
      return this.calculateBestTaskByCost(this.civilianRescueQueue);
    }
    return null;
  }

  /**
   * 最后兜底的战友电报任务抽取器。
   * 顺序：消防车 -> 救护车 -> 警察。
   */
  private EntityID pickBestLateTeammateTaskFromTelegrams() {
    EntityID targetId = this.pickBestTeammateTaskByType(FIRE_BRIGADE);
    if (targetId != null) {
      return targetId;
    }

    targetId = this.pickBestTeammateTaskByType(AMBULANCE_TEAM);
    if (targetId != null) {
      return targetId;
    }

    return this.pickBestTeammateTaskByType(POLICE_FORCE);
  }

  /**
   * 从战友电报队列中按兵种筛选目标，再按原有代价函数选择最优者。
   */
  private EntityID pickBestTeammateTaskByType(StandardEntityURN targetType) {
    Set<EntityID> filteredQueue = new HashSet<>();

    for (EntityID candidateId : this.teammateRescueQueue) {
      StandardEntity rawEntity = this.worldInfo.getEntity(candidateId);
      if (rawEntity == null || !rawEntity.getStandardURN().equals(targetType)) {
        continue;
      }
      if (this.evaluateRescueNecessity(candidateId)) {
        filteredQueue.add(candidateId);
      }
    }

    if (filteredQueue.isEmpty()) {
      return null;
    }
    return this.calculateBestTaskByCost(filteredQueue);
  }


  /**
   */
  private boolean isUnreachable() {
    final EntityID myId = this.agentInfo.getID();
    final StandardEntityURN myType = this.agentInfo.me().getStandardURN();

    if (this.finalRescueTarget == null) {
      return false;
    }
    // 警察有锯子，不会瘫痪
    if (myType == POLICE_FORCE) {
      return false;
    }

    this.unreachablePathRadar.calc();
    this.physicalTrapRadar.calc();

    final boolean isRoadBlocked = this.unreachablePathRadar.getClusterIndex(myId) >= 0;
    final boolean isBodyStuck = this.physicalTrapRadar.getClusterIndex(myId) >= 0;

    return isBodyStuck || isRoadBlocked;
  }
}
