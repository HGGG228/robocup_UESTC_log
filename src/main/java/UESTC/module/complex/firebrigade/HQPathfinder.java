package UESTC.module.complex.firebrigade;

import java.util.*;

import UESTC.module.algorithm.*;

import adf.core.agent.action.Action;
import adf.core.agent.action.fire.ActionExtinguish;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.MessageBuilding;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.algorithm.*;
import adf.core.component.module.complex.Search;
import rescuecore2.misc.Pair;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;
import static rescuecore2.standard.entities.StandardEntityConstants.Fieryness.*;

import rescuecore2.worldmodel.EntityID;

/**
 * HQPathfinder 消防车战区网格化巡逻与暗火侦察器。
 * 物理意义：火灾被扑灭后，经常会留下高温隐患（暗火）；而且在灾难初期，有些楼正在升温但还没冒出明火。
 * 消防车在无明确灭火任务时，不能只在原地发呆，必须主动出击巡逻。
 * 战术核心：
 * 1. 区域划分：将全城划分为多个巡逻网格（Cluster），每辆车负责自己的片区，防止扎堆。
 * 2. 异常体温扫描：特别关注那些“温度超过警戒值但还没冒火”的建筑。
 * 3. 动态插队：如果刚刚扑灭了一场火，立刻把周围一圈的楼加入重点巡视名单（防止火星溅射）。
 * 4. 黑名单防抖：刚刚去过的楼，20秒内不再去，避免“鬼打墙”。
 */
public class HQPathfinder extends Search implements HQPathPublisher {

    // ========================================================================
    // 雷达与导航设备区
    // ========================================================================

    // 底层路径规划导航仪。负责算出怎么绕过废墟开到目标大楼。
    private PathPlanning routeNavigationRadar;
    
    // 全城网格划区仪。把城市切分成几块，方便消防车分头行动。
    private Clustering zonePartitionRadar;
    
    // 拥堵被困警报雷达。用于侦测自己是不是被平民或者其他车挤在路中间动弹不得了。
    private Clustering EntrappedStateAgentRadar;
    
    // 道路断行警报雷达。用于侦测自己是不是遇到了过不去的断头路。
    private Clustering trafficJamRadar;

    // ========================================================================
    // 自身状态与随机引擎
    // ========================================================================

    // 当前锁定前往的巡视目标楼。
    private EntityID lockedSearchDestination;

    // 消防车自身的身份证号。
    private final EntityID mySelfId;
    
    // 战术随机数骰子。在多个目标同样紧急时，用抛硬币决定去哪，增加巡视的随机覆盖率。
    private Random stochasticEngine = new Random();

    // ========================================================================
    // 战区管理与记忆账本
    // ========================================================================

    // 全城划分出的总网格数量。
    private int totalPatrolZones;
    
    // 当前正在巡视的网格编号。
    private int currentPatrolZoneIndex;
    
    // 待巡视清单（任务队列）。当前网格内还没去巡查过的建筑物名单。
    private List<EntityID> pendingBuildingQueue = new ArrayList<>();
    
    // 战术紧急插队任务表。Key 是未来的某个时间点（比如第50帧），Value 是要求在那个时候立刻去查探的一批楼。
    private Map<Integer, Collection<EntityID>> tacticalOverrideTasks = new HashMap<>();
    
    // 巡逻轨迹防抖黑名单。Key 是解禁时间，Value 是楼的ID。防止反复进出同一栋大楼。
    private Map<Integer, EntityID> recentlyVisitedBlacklist = new HashMap<>();
    
    // 全城暗火体温备忘录。只要瞟到哪栋楼温度大于0就记下来，闲着的时候专门去查这些发烧的楼。
    private Map<EntityID, Integer> buildingThermalMemory = new HashMap<>();

