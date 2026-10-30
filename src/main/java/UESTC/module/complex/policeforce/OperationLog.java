package UESTC.module.complex.policeforce;

import UESTC.module.complex.common.SurvivalChronicle;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.communication.standard.bundle.information.MessagePoliceForce;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.AbstractModule;
import rescuecore2.misc.Pair;
import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

/**
 * OperationLog 警察部队战损与难民情报处理中枢。
 * 物理意义：警察本身不会灭火和救治，但他们拿着电锯在全城跑，往往能第一时间看到被埋的平民。
 * 此时，他们需要把这些情报记录下来并分类整理，方便后续统筹或者发报给救护车/消防车。
 * 这是一个后台记账模块，负责：
 * 1. 从视野中收集所有被埋人员（平民、战友）。
 * 2. 对这些人员进行伤情分类（急需挖掘的、只需要装车的）。
 * 3. 评估这些伤者的预期寿命，为上层决策提供“高优先级”任务推荐。
 */
public class OperationLog extends AbstractModule {

    // ========================================================================
    // 指挥部情报账本
    // ========================================================================

    // 自我遇险标志位。如果我这个警察自己都被倒塌的房子压住了，我就什么也干不了了。
    private boolean isMyselfEntrappedState = false;

    // 待挖掘平民名册。被埋在废墟里、急需消防队来挖的普通百姓。
    private Set<EntityID> buriedCivilianRoster = new HashSet<>();

    // 待挖掘战友名册。被埋在废墟里的消防、警察、救护人员。战友的优先级永远高于平民。
    private Set<EntityID> buriedTeammateRoster = new HashSet<>();

    // 待装载平民名册。没有被埋、但身上有伤在流血的平民。他们需要救护车来接，警察只负责记下位置。
    private Set<EntityID> bleedingCivilianRoster = new HashSet<>();

    /**
     * 情报中枢构造器。
     */
    public OperationLog(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        // 调用底层加载基础地图和参数
        super(ai, wi, si, moduleManager, developData);
    }

    // ========================================================================
    // 内部类 1：战术感知雷达 (Sensory Radar)
    // 【位置已提前并乱序】处理找人、测距、视线障碍等物理几何检测
    // ========================================================================
    private class SensoryRadar {

        /**
         * 底层几何线段碰撞检测核芯。
         * 数学证明两条线段是否在空间中发生了十字交叉（或极其贴近）。
         */
        private boolean detectSegmentCollision(double ray1X1, double ray1Y1, double ray1X2, double ray1Y2,
                                               double ray2X1, double ray2Y1, double ray2X2, double ray2Y2) {

            // 组装射线 1：我的目光
            final java.awt.geom.Point2D.Double p1Start = new java.awt.geom.Point2D.Double(ray1X1, ray1Y1);
            final java.awt.geom.Point2D.Double p1End = new java.awt.geom.Point2D.Double(ray1X2, ray1Y2);
            final java.awt.geom.Line2D geometryRay1 = new java.awt.geom.Line2D.Double(p1Start, p1End);

            // 组装射线 2：石头的物理边缘
            final java.awt.geom.Point2D.Double p2Start = new java.awt.geom.Point2D.Double(ray2X1, ray2Y1);
            final java.awt.geom.Point2D.Double p2End = new java.awt.geom.Point2D.Double(ray2X2, ray2Y2);
            final java.awt.geom.Line2D geometryRay2 = new java.awt.geom.Line2D.Double(p2Start, p2End);

            // JDK 原生微积分交叉验证：看看真十字交叉了没？
            boolean isColliding = geometryRay1.intersectsLine(geometryRay2);

            // 极限容差判定：即便没有严丝合缝地交叉，如果它们之间的距离小于 1 个像素点，依然视为擦肩而过的物理碰撞！
            isColliding |= geometryRay1.ptSegDist(p2Start) <= 1.0;
            isColliding |= geometryRay1.ptSegDist(p2End) <= 1.0;
            isColliding |= geometryRay2.ptSegDist(p1Start) <= 1.0;
            isColliding |= geometryRay2.ptSegDist(p1End) <= 1.0;

            return isColliding;
        }

