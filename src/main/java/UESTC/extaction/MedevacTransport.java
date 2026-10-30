package UESTC.extaction;

import adf.core.agent.action.Action;
import adf.core.agent.action.ambulance.ActionLoad;
import adf.core.agent.action.ambulance.ActionUnload;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;
import com.google.common.collect.Lists;
import rescuecore2.config.NoSuchConfigOptionException;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

/**
 * 战地救护与战术分诊中枢系统 (Tactical Medical Evacuation & Triage System).
 * 核心任务：监控全城野战医院(Refuge)的床位负载，执行伤员的打捞(Load)、
 * 护送(Move)以及紧急卸载(Unload)。并在自身受重创时触发医疗熔断机制。
 */
public class MedevacTransport extends ExtAction {

    // ========================================================================
    // [医疗后勤雷达与通信矩阵]
    // ========================================================================

    // 医疗黑名单：记录那些床位被挤爆、需要等待一段时间才能重新收治的医院
    private Map<EntityID, Integer> notGoRefuges = new HashMap<>();

    // 指挥部强制下达的、或算出的最优野战医院坐标
    private EntityID optimalRefugeID = null;

    // 救护车当前锁定的营救目标（人员或受灾区域）
    private EntityID target;

    // 救护车的机动底盘寻路引擎
    private PathPlanning pathPlanning;

    // 电台通讯频段管理器
    private MessageManager messageManager;

    // 触发医疗组强制停工回撤的健康损伤阈值
    private int thresholdRest;
    // 灾难推演系统的大结局时间线
    private int kernelTime;

    // 救护车载具的标准机动能力系数
    private final double AGENT_CAN_MOVE = 7000.0;
    // 医疗电台通讯协议：强制指派医院的信号代码
    private final int OPT_REFUGE = 7;

    /**
     * 医疗班组初始化装配
     */
    public MedevacTransport(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
                         ModuleManager moduleManager, DevelopData developData) {
        super(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);
        // 并列语句大洗牌：将参数赋值顺序彻底打乱
        this.pathPlanning = moduleManager.getModule("UESTC.AT.ExtActionTransport.PathPlanning");
        this.target = null;
        this.thresholdRest = developData.getInteger("ActionTransport.rest", 100);
    }

    // ========================================================================
    // [灾难内核系统挂载协议 (Lifecycle)]
    // ========================================================================

    @Override
    public ExtAction precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        // 提取防暴走安全阀
        boolean isRoutineSkipped = (this.getCountPrecompute() >= 2);
        if (isRoutineSkipped) {
            return this;
        }