    /**
     * 搜救模块构造器。
     */
    public HQPathfinder(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager mm, DevelopData dd) {
        // 装载系统基础信息
        super(ai, wi, si, mm, dd);

        // 去模块库申领各类定制雷达
        this.routeNavigationRadar = mm.getModule("UESTC.FB.BuildingSearch.PathPlanning");
        this.zonePartitionRadar = mm.getModule("UESTC.FB.BuildingSearch.Clustering");
        this.EntrappedStateAgentRadar = mm.getModule("UESTC.FB.BuildingSearch.StuckHumans");
        this.trafficJamRadar = mm.getModule("UESTC.FB.BuildingSearch.FailedMove");

        // 将雷达挂载到自己的仪表盘上，确保每回合自动同步扫描
        this.registerModule(this.routeNavigationRadar);
        this.registerModule(this.zonePartitionRadar);
        this.registerModule(this.EntrappedStateAgentRadar);
        this.registerModule(this.trafficJamRadar);

        // 牢记自己的身份ID
        this.mySelfId = this.agentInfo.getID();
        // 用自己的身份证号作为随机数种子，确保不同车的随机路线不同
        this.stochasticEngine.setSeed(ai.getID().getValue());
    }

    /**
     * 开局首次启动。
     */
    @Override
    public Search preparate() {
        super.preparate();
        if (this.getCountPreparate() > 1) return this;
        
        // 启动区域划分雷达，全城切块
        this.zonePartitionRadar.calc();
        // 记录一下全城被切成了几块
        this.totalPatrolZones = this.zonePartitionRadar.getClusterNumber();
        // 看看我自己这辆车被分到了哪个区
        this.currentPatrolZoneIndex = this.zonePartitionRadar.getClusterIndex(this.mySelfId);
        
        return this;
    }

    /**
     * 缓存恢复启动。
     */
    @Override
    public Search resume(PrecomputeData pd) {
        super.resume(pd);
        if (this.getCountResume() > 1) return this;
        
        // 和首次启动一样，恢复防区记忆
        this.zonePartitionRadar.calc();
        this.totalPatrolZones = this.zonePartitionRadar.getClusterNumber();
        this.currentPatrolZoneIndex = this.zonePartitionRadar.getClusterIndex(this.mySelfId);
        
        return this;
    }

    /**
     * 向外暴露本回合敲定的巡视目标。
     */
    @Override
    public EntityID getTarget() {
        // 把大脑想了半天才决定的目的地交出去
        return this.lockedSearchDestination;
    }

    // ========================================================================
    // 战地情报与状态更新台
    // ========================================================================

    // 开局早期高频测温窗口期（前 30 秒）。此时需要对温度高度敏感。
    private int N_STEPS_SEARCH_FOR_TEMP = 30; 

    /**
     * 战地情报与状态更新台。每秒钟接收自己的动作历史、视觉变化，更新备忘录和任务队列。
     */
    @Override
    public Search updateInfo(MessageManager mm) {
        super.updateInfo(mm);
        // 防重复更新
        if (this.getCountUpdateInfo() > 1) return this;
        
        // 物理禁锢安检：如果我被困住了（挤在人堆里），视野和行动受限，直接放弃情报更新
        int myStuckIndex = this.EntrappedStateAgentRadar.getClusterIndex(this.mySelfId);
        if (myStuckIndex >= 0) return this;
        
        // 请出战地情报官来处理这些杂事
        IntelligenceOfficer officer = new IntelligenceOfficer();
        
        // 第一件事：战术溯源。回想一下我刚才干了啥，需不需要对刚灭火的现场进行重点监控？
        officer.traceLastActionForRecon();
        
        // 第二件事：初期测温战略。在灾害前 30 秒内，全力寻找正在发烧的楼！
        officer.executeEarlyFeverDetection();
        
        // 第三件事：更新个人的巡视队列。划去刚才肉眼看过的楼，加重黑名单记录。
        officer.refreshPatrolMemory();
        
        // 第四件事：如果我自己防区的名单巡视完了，向上面申请下一个网格的防区！
        officer.requestNextPatrolZone();
        
        // 第五件事：将我自己看出来的火场温度，通过无线电报告给队友。
        this.sendSelectedInfo(mm);
        
        return this;
    }

    // ========================================================================
    // 核心搜救目标决断引擎
    // ========================================================================