        /**
         * 听力范围内最近救护车探测器。
         * 寻找一个能听见我喊叫的救护车，且要求这辆救护车必须停在马路上（没进屋救人）。
         */
        EntityID locateNearestAmbulanceInEarshot() {
            Set<EntityID> visuallyChangedEntities = worldInfo.getChanged().getChangedEntities();
            EntityID mySelfLocationId = agentInfo.getPosition();
            EntityID mySelfEntityId = agentInfo.getID();

            int acousticRadius = scenarioInfo.getRawConfig().getIntValue("comms.channels.0.range");

            EntityID closestAmbulanceId = null;
            double shortestDistanceToMe = Double.MAX_VALUE;

            for (EntityID currentId : visuallyChangedEntities) {
                // 防精神分裂：我不能自己喊我自己
                if (currentId.equals(mySelfEntityId)) continue;

                StandardEntity rawEntity = worldInfo.getEntity(currentId);
                if (rawEntity instanceof AmbulanceTeam) {
                    AmbulanceTeam atEntity = (AmbulanceTeam) rawEntity;
                    EntityID atLocationId = atEntity.getPosition();

                    // 战术前提：这辆救护车必须停在路面上，说明他现在手头没急活
                    if (worldInfo.getPosition(atEntity.getID()) instanceof Road) {

                        double distanceToMyBody = worldInfo.getDistance(atLocationId, mySelfEntityId);
                        // 在喇叭射程范围内
                        if (distanceToMyBody < acousticRadius) {

                            double distanceToMyLocation = worldInfo.getDistance(atLocationId, mySelfLocationId);
                            if (distanceToMyLocation < shortestDistanceToMe) {
                                shortestDistanceToMe = distanceToMyLocation;
                                closestAmbulanceId = atEntity.getID();
                            }
                        }
                    }
                }
            }
            return closestAmbulanceId;
        }

        /**
         * 视距阻碍射线碰撞检测器。
         * 物理意义：我看着前方的一块废墟（Blockade），我想知道这块废墟有没有挡住我看向远处的视线？
         * 发射一条虚拟射线，如果射线和废墟的任何一条边缘交叉了，说明视线受阻。
         */
        boolean checkVisualIntersectionWithRubble(double myX, double myY, double targetX, double targetY, Blockade rubblePile) {

            // 把这块石头的所有墙角提取出来，连成一圈封闭的几何线条网络
            List<Line2D> rubbleEdges = GeometryTools2D.pointsToLines(
                    GeometryTools2D.vertexArrayToPoints(rubblePile.getApexes()), true);

            // 遍历石头的每一条锋利的边
            for (Line2D rubbleEdge : rubbleEdges) {
                Point2D edgeStart = rubbleEdge.getOrigin();
                Point2D edgeEnd = rubbleEdge.getEndPoint();

                double edgeX1 = edgeStart.getX();
                double edgeY1 = edgeStart.getY();
                double edgeX2 = edgeEnd.getX();
                double edgeY2 = edgeEnd.getY();

                // 启动底层微积分核芯：用我的目光射线，去狠狠地切这块石头的边。如果切上了，报警！
                if (detectSegmentCollision(myX, myY, targetX, targetY, edgeX1, edgeY1, edgeX2, edgeY2)) {
                    System.out.println("视线受阻：发现碎石遮挡目标！ intersect!");
                    return true;
                }
            }
            return false;
        }

