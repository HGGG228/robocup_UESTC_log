package UESTC.module.complex.policeforce;

import UESTC.module.util.AgentTelemetry;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.complex.Search;
import adf.core.component.communication.CommunicationMessage;
import rescuecore2.messages.Command;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.Edge;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.Refuge;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.EntityID;

import static rescuecore2.standard.entities.StandardEntityURN.*;

/**
 * RiotSquadRouter 警察部队生命探测与网格巡逻雷达。
 * 物理意义：警察的主要职责是清障，但当街上没路障时，警察也不能闲着，
 * 必须去各个建筑物里找人。而且，警察有义务优先去“消防车正在灭火”的楼附近清障，
 * 方便消防车撤离或救护车进入。
 * 战术核心：
 * 1. 优先查探那些被明确报告有“被埋平民”的楼。
 * 2. 其次查探“求救声（Voice）”最密集的楼。
 * 3. 网格化扫楼，扫完自动扩张，并规避大火和废墟。
 */
public class RiotSquadRouter extends Search {

    // ========================================================================
    // 指挥部核心战术账本
    // ========================================================================

    // 跨区远征惩罚系数。如果是去借别的区的活干，路途遥远，在算距离分时要加上这个惩罚值。
    private double zoneDiagonalPenalty = 0.0;

    // 消防车作业区高优定位。全城所有的消防车现在都在哪？这些地方可能正在恶战，需要我优先去！
    private Set<EntityID> activeFireBrigadePositions = new HashSet<>();

    // 确诊被埋平民倒计时。听到别人发报说这里有平民被埋了，必须赶在倒计时归零前去看一眼。
    private Map<EntityID, Integer> buriedCiviliansCountdown = new HashMap<>();

    // 本回合锁定的巡逻目标。经过所有雷达和算法博弈后，警车这秒钟决定开过去的终点站。
    private EntityID finalSearchTarget;

    // 当前防区内的扫楼名单。记录了我这辆车目前负责的这几条街上，还有哪些楼没去看过。
    private Set<EntityID> currentPatrolZone = new HashSet<>();

    // 建筑物生命潜能声呐板。Key是一栋楼，Value是里面传出来的求救声(平民ID)。
    private Map<EntityID, Set<EntityID>> buildingLifePotentials = new HashMap<>();

    // 已查探的黑名单楼宇。去过一次的楼就记下来，防止鬼打墙来回转圈。
    private Set<EntityID> alreadySearchedBuildings = new HashSet<>();

    // 因断头路被迫放弃的楼。明明想去，但底层雷达说路堵死了去不了，加入“以后再说”的名单。
    private Set<EntityID> delayedUnreachableTargets = new HashSet<>();

    // ========================================================================
    // 底层探测雷达阵列
    // ========================================================================

    private Clustering zonePartitionRadar;     // 全城网格划区雷达
    private Clustering unreachablePathRadar;   // 断头路探测器
    private Clustering physicalTrapRadar;      // 肉体被困探测器

    /**
     * 警察巡逻车总控装配台。
     */
    public RiotSquadRouter(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager mm, DevelopData dd) {
        super(ai, wi, si, mm, dd);

        // 去装备库把需要的雷达都申请出来
        this.zonePartitionRadar = mm.getModule("UESTC.PF.Search.Clustering");
        this.unreachablePathRadar = mm.getModule("UESTC.PF.Search.FailedMove");
        this.physicalTrapRadar = mm.getModule("UESTC.PF.Search.StuckHumans");

        // 挂载到警车仪表盘上，每秒自动通电
        this.registerModule(this.zonePartitionRadar);
        this.registerModule(this.unreachablePathRadar);
        this.registerModule(this.physicalTrapRadar);
    }