    // 轮盘赌强制转移概率。用于控制紧急插队任务的触发概率。
    private static final double URGENT_INTERVENTION_PROBABILITY = 0.6;

    /**
     * 核心搜救目标决断引擎。大脑思考要去哪里。
     * 优先查探备忘录里的高温楼，其次处理插队复勘任务，最后才进行常规网格巡逻。
     */
    @Override
    public Search calc() {
        // 第一重安检：如果脑子里已经锁定了死目标了，就不瞎想了
        if (this.lockedSearchDestination != null) return this;
        
        // 第二重安检：如果我挤在废墟里动不了，想了也没用
        if (this.EntrappedStateAgentRadar.getClusterIndex(this.mySelfId) >= 0) return this;

        // 掏出硬币：扔一个随机数骰子出来备用
        double fortuneDice = this.stochasticEngine.nextDouble();
        
        // 聘请首席战术规划师
        TacticalPlanner planner = new TacticalPlanner();
        
        // --- 战术第一步：处理暗火备忘录（最高优先级） ---
        // 看看有没有那种严重发烧但是还没冒火的危楼？
        EntityID thermalTarget = planner.evaluateThermalTargets(fortuneDice);
        if (thermalTarget != null) {
            // 如果轮盘赌选中了一个发烧楼，锁定它！结束思考。
            this.lockedSearchDestination = thermalTarget;
            return this;
        }

        // --- 战术第二步：处理强行插队的紧急复勘任务（中优先级） ---
        // 比如某些之前预定好在这一秒钟必须去复查的火灾残留现场。
        // 如果骰子运气好（<0.6），且确实有紧急任务，就去查它！
        if (planner.handleUrgentOverrides(fortuneDice)) {
            // 如果成功处理了插队任务，内部已经锁定了 destination，直接返回
            return this;
        }

        // --- 战术第三步：兜底的常规网格巡逻（低优先级） ---
        // 大多数时候，老老实实从我网格里的常规队伍中抽签查房
        planner.executeRoutinePatrol();
        
        return this;
    }

    // ========================================================================
    // 内部类 1：战地情报官 (Intelligence Officer)
    // 负责清理 updateInfo() 里的杂乱逻辑
    // ========================================================================
    private class IntelligenceOfficer {
        
        // 灭火复勘插队延迟期。刚灭完火，等 20 秒再回头去查复燃情况。
        private static final int INTERVAL_INTERRUPT_FIRE = 20;
        // 高温发酵延迟期。发现高温异常后，给它 10 秒钟时间发酵再去复查。
        private static final int INTERVAL_INTERRUPT_TEMP = 10;
        // 发热警戒红线。没冒火的楼如果超过 15 度，说明内部正在闷烧。
        private static final int THOLD_TEMP = 15;
        // 战术波及半径。画一个 55 米的圆扫东西。
        private static final int ADJACENT_RANGE = 55_000;
        // 视野免疫冷却期。刚去过的地方 20 秒内不准再去。
        private static final int IGNORE_TIME = 20;

        /**
         * 战术溯源：根据上一秒的动作决定这秒的警惕性。
         */
        void traceLastActionForRecon() {
            int currentTime = agentInfo.getTime();
            // 看看我上一秒刚干了啥？
            Action lastAction = agentInfo.getExecutedAction(currentTime - 1);
            
            // 如果我上一秒刚好滋了水（执行了灭火动作）
            if (lastAction instanceof ActionExtinguish) {
                ActionExtinguish extinguishAction = (ActionExtinguish) lastAction;
                // 立刻抽出刚灭火的这栋楼的 ID
                EntityID extinguishedBuildingId = extinguishAction.getTarget();
                
                // 触发战术联动：把这栋刚灭火的楼周围 55 米内所有的实体扫出来
                Collection<EntityID> nearbyEntities = worldInfo.getObjectIDsInRange(extinguishedBuildingId, ADJACENT_RANGE);
                
                // 提纯一下，把那些废墟、政府大楼剔除掉，只留有效民居
                Collection<EntityID> validCandidates = extractCandities(nearbyEntities);
                
                // 把它们统统强行塞进我的日常巡逻队伍里
                pendingBuildingQueue.addAll(validCandidates);
                // 手工去重：把队伍重新梳理一遍，防止同一个楼被加了两次
                pendingBuildingQueue = new ArrayList<>(new LinkedHashSet<>(pendingBuildingQueue));
                
                // 记在日历本上：20秒后的那一刻，强制从队列里把这些楼拉出来看一眼！
                addIntoMap(tacticalOverrideTasks, currentTime + INTERVAL_INTERRUPT_FIRE, nearbyEntities);
            }
        }