        /**
         * 听力范围内最近警察同行探测器。
         */
        EntityID locateNearestPoliceInEarshot() {
            Set<EntityID> visuallyChangedEntities = worldInfo.getChanged().getChangedEntities();
            EntityID mySelfLocationId = agentInfo.getPosition();
            EntityID mySelfEntityId = agentInfo.getID();

            int acousticRadius = scenarioInfo.getRawConfig().getIntValue("comms.channels.0.range");

            EntityID closestPoliceId = null;
            double shortestDistanceToMe = Double.MAX_VALUE;

            for (EntityID currentId : visuallyChangedEntities) {
                if (currentId.equals(mySelfEntityId)) continue;

                StandardEntity rawEntity = worldInfo.getEntity(currentId);
                if (rawEntity instanceof PoliceForce) {
                    PoliceForce pfEntity = (PoliceForce) rawEntity;
                    EntityID pfLocationId = pfEntity.getPosition();

                    if (worldInfo.getPosition(pfEntity.getID()) instanceof Road) {
                        double distanceToMyBody = worldInfo.getDistance(pfLocationId, mySelfEntityId);
                        if (distanceToMyBody < acousticRadius) {
                            double distanceToMyLocation = worldInfo.getDistance(pfLocationId, mySelfLocationId);
                            if (distanceToMyLocation < shortestDistanceToMe) {
                                shortestDistanceToMe = distanceToMyLocation;
                                closestPoliceId = pfEntity.getID();
                            }
                        }
                    }
                }
            }
            return closestPoliceId;
        }

        /**
         * 听力范围内最近消防兵探测器。
         * 我想大喊一声找人帮忙，周围有消防车能听见吗？
         */
        EntityID locateNearestFireTruckInEarshot() {
            Set<EntityID> visuallyChangedEntities = worldInfo.getChanged().getChangedEntities();
            EntityID mySelfLocationId = agentInfo.getPosition();
            EntityID mySelfEntityId = agentInfo.getID();

            // 从底层配置文件读取我的大嗓门能喊多远（通常是 30 米）
            int acousticRadius = scenarioInfo.getRawConfig().getIntValue("comms.channels.0.range");

            EntityID closestFireTruckId = null;
            double shortestDistanceToMe = Double.MAX_VALUE;

            // 环顾四周看一看
            for (EntityID currentId : visuallyChangedEntities) {
                StandardEntity rawEntity = worldInfo.getEntity(currentId);

                // 确保那是一辆消防车
                if (rawEntity instanceof FireBrigade) {
                    FireBrigade fbEntity = (FireBrigade) rawEntity;
                    EntityID fbLocationId = fbEntity.getPosition();

                    // 第一重物理安检：他到底在不在我的大嗓门波及范围内？
                    double distanceToMyBody = worldInfo.getDistance(fbLocationId, mySelfEntityId);
                    if (distanceToMyBody < acousticRadius) {

                        // 第二重数学安检：用于打擂台比谁最近
                        double distanceToMyLocation = worldInfo.getDistance(fbLocationId, mySelfLocationId);
                        if (distanceToMyLocation < shortestDistanceToMe) {
                            shortestDistanceToMe = distanceToMyLocation;
                            closestFireTruckId = fbEntity.getID();
                        }
                    }
                }
            }
            return closestFireTruckId;
        }
    }

    // ========================================================================
    // 核心生命探测与记录推演 (The Pipeline)
    // ========================================================================

    /**
     * 核心情报处理引擎。每一秒钟看一眼周围，对所有人进行检伤分类。
     */
    @Override
    public AbstractModule calc() {
        final EntityID mySelfId = this.agentInfo.getID();

        // 聘请首席战地军医
        MedicalTriage medic = new MedicalTriage();

        // 第一重安检：伤情自检！看自己有没有被埋
        this.isMyselfEntrappedState = medic.evaluateIfNeedsDigging(mySelfId);
        // 如果被压在石头底下了，直接躺平，停止情报录入
        if (this.isMyselfEntrappedState) return this;

        // 拿出一个网兜，把视野里刚发生变化（活蹦乱跳或者刚被砸）的所有人都网进来
        Set<EntityID> visuallyChangedEntities = this.worldInfo.getChanged().getChangedEntities();
        // 兜底防呆：把自己也装进网兜，免得自己伤情恶化了没发现
        visuallyChangedEntities.add(mySelfId);

        // 军医开始接客：把网兜里的人挨个拉出来检伤，并扔进不同的档案柜
        medic.performTriage(visuallyChangedEntities);

        // 记完账了，返回中枢本身
        return this;
    }

    // ========================================================================
    // 内部类 2：战地医疗分诊台 (Medical Triage)
    // ========================================================================
    private class MedicalTriage {

