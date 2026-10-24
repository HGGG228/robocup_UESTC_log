package UESTC.module.complex.firebrigade;

import java.util.*;

import UESTC.module.algorithm.*;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.complex.BuildingDetector;
import rescuecore2.misc.Pair;
import rescuecore2.misc.geometry.*;
import rescuecore2.standard.entities.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import rescuecore2.worldmodel.EntityID;

/**
 * HQReconModule 消防车火情探索与目标锁定雷达。
 * 物理意义：一辆消防车在广阔的灾区中，不能像无头苍蝇一样乱撞，也不能直接冲进火海中心送死。
 * 这个类的核心战术逻辑是“边缘控制（Perimeter Control）”与“集群分配（Clustering）”：
 * 1. 它首先将全城的火灾划分为几个大片区（Fire Clusters）。
 * 2. 找到离自己最近的火灾片区。
 * 3. 利用计算几何学中的“凸包（Convex Hull）”算法，提取出这片火海的最外围边界（Perimeter）。
 * 4. 优先扑灭处于蔓延边界上、且处于初起火灾阶段（Fieryness = 1或2）的建筑，从而建立防火隔离带。
 */
public class HQReconModule extends BuildingDetector implements HQReconPublisher {

    // ========================================================================
    // 雷达阵列与战术组件 
    // ========================================================================

    // 火场聚类分析雷达。负责将全城零散的着火点打包成一块块连片的“火海群落”。
    private final Clustering fireClusterRadar;
    
    // 警力网格化分配器。负责将消防车划分为几个防区，确保大家分散作战，不挤在一起抢水。
    private final Clustering squadDistributionRadar;
    
    // 战术随机数引擎。当多个火场优先级完全相同时，引入随机性抛硬币，防止所有车做同样的决定。
    private final Random stochasticEngine = new Random();
    
    // 最终锁定的灭火目标。经过层层筛选后，这辆车这秒钟决定要去死磕的那栋楼的身份证号。
    private EntityID finalAssignedFireTarget = null;

    /**
     * 雷达构造与装配车间。
     * 在出库前，将底层聚类分析仪器塞进大脑，并用本车编号作为随机种子。
     */
    public HQReconModule(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager mm, DevelopData dd) {
        super(ai, wi, si, mm, dd);
        
        // 领装备：申领火场聚类分析仪
        this.fireClusterRadar = moduleManager.getModule("UESTC.FB.BuildingDetector.Clustering");
        // 领装备：申领防区网格划分仪
        this.squadDistributionRadar = mm.getModule("UESTC.FB.BuildingDetector.Dispersible");
        
        // 挂载仪器，确保雷达在每个回合自动通电运转
        this.registerModule(this.fireClusterRadar);
        this.registerModule(this.squadDistributionRadar);
        
        // 注入灵魂：用这辆车独一无二的车牌号作为随机种子，确保同等条件下不同车辆选择的差异化
        this.stochasticEngine.setSeed(ai.getID().getValue());
    }

    // ========================================================================
    // 生命周期与预热系统
    // ========================================================================