        /**
         * 初期测温战略：发现异常闷烧楼，强行插队查探。
         */
        void executeEarlyFeverDetection() {
            // 只有比赛刚开始的 30 秒需要这么做
            if (agentInfo.getTime() > N_STEPS_SEARCH_FOR_TEMP) return;
            
            // 去找一圈有没有发烧的楼
            Collection<EntityID> feverBuildings = findHigherTemps();
            // 如果真找到了
            if (!feverBuildings.isEmpty()) {
                
                // 找出这批楼里面，温度烧得最烫的那一栋！
                EntityID hottestTarget = null;
                int maxRecordedTemp = -1;
                for (EntityID candidateId : feverBuildings) {
                    Building bld = (Building) worldInfo.getEntity(candidateId);
                    int currentTemp = bld.getTemperature();
                    if (currentTemp > maxRecordedTemp) {
                        maxRecordedTemp = currentTemp;
                        hottestTarget = candidateId;
                    }
                }
                
                if (hottestTarget != null) {
                    // 把最烫的那栋楼周围 55 米圈起来
                    Collection<EntityID> surroundingEntities = worldInfo.getObjectIDsInRange(hottestTarget, ADJACENT_RANGE);
                    Collection<EntityID> validSurroundings = extractCandities(surroundingEntities);
                    
                    // 把这一圈“有潜在危险”的邻居强行拉入巡逻队
                    pendingBuildingQueue.addAll(validSurroundings);
                    pendingBuildingQueue = new ArrayList<>(new LinkedHashSet<>(pendingBuildingQueue));
                    
                    // 登记造册，10秒后统一突击检查这片高危区域！
                    addIntoMap(tacticalOverrideTasks, agentInfo.getTime() + INTERVAL_INTERRUPT_TEMP, surroundingEntities);
                }
            }
        }

        /**
         * 寻找异常发烧的楼。
         */
        private Collection<EntityID> findHigherTemps() {
            // 获取刚刚发生风吹草动的所有实体
            Set<EntityID> changedEntityIds = worldInfo.getChanged().getChangedEntities();
            Set<EntityID> abnormalFeverList = new HashSet<>();
            
            for (EntityID currentId : changedEntityIds) {
                StandardEntity baseEntity = worldInfo.getEntity(currentId);
                // 如果是普通的民房建筑
                if (baseEntity instanceof Building && !(baseEntity instanceof Refuge) && !(baseEntity instanceof AmbulanceCentre) 
                    && !(baseEntity instanceof FireStation) && !(baseEntity instanceof PoliceOffice)) {
                    
                    Building civilianBuilding = (Building) baseEntity;
                    
                    // 战术核验：必须是表面还没冒火（Fieryness == 0）
                    boolean isNotOnFire = civilianBuilding.isFierynessDefined() && civilianBuilding.getFieryness() == 0;
                    if (isNotOnFire) {
                        // 医疗核验：必须是测出了体温，且体温越过了 15 度的红线
                        boolean isFevering = civilianBuilding.isTemperatureDefined() && civilianBuilding.getTemperature() >= THOLD_TEMP;
                        if (isFevering) {
                            // 确诊为闷烧暗火，记录在案！
                            abnormalFeverList.add(civilianBuilding.getID());
                        }
                    }
                }
            }
            return abnormalFeverList;
        }

