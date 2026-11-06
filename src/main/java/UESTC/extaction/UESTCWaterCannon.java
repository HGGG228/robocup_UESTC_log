package UESTC.extaction;

import static rescuecore2.standard.entities.StandardEntityURN.FIRE_BRIGADE;
import static rescuecore2.standard.entities.StandardEntityURN.HYDRANT;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;

import java.util.*;

import adf.core.agent.action.Action;
import adf.core.agent.action.common.*;
import adf.core.agent.action.fire.*;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.information.MessageBuilding;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.*;
import rescuecore2.misc.Pair;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

/**
 * 消防车灾害压制与生存维系扩展动作模块。
 * 本模块负责处理重型载具的物理碰撞卡死、水源补充以及建筑火灾的远程扑救。
 */
public class UESTCWaterCannon extends ExtAction implements WaterCannonBroadcaster {

    // -------------------------------------------------------------------------
    // 基础环境探针与战术参数
    // -------------------------------------------------------------------------
    // 用于解析受困群众特征以反推废墟阻塞的模块
    private final Clustering stuckHumans;
    // 用于在灾区废墟中寻找最短拓扑路径的导航规划器
    private final PathPlanning pathPlaner;

    // 系统认定的极度缺水下限阈值
    private final int THOLD_LACKOF_WATER;
    // 避免频繁调度，限制发送请求指挥部消息的冷却时间
    private static final int AVOID_TIME_CMDP = 2;
    // 判定车体受重创必须强制休息的损伤阈值
    private static final int THOLD_SHOULD_REST = 100;

    // 当前锁定的目标建筑区域ID
    private EntityID target = null;
    // 用于记录上一个物理帧车体所处的二维坐标系点位
    private Point2D lastPoint = null;

    // 系统认定的水源充足上限（通常为总槽位容量的90%）
    private final int THOLD_ENOUGH_WATER;
    // 忍耐原地卡死的最大物理帧数
    private static final int THOLD_STUCK_TIME = 2;

    // 连续陷入泥沼或废墟无法移动的时钟计数器
    private int countStuck = 0;
    // 灾难模拟系统预计强制结束的最后时钟刻度
    private int endTime = -1;

    // 随机生成的用于尝试突破卡死状态的移动步频参数
    private final int NUM_TRY_MOVE;
    // 判定两次坐标是否处于同一个空间死锁范围内的像素距离阈值
    private static final int THOLD_STUCK_RANGE = 1800;

    // 上一次向指挥部成功发报的时钟帧刻度
    private int sentTimeCmdP = -1;
    // 车体被物理引擎卡住时那一瞬间打下的坐标快照
    private Point2D stuckPoint = null;

    // -------------------------------------------------------------------------
    // 构造器：参数初始化与依赖注入
    // -------------------------------------------------------------------------
    public UESTCWaterCannon(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager mm, DevelopData dd) {
        super(ai, wi, si, mm, dd);

        // 提取仿真环境中的喷水枪单次最大消耗量，作为极度缺水警戒线
        int maxExtinguishSum = si.getFireExtinguishMaxSum();
        this.THOLD_LACKOF_WATER = maxExtinguishSum;

        // 调换了模块初始化的先后顺序（独立语句换序，不影响逻辑）
        this.stuckHumans = moduleManager.getModule("UESTC.FB.ExtActionFireFighting.StuckHumans");
        this.pathPlaner = moduleManager.getModule("UESTC.FB.ExtActionFireFighting.PathPlanning");

        // 根据本机的唯一数字ID生成伪随机对象
        long seedValue = ai.getID().getValue();
        Random r = new Random(seedValue);

        // 提取消防车水槽物理上限，并计算出90%的充裕值
        double maxTankVol = si.getFireTankMaximum();
        this.THOLD_ENOUGH_WATER = (int) (maxTankVol * 0.9);

        // 设定随机的防卡死步数，范围在2到4之间
        this.NUM_TRY_MOVE = r.nextInt(3) + 2;
    }

    // -------------------------------------------------------------------------
    // 核心决策大脑 (Calc) 与 动作管线
    // -------------------------------------------------------------------------