        /**
         * 检伤分类流水线。
         */
        void performTriage(Set<EntityID> visibleEntities) {
            // 建立各兵种在全城的临时分布地图（记录他们到底在哪条街、哪栋楼）
            Map<EntityID, Set<EntityID>> civLocationMap = new HashMap<>();
            Map<EntityID, Set<EntityID>> ambLocationMap = new HashMap<>();
            Map<EntityID, Set<EntityID>> fbLocationMap = new HashMap<>();

            // 逐一检阅视野中发生了变化的东西
            for (EntityID currentId : visibleEntities) {
                StandardEntity rawEntity = worldInfo.getEntity(currentId);
                // 警察的情报系统只关心人类死活，石头和火不管
                if (!(rawEntity instanceof Human)) continue;

                StandardEntity safeHouseLocation = worldInfo.getPosition(currentId);
                // 安全屋豁免：如果人已经待在避难所（医院）里，那是绝对安全的，跳过
                if (safeHouseLocation.getStandardURN().equals(REFUGE)) continue;

                // 临床诊断：他需要被铁锹挖吗？他需要担架抬吗？
                boolean requiresDigging = this.evaluateIfNeedsDigging(currentId);
                boolean requiresLoading = this.evaluateIfNeedsLoading(currentId);

                StandardEntityURN entityProfession = rawEntity.getStandardURN();
                EntityID physicalLocationId = safeHouseLocation.getID();

                // 按照衣服上的徽章（兵种），将其分拨到不同的档案柜
                if (entityProfession.equals(AMBULANCE_TEAM)) {
                    if (requiresDigging) {
                        // 救护车被埋，拉入战友急救特快通道！
                        buriedTeammateRoster.add(currentId);
                    } else {
                        // 没被埋的健康战友，只记录他现在的坐标位置
                        Set<EntityID> squad = ambLocationMap.getOrDefault(physicalLocationId, new HashSet<>());
                        squad.add(currentId);
                        ambLocationMap.put(physicalLocationId, squad);
                    }
                } else if (entityProfession.equals(FIRE_BRIGADE)) {
                    if (requiresDigging) {
                        // 消防车兄弟被埋！
                        buriedTeammateRoster.add(currentId);
                    } else {
                        Set<EntityID> squad = fbLocationMap.getOrDefault(physicalLocationId, new HashSet<>());
                        squad.add(currentId);
                        fbLocationMap.put(physicalLocationId, squad);
                    }
                } else if (entityProfession.equals(POLICE_FORCE)) {
                    if (requiresDigging) {
                        // 警察同事被埋！
                        buriedTeammateRoster.add(currentId);
                    }
                } else if (entityProfession.equals(CIVILIAN)) {
                    if (requiresDigging) {
                        // 记录平民的位置
                        Set<EntityID> squad = civLocationMap.getOrDefault(physicalLocationId, new HashSet<>());
                        squad.add(currentId);
                        civLocationMap.put(physicalLocationId, squad);

                        // 平民被埋，列入常规待挖平民名册
                        buriedCivilianRoster.add(currentId);
                    } else if (requiresLoading) {
                        // 没被埋但流血了，列入担架名单，回头好发报给救护车
                        bleedingCivilianRoster.add(currentId);
                    }
                }
            }
        }

        /**
         * 挖掘需求评估仪。
         * 物理意义：被压在石头下（埋藏度 > 0）且还有一口气（HP > 0），才需要动用铁锹。
         */
        boolean evaluateIfNeedsDigging(EntityID suspectId) {
            final Human victimBody = (Human) worldInfo.getEntity(suspectId);
            final int currentHp = victimBody.getHP();
            final int rubbleDepth = victimBody.getBuriedness();
            // 只有当血量和深度都不为 0 时，乘积才不为 0
            return (currentHp * rubbleDepth != 0);
        }

        /**
         * 担架装载需求评估仪。
         * 物理意义：没被石头压住（埋藏度 == 0），但血没满（受伤了），说明他需要上救护车。
         */
        boolean evaluateIfNeedsLoading(EntityID suspectId) {
            final Human victimBody = (Human) worldInfo.getEntity(suspectId);
            if (victimBody == null) return false;

            final int currentHp = victimBody.getHP();
            final int rubbleDepth = victimBody.getBuriedness();
            return (currentHp > 0 && rubbleDepth == 0);
        }