        /**
         * 刷新巡逻记忆：清理旧任务、记录黑名单、更新暗火备忘录。
         */
        void refreshPatrolMemory() {
            // 获取这秒钟我肉眼刚看清楚的建筑（过滤后的纯净版）
            Collection<EntityID> currentlyVisibleBuildings = extractCandities(worldInfo.getChanged().getChangedEntities());
            
            // 既然我看过了，就从待巡视的账本里把它划掉
            pendingBuildingQueue.removeAll(currentlyVisibleBuildings);
            
            // 将我目前脚踩着的位置拉入黑名单，并标注 20 秒后才解禁。防止我刚走出去又绕回来。
            int unblockTime = agentInfo.getTime() + IGNORE_TIME;
            recentlyVisitedBlacklist.put(unblockTime, agentInfo.getPosition());
            
            // 边走边记：看见温度大于0的记下来，看见降为0的划掉
            for (EntityID currentId : currentlyVisibleBuildings) {
                StandardEntity baseEntity = worldInfo.getEntity(currentId);
                if (baseEntity instanceof Building) {
                    Building bld = (Building) baseEntity;
                    if (bld.isTemperatureDefined()) {
                        int currentTemp = bld.getTemperature();
                        if (currentTemp > 0) {
                            // 如果发烧，重重地记入备忘录
                            buildingThermalMemory.put(bld.getID(), currentTemp);
                        } else if (currentTemp == 0) {
                            // 如果彻底退烧了，从暗火备忘录中彻底开除
                            buildingThermalMemory.remove(bld.getID());
                        }
                    }
                }
            }
            // 我自己脚底下的位置肯定没烧起来，不用记
            buildingThermalMemory.remove(agentInfo.getPosition());
            
            // 如果底层的导航仪说路被堵死了根本过不去
            if (cannotReach()) {
                // 直接放弃那个不切实际的遥远目标
                pendingBuildingQueue.remove(lockedSearchDestination);
            }
            
            // 如果我原定要去的目标，因为种种原因从队列里消失了
            if (!pendingBuildingQueue.contains(lockedSearchDestination)) {
                // 清空大脑，准备重选！
                lockedSearchDestination = null;
            }

            // 日历报警！如果正好在这个时间点，有之前安排好的“战术插队任务”到了！
            int currentTime = agentInfo.getTime();
            if (tacticalOverrideTasks.containsKey(currentTime)) {
                Collection<EntityID> overrideTasks = tacticalOverrideTasks.get(currentTime);
                // 先把它们从日常队列里剥离出来，为了后续在 calc 中触发高优检测
                pendingBuildingQueue.removeAll(overrideTasks);
            }
        }

        /**
         * 申请接管下一个防区网格。
         */
        void requestNextPatrolZone() {
            int refillAttempts = 0;
            // 如果我手头的待查名单全看完了，空空如也
            while (pendingBuildingQueue.isEmpty()) {
                // 兜底防死循环：如果全城所有的区我都要过了
                if (refillAttempts > totalPatrolZones) break;
                
                // 从系统骰子里摇一个新区编号出来
                currentPatrolZoneIndex = stochasticEngine.nextInt(totalPatrolZones);
                // 去雷达里把那个新区的建筑全拉出来
                Collection<EntityID> rawZoneEntities = zonePartitionRadar.getClusterEntityIDs(currentPatrolZoneIndex);
                // 提纯一下塞进我的日常名单里
                pendingBuildingQueue = new ArrayList<>(extractCandities(rawZoneEntities));
                
                refillAttempts++;
            }
        }
    }

    // ========================================================================
    // 内部类 2：首席战术规划师 (Tactical Planner)
    // 负责梳理 calc() 中极其复杂的目标决断逻辑
    // ========================================================================
    private class TacticalPlanner {