    @Override
    public EntityID getTarget() {
        // 向司机出示这回合要去的目标
        AgentTelemetry.decision(this.agentInfo, this.worldInfo, "RiotSquadRouter", this.finalSearchTarget,
            "patrolZoneSize", this.currentPatrolZone.size(),
            "searchedCount", this.alreadySearchedBuildings.size(),
            "unreachableCount", this.delayedUnreachableTargets.size(),
            "voiceBuildingCount", this.buildingLifePotentials.size(),
            "targetVoiceCount", this.buildingLifePotentials.getOrDefault(this.finalSearchTarget, java.util.Collections.emptySet()).size(),
            "buriedCivilianReports", this.buriedCiviliansCountdown.size(),
            "activeFireBrigadePositions", this.activeFireBrigadePositions.size(),
            "zonePenalty", this.zoneDiagonalPenalty);
        return this.finalSearchTarget;
    }

    // ========================================================================
    // 内部类 1：战区规划师 (Zone Planner)
    // 调换顺序：负责防区分配、扩张与网格管理，移至最前方
    // ========================================================================
    private class ZonePlanner {

        /**
         * 初始划区。找雷达要到我的地盘，并算出地盘的对角线长度作为惩罚基数。
         */
        void initCluster() {
            zonePartitionRadar.calc();
            final EntityID mySelfId = agentInfo.getID();
            final int myAssignedZoneIndex = zonePartitionRadar.getClusterIndex(mySelfId);

            Collection<EntityID> rawZoneEntities = zonePartitionRadar.getClusterEntityIDs(myAssignedZoneIndex);

            // 提纯：只留下建筑物，剔除马路牙子
            Set<EntityID> cleanBuildingSet = new HashSet<>();
            for (EntityID entityId : rawZoneEntities) {
                if (worldInfo.getEntity(entityId) instanceof Building) cleanBuildingSet.add(entityId);
            }

            currentPatrolZone.addAll(cleanBuildingSet);
            // 算出我这块地盘的对角线到底有多长，留作备用
            zoneDiagonalPenalty = computeZoneDiagonalSpan(cleanBuildingSet);
        }

        /**
         * 跨区越权：去抢别人的活干。
         */
        void expandPatrolZone() {
            final EntityID mySelfId = agentInfo.getID();
            final int totalZoneCount = zonePartitionRadar.getClusterNumber();
            final int myOriginalZoneIndex = zonePartitionRadar.getClusterIndex(mySelfId);
            final int oldSize = currentPatrolZone.size();

            // 每次跨越一个网格，把那个网格的楼也加进我的待办清单
            for (int step = 1; step < totalZoneCount; ++step) {
                if (currentPatrolZone.size() > oldSize) break; // 只要加进来了新活，就停止越权

                int nextZoneIndex = myOriginalZoneIndex + step * totalZoneCount;
                Collection<EntityID> newRawEntities = zonePartitionRadar.getClusterEntityIDs(nextZoneIndex);

                Set<EntityID> cleanBuildingSet = new HashSet<>();
                for (EntityID entityId : newRawEntities) {
                    if (worldInfo.getEntity(entityId) instanceof Building) cleanBuildingSet.add(entityId);
                }
                currentPatrolZone.addAll(cleanBuildingSet);
            }
        }

        /**
         * 评估是否需要扩张（当前活干了 90% 以上了）。
         */
        boolean evaluateIfExpansionNeeded() {
            return alreadySearchedBuildings.size() >= currentPatrolZone.size() * 0.9;
        }

        /**
         * 评估全城是不是已经被我犁庭扫穴看光了。
         */
        boolean evaluateIfCityIsFullySearched() {
            Collection<StandardEntity> allBuildingEntities = worldInfo.getEntitiesOfType(BUILDING);
            Collection<StandardEntity> allRefugeEntities = worldInfo.getEntitiesOfType(REFUGE);

            Set<EntityID> generalBuildings = new HashSet<>();
            for (StandardEntity e : allBuildingEntities) generalBuildings.add(e.getID());

            Set<EntityID> safeRefuges = new HashSet<>();
            for (StandardEntity e : allRefugeEntities) safeRefuges.add(e.getID());

            // 避难所不用查
            generalBuildings.removeAll(safeRefuges);

            long validReachedCount = 0;
            for (EntityID reachedId : alreadySearchedBuildings) {
                if (!safeRefuges.contains(reachedId)) validReachedCount++;
            }
            return validReachedCount == generalBuildings.size();
        }