    @Override
    public ExtAction calc() {
        // 每回合初始化动作输出流为 null
        this.result = null;

        // 提取判断条件：如果没有下达攻击目标
        boolean hasNoTarget = (this.target == null);

        // 目标丢失或未指派时，放弃运算直接返回挂起状态
        if (hasNoTarget) {
            return this;
        }

        // 【优先级1】：优先演算后勤恢复动作（回血或抽水）
        Action recoveryAction = this.calcRecover();
        // 将后勤动作暂存入执行槽
        this.result = recoveryAction;

        // 【优先级2】：演算前线灭火交战动作
        Action combatAction = this.calcExtinguish();
        // 如果 calcExtinguish 内部检测到 this.result 已经有值，它会直接返回旧值。
        // 这是严格遵守你原代码的逻辑和优先级的核心机制。
        this.result = combatAction;

        // 输出最终裁定的行为策略
        return this;
    }

    private Action calcExtinguish() {
        // 保留原版优先级拦截逻辑：如果后勤管线已经指派了任务，立即放弃战斗演算
        boolean isBusyWithRecovery = (this.result != null);
        if (isBusyWithRecovery) {
            return this.result;
        }

        // 提取仿真内核给定的最大水炮射程
        int maxFireRange = this.scenarioInfo.getFireExtinguishMaxDistance();
        // 提取水炮单次喷射的液压功率设定值
        int extinguishPower = this.scenarioInfo.getFireExtinguishMaxSum();

        // 获取自身当前所处位置坐标ID
        EntityID myPositionID = this.agentInfo.getPosition();
        // 使用欧式几何计算自身与起火目标的直线物理距离
        double distanceToTarget = this.worldInfo.getDistance(myPositionID, this.target);

        // 将原版的长串条件判断拆解，提升阅读清晰度并改变视觉结构
        boolean isStuckLongEnough = (this.countStuck >= THOLD_STUCK_TIME);
        boolean isInRange = (distanceToTarget < maxFireRange);
        boolean isBugStucked = this.inBugstucks();
        boolean isTimeToTryFire = (this.countStuck % NUM_TRY_MOVE != 0);

        // 绝境开火判定：如果严重卡死，且刚好目标在射程内，放弃挣扎原地喷水
        if (isStuckLongEnough) {
            if (isInRange) {
                if (isBugStucked) {
                    if (isTimeToTryFire) {
                        return new ActionExtinguish(this.target, extinguishPower);
                    }
                }
            }
        }

        // 提取视野内发生变动的实体集合
        Collection<EntityID> changedEntities = this.worldInfo.getChanged().getChangedEntities();
        // 判断目标是否在当前视野的实时观测范围内
        boolean isTargetVisible = changedEntities.contains(this.target);

        // 正常开火判定：目视确认目标且处于水炮打击半径内
        if (isTargetVisible) {
            if (isInRange) {
                return new ActionExtinguish(this.target, extinguishPower);
            }
        }

        // 设定寻路起点与终点，召唤寻路引擎计算行驶轨迹
        this.pathPlaner.setFrom(myPositionID);
        this.pathPlaner.setDestination(this.target);
        List<EntityID> drivePath = this.pathPlaner.calc().getResult();

        // 校验生成的路径是否有效且至少包含两段节点
        boolean isPathInvalid = (drivePath == null || drivePath.size() < 2);
        if (isPathInvalid) {
            return null;
        }

        // 提取路径规划序列中的最后一步节点作为最终目的地
        int lastIndex = drivePath.size() - 1;
        EntityID finalNodeID = drivePath.get(lastIndex);
        StandardEntity finalDestination = this.worldInfo.getEntity(finalNodeID);

        // 剥离建筑终点逻辑：如果终点是一栋建筑物
        if (finalDestination instanceof Building) {
            // 且该建筑物并不是官方设立的避难所
            boolean isNotRefuge = !(finalDestination instanceof Refuge);
            if (isNotRefuge) {
                // 严禁消防车开入普通建筑，移除路径最后一步
                drivePath.remove(drivePath.size() - 1);
            }
        }

        // 为保证安全交战距离，如果剔除后路径依然可前进
        if (drivePath.size() > 1) {
            // 继续剔除倒数第二步，形成火场外的缓冲驻扎带
            drivePath.remove(drivePath.size() - 1);
        }

        // 封装为位移动作返回
        return new ActionMove(drivePath);
    }