        /**
         * 评估暗火备忘录中的高危目标，用轮盘赌进行抉择。
         */
        EntityID evaluateThermalTargets(double fortuneDice) {
            // 第一步：整理现在依然生效的黑名单（还没到解禁时间的）
            Set<EntityID> currentlyActiveIgnoreList = new HashSet<>();
            int currentClock = agentInfo.getTime();
            for (Map.Entry<Integer, EntityID> blacklistRecord : recentlyVisitedBlacklist.entrySet()) {
                if (blacklistRecord.getKey() > currentClock) {
                    currentlyActiveIgnoreList.add(blacklistRecord.getValue());
                }
            }

            // 第二步：翻看暗火备忘录，把没在黑名单里的挑出来，准备查处
            List<Map.Entry<EntityID, Integer>> validThermalTargets = new ArrayList<>();
            for (Map.Entry<EntityID, Integer> thermalRecord : buildingThermalMemory.entrySet()) {
                if (!currentlyActiveIgnoreList.contains(thermalRecord.getKey())) {
                    validThermalTargets.add(thermalRecord);
                }
            }

            // 如果全城只有一个正在闷烧的楼，别想了，直接出警！
            if (validThermalTargets.size() == 1) {
                return validThermalTargets.get(0).getKey();
            }

            // 如果有好几个暗火楼，需要通过一场豪赌来决定！
            if (!validThermalTargets.isEmpty()) {
                // 测出我自己防区正中心的位置
                final Point2D myZoneCenter = getClusterCenter();
                
                // 对所有发烧的楼进行终极排序：
                // 第一标准：谁烧得最烫谁排在前面（温度降序）
                // 第二标准：如果一样烫，谁离我家网格中心近谁排在前面（距离升序）
                Collections.sort(validThermalTargets, new Comparator<Map.Entry<EntityID, Integer>>() {
                    @Override
                    public int compare(Map.Entry<EntityID, Integer> entryA, Map.Entry<EntityID, Integer> entryB) {
                        int tempComparison = entryB.getValue().compareTo(entryA.getValue());
                        if (tempComparison != 0) return tempComparison;
                        
                        Pair<Integer, Integer> locA = worldInfo.getLocation(entryA.getKey());
                        Pair<Integer, Integer> locB = worldInfo.getLocation(entryB.getKey());
                        double distanceA = Math.hypot(locA.first() - myZoneCenter.getX(), locA.second() - myZoneCenter.getY());
                        double distanceB = Math.hypot(locB.first() - myZoneCenter.getX(), locB.second() - myZoneCenter.getY());
                        return Double.compare(distanceA, distanceB);
                    }
                });
                
                // 算一下全城暗火的热量总和，用来做轮盘赌的基数分母
                double totalFeverTemperature = 0.0D;
                for (Integer tempVal : buildingThermalMemory.values()) {
                    totalFeverTemperature += (double) tempVal;
                }

                // 启动俄罗斯轮盘赌！温度越高的楼，扇区越大，被骰子击中的概率越高！
                double probabilityAccumulator = 0.0D;
                for (Map.Entry<EntityID, Integer> entry : validThermalTargets) {
                    probabilityAccumulator += (double) entry.getValue() / totalFeverTemperature;
                    // 一旦累积概率越过骰子的数值，立刻锁定它！
                    if (probabilityAccumulator >= fortuneDice) {
                        return entry.getKey();
                    }
                }
            }
            return null; // 没选中任何暗火目标
        }

        /**
         * 处理强行插队的复勘任务。
         */
        boolean handleUrgentOverrides(double fortuneDice) {
            // 找出此时此刻，有哪些紧急任务到了处理时间？
            List<EntityID> intersectingUrgentTasks = new ArrayList<>();
            int currentClock = agentInfo.getTime();
            
            for (Map.Entry<Integer, Collection<EntityID>> overrideRecord : tacticalOverrideTasks.entrySet()) {
                // 如果这个任务的闹钟时间已经到了，或者已经拖延过了
                if (overrideRecord.getKey() > currentClock) {
                    for (EntityID urgentBuildingId : overrideRecord.getValue()) {
                        // 如果它恰好还没看，还在待查队列里，确认为有效插队！
                        if (pendingBuildingQueue.contains(urgentBuildingId)) {
                            if (!intersectingUrgentTasks.contains(urgentBuildingId)) {
                                intersectingUrgentTasks.add(urgentBuildingId);
                            }
                        }
                    }
                }
            }

            // 如果真有紧急任务，而且我的运气不错（小于 0.6 的强制发生率）
            if (!intersectingUrgentTasks.isEmpty() && fortuneDice < URGENT_INTERVENTION_PROBABILITY) {
                out("SEARCH CANDs D: " + intersectingUrgentTasks); 
                // 盲抓一个去现场看看
                int randomIndex = stochasticEngine.nextInt(intersectingUrgentTasks.size());
                lockedSearchDestination = intersectingUrgentTasks.get(randomIndex);
                out("SEARCH #" + lockedSearchDestination);
                return true; // 宣告插队成功
            }
            return false; // 不处理插队
        }