    /**
     * 赛前预计算缓存写入（断点阶段）。
     */
    @Override
    public BuildingDetector precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() > 1) return this;
        return this;
    }

    /**
     * 赛前预计算缓存读取（断点恢复阶段）。利用以往的计算结果快速冷启动。
     */
    @Override
    public BuildingDetector resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() > 1) return this;
        this.preparate();
        return this;
    }

    /**
     * 雷达现场开机预热。当没有缓存时，比赛第一秒强行拉拽两大雷达进行全城扫描。
     */
    @Override
    public BuildingDetector preparate() {
        super.preparate();
        if (this.getCountPreparate() > 1) return this;
        
        // 强行推动两大雷达扫第一波盲
        this.fireClusterRadar.calc();
        this.squadDistributionRadar.calc();
        return this;
    }

    /**
     * 战术结果提取接口。向上级汇报本回合的作战去向。
     */
    @Override
    public EntityID getTarget() {
        return this.finalAssignedFireTarget;
    }

    /**
     * 战地情报更新接口。决策模块不收发消息，情报更新交由父类统一抹平。
     */
    @Override
    public BuildingDetector updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() > 1) return this;
        return this;
    }

    // ========================================================================
    // 核心灭火目标决策中枢 (The Brain Pipeline)
    // ========================================================================

    /**
     * 灭火目标决策中枢。每秒钟调用一次，评估水量、防区、蔓延情况，画圈锁定目标。
     */
    @Override
    public BuildingDetector calc() {
        // 第一步：清点兵马，把水箱空了的“废铁”踢出聊天室
        Quartermaster quartermaster = new Quartermaster();
        List<EntityID> combatReadySquad = quartermaster.getCombatReadyBrigades();

        // 必须把自己强行加入现役序列（防死锁兜底）
        combatReadySquad.add(this.agentInfo.getID());

        // 第二步：聘请战术分析部，在全城的火海中，切出属于我们的那块蛋糕边界
        TacticalAnalysts analysts = new TacticalAnalysts();
        this.finalAssignedFireTarget = analysts.determineStrategicTarget(combatReadySquad);
        
        // 第三步：如果成功锁定了作战目标，把它大声地吼在车载屏幕上
        if (this.finalAssignedFireTarget != null) {
            this.printTacticalLog("锁定最新灭火目标 #" + this.finalAssignedFireTarget);
        }
        
        return this; // 返回当前计算完的大脑
    }

    // ========================================================================
    // 内部类 1：军需官 (Quartermaster)
    // 负责过滤失去战斗力的单位
    // ========================================================================
    private class Quartermaster {

        /**
         * 水源战备状态筛查器。消防车没水就是一块废铁，必须剔除。
         */
        List<EntityID> getCombatReadyBrigades() {
            // 拉取全城所有的消防车名册
            Collection<EntityID> allFireBrigades = worldInfo.getEntityIDsOfType(FIRE_BRIGADE);
            List<EntityID> combatReadyList = new ArrayList<>();
            
            // 挨个点名查水表
            for (EntityID currentId : allFireBrigades) {
                StandardEntity rawEntity = worldInfo.getEntity(currentId);
                if (rawEntity instanceof FireBrigade) {
                    FireBrigade truck = (FireBrigade) rawEntity;
                    // 水表读数大于0，才允许其加入现役作战序列
                    if (truck.getWater() > 0) {
                        combatReadyList.add(truck.getID());
                    }
                }
            }
            return combatReadyList;
        }
    }

    // ========================================================================
    // 内部类 2：战术分析部 (Tactical Analysts)
    // 包含最核心的“火海剥皮（凸包）”与“火势分诊”逻辑
    // ========================================================================
    private class TacticalAnalysts {

        /**
         * 火海群落战术分析主流程。
         * 1. 找最近的火海 -> 2. 拉橡皮筋(凸包) -> 3. 找边缘建筑 -> 4. 按火势分诊
         */
        EntityID determineStrategicTarget(List<EntityID> activeAgentIDs) {
            // 极端防呆：如果全城一辆有水的车都没了，那还推演个屁，直接摆烂
            if (activeAgentIDs.isEmpty()) return null;

            // 呼叫火场雷达重新扫描全城的火势群落
            fireClusterRadar.calc();
            int totalFireZones = fireClusterRadar.getClusterNumber();
            
            double shortestDistanceToZone = Double.MAX_VALUE;
            List<EntityID> nearestFireZoneEntities = new ArrayList<>();
            Point2D myCurrentLocation = new Point2D(agentInfo.getX(), agentInfo.getY());
            
            // --- 阶段 1：寻找最近的一片火海 ---
            for (int zoneIndex = 0; zoneIndex < totalFireZones; ++zoneIndex) {
                // 提取这个群落里的所有建筑物
                List<EntityID> currentZoneBuildings = new ArrayList<>(fireClusterRadar.getClusterEntityIDs(zoneIndex));
                
                // 算出这片火海的物理质心点
                GeometryEngine geoEngine = new GeometryEngine();
                Point2D zoneCenterPoint = geoEngine.calculateClusterCenter(currentZoneBuildings);

                // 拉起皮尺，测量我离这片火海质心的直线距离
                double distanceToZone = geoEngine.calculateDistance(myCurrentLocation, zoneCenterPoint);
                
                // 打擂台，寻找离我最近的那片火海作为主防守阵地
                if (distanceToZone < shortestDistanceToZone) {
                    shortestDistanceToZone = distanceToZone;
                    nearestFireZoneEntities = currentZoneBuildings;
                }
            }

            // --- 阶段 2：拉橡皮筋，提取物理边界 ---
            // 启动凸包几何生成器（这就是那根包围整片火海的橡皮筋）
            ExteriorEnvelope defensivePerimeterHull = new ExteriorEnvelope();
            for (EntityID buildingId : nearestFireZoneEntities) {
                StandardEntity rawEntity = worldInfo.getEntity(buildingId);
                if (rawEntity instanceof Area) {
                    // 将火海里的这栋建筑丢进去撑开橡皮筋
                    defensivePerimeterHull.add((Area) rawEntity);
                }
            }
            // 闭合橡皮筋，计算外围所有转折点（Apexes）
            defensivePerimeterHull.compute();
            
            // 让几何引擎去算一下，到底哪些楼真正碰到了这根橡皮筋？
            GeometryEngine geoEngine = new GeometryEngine();
            List<EntityID> boundaryBuildingIDs = geoEngine.extractPerimeterBuildings(nearestFireZoneEntities, defensivePerimeterHull.getApexes());

            // --- 阶段 3：按燃烧烈度进行医疗分诊 ---
            // 拿着这批“前线危楼”名单，去门诊部按火势轻重排个号
            TriageClinic clinic = new TriageClinic();
            return clinic.getOptimalTargetByFieryness(activeAgentIDs, boundaryBuildingIDs);
        }
    }

    // ========================================================================
    // 内部类 3：燃烧烈度分诊台 (Triage Clinic)
    // 决定这群处于边界的房子，谁最需要第一口水
    // ========================================================================
    private class TriageClinic {

        /**
         * 燃烧烈度分诊台。
         * 优先扑灭刚起火的（性价比极高）；再去对抗大火；快烧成灰的最后处理。
         */
        EntityID getOptimalTargetByFieryness(List<EntityID> activeAgentIDs, List<EntityID> candidateBuildingIDs) {
            
            // 建立三个停尸间/急救室，将危楼按火势分类
            List<EntityID> justIgnitedBuildings = new ArrayList<>();     // 刚冒烟，极易扑灭
            List<EntityID> fierceBurningBuildings = new ArrayList<>();   // 熊熊大火，硬仗
            List<EntityID> ashesAndInfernoBuildings = new ArrayList<>(); // 即将烧塌的人间炼狱

            // 将前线送来的名单挨个检伤分类
            for (EntityID currentId : candidateBuildingIDs) {
                StandardEntity rawEntity = worldInfo.getEntity(currentId);
                if (rawEntity instanceof Building) {
                    Building bld = (Building) rawEntity;
                    int fireLevel = bld.getFieryness();
                    
                    if (fireLevel == 1) justIgnitedBuildings.add(currentId);
                    else if (fireLevel == 2) fierceBurningBuildings.add(currentId);
                    else if (fireLevel == 3) ashesAndInfernoBuildings.add(currentId);
                }
            }

            // 最高优先级梯队：去掐灭刚起火的苗头，防患于未然
            if (!justIgnitedBuildings.isEmpty()) {
                EntityID bestChoice = findClosestInMySquadZone(activeAgentIDs, justIgnitedBuildings);
                // 如果自己防区里没有初起火灾，为了不发呆，闭着眼从全市初起火灾里抛硬币盲抓一个
                if (bestChoice == null) return justIgnitedBuildings.get(stochasticEngine.nextInt(justIgnitedBuildings.size()));
                return bestChoice;
            }
            
            // 第二优先级梯队：啃硬骨头，对抗中期大火
            if (!fierceBurningBuildings.isEmpty()) {
                EntityID bestChoice = findClosestInMySquadZone(activeAgentIDs, fierceBurningBuildings);
                if (bestChoice == null) return fierceBurningBuildings.get(stochasticEngine.nextInt(fierceBurningBuildings.size()));
                return bestChoice;
            }
            
            // 最差梯队：实在没得救了，给快塌的楼滋点水尽人事
            if (!ashesAndInfernoBuildings.isEmpty()) {
                EntityID bestChoice = findClosestInMySquadZone(activeAgentIDs, ashesAndInfernoBuildings);
                if (bestChoice == null) return ashesAndInfernoBuildings.get(stochasticEngine.nextInt(ashesAndInfernoBuildings.size()));
                return bestChoice;
            }

            return null; // 火全灭了
        }
    }

    // ========================================================================
    // 基础防区测算方法 
    // ========================================================================

    /**
     * 防区内就近目标选择器。
     * 消防车必须优先选择位于自己“防区（Cluster）”内，且离自己最近的房子。
     */
    private EntityID findClosestInMySquadZone(List<EntityID> activeAgentIDs, List<EntityID> candidates) {
        EntityID myTruckId = this.agentInfo.getID();
        
        // 问防区雷达：“长官，我被分在了第几个网格？”
        int mySquadZoneIndex = this.squadDistributionRadar.getClusterIndex(myTruckId);
        // 问防区雷达：“这个网格里具体管哪几条街、哪几栋楼？”
        Collection<EntityID> myAssignedPatrolZone = new HashSet<>(this.squadDistributionRadar.getClusterEntityIDs(mySquadZoneIndex));
        
        // 战区态势感知：去世界地图上扫查一遍，找出此时此刻全城所有正在冒火的楼
        List<EntityID> allCurrentlyBurningBuildings = new ArrayList<>();
        Collection<StandardEntity> allBuildings = this.worldInfo.getEntitiesOfType(BUILDING);
        for (StandardEntity bEntity : allBuildings) {
            if (bEntity instanceof Building) {
                Building bld = (Building) bEntity;
                if (bld.isOnFire()) allCurrentlyBurningBuildings.add(bld.getID());
            }
        }

        // 战术防呆兜底：检查我这片辖区里，是不是根本没有火灾？
        boolean hasFireInMyZone = false;
        for (EntityID fireId : allCurrentlyBurningBuildings) {
            if (myAssignedPatrolZone.contains(fireId)) {
                hasFireInMyZone = true;
                break;
            }
        }
        
        // 如果我的辖区里根本没火，我不能就在那看戏，直接撕毁防区边界，全城的火都归我管！
        if (!hasFireInMyZone) {
            myAssignedPatrolZone.addAll(allCurrentlyBurningBuildings);
        }

        // 寻找辖区内的最近目标
        EntityID ultimateTarget = null;
        double minimumDistanceFound = Double.MAX_VALUE;
        
        for (EntityID candidateId : candidates) {
            // 第一重约束：这栋危楼必须处于我目前认定的管辖防区内
            if (myAssignedPatrolZone.contains(candidateId)) {
                // 拉起皮尺，测量车和这栋楼的真实物理距离
                double currentDistance = this.worldInfo.getDistance(myTruckId, candidateId);
                
                // 打擂台，寻找离我最近的那一个幸运儿
                if (currentDistance < minimumDistanceFound) {
                    minimumDistanceFound = currentDistance;
                    ultimateTarget = candidateId;
                }
            }
        }
        return ultimateTarget;
    }

    // ========================================================================
    // 内部类 4：计算几何引擎 (Geometry Engine)
    // 处理坐标、测距、凸包碰撞点判断
    // ========================================================================
    private class GeometryEngine {

        /**
         * 边缘建筑提取器（凸包碰撞检测）。
         * 检查这片火海里的每一栋楼，如果它的某个墙角碰到了橡皮筋（顶点序列），它就是最外围边界！
         */
        List<EntityID> extractPerimeterBuildings(List<EntityID> clusterEntityIDs, Point2D[] boundingHullApexes) {
            List<EntityID> boundaryDefenders = new ArrayList<>();
            
            // 挨个检查火海里的每一栋楼
            for (EntityID currentBuildingId : clusterEntityIDs) {
                StandardEntity entity = worldInfo.getEntity(currentBuildingId);
                if (!(entity instanceof Building)) continue;
                
                Building bld = (Building) entity;
                // 调取这栋楼的房产证，拿到它所有墙角的绝对物理坐标序列 [x1, y1, x2, y2...]
                int[] wallCorners = bld.getApexList();
                boolean isTouchingBoundary = false;
                
                // 遍历这栋楼的每一个墙角（步长为 2）
                for (int i = 0; i < wallCorners.length - 1; i += 2) {
                    Point2D currentCorner = new Point2D(wallCorners[i], wallCorners[i + 1]);
                    
                    // 拿这个墙角，去跟橡皮筋的每一个转折点比对
                    for (int j = 0; j < boundingHullApexes.length; ++j) {
                        // 如果极其接近（nearlyZero），判定为墙角碰到了橡皮筋！
                        if (GeometryTools2D.nearlyZero(GeometryTools2D.getDistance(currentCorner, boundingHullApexes[j]))) {
                            isTouchingBoundary = true;
                            break; // 只要碰到了一个角，这楼就在边界上，结束扫描
                        }
                    }
                    if (isTouchingBoundary) {
                        // 确诊为火海边界的隔离带建筑，登记造册
                        boundaryDefenders.add(currentBuildingId);
                        break; 
                    }
                }
            }
            return boundaryDefenders;
        }

        /**
         * 群落几何中心测绘仪。给定一堆建筑物，算出平均地理重心。
         */
        Point2D calculateClusterCenter(List<EntityID> clusterBuildings) {
            double totalX = 0, totalY = 0;
            for (EntityID buildingId : clusterBuildings) {
                Pair<Integer, Integer> coords = worldInfo.getLocation(buildingId);
                totalX += coords.first();
                totalY += coords.second();
            }
            return new Point2D(totalX / clusterBuildings.size(), totalY / clusterBuildings.size());
        }

        /**
         * 直线距离测距仪（欧氏距离）。
         */
        double calculateDistance(Point2D p1, Point2D p2) {
            return Math.hypot(p2.getX() - p1.getX(), p2.getY() - p1.getY());
        }
    }

    /**
     * 战术日志打印机。
     * 将枯燥的数据在黑框框里格式化输出，方便看清这辆车到底在发什么疯。
     */
    private void printTacticalLog(String logMessage) {
        String logHeader = "🚒  [" + String.format("%10d", this.agentInfo.getID().getValue()) + "]";
        logHeader += " FIRE-TACTICAL-RADAR @" + String.format("%3d", this.agentInfo.getTime()) + " -> ";
        System.out.println(logHeader + logMessage);
    }
}