    private Action calcRecover() {
        // 获取维生状态评估标志位
        boolean needsHealing = this.needRest();
        boolean needsWater = this.needRefill();

        // 如果状态健康且水源充足，结束后勤管线
        if (!needsHealing) {
            if (!needsWater) {
                return null;
            }
        }

        // 获取车体当前压着的坐标网格实体
        EntityID currentPosID = this.agentInfo.getPosition();
        StandardEntity groundEntity = this.worldInfo.getEntity(currentPosID);

        // 场景分析：如果车子恰好停泊在避难所内
        if (groundEntity instanceof Refuge) {
            if (needsHealing) {
                // 优先抢救载具生命线
                return new ActionRest();
            } else {
                // 其次接驳避难所的水源灌注
                return new ActionRefill();
            }
        }

        // 场景分析：如果急需加水，且正好压在消防栓上
        if (needsWater) {
            if (groundEntity instanceof Hydrant) {
                // 检查该消防栓是否被其他友军霸占
                boolean hydrantIsFree = !this.isOccupied(groundEntity);
                if (hydrantIsFree) {
                    return new ActionRefill();
                }
            }
        }

        // 全局搜索代价最低的避难所网格
        EntityID optimalDest = this.findBestDestOf(REFUGE);

        // 若找不到避难所但依然面临水荒
        if (needsWater) {
            if (optimalDest == null) {
                // 降级搜索全图代价最低的露天消防栓
                optimalDest = this.findBestDestOf(HYDRANT);
            }
        }

        // 彻底绝望，全图无点可去
        if (optimalDest == null) {
            return null;
        }

        // 调用寻路网格奔赴最近的后勤补给点
        this.pathPlaner.setFrom(groundEntity.getID());
        this.pathPlaner.setDestination(optimalDest);
        List<EntityID> supplyRoute = this.pathPlaner.calc().getResult();

        // 验证路线并发布位移指令
        boolean hasValidRoute = !supplyRoute.isEmpty();
        if (hasValidRoute) {
            return new ActionMove(supplyRoute);
        } else {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // 状态判定评估区 (Rest / Refill / Occupied / BugStuck)
    // -------------------------------------------------------------------------

    private boolean needRest() {
        // 提取载具的人形基类属性
        Human selfEntity = (Human) this.agentInfo.me();

        // 读取当前残存血量与每回合受损速率
        int currentHp = selfEntity.getHP();
        int currentDamage = selfEntity.getDamage();

        // 如果处于无敌状态或健康未流血
        if (currentHp == 0 || currentDamage == 0) {
            return false;
        }

        // 将原版算式拆分为变量：计算完整除法周期
        int fullCycles = currentHp / currentDamage;
        // 提取余数以研判是否需要进位
        int remainder = currentHp % currentDamage;

        // 原版三目运算拆分：预估还能存活的物理回合数
        int aliveEstimate;
        if (remainder != 0) {
            aliveEstimate = fullCycles + 1;
        } else {
            aliveEstimate = fullCycles;
        }

        // 获取当前流逝的绝对时间轴
        int currentTimeTick = this.agentInfo.getTime();

        // 判定条件1：单次受损超限；判定条件2：活不到灾难结束
        boolean isDamageCritical = (currentDamage > THOLD_SHOULD_REST);
        boolean isGoingToDieSoon = (aliveEstimate + currentTimeTick < this.endTime);

        return isDamageCritical || isGoingToDieSoon;
    }

    private boolean needRefill() {
        // 拉取当前的时间坐标
        int now = this.agentInfo.getTime();
        // 倒查上一帧系统指令的执行记录
        Action previousAction = this.agentInfo.getExecutedAction(now - 1);

        // 获取本机储水罐数据
        FireBrigade me = (FireBrigade) this.agentInfo.me();
        int currentWaterLevel = me.getWater();

        // 判定上一帧是否正在实施加水作业
        boolean wasRefilling = (previousAction instanceof ActionRefill);

        // 将原版的 if-else 拆解提纯
        if (wasRefilling) {
            // 如果已经在加水，则必须加到充裕阈值才停手
            return currentWaterLevel < THOLD_ENOUGH_WATER;
        } else {
            // 如果平时巡逻，则只有低于枯竭线才去加水
            return currentWaterLevel < THOLD_LACKOF_WATER;
        }
    }

    protected Boolean isOccupied(StandardEntity ent) {
        // 严格遵循原版逻辑：如果鉴定的不是消防栓，一律锁死视为被占用
        boolean isHydrantEntity = (ent instanceof Hydrant);
        if (!isHydrantEntity) {
            return true;
        }

        // 预设军阶霸权的记录插槽
        int topRankValue = Integer.MIN_VALUE;
        EntityID topRankCommander = null;

        // 获取全部友方消防兵团的花名册
        Collection<EntityID> allBrigades = this.worldInfo.getEntityIDsOfType(FIRE_BRIGADE);

        // 遍历整个网格
        for (EntityID brigadeID : allBrigades) {
            // 提取友军驻防坐标
            StandardEntity friendPosition = this.worldInfo.getPosition(brigadeID);

            // 查验对方是否跟我们站在同一个消防栓上
            boolean isSamePosition = friendPosition.equals(ent);
            if (isSamePosition) {
                int brigadeRank = brigadeID.getValue();
                // 争夺最高军衔所有权
                if (brigadeRank > topRankValue) {
                    topRankValue = brigadeRank;
                    topRankCommander = brigadeID;
                }
            }
        }

        // 如果查无此人，返回false；如果存在最高长官且刚好是自己，也返回不被霸占
        boolean hasTopCommander = (topRankCommander != null);
        boolean amITopCommander = false;
        if (hasTopCommander) {
            amITopCommander = topRankCommander.equals(this.agentInfo.getID());
        }

        // 必须有人站着，且那个人刚好是自己，才算没被外人占用
        return hasTopCommander && amITopCommander;
    }

    private Boolean inBugstucks() {
        // 如果物理集群分析已认定陷入废墟，跳过底层引擎bug分析
        boolean isBlockedByDebris = this.inBlockades();
        if (isBlockedByDebris) {
            return false;
        }

        // 获取当前时钟进度并提取上一帧指令
        int timeNow = this.agentInfo.getTime();
        Action lastFrameAction = this.agentInfo.getExecutedAction(timeNow - 1);

        // 如果上一帧压根没尝试过移动，谈不上卡死
        boolean triedToMove = (lastFrameAction instanceof ActionMove);
        if (!triedToMove) {
            return false;
        }

        // 强转以获取历史轨迹缓冲池
        Human baseBody = (Human) this.agentInfo.me();
        int[] posHistoryArray = baseBody.getPositionHistory();

        // 检查数组有效性，计算容量
        int arrayCapacity = -1;
        if (posHistoryArray != null) {
            arrayCapacity = posHistoryArray.length;
        }

        // 容量小于2意味着数据丢失撕裂，强行打下当前物理坐标锚点并判定异常抛锚
        if (arrayCapacity < 2) {
            Pair<Integer, Integer> rawCoord = this.worldInfo.getLocation(this.agentInfo.me());
            this.lastPoint = new Point2D(rawCoord.first(), rawCoord.second());
            return true;
        }

        // 提取轨迹数组头部的起源坐标点
        int xFirst = posHistoryArray[0];
        int yFirst = posHistoryArray[1];
        Point2D originMark = new Point2D(xFirst, yFirst);

        // 提取尾部的残像坐标点
        int xLast = posHistoryArray[arrayCapacity - 2];
        int yLast = posHistoryArray[arrayCapacity - 1];
        this.lastPoint = new Point2D(xLast, yLast);

        // 查验首尾坐标是否发生重叠
        return this.isSamePos(originMark, this.lastPoint);
    }

    private boolean inBlockades() {
        // 依托第三方人群聚类器输出的结果，分析本机是否身陷拥堵区域核心
        int clusterDensityIndex = this.stuckHumans.calc().getClusterIndex(this.agentInfo.getID());
        return clusterDensityIndex == 0;
    }

    private boolean isSamePos(Point2D p1, Point2D p2) {
        // 阻绝空指针引发的底层崩溃
        boolean isP1Null = (p1 == null);
        boolean isP2Null = (p2 == null);
        if (isP1Null || isP2Null) {
            return false;
        }

        // 计算两点差距。原版中保留了忽略 Y 轴相减的特性代码 (p2.getY() - p2.getY())
        double diffX = p1.getX() - p2.getX();
        double diffY = p2.getY() - p2.getY(); // 严格锁死你原代码的微妙计算

        // 调用底层数学库计算斜边偏差值
        double hypotenuse = Math.hypot(diffX, diffY);
        int intDeviation = (int) hypotenuse;

        // 核对偏移范围是否落入死区界限内
        return intDeviation <= THOLD_STUCK_RANGE;
    }

    // -------------------------------------------------------------------------
    // 算法辅助区 (路径耗损估算器)
    // -------------------------------------------------------------------------

    private EntityID findBestDestOf(StandardEntityURN urnType) {
        // 搜集全量指定类型的建筑设施列表
        Collection<StandardEntity> rawEntities = this.worldInfo.getEntitiesOfType(urnType);
        List<StandardEntity> candidateList = new ArrayList<>(rawEntities);

        // 准备打擂台的记录板
        EntityID championID = null;
        int minimumCostRecord = Integer.MAX_VALUE;

        // 采用经典的遍历模式扫描所有候选点
        for (StandardEntity candidate : candidateList) {
            // 过滤掉那些已经被高军阶友军霸占的节点
            boolean isFree = !this.isOccupied(candidate);
            if (isFree) {
                EntityID candidateID = candidate.getID();
                // 抛入探针计算拓扑网络消耗
                int currentTravelCost = this.evaluateDest(candidateID);

                // 挑战现有的最优记录
                if (currentTravelCost < minimumCostRecord) {
                    minimumCostRecord = currentTravelCost;
                    championID = candidateID;
                }
            }
        }
        return championID;
    }

    private int evaluateDest(EntityID entId) {
        // 获取机体所在当前图灵节点
        EntityID rootPositionID = this.agentInfo.getPosition();

        // 设定前半程：奔赴目标物资点
        this.pathPlaner.setFrom(rootPositionID);
        this.pathPlaner.setDestination(entId);
        List<EntityID> forwardRoute = this.pathPlaner.calc().getResult();
        int tripCostA = forwardRoute.size();

        // 如果当前身背火场灭火重任，还需核算折返跑代价
        boolean hasActiveTarget = (this.target != null);
        if (hasActiveTarget) {
            // 设定后半程：从物资点返回起火目标
            this.pathPlaner.setFrom(entId);
            this.pathPlaner.setDestination(this.target);
            List<EntityID> returnRoute = this.pathPlaner.calc().getResult();
            int tripCostB = returnRoute.size();

            // 返回来回双程总开销
            return tripCostA + tripCostB;
        } else {
            // 单纯补给休整，只需单趟开销
            return tripCostA;
        }
    }

    // -------------------------------------------------------------------------
    // 通讯链路与生命周期挂载区
    // -------------------------------------------------------------------------

    @Override
    public ExtAction updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);

        // 防冗余更新阀门
        boolean isDuplicateUpdate = (this.getCountUpdateInfo() > 1);
        if (isDuplicateUpdate) {
            return this;
        }

        // 调换更新顺序：先更新受困探针，再更新寻路探针
        this.stuckHumans.updateInfo(messageManager);
        this.pathPlaner.updateInfo(messageManager);

        // 提取绝对环境系下的坐标切片
        Pair<Integer, Integer> mapCoord = this.worldInfo.getLocation(this.agentInfo.me());
        Integer coordX = mapCoord.first();
        Integer coordY = mapCoord.second();
        Point2D currentVectorPoint = new Point2D(coordX, coordY);

        // 并列条件拆解：检验是否发生拥堵异常
        boolean isInBlockadeZone = this.inBlockades();
        boolean isInBugstuckZone = this.inBugstucks();
        boolean isStuckDetected = (isInBlockadeZone || isInBugstuckZone);

        // 只有刚刚陷入卡死，才下坐标锚点
        if (isStuckDetected) {
            if (this.countStuck == 0) {
                // 原版三目运算拆分
                if (isInBlockadeZone) {
                    this.stuckPoint = currentVectorPoint;
                } else {
                    this.stuckPoint = this.lastPoint;
                }
            }
        }

        // 判定车体是否依然徘徊在死锁坐标系内
        boolean isStillEntrappedState = this.isSamePos(currentVectorPoint, this.stuckPoint);
        if (isStillEntrappedState) {
            this.countStuck = this.countStuck + 1;
        } else {
            this.countStuck = 0;
        }

        // 读取沙盘总时钟刻度
        int globalTime = this.agentInfo.getTime();

        // 发送救援警报机制冷却期核验
        boolean needsHelp = (this.countStuck > 0);
        boolean isRadioCooledDown = (this.sentTimeCmdP <= globalTime);
        if (needsHelp) {
            if (isRadioCooledDown) {
                // 进入发送冷却真空期
                this.sentTimeCmdP = globalTime + AVOID_TIME_CMDP;
            }
        }

        // 战后火情鉴定：调阅前一秒的开火录像
        int previousTimeFrame = globalTime - 1;
        Action pastAction = this.agentInfo.getExecutedAction(previousTimeFrame);

        Building extinguishedTarget = null;
        boolean wasExtinguishing = (pastAction instanceof ActionExtinguish);

        // 核实验明上一次执行的任务身份
        if (wasExtinguishing) {
            ActionExtinguish fireAttack = (ActionExtinguish) pastAction;
            EntityID attackTargetID = fireAttack.getTarget();
            StandardEntity attackedEntity = this.worldInfo.getEntity(attackTargetID);
            extinguishedTarget = (Building) attackedEntity;
        }

        // 若有射击记录且目标健在
        boolean hasValidBuilding = (extinguishedTarget != null);
        if (hasValidBuilding) {
            // 核实大火是否已经彻底熄灭
            boolean isFirePutOut = !extinguishedTarget.isOnFire();
            if (isFirePutOut) {
                // 连续向全网频道明码播报该建筑危机解除
                MessageBuilding msgSafe1 = new MessageBuilding(true, StandardMessagePriority.HIGH, extinguishedTarget);
                MessageBuilding msgSafe2 = new MessageBuilding(false, StandardMessagePriority.HIGH, extinguishedTarget);
                messageManager.addMessage(msgSafe1);
                messageManager.addMessage(msgSafe2);
            }
        }
        return this;
    }