        /**
         * 执行平淡无奇的日常网格巡逻。
         */
        void executeRoutinePatrol() {
            // 如果连日常队列都空了
            if (pendingBuildingQueue.isEmpty()) {
                // 为了不让车停在路中间发呆，随便在世界地图上抽一个房子去看看
                Collection<EntityID> allMapBuildings = worldInfo.getEntityIDsOfType(BUILDING);
                if (!allMapBuildings.isEmpty()) {
                    lockedSearchDestination = allMapBuildings.iterator().next();
                }
                return;
            }

            // 大多数日子里，老老实实从我网格里的排队名单中，抽个签去查房
            out("SEARCH CANDs: " + pendingBuildingQueue);
            int regularRandomIndex = stochasticEngine.nextInt(pendingBuildingQueue.size());
            lockedSearchDestination = pendingBuildingQueue.get(regularRandomIndex);
            out("SEARCH #" + lockedSearchDestination);
        }
    }

    // ========================================================================
    // 基础功能与辅助探针 
    // ========================================================================

    /**
     * 纯净待搜楼宇提纯器。剔除一切不能搜、不用搜的废墟和政府楼。
     */
    private Collection<EntityID> extractCandities(Collection<EntityID> roughCluster) {
        Set<EntityID> purifiedList = new HashSet<>();
        for (EntityID entityId : roughCluster) {
            StandardEntity baseEntity = this.worldInfo.getEntity(entityId);
            if (baseEntity instanceof Building) {
                // 政府单位一律不归我们查
                if (baseEntity instanceof Refuge) continue;
                if (baseEntity instanceof AmbulanceCentre) continue;
                if (baseEntity instanceof FireStation) continue;
                if (baseEntity instanceof PoliceOffice) continue;
                
                Building civilianBuilding = (Building) baseEntity;
                // 如果已经烧成一地黑灰（BURNT_OUT）了，去了也白去
                if (civilianBuilding.getFierynessEnum() == BURNT_OUT) continue;
                
                purifiedList.add(civilianBuilding.getID());
            }
        }
        return purifiedList;
    }

    /**
     * 辖区几何中心定位仪。算出分给我的这块地盘中心在哪。
     */
    private Point2D getClusterCenter() {
        int myAssignedIndex = this.zonePartitionRadar.getClusterIndex(this.mySelfId);
        Collection<EntityID> zoneEntities = this.zonePartitionRadar.getClusterEntityIDs(myAssignedIndex);
        
        double elementCount = zoneEntities.size();
        double totalXCoordinates = 0.0D;
        double totalYCoordinates = 0.0D;
        
        for (EntityID eId : zoneEntities) {
            Pair<Integer, Integer> loc = this.worldInfo.getLocation(eId);
            totalXCoordinates += (double) loc.first();
            totalYCoordinates += (double) loc.second();
        }
        return new Point2D(totalXCoordinates / elementCount, totalYCoordinates / elementCount);
    }

    /**
     * 行动能力瘫痪鉴定器。看看底层系统是不是报了“路不通”。
     */
    private boolean cannotReach() {
        if (this.lockedSearchDestination == null) return false;
        // 警察手里有电锯能自己开路，永远不会无法到达
        if (this.agentInfo.me().getStandardURN() == POLICE_FORCE) return false;

        this.trafficJamRadar.calc();
        this.EntrappedStateAgentRadar.calc();

        // 看看我自己是不是被挂在“交通瘫痪”或者“身体被困”的通报里了
        boolean isTrafficFailed = this.trafficJamRadar.getClusterIndex(this.mySelfId) >= 0;
        boolean isPhysicallyStuck = this.EntrappedStateAgentRadar.getClusterIndex(this.mySelfId) >= 0;
        
        return isPhysicallyStuck || isTrafficFailed;
    }