        /**
         * 医学伤情成本核算仪。
         * 物理意义：如果我同时知道好几个人被埋了，我该向上级汇报哪一个是“最高优先级”的？
         * 这个方法用来算经济账：成本 = 到那个人的直线距离 + 这个人的预期寿命。
         * 成本分越低，说明他越近，或者他马上就要死了越紧急！
         */
        EntityID determineHighestPriorityTask(Set<EntityID> candidateQueue) {
            EntityID optimalTargetId = null;
            double lowestCalculatedCost = Double.MAX_VALUE;

            // 把名单里的人挨个拉出来算一算
            for (EntityID suspectId : candidateQueue) {
                final Human victimBody = (Human) worldInfo.getEntity(suspectId);
                final Pair<Integer, Integer> victimCoords = worldInfo.getLocation(suspectId);

                // 物理路费测算：我的位置到他的位置的曼哈顿距离开个根号
                final double rawManhattanDistance = Math.abs(agentInfo.getX() - victimCoords.first())
                                                  + Math.abs(agentInfo.getY() - victimCoords.second());
                final double geographicCost = Math.sqrt(rawManhattanDistance);

                // 医学寿命测算：调用底层长寿仪算一下他还能喘气多久
                final SurvivalChronicle medicalAnalyzer = new SurvivalChronicle(victimBody);
                // 活得越久，成本分越高，排名越靠后；快死了，成本分极低，立马拔得头筹！
                final double medicalCost = medicalAnalyzer.getLifeXpectancy() * 1.0;

                // 综合打分：地理路费 + 阎王爷催命符
                final double totalCost = geographicCost + medicalCost;

                // 打擂台，寻找代价最小（最紧急、最近）的任务
                if (lowestCalculatedCost > totalCost) {
                    lowestCalculatedCost = totalCost;
                    optimalTargetId = suspectId;
                }
            }
            return optimalTargetId;
        }
    }

    // ========================================================================
    // 监听与外部接口 (Listener & API)
    // ========================================================================

    /**
     * 紧急任务推荐器（提供给上层警长模块调用）。
     * 先看有没有被埋的战友，如果有，推荐最紧急的战友。
     * 如果战友都没事，再去平民名单里挑一个最紧急的平民。
     */
    private EntityID recommendDispatchTask() {
        MedicalTriage medic = new MedicalTriage();

        // 永远是战友兄弟优先！
        if (!this.buriedTeammateRoster.isEmpty()) {
            return medic.determineHighestPriorityTask(this.buriedTeammateRoster);
        }
        // 如果没有战友蒙难，再推荐平民
        if (!this.buriedCivilianRoster.isEmpty()) {
            return medic.determineHighestPriorityTask(this.buriedCivilianRoster);
        }
        // 真是个太平盛世
        return null;
    }

    /**
     * 监听消防兵电报：消防员发来自己的遇险状况，更新我的战友被埋名册。
     */
    private void decodeColleagueTelegram(MessageFireBrigade msg) {
        final EntityID senderId = msg.getAgentID();
        MedicalTriage medic = new MedicalTriage();
        if (medic.evaluateIfNeedsDigging(senderId)) this.buriedTeammateRoster.add(senderId);
        else this.buriedTeammateRoster.remove(senderId);
    }

    /**
     * 监听救护兵电报。
     */
    private void decodeColleagueTelegram(MessageAmbulanceTeam msg) {
        final EntityID senderId = msg.getAgentID();
        MedicalTriage medic = new MedicalTriage();
        if (medic.evaluateIfNeedsDigging(senderId)) this.buriedTeammateRoster.add(senderId);
        else this.buriedTeammateRoster.remove(senderId);
    }

    /**
     * 监听警察同行电报。
     */
    private void decodeColleagueTelegram(MessagePoliceForce msg) {
        final EntityID senderId = msg.getAgentID();
        MedicalTriage medic = new MedicalTriage();
        if (medic.evaluateIfNeedsDigging(senderId)) this.buriedTeammateRoster.add(senderId);
        else this.buriedTeammateRoster.remove(senderId);
    }
}