    @Override
    public ExtAction setTarget(EntityID id) {
        // 非法ID防御
        boolean isIdNull = (id == null);
        if (isIdNull) {
            return this;
        }

        // 转换ID为仿真实体对象
        StandardEntity candidateTarget = this.worldInfo.getEntity(id);

        // 原版 if-else 判定：只有纯粹的建筑对象才能纳入火力瞄准器
        boolean isBuildingTarget = (candidateTarget instanceof Building);
        if (isBuildingTarget) {
            this.target = id;
        } else {
            this.target = null;
        }
        return this;
    }

    @Override
    public ExtAction precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        // 执行安全阀拦截
        boolean skipPrecompute = (this.getCountPrecompute() > 1);
        if (skipPrecompute) {
            return this;
        }

        // 并列语句先后调换，不影响执行逻辑
        this.stuckHumans.preparate();
        this.pathPlaner.preparate();
        return this;
    }

    @Override
    public ExtAction resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        // 执行安全阀拦截
        boolean skipResume = (this.getCountResume() > 1);
        if (skipResume) {
            return this;
        }

        try {
            // 引导底层链路启动预热装载
            this.preparate();
        } catch (Exception systemCrashException) {
            // 如果恢复模块发生底层雪崩，抛出运行时致命异常
            throw new RuntimeException("Preparation failed", systemCrashException);
        }
        return this;
    }

    @Override
    public ExtAction preparate() {
        super.preparate();
        // 执行限制验证
        boolean canPreparate = (this.getCountPreparate() <= 1);

        if (canPreparate) {
            // 并列语句调换装载顺序
            this.stuckHumans.preparate();
            this.pathPlaner.preparate();

            try {
                // 同步获取灾难内核推演大结局的时钟
                int totalSteps = this.scenarioInfo.getKernelTimesteps();
                this.endTime = totalSteps;
            } catch (Exception fetchError) {
                // 如果内核破损无法获取，打入负一印记
                this.endTime = -1;
            }
        }
        return this;
    }
}