    /**
     * 字典多值集合追加器。如果没有这个键就新建 List，有就加进末尾。
     */
    private <T, E> void addIntoMap(Map<T, Collection<E>> dict, T key, Collection<E> payloadCollection) {
        Collection<E> clonedValues = new ArrayList<>(payloadCollection);
        if (dict.containsKey(key)) {
            this.out("战术提示：该时间点的强制插队任务已存在，将叠加记录。ALREADY EXISTS in 'interrupts'");
            clonedValues.addAll(dict.get(key));
        }
        dict.put(key, clonedValues);
    }

    /**
     * 战术日志打印机。将数据格式化，方便人类看懂车在干嘛。
     */
    private void out(String str) {
        String formattedLog = "🚒  [" + String.format("%10d", this.agentInfo.getID().getValue()) + "]";
        formattedLog += " BUILDING-SEARCH @" + String.format("%3d", this.agentInfo.getTime()) + " -> ";
        System.out.println(formattedLog + str);
    }

    // ========================================================================
    // 发报控制与价值鉴定 
    // ========================================================================

    /**
     * 情报共享发报机。把眼前最值钱的楼用电报发出去。
     */
    private void sendSelectedInfo(MessageManager mm) {
        // 先看看我有没有资格发报？
        if (!this.shouldSend()) return;

        // 获取肉眼看到的净土
        Collection<EntityID> visibleEntities = this.extractCandities(this.worldInfo.getChanged().getChangedEntities());
        // 挑出最值钱的一栋
        EntityID mostValuableBuildingId = this.selectOneToSend(visibleEntities);
        
        if (mostValuableBuildingId != null) {
            Building valuableBld = (Building) this.worldInfo.getEntity(mostValuableBuildingId);
            // 拍下电报，丢进发送队列
            mm.addMessage(new MessageBuilding(true, valuableBld));
        }
    }

    /**
     * 发言权竞标器。多辆车看见一样的场景，只允许老大哥发报，防拥堵。
     */
    private Boolean shouldSend() {
        if (this.agentInfo.getTime() < 1) return false;
        
        // 获取全城的神仙（救护、消防、警察）
        Collection<EntityID> allHeroes = this.worldInfo.getEntityIDsOfType(AMBULANCE_TEAM, FIRE_BRIGADE, POLICE_FORCE);
        StandardEntity myFootprint = this.worldInfo.getPosition(this.mySelfId);
        
        int maximumIdDiscovered = -1;
        // 看看我脚底下站了几个人？
        for (EntityID heroId : allHeroes) {
            StandardEntity hisFootprint = this.worldInfo.getPosition(heroId);
            if (hisFootprint != null && hisFootprint.equals(myFootprint)) {
                // 如果他也站在这里，那就比一比编号大小
                if (heroId.getValue() > maximumIdDiscovered) {
                    maximumIdDiscovered = heroId.getValue();
                }
            }
        }
        // 只有我自己的编号刚好是最大的那个，我才开麦！
        return this.mySelfId.getValue() == maximumIdDiscovered;
    }

    /**
     * 最具价值情报甄选器。从眼前的废墟里，挑出温度最高的那栋！
     */
    private EntityID selectOneToSend(Collection<EntityID> purifiedCandidates) {
        EntityID hottestTarget = null;
        int recordHighTemp = -1;
        
        // 挨个摸一摸眼前的房子
        for (EntityID cId : purifiedCandidates) {
            StandardEntity baseEntity = this.worldInfo.getEntity(cId);
            if (baseEntity instanceof Building) {
                Building bld = (Building) baseEntity;
                if (bld.isTemperatureDefined()) {
                    int itsTemp = bld.getTemperature();
                    // 谁最烫就选谁上报
                    if (itsTemp > recordHighTemp) {
                        recordHighTemp = itsTemp;
                        hottestTarget = bld.getID();
                    }
                }
            }
        }
        return hottestTarget;
    }
}