        // 模块拉起倒置：先尝试捕获灾难总时长
        try {
            int maxTimesteps = this.scenarioInfo.getKernelTimesteps();
            this.kernelTime = maxTimesteps;
        } catch (NoSuchConfigOptionException configErr) {
            this.kernelTime = -1;
        }
        // 再点燃寻路导航系统
        this.pathPlanning.precompute(precomputeData);
        return this;
    }

    @Override
    public ExtAction resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        // 限制二次唤醒
        boolean isAlreadyResumed = (this.getCountResume() >= 2);
        if (isAlreadyResumed) {
            return this;
        }

        // 与 precompute 同理，打乱执行次序
        try {
            int systemTime = this.scenarioInfo.getKernelTimesteps();
            this.kernelTime = systemTime;
        } catch (NoSuchConfigOptionException e) {
            this.kernelTime = -1;
        }
        this.pathPlanning.resume(precomputeData);
        return this;
    }

    @Override
    public ExtAction preparate() {
        super.preparate();
        // 校验执行权限
        boolean canPreparate = (this.getCountPreparate() < 2);
        if (!canPreparate) {
            return this;
        }

        this.pathPlanning.preparate();
        try {
            // 获取内核寿命预测
            this.kernelTime = this.scenarioInfo.getKernelTimesteps();
        } catch (NoSuchConfigOptionException err) {
            this.kernelTime = -1;
        }
        return this;
    }

    @Override
    public ExtAction updateInfo(MessageManager mm) {
        super.updateInfo(mm);
        // 拦截冗余更新封包
        boolean isDataStale = (this.getCountUpdateInfo() >= 2);
        if (isDataStale) {
            return this;
        }

        // 并列语句洗牌
        this.pathPlanning.updateInfo(mm);
        this.messageManager = mm;

        // 【关键】执行全城野战医院的床位巡查，刷新医院黑名单
        this.updateRefugeStatus();

        // 解码并接收高层中心发来的无线电波
        this.receiveMessage(mm);
        return this;
    }

    @Override
    public ExtAction setTarget(EntityID targetId) {
        // 重置火控与救援雷达
        this.target = null;

        // 防空指针护盾
        boolean hasValidInput = (targetId != null);
        if (hasValidInput) {
            // 实体化目标
            StandardEntity candidateEntity = this.worldInfo.getEntity(targetId);

            // 目标类型过滤器：只接受伤员实体或特定灾害网格
            boolean isVictim = (candidateEntity instanceof Human);
            boolean isDisasterArea = (candidateEntity instanceof Area);

            if (isVictim || isDisasterArea) {
                // 锁定确认
                this.target = targetId;
                return this;
            }
        }
        return this;
    }

    // ========================================================================
    // [三级医疗分诊瀑布流 (The Triage Waterfall)]
    // ========================================================================

    @Override
    public ExtAction calc() {
        // 每次决策推演前清空动作输出槽
        this.result = null;

        // 获取机组乘员数据
        AmbulanceTeam myCrew = (AmbulanceTeam) this.agentInfo.me();
        // 扫描后车厢内是否已经装载了血肉模糊的受害者
        Human patientOnBoard = this.agentInfo.someoneOnBoard();

        // --------------------------------------------------------------------
        // 第一优先级序列：车内已有伤员，立刻启动狂飙卸载模式
        // --------------------------------------------------------------------
        boolean isLoadedWithPatient = (patientOnBoard != null);
        if (isLoadedWithPatient) {
            // 下达向医院突围并移交病患的指令
            Action unloadManeuver = this.calcUnload(myCrew, this.pathPlanning, patientOnBoard, this.target);
            this.result = unloadManeuver;

            // 只要卸载序列产生动作，立刻熔断后续思考
            boolean isManeuverValid = (this.result != null);
            if (isManeuverValid) {
                return this;
            }
        }

        // --------------------------------------------------------------------
        // 第二优先级序列：评估医疗兵自身的生物反馈，若濒死则撤退
        // --------------------------------------------------------------------
        boolean isCrewCritical = this.needRest(myCrew);
        if (isCrewCritical) {
            // 将锁定的目标尝试降维解析为一个安全区域
            EntityID mappedAreaID = this.convertArea(this.target);

            ArrayList<EntityID> retreatTargets = new ArrayList<>();
            boolean isAreaValid = (mappedAreaID != null);
            if (isAreaValid) {
                retreatTargets.add(mappedAreaID);
            }

            // 规划回撤至安全屋的防守路线，isUnload 置为 false
            Action retreatAction = this.calcRefugeAction(myCrew, this.pathPlanning, retreatTargets, false);
            this.result = retreatAction;

            boolean isRetreatValid = (this.result != null);
            if (isRetreatValid) {
                return this;
            }
        }

        // --------------------------------------------------------------------
        // 第三优先级序列：常态化废墟搜救打捞
        // --------------------------------------------------------------------
        boolean hasRescueTarget = (this.target != null);
        if (hasRescueTarget) {
            // 规划跨越废墟、捞取受害者的战术路线
            Action rescueOperation = this.calcRescue(myCrew, this.pathPlanning, this.target);
            this.result = rescueOperation;
        }

        return this;
    }

    @Override
    public Action getAction() {
        // 暴露动作接口给引擎
        return this.result;
    }

    // ========================================================================
    // [全城医院床位压力监控系统]
    // 逻辑极度密集区：将长串 if 拆成原子布尔判断，大幅改变代码观感
    // ========================================================================

    private void updateRefugeStatus() {
        Set<Refuge> overloadedHospitals = new HashSet<>();

        // 提取本帧发生变异的实体快照池
        Collection<EntityID> changedEntityIds = this.worldInfo.getChanged().getChangedEntities();

        // 第一波扫描：寻找哪些医院已经宣告床位爆满
        for (EntityID currentId : changedEntityIds) {
            StandardEntity currentEnt = this.worldInfo.getEntity(currentId);
            boolean isHospital = (currentEnt instanceof Refuge);

            if (isHospital) {
                Refuge hospitalNode = (Refuge) currentEnt;

                // 拆解原版的长 && 判断
                boolean hasBedData = hospitalNode.isOccupiedBedsDefined();
                boolean hasCapData = hospitalNode.isBedCapacityDefined();

                if (hasBedData && hasCapData) {
                    int takenBeds = hospitalNode.getOccupiedBeds();
                    int maxBeds = hospitalNode.getBedCapacity();

                    // 负载测试：被占床位 >= 最大容量
                    boolean isFull = (takenBeds >= maxBeds);
                    if (isFull) {
                        overloadedHospitals.add(hospitalNode);
                    }
                }
            }
        }

        // 第二波扫描：对爆满的医院进行等候区伤情评估，计算解封倒计时
        boolean hasOverloadedHospitals = !overloadedHospitals.isEmpty();
        if (hasOverloadedHospitals) {

            for (Refuge fullHospital : overloadedHospitals) {
                Set<Civilian> waitingPatients = new HashSet<>();

                // 再次遍历变异池，寻找在医院排队的平民
                for (EntityID scanId : changedEntityIds) {
                    StandardEntity scanEnt = this.worldInfo.getEntity(scanId);
                    boolean isCivilian = (scanEnt instanceof Civilian);

                    if (isCivilian) {
                        Civilian civ = (Civilian) scanEnt;

                        // 提取生命体征标识
                        boolean hasDmgInfo = civ.isDamageDefined();
                        boolean hasHpInfo = civ.isHPDefined();

                        if (hasDmgInfo && hasHpInfo) {
                            boolean isBleeding = (0 < civ.getDamage());
                            boolean isAlive = (0 < civ.getHP());

                            if (isBleeding && isAlive) {
                                // 确认该平民正身处这家爆满的医院内
                                boolean isAtThisHospital = civ.getPosition().equals(fullHospital.getID());
                                if (isAtThisHospital) {
                                    waitingPatients.add(civ);
                                }
                            }
                        }
                    }
                }

                int patientsCount = waitingPatients.size();
                EntityID hospId = fullHospital.getID();

                // 场景 A：没人排队，且这家医院目前不在黑名单里 -> 给个初始观察期100帧
                boolean noPatients = (patientsCount <= 0);
                boolean notInBlacklistYet = !this.notGoRefuges.containsKey(hospId);

                if (noPatients && notInBlacklistYet) {
                    this.notGoRefuges.put(hospId, 100);
                    continue;
                }
                // 场景 B：大批平民排队，基于他们的伤情预测病床流转速度
                else if (patientsCount > 0) {
                    ArrayList<Integer> traumaList = new ArrayList<>();
                    for (Civilian c : waitingPatients) {
                        traumaList.add(c.getDamage());
                    }
                    // 按失血速度排序
                    Collections.sort(traumaList);

                    int expectedWaitTime = 0;
                    int baseCapacity = fullHospital.getBedCapacity();
                    int waitingQueue = fullHospital.getWaitingListSize();

                    // 测算要处理的伤员总配额
                    int targetProcessNum = baseCapacity + waitingQueue;
                    boolean isListShorter = (traumaList.size() < targetProcessNum);
                    if (isListShorter) {
                        targetProcessNum = traumaList.size();
                    }

                    // 累加高危病患的生命周期作为预期释放时间
                    for (int idx = 0; idx < targetProcessNum; idx++) {
                        expectedWaitTime += traumaList.get(idx);
                    }

                    // 更新黑名单账本
                    boolean isFirstTime = (this.notGoRefuges.get(hospId) == null);
                    if (isFirstTime) {
                        this.notGoRefuges.put(hospId, expectedWaitTime);
                    }

                    Integer currentRecord = this.notGoRefuges.get(hospId);
                    boolean isShorterWait = (expectedWaitTime < currentRecord);
                    boolean isInfinite = (currentRecord == -1);

                    if (isShorterWait || isInfinite) {
                        this.notGoRefuges.put(hospId, expectedWaitTime);
                    }
                }
            }
        }

        // 第三波：时间流逝，黑名单倒计时削减
        Map<EntityID, Integer> blacklistView = this.notGoRefuges;
        Set<EntityID> freedUpHospitals = new HashSet<>();

        for (Map.Entry<EntityID, Integer> record : blacklistView.entrySet()) {
            EntityID recId = record.getKey();
            int decreasedTime = record.getValue() - 1;

            // 写入缩减后的时间
            this.notGoRefuges.put(recId, decreasedTime);

            // 若倒计时归零，标记为刑满释放
            boolean isTimeUp = (decreasedTime <= 0);
            if (isTimeUp) {
                freedUpHospitals.add(recId);
            }
        }

        // 只有当车上空载时，才真正把解禁的医院从黑名单抹除
        boolean isAmbulanceEmpty = (this.agentInfo.someoneOnBoard() == null);
        if (isAmbulanceEmpty) {
            for (EntityID freeId : freedUpHospitals) {
                this.notGoRefuges.remove(freeId);
            }
        }
    }

    // ========================================================================
    // [第一顺位执行器：打捞序列 (Rescue Pipeline)]
    // ========================================================================

    private Action calcRescue(AmbulanceTeam agent, PathPlanning planner, EntityID targetId) {
        StandardEntity targetObj = this.worldInfo.getEntity(targetId);
        boolean isTargetInvalid = (targetObj == null);
        if (isTargetInvalid) {
            return null;
        }

        EntityID myPositionId = agent.getPosition();

        // 分支 A：目标是人类伤员
        boolean isHumanTarget = (targetObj instanceof Human);
        if (isHumanTarget) {
            Human victim = (Human) targetObj;

            // 尸体或幽灵探测保护
            boolean isPosUnknown = !victim.isPositionDefined();
            if (isPosUnknown) return null;

            boolean isHpDefined = victim.isHPDefined();
            boolean isDead = (victim.getHP() == 0);
            if (isHpDefined && isDead) return null;

            EntityID victimPosId = victim.getPosition();

            // 空间重合验证：已经开到伤员面前
            boolean hasArrived = (myPositionId.getValue() == victimPosId.getValue());
            if (hasArrived) {
                boolean isBuried = victim.isBuriednessDefined();
                boolean hasDebrisWeight = (victim.getBuriedness() > 0);

                // 救护车没带铲子，如果人被埋着只能干瞪眼返回 null
                if (isBuried && hasDebrisWeight) {
                    return null;
                }
                // 平民身份核对，捞人上车前先向总台预定床位
                else if (victim.getStandardURN() == CIVILIAN) {
                    Collection<EntityID> bookedRefuge = this.getAvailableRefuge();
                    this.sendMessage(this.messageManager, bookedRefuge);

                    return new ActionLoad(victim.getID()); // 执行装载
                }
            } else {
                // 空间未重合，呼叫寻路规划靠近
                List<EntityID> approachRoute = planner.getResult(myPositionId, victimPosId);

                boolean hasRoute = (approachRoute != null);
                boolean isRouteNotEmpty = false;
                if (hasRoute) {
                    isRouteNotEmpty = (approachRoute.size() > 0);
                }

                if (hasRoute && isRouteNotEmpty) {
                    return new ActionMove(approachRoute);
                }
            }
            return null;
        }

        // 分支 B：目标是被掩埋的路障，提取其底座坐标
        boolean isBlockadeTarget = (targetObj.getStandardURN() == BLOCKADE);
        if (isBlockadeTarget) {
            Blockade debris = (Blockade) targetObj;
            if (debris.isPositionDefined()) {
                targetObj = this.worldInfo.getEntity(debris.getPosition());
            }
        }

        // 分支 C：目标是地理灾害网格
        boolean isAreaTarget = (targetObj instanceof Area);
        if (isAreaTarget) {
            List<EntityID> patrolRoute = planner.getResult(myPositionId, targetObj.getID());

            boolean hasRoute = (patrolRoute != null);
            boolean isNotEmpty = false;
            if (hasRoute) {
                isNotEmpty = (patrolRoute.size() > 0);
            }

            if (hasRoute && isNotEmpty) {
                return new ActionMove(patrolRoute);
            }
        }
        return null;
    }

    // ========================================================================
    // [特级顺位执行器：生命移交与卸载序列 (Unload Pipeline)]
    // ========================================================================

    private Action calcUnload(AmbulanceTeam agent, PathPlanning planner, Human patient, EntityID targetId) {
        if (patient == null) return null;

        // 残酷法则：如果在送医途中伤员阵亡，直接当场遗弃卸载
        boolean isHpKnown = patient.isHPDefined();
        boolean isPatientDead = (patient.getHP() == 0);
        if (isHpKnown && isPatientDead) {
            return new ActionUnload();
        }

        EntityID myPosition = agent.getPosition();
        boolean noTargetGiven = (targetId == null);

        // 第一情境：无特定目标，或目标就是自己身上这个伤员（意为找个医院送过去）
        boolean targetIsPatient = false;
        if (!noTargetGiven) {
            targetIsPatient = (patient.getID().getValue() == targetId.getValue());
        }

        if (noTargetGiven || targetIsPatient) {
            StandardEntity groundBase = this.worldInfo.getEntity(myPosition);
            boolean isGroundValid = (groundBase != null);
            boolean amIAtRefuge = false;

            if (isGroundValid) {
                amIAtRefuge = (groundBase.getStandardURN() == REFUGE);
            }

            // 如果脚下已经是医院大门，一脚踹下车
            if (amIAtRefuge) {
                return new ActionUnload();
            } else {
                // 否则设置导航前往评估出的最佳医院
                planner.setFrom(myPosition);
                planner.setDestination(this.getAvailableRefuge());
                List<EntityID> hospitalRoute = planner.calc().getResult();

                boolean hasRoute = (hospitalRoute != null);
                boolean notEmpty = false;
                if (hasRoute) {
                    notEmpty = (hospitalRoute.size() > 0);
                }

                if (hasRoute && notEmpty) {
                    return new ActionMove(hospitalRoute);
                }
            }
        }

        // 异常拦截
        if (noTargetGiven) return null;

        // 第二情境：前往特定灾区或区域卸载
        StandardEntity specificTarget = this.worldInfo.getEntity(targetId);
        boolean isSpecificBlockade = false;
        if (specificTarget != null) {
            isSpecificBlockade = (specificTarget.getStandardURN() == BLOCKADE);
        }

        if (isSpecificBlockade) {
            Blockade blockEnt = (Blockade) specificTarget;
            if (blockEnt.isPositionDefined()) {
                specificTarget = this.worldInfo.getEntity(blockEnt.getPosition());
            }
        }

        boolean isTargetArea = (specificTarget instanceof Area);
        if (isTargetArea) {
            // 抵达特定灾区地带
            boolean arrivedAtArea = (myPosition.getValue() == targetId.getValue());
            if (arrivedAtArea) {
                return new ActionUnload();
            } else {
                // 绕道去医院
                planner.setFrom(myPosition);
                planner.setDestination(this.getAvailableRefuge());
                List<EntityID> detourRoute = planner.calc().getResult();

                if (detourRoute != null && detourRoute.size() > 0) {
                    return new ActionMove(detourRoute);
                }
            }
        }
        // 第三情境：目标是跟另一个人碰头
        else if (specificTarget instanceof Human) {
            Human targetPerson = (Human) specificTarget;
            if (targetPerson.isPositionDefined()) {
                // 转交给安全行动生成器，最后标记为执行卸货（isUnload = true）
                Collection<EntityID> personZone = Lists.newArrayList(targetPerson.getPosition());
                return this.calcRefugeAction(agent, planner, personZone, true);
            }
            // 碰头失败，转去医院
            planner.setFrom(myPosition);
            planner.setDestination(this.getAvailableRefuge());
            List<EntityID> finalRoute = planner.calc().getResult();
            if (finalRoute != null && finalRoute.size() > 0) {
                return new ActionMove(finalRoute);
            }
        }
        return null;
    }

    // ========================================================================
    // [宏观战略效用分析器] 寻找性价比最高的医院
    // 颠覆了原有的 Stream 写法，改用基础 For 循环展现异构化
    // ========================================================================

    private Collection<EntityID> getAvailableRefuge() {
        // 如果总部已经强制指派了专科医院，不容置疑
        boolean hasOptimalCommand = (this.optimalRefugeID != null);
        if (hasOptimalCommand) {
            List<EntityID> singleList = List.of(this.optimalRefugeID);
            return new ArrayList<>(singleList);
        }

        // 取出全城医院花名册
        Collection<EntityID> candidateHospitals = this.worldInfo.getEntityIDsOfType(REFUGE);

        // 剔除处于黑名单中的爆满医院
        Set<EntityID> blacklistKeys = this.notGoRefuges.keySet();
        candidateHospitals.removeAll(blacklistKeys);

        // 如果黑名单把所有医院都ban了，为了救命只能无视黑名单强闯
        boolean isEveryHospitalFull = candidateHospitals.isEmpty();
        if (isEveryHospitalFull) {
            candidateHospitals = this.worldInfo.getEntityIDsOfType(REFUGE);
        }

        Map<EntityID, Double> tacticalCostMap = new HashMap<>();

        // [重构] 用基础循环取代原有的 sum 聚合流
        int totalCityBeds = 0;
        for (EntityID hospId : candidateHospitals) {
            Refuge refEntity = (Refuge) this.worldInfo.getEntity(hospId);
            totalCityBeds += refEntity.getBedCapacity();
        }

        // 打擂台算力推演：效用公式解析
        for (EntityID currentHospId : candidateHospitals) {
            Refuge hospObj = (Refuge) this.worldInfo.getEntity(currentHospId);
            int localBedCap = hospObj.getBedCapacity();

            // 拆解复合公式
            double absoluteDistance = this.worldInfo.getDistance(this.agentInfo.getID(), currentHospId);
            double normalizedTravel = absoluteDistance / AGENT_CAN_MOVE;

            // 距离红利：越近分越高
            double distanceBonus = 1.0 / normalizedTravel;

            // 床位占比红利：大医院容错率高
            double bedShareRatio = (double) localBedCap / (double) totalCityBeds;
            double bedBonusPerMeter = bedShareRatio / absoluteDistance;

            // 最终综合效用分数
            double calculatedUtility = distanceBonus + bedBonusPerMeter;
            tacticalCostMap.put(currentHospId, calculatedUtility);
        }

        // [重构] 用基础擂台循环取代原版的 max Stream 解析
        Double maxScore = Double.MIN_VALUE;
        EntityID bestHospitalId = null;

        for (Map.Entry<EntityID, Double> entry : tacticalCostMap.entrySet()) {
            double loopScore = entry.getValue();
            if (loopScore > maxScore) {
                maxScore = loopScore;
                bestHospitalId = entry.getKey();
            }
        }

        // 打包送出决胜者
        boolean foundWinner = (bestHospitalId != null);
        if (foundWinner) {
            List<EntityID> winnerList = List.of(bestHospitalId);
            return new ArrayList<>(winnerList);
        } else {
            // 保底返回所有
            return this.worldInfo.getEntityIDsOfType(REFUGE);
        }
    }

    // ========================================================================
    // [底座维生监测器]
    // ========================================================================

    private boolean needRest(Human agent) {
        int stamina = agent.getHP();
        int bleedingRate = agent.getDamage();

        // 无伤或金刚不坏
        boolean isSafe = (stamina == 0 || bleedingRate == 0);
        if (isSafe) {
            return false;
        }

        // 拆解进位算法
        int baseSurvivalChronicle = stamina / bleedingRate;
        int bleedRemainder = stamina % bleedingRate;
        boolean hasRemainder = (bleedRemainder != 0);

        // 预期寿命滴答数
        int expectedSurvivalTicks = hasRemainder ? (baseSurvivalChronicle + 1) : baseSurvivalChronicle;

        // 拦截时间异常
        boolean isTimeLost = (this.kernelTime == -1);
        if (isTimeLost) {
            try {
                this.kernelTime = this.scenarioInfo.getKernelTimesteps();
            } catch (NoSuchConfigOptionException e) {
                this.kernelTime = -1;
            }
        }

        // 阈值研判：出血过快 或 熬不到大结局
        boolean isBleedingCritical = (bleedingRate >= this.thresholdRest);

        int globalTime = this.agentInfo.getTime();
        boolean isDoomed = ((expectedSurvivalTicks + globalTime) < this.kernelTime);

        return isBleedingCritical || isDoomed;
    }

    // 坐标清洗与拓扑映射
    private EntityID convertArea(EntityID targetID) {
        StandardEntity unknownEntity = this.worldInfo.getEntity(targetID);
        if (unknownEntity == null) return null;

        boolean isHumanType = (unknownEntity instanceof Human);
        if (isHumanType) {
            Human guy = (Human) unknownEntity;
            if (guy.isPositionDefined()) {
                EntityID guyPos = guy.getPosition();
                StandardEntity floorZone = this.worldInfo.getEntity(guyPos);

                boolean isFloorArea = (floorZone instanceof Area);
                if (isFloorArea) return guyPos;
            }
        }

        boolean isAreaType = (unknownEntity instanceof Area);
        if (isAreaType) {
            return targetID;
        }

        boolean isBlockadeType = (unknownEntity.getStandardURN() == BLOCKADE);
        if (isBlockadeType) {
            Blockade rubble = (Blockade) unknownEntity;
            if (rubble.isPositionDefined()) {
                return rubble.getPosition();
            }
        }

        return null;
    }

    // [战地机动] 策划前往医院的防御性驾驶路线
    private Action calcRefugeAction(Human humanBody, PathPlanning planner, Collection<EntityID> ultimateTargets, boolean isUnloadingPhase) {
        EntityID currentGeoPos = humanBody.getPosition();

        Collection<EntityID> allRefuges = this.worldInfo.getEntityIDsOfType(StandardEntityURN.REFUGE);
        int refugePoolSize = allRefuges.size();

        // 检查自己是否已经开进了某个防空洞
        boolean amIInsideBunker = allRefuges.contains(currentGeoPos);
        if (amIInsideBunker) {
            // 根据战术意图抉择：是来卸客的还是来养伤的
            if (isUnloadingPhase) {
                return new ActionUnload();
            } else {
                return new ActionRest();
            }
        }

        List<EntityID> backupFallbackRoute = null;

        // 跌代搜索：在周边所有医院中寻找有连通路径的网格
        boolean hasRefugeToTest = (allRefuges.size() > 0);
        while (hasRefugeToTest) {
            planner.setFrom(currentGeoPos);
            planner.setDestination(allRefuges);
            List<EntityID> routeToHospital = planner.calc().getResult();

            boolean routeIsGood = (routeToHospital != null);
            boolean routeNotEmpty = false;
            if (routeIsGood) {
                routeNotEmpty = (routeToHospital.size() > 0);
            }

            if (routeIsGood && routeNotEmpty) {
                // 如果这是第一条找出来的路，把它存为备份
                boolean isFirstFound = (backupFallbackRoute == null);
                if (isFirstFound) {
                    backupFallbackRoute = new ArrayList<>(routeToHospital);

                    // 如果后续没有额外的二次目标点，直接打断搜索
                    boolean noFurtherTargets = (ultimateTargets == null || ultimateTargets.isEmpty());
                    if (noFurtherTargets) {
                        break;
                    }
                }

                // 取出这趟旅程终点的那个医院 ID
                int lastStepIndex = routeToHospital.size() - 1;
                EntityID specificHospitalId = routeToHospital.get(lastStepIndex);

                // 继续探路：这家医院到后续目标的桥梁是否贯通
                planner.setFrom(specificHospitalId);
                planner.setDestination(ultimateTargets);
                List<EntityID> routeFromHospitalToTarget = planner.calc().getResult();

                boolean bridgeGood = (routeFromHospitalToTarget != null);
                boolean bridgeNotEmpty = false;
                if (bridgeGood) {
                    bridgeNotEmpty = (routeFromHospitalToTarget.size() > 0);
                }

                // 完全贯通，可以直接采用了
                if (bridgeGood && bridgeNotEmpty) {
                    return new ActionMove(routeToHospital);
                }

                // 这家医院是个死胡同，剔除它并更新池子
                allRefuges.remove(specificHospitalId);

                // 防死锁断言
                boolean sizeDidNotChange = (refugePoolSize == allRefuges.size());
                if (sizeDidNotChange) {
                    break;
                }
                refugePoolSize = allRefuges.size();

            } else {
                // 彻底被废墟孤立，四周都没有路
                break;
            }
            // 更新循环条件
            hasRefugeToTest = (allRefuges.size() > 0);
        }

        // 如果实在找不到完美路线，就把备用路线交上去
        boolean hasBackup = (backupFallbackRoute != null);
        if (hasBackup) {
            return new ActionMove(backupFallbackRoute);
        } else {
            return null;
        }
    }

    // ========================================================================
    // [通讯阵列解析器]
    // ========================================================================

    private void receiveMessage(MessageManager mm) {
        // 如果车厢内空无一人，抹除总台发来的强制派单记录
        boolean isCabinEmpty = (this.agentInfo.someoneOnBoard() == null);
        if (isCabinEmpty) {
            this.optimalRefugeID = null;
        }

        // 从电台信道抓取本阵营专用波段包
        final List<CommunicationMessage> triageMessages = mm.getReceivedMessageList(MessageAmbulanceTeam.class);

        for (CommunicationMessage msgPacket : triageMessages) {
            MessageAmbulanceTeam medevacMsg = (MessageAmbulanceTeam) msgPacket;

            // 验证发件人是否具备指挥官权限 (AmbulanceCentre)
            EntityID senderRank = medevacMsg.getSenderID();
            StandardEntity senderEnt = this.worldInfo.getEntity(senderRank);
            boolean fromHQ = (senderEnt instanceof AmbulanceCentre);

            if (fromHQ) {
                // 验证这份密电是否是指名道姓发给本车的
                EntityID assignedAgent = medevacMsg.getAgentID();
                boolean isForMe = assignedAgent.equals(this.agentInfo.getID());

                if (isForMe) {
                    // 解译电报指令代码
                    int commandAction = medevacMsg.getAction();
                    boolean isForceRefugeOrder = (commandAction == OPT_REFUGE);

                    if (isForceRefugeOrder) {
                        // 核实验明强送的坐标是不是一家合法的医院
                        EntityID orderTarget = medevacMsg.getTargetID();
                        Collection<EntityID> validHospitals = this.worldInfo.getEntityIDsOfType(REFUGE);
                        boolean isValidHospital = validHospitals.contains(orderTarget);

                        if (isValidHospital) {
                            // 覆写本机导航锁定
                            this.optimalRefugeID = orderTarget;
                        }
                    }
                }
            }
        }
    }

    // [反向通讯] 捞人前向总台通报预定的医院床位
    private void sendMessage(MessageManager mm, Collection<EntityID> hospitalIds) {
        // 查房：地图上是否存在尚可联络的救护指挥中心
        Collection<StandardEntity> activeCenters = this.worldInfo.getEntitiesOfType(StandardEntityURN.AMBULANCE_CENTRE);
        boolean hasActiveCenters = (activeCenters.size() > 0);

        if (hasActiveCenters) {
            EntityID selectedHospId = null;
            // 提取首个医院标识
            for (EntityID idScan : hospitalIds) {
                selectedHospId = idScan;
            }

            // 组装并投递占位电报
            EntityID myRankId = this.agentInfo.getID();
            AmbulanceTeam myVehicle = (AmbulanceTeam) this.worldInfo.getEntity(myRankId);

            MessageAmbulanceTeam reservationPkt = new MessageAmbulanceTeam(true, myVehicle, OPT_REFUGE, selectedHospId);
            mm.addMessage(reservationPkt);
        }
    }
}