        /**
         * 计算当前防区的对角线跨度（用于测算跨区惩罚）。
         */
        private double computeZoneDiagonalSpan(Set<EntityID> patrolZone) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
            boolean hasBuilding = false;

            for (EntityID elementId : patrolZone) {
                StandardEntity rawEntity = worldInfo.getEntity(elementId);
                if (rawEntity instanceof Building) {
                    Building bld = (Building) rawEntity;
                    hasBuilding = true;
                    if (bld.getX() < minX) minX = bld.getX();
                    if (bld.getY() < minY) minY = bld.getY();
                    if (bld.getX() > maxX) maxX = bld.getX();
                    if (bld.getY() > maxY) maxY = bld.getY();
                }
            }
            if (!hasBuilding) return 0.0;
            return Math.hypot(maxX - minX, maxY - minY);
        }
    }

    // ========================================================================
    // 核心管线：情报更新与清洗 (Information Update Pipeline)
    // ========================================================================

    @Override
    public Search updateInfo(MessageManager mm) {
        super.updateInfo(mm);
        if (this.getCountUpdateInfo() > 1) return this;

        // 开局防呆：如果防区是空的，赶紧向雷达申请划分防区！
        if (this.currentPatrolZone.isEmpty()) {
            ZonePlanner planner = new ZonePlanner();
            planner.initCluster();
        }

        // 聘请战术情报处来处理极其繁杂的视觉、听觉和电报数据
        TacticalIntelligenceUnit intelUnit = new TacticalIntelligenceUnit();

        // 1. 记录所有消防车现在正在哪打硬仗
        intelUnit.trackActiveFireBrigadePositions();

        // 2. 看看我之前定的目标是不是走不通？加入断头路黑名单
        intelUnit.checkUnreachableTargets();

        // 3. 更新那些被埋平民的生死倒计时
        intelUnit.processBuriedCiviliansVisibility();

        // 4. 竖起耳朵听惨叫，把惨叫声映射到具体的楼上
        AcousticSonar sonar = new AcousticSonar();
        sonar.mapAcousticSignalsToBuildings();

        // 5. 把我眼睛刚看清楚的楼，统统加入“已查探”黑名单
        intelUnit.logVisuallySearchedBuildings();

        // 6. 剔除根本进不去的废墟、着火的楼、已经发现的平民
        intelUnit.cleanseInvalidTargets();

        // 7. 查收外来的无线电报
        intelUnit.processIncomingTelegrams(mm);

        return this;
    }

    // ========================================================================
    // 内部类 2：战术情报处 (Tactical Intelligence Unit)
    // ========================================================================
    private class TacticalIntelligenceUnit {

        /**
         * 交通瘫痪状态鉴定器。
         */
        private boolean isUnreachable() {
            final EntityID myId = agentInfo.getID();
            final StandardEntityURN myType = agentInfo.me().getStandardURN();

            if (finalSearchTarget == null) return false;
            // 警察手里有电锯能自己开路，永远不会报“无法到达”
            if (myType == POLICE_FORCE) return false;

            unreachablePathRadar.calc();
            physicalTrapRadar.calc();

            final boolean isRoadBlocked = unreachablePathRadar.getClusterIndex(myId) >= 0;
            final boolean isBodyStuck = physicalTrapRadar.getClusterIndex(myId) >= 0;
            return isBodyStuck || isRoadBlocked;
        }

        void checkUnreachableTargets() {
            Human mySelfBody = (Human) agentInfo.me();
            // 如果我没被埋，但是底层导航仪报了“路被堵死无法到达”
            boolean amINotBuried = !mySelfBody.isBuriednessDefined() || mySelfBody.getBuriedness() == 0;
            if (this.isUnreachable() && amINotBuried) {
                // 把我朝思暮想但去不了的目标，扔进“断头路”黑名单
                if (finalSearchTarget != null) delayedUnreachableTargets.add(finalSearchTarget);
            }
        }

        /**
         * 追踪消防车作业区。将全城所有停在建筑里的消防车拉入警戒名单。
         */
        void trackActiveFireBrigadePositions() {
            activeFireBrigadePositions.clear();
            Collection<StandardEntity> allFireTrucks = worldInfo.getEntitiesOfType(FIRE_BRIGADE);

            for (StandardEntity fEntity : allFireTrucks) {
                FireBrigade fb = (FireBrigade) fEntity;
                if (fb.isPositionDefined()) {
                    StandardEntity footprint = worldInfo.getEntity(fb.getPosition());
                    // 如果他停在一栋楼里打阵地战，记录这栋楼！
                    if (footprint instanceof Building) {
                        activeFireBrigadePositions.add(footprint.getID());
                    }
                }
            }
        }

        /**
         * 被埋平民实勘更新器。肉眼看到平民死了就划掉。
         */
        void processBuriedCiviliansVisibility() {
            // 所有在册的倒计时 -1
            for (Map.Entry<EntityID, Integer> entry : buriedCiviliansCountdown.entrySet()) {
                buriedCiviliansCountdown.put(entry.getKey(), Math.max(entry.getValue() - 1, 0));
            }

            // 视觉搜捕被埋者
            Set<EntityID> changedEntities = worldInfo.getChanged().getChangedEntities();
            for (EntityID cId : changedEntities) {
                StandardEntity rawEntity = worldInfo.getEntity(cId);
                if (rawEntity instanceof Civilian) {
                    Civilian civ = (Civilian) rawEntity;
                    if (civ.isBuriednessDefined() && civ.getBuriedness() > 0) {
                        StandardEntity hisLocation = worldInfo.getEntity(civ.getPosition());
                        if (!(hisLocation instanceof Refuge)) {
                            // 肉眼确诊，登入确诊名单
                            buriedCiviliansCountdown.put(civ.getPosition(), civ.getBuriedness());
                            // 如果眼看他咽气了，无情划掉
                            if (civ.isHPDefined() && civ.getHP() <= 0) {
                                buriedCiviliansCountdown.remove(civ.getPosition());
                            }
                        }
                    }
                }
            }

            // 实地打卡注销：如果我已经站在这个平民所在的楼了，且倒计时是0，说明我来看过了，清账！
            EntityID myFootprint = agentInfo.getPosition();
            if (buriedCiviliansCountdown.containsKey(myFootprint)) {
                if (buriedCiviliansCountdown.get(myFootprint) == 0) {
                    buriedCiviliansCountdown.remove(myFootprint);
                }
            }
        }

        /**
         * 把眼睛看过的楼加入“已查探”黑名单，并剥夺它们的声呐潜力。
         */
        void logVisuallySearchedBuildings() {
            EntityID myFootprint = agentInfo.getPosition();
            // 走到哪黑到哪
            if (worldInfo.getEntity(myFootprint) instanceof Building) {
                alreadySearchedBuildings.add(myFootprint);
            }

            // 用视线扫视所有变动的楼
            Set<EntityID> visuallyChangedEntities = worldInfo.getChanged().getChangedEntities();
            for (EntityID cId : visuallyChangedEntities) {
                StandardEntity rawEntity = worldInfo.getEntity(cId);
                if (rawEntity instanceof Building) {
                    Building bld = (Building) rawEntity;
                    // 如果视线能完全看穿这栋楼（中间没被墙挡住）
                    if (this.isVisuallyCleared(bld)) {
                        alreadySearchedBuildings.add(bld.getID());
                    }
                }
            }

            // 如果这楼已经看过了，它以前积攒的求救声潜能作废！
            for (EntityID searchedId : alreadySearchedBuildings) {
                buildingLifePotentials.remove(searchedId);
            }
        }

        /**
         * 视线穿透力检测仪。发射一条激光，看有没有被建筑物的实心墙挡住。
         */
        private boolean isVisuallyCleared(Building targetBuilding) {
            int maxSightDistance = scenarioInfo.getPerceptionLosMaxDistance();
            Line2D sightLine = new Line2D(
                new Point2D(agentInfo.getX(), agentInfo.getY()),
                new Point2D(targetBuilding.getX(), targetBuilding.getY())
            );

            // 如果距离太远（达到极限视距 80% 以上），根本看不清，不算 clear
            if (sightLine.getDirection().getLength() >= maxSightDistance * 0.8) return false;

            // 用我的视线去切这栋楼的每一面墙
            for (Edge structuralEdge : targetBuilding.getEdges()) {
                // 如果这是一堵不透风的实心墙
                if (!structuralEdge.isPassable()) {
                    Point2D collisionPoint = GeometryTools2D.getSegmentIntersectionPoint(sightLine, structuralEdge.getLine());
                    if (collisionPoint != null) return true; // 视线被挡死了
                }
            }
            return false;
        }

        /**
         * 大清洗合集：过滤无用目标。
         */
        void cleanseInvalidTargets() {
            Set<EntityID> changedEntities = worldInfo.getChanged().getChangedEntities();
            Set<EntityID> visibleCivilianIds = new HashSet<>();

            for (EntityID cId : changedEntities) {
                StandardEntity rawEntity = worldInfo.getEntity(cId);

                // 清洗 1：剔除一点损坏都没有（Brokenness=0）的新房子，里面肯定没埋人
                if (rawEntity instanceof Building) {
                    Building bld = (Building) rawEntity;
                    if (bld.isBrokennessDefined() && bld.getBrokenness() == 0) {
                        alreadySearchedBuildings.add(bld.getID());
                    }
                    // 清洗 2：警察怕火！看到冒火的楼，直接拉入黑名单，去了也是送死
                    if (bld.isOnFire()) {
                        alreadySearchedBuildings.add(bld.getID());
                    }
                }
                // 收集可见平民，准备清洗 3
                if (rawEntity instanceof Civilian) visibleCivilianIds.add(rawEntity.getID());
            }

            for (EntityID searchedId : alreadySearchedBuildings) buildingLifePotentials.remove(searchedId);

            // 清洗 3：如果肉眼已经看到这些平民了，他们之前发的求救声潜能作废
            for (Set<EntityID> potentialList : buildingLifePotentials.values()) {
                potentialList.removeAll(visibleCivilianIds);
            }
        }

        /**
         * 电报分拣台。处理远方传来的平民求救。
         */
        void processIncomingTelegrams(MessageManager mm) {
            List<CommunicationMessage> civMails = mm.getReceivedMessageList(MessageCivilian.class);
            for (CommunicationMessage msg : civMails) {
                MessageCivilian civMsg = (MessageCivilian) msg;
                EntityID distressLocationId = civMsg.getPosition();

                // 只有我管辖的防区内的求救，我才接单！
                int mySectorIndex = zonePartitionRadar.getClusterIndex(agentInfo.getID());
                Collection<EntityID> mySectorEntities = zonePartitionRadar.getClusterEntityIDs(mySectorIndex);

                if (mySectorEntities.contains(distressLocationId)) {
                    // 记录电报上的地点，并将电报中声称的“深埋度减 1”作为我出警查探的倒计时！
                    buriedCiviliansCountdown.put(distressLocationId, civMsg.getBuriedness() - 1);
                }
            }
        }
    }

    // ========================================================================
    // 核心管线：巡视大脑 (The Brain Pipeline)
    // ========================================================================

    /**
     * 核心巡视大脑。每秒钟决定这辆警车往哪开。
     */
    @Override
    public Search calc() {
        // 聘请战区规划师
        ZonePlanner planner = new ZonePlanner();

        // 战术扩张：如果我负责的楼马上就快查完了，主动去借别的区的活干，防止摸鱼！
        if (planner.evaluateIfExpansionNeeded()) {
            planner.expandPatrolZone();
        }

        // 如果全城都被我一网打尽全看光了，清空黑名单开始二刷！
        if (planner.evaluateIfCityIsFullySearched()) {
            this.alreadySearchedBuildings.clear();
        }

        // 把当前防区的待查名单拉出来，剔除掉已经去过的黑名单楼宇
        Set<EntityID> activeCandidates = new HashSet<>(this.currentPatrolZone);
        activeCandidates.removeAll(this.alreadySearchedBuildings);

        // 聘请目标锁定员
        TargetLockProcessor locker = new TargetLockProcessor();

        // 优先级 1：人命关天！如果有倒计时归零的确诊被埋平民，直接过去挖！
        this.finalSearchTarget = locker.extractBestBuriedCivilianTarget();
        if (this.finalSearchTarget != null) return this; // 锁定了，下班

        // 优先级 2：用声呐板结合物理距离，挑一栋最有希望（叫声最惨、离我最近）的楼
        this.finalSearchTarget = locker.evaluateAcousticAndDistanceTargets(activeCandidates);

        return this;
    }

    // ========================================================================
    // 内部类 3：声学声呐 (Acoustic Sonar)
    // ========================================================================
    private class AcousticSonar {

        /**
         * 听觉雷达潜力映射仪。把听到的惨叫声，死死地钉在附近的楼上。
         */
        void mapAcousticSignalsToBuildings() {
            Set<EntityID> buildingsInEarshot = this.findBuildingsInAcousticRange();
            Set<EntityID> criesForHelpIds = this.listenToCivilianCries();

            // 给听力范围内的每一栋楼，都挂上这些求救者的牌子
            for (EntityID bldId : buildingsInEarshot) {
                Set<EntityID> potentialRecord = buildingLifePotentials.getOrDefault(bldId, new HashSet<>());
                potentialRecord.addAll(criesForHelpIds);
                buildingLifePotentials.put(bldId, potentialRecord);
            }
        }

        /**
         * 以自己为圆心，圈出嗓门射程内的所有建筑。
         */
        private Set<EntityID> findBuildingsInAcousticRange() {
            int acousticRadius = scenarioInfo.getRawConfig().getIntValue("comms.channels.0.range");
            EntityID mySelfId = agentInfo.getID();

            Collection<StandardEntity> rawEntitiesInRange = worldInfo.getObjectsInRange(mySelfId, acousticRadius);
            Set<EntityID> cleanBuildingSet = new HashSet<>();
            for (StandardEntity entity : rawEntitiesInRange) {
                if (entity instanceof Building) cleanBuildingSet.add(entity.getID());
            }
            return cleanBuildingSet;
        }

        /**
         * 捕捉近距离的平民语音求救。
         */
        private Set<EntityID> listenToCivilianCries() {
            Set<EntityID> officialAgents = new HashSet<>(worldInfo.getEntityIDsOfType(
                FIRE_BRIGADE, FIRE_STATION, AMBULANCE_TEAM, AMBULANCE_CENTRE, POLICE_FORCE, POLICE_OFFICE));

            Set<EntityID> distressedCivilianIds = new HashSet<>();
            Collection<Command> heardSignals = agentInfo.getHeard();

            for (Command signal : heardSignals) {
                if (signal instanceof AKSpeak) {
                    AKSpeak voiceSignal = (AKSpeak) signal;
                    // Channel 0 物理意义代表着人类肉嗓子大喊
                    if (voiceSignal.getChannel() == 0) {
                        EntityID speakerId = voiceSignal.getAgentID();
                        // 过滤掉官方人员的喊话，只留下平民的惨叫
                        if (!officialAgents.contains(speakerId)) {
                            distressedCivilianIds.add(speakerId);
                        }
                    }
                }
            }
            return distressedCivilianIds;
        }
    }

    // ========================================================================
    // 内部类 4：战术目标锁定员 (Target Lock Processor)
    // ========================================================================
    private class TargetLockProcessor {

        /**
         * 声呐与距离综合评估器。
         * 物理意义：找叫声（生命潜能）最惨烈的楼；如果叫声一样惨，找离我最近的楼。
         */
        EntityID evaluateAcousticAndDistanceTargets(Set<EntityID> activeCandidates) {
            EntityID optimalTarget = null;
            int maxPotentialScore = -1; // 记录目前听到的最惨烈的叫声数量
            double minDistanceScore = Double.MAX_VALUE; // 记录目前最近的距离

            for (EntityID candidateId : activeCandidates) {
                // 去声呐板上查一下这栋楼挂了几个求救声？
                Set<EntityID> potentialsForThisBuilding = buildingLifePotentials.get(candidateId);
                int currentPotentialScore = (potentialsForThisBuilding == null) ? 0 : potentialsForThisBuilding.size();

                // 算算物理距离
                double currentDistanceScore = worldInfo.getDistance(candidateId, agentInfo.getID());

                // 跨区惩罚：如果这栋楼是因为之前路堵死了被迫放弃的，加上一个跨区远征惩罚系数！
                if (delayedUnreachableTargets.contains(candidateId)) {
                    currentDistanceScore += zoneDiagonalPenalty;
                }

                // 战术打擂台：
                // 1. 叫声更惨？直接取代擂主！
                boolean isBetterPotential = (currentPotentialScore > maxPotentialScore);
                // 2. 叫声一样惨，但是离我更近？也取代擂主！
                boolean isSamePotentialButCloser = (currentPotentialScore == maxPotentialScore) && (currentDistanceScore < minDistanceScore);

                if (isBetterPotential || isSamePotentialButCloser) {
                    maxPotentialScore = currentPotentialScore;
                    minDistanceScore = currentDistanceScore;
                    optimalTarget = candidateId;
                }
            }
            return optimalTarget;
        }

        /**
         * 被埋平民优先抽取器。
         * 物理意义：在倒计时为 0 的确诊地点里，挑一个离自己最近的。
         * 但是，如果那个地点旁边停着消防车（说明正打着火呢，极度危险），优先避开或者最后考虑！
         */
        EntityID extractBestBuriedCivilianTarget() {
            EntityID myLocationId = agentInfo.getID();
            EntityID optimalBuriedTarget = null;
            double minDistance = Double.MAX_VALUE;

            // 分为两档：没有消防车的安全区优先级高，有消防车的火线区优先级低
            boolean foundInSafeZone = false;

            for (Map.Entry<EntityID, Integer> record : buriedCiviliansCountdown.entrySet()) {
                // 必须是倒计时走到了 0，才触发紧急实地查探！
                if (record.getValue() == 0) {
                    EntityID targetBldId = record.getKey();
                    // 看看这栋楼是不是正在被消防车围攻？
                    boolean hasFireTruck = activeFireBrigadePositions.contains(targetBldId);
                    double currentDistance = worldInfo.getDistance(targetBldId, myLocationId);

                    // 如果这里没有消防车（算是安全区）
                    if (!hasFireTruck) {
                        // 如果目前还没找到任何安全区的目标，或者找到了但是眼前的这个更近！
                        if (!foundInSafeZone || currentDistance < minDistance) {
                            foundInSafeZone = true;
                            minDistance = currentDistance;
                            optimalBuriedTarget = targetBldId;
                        }
                    } else {
                        // 如果是火线区，只有在“还没找到任何安全区目标”的前提下，才退而求其次去比较远近
                        if (!foundInSafeZone && currentDistance < minDistance) {
                            minDistance = currentDistance;
                            optimalBuriedTarget = targetBldId;
                        }
                    }
                }
            }
            return optimalBuriedTarget;
        }
    }
}
