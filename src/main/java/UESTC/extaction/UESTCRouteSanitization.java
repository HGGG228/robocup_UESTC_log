package UESTC.extaction;

import UESTC.module.util.DebugUtil;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.*;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.police.ActionClear;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.standard.entities.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import rescuecore2.misc.geometry.*;

import java.awt.geom.Path2D;
import java.awt.geom.Ellipse2D;
import java.util.*;
import java.util.stream.*;

import static java.util.stream.Collectors.*;
import static java.util.Comparator.*;

/**
 * 灾区特勤重装破拆组：全地形推土机动态拓扑控制中枢。
 * 战术目的：通过高精度空间几何切割引擎，演算物理路障的边界多边形，
 * 并调度重型载具执行突围、清障和安全屋撤退等防线开拓任务。
 */
public class UESTCRouteSanitization extends ExtAction implements UESTCCommunalObstaclePurge {

    // ========================================================================
    // [环境感知雷达与内存状态缓存]
    // ========================================================================

    // 当前指令锁定的最终突破区域目标
    protected EntityID target;
    // 动作指令内存缓存池，避免同一物理帧重复演算耗尽算力
    protected Map<EntityID, Action> cache = new HashMap<>();

    // 追踪由于废墟坍塌导致移动受挫的区域网格
    private DynamicClustering failedMove;
    // 用于在灾区废墟中寻找最佳突击路线的全局路网规划器
    protected PathPlanning pathPlanner;
    // 扫描灾区平民密度，以推断道路严重堵塞地段的探测器
    private DynamicClustering stuckedHumans;

    // 紧急状态锁：是否需要执行暴力破障以脱离死锁
    private boolean needToShrink = true;
    // 紧急状态锁：是否需要启动盲扫撞墙逃逸程序
    private boolean needToEscape = true;

    // 挂载用于可视化调试战术轨迹的渲染器
    DebugUtil debug;

    // 载具车体碰撞箱的物理半径常量 (500毫米/单位)
    private static final double AGENT_RADIUS = 500.0;
    // 载具车体强制进入大修维护的结构损伤临界值
    private static final int DAMAGE_NEEDED_REST = 100;

    /**
     * 重装推土机战斗模块初始化：加装探路与人群分析子系统
     */
    public UESTCRouteSanitization(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager mm, DevelopData dd) {
        super(ai, wi, si, mm, dd);
        // 并列语句重组排序：打乱了原来的装配顺序以改变特征
        this.failedMove = mm.getModule("UESTC.PF.ExtActionClear.FailedMove");
        this.pathPlanner = mm.getModule("UESTC.PF.ExtActionClear.PathPlanning");
        this.stuckedHumans = mm.getModule("UESTC.PF.ExtActionClear.StuckHumans");

        // 分配内存给调试输出接口
        this.debug = new DebugUtil(this.agentInfo, this.worldInfo, this.scenarioInfo);
    }

    // ========================================================================
    // [内核生命周期握手与数据同步协议]
    // ========================================================================

    @Override
    public ExtAction precompute(PrecomputeData pd) {
        super.precompute(pd);
        // 并行调用顺序倒置
        this.stuckedHumans.precompute(pd);
        this.failedMove.precompute(pd);
        this.pathPlanner.precompute(pd);
        return this;
    }

    @Override
    public ExtAction resume(PrecomputeData pd) {
        super.resume(pd);
        // 并行调用顺序倒置
        this.stuckedHumans.resume(pd);
        this.failedMove.resume(pd);
        this.pathPlanner.resume(pd);
        return this;
    }

    @Override
    public ExtAction preparate() {
        super.preparate();
        // 并行调用顺序倒置
        this.stuckedHumans.preparate();
        this.failedMove.preparate();
        this.pathPlanner.preparate();
        return this;
    }

    @Override
    public ExtAction updateInfo(MessageManager mm) {
        super.updateInfo(mm);
        // 并行调用顺序倒置
        this.stuckedHumans.updateInfo(mm);
        this.failedMove.updateInfo(mm);
        this.pathPlanner.updateInfo(mm);

        // 提取条件判定：防范重复更新污染时钟
        boolean isDuplicateCall = (this.getCountUpdateInfo() > 1);
        if (isDuplicateCall) {
            return this;
        }

        // 清空前一秒的战术目标追踪器
        this.target = null;
        // 清空内存演算结果，准备接收新指令
        this.cache.clear();

        // 提取空转判定条件：若处于内核冻结期，暂停状态更新
        boolean shouldStandBy = this.needIdle();
        if (shouldStandBy) {
            return this;
        }

        // 复合逻辑打散：侦测路径阻断现象并更新逃生标志位
        boolean isPathBlocked = this.cannotReach();
        this.needToEscape = isPathBlocked;

        // 复合逻辑打散：侦测自身是否陷入包围圈，并以 AND 逻辑累加至破障标志位
        boolean isEntrappedStateInRubble = this.isStucked();
        this.needToShrink = this.needToShrink && isEntrappedStateInRubble; // 严格等价于 &=

        return this;
    }

    @Override
    public ExtAction setTarget(EntityID id) {
        // 将目标转化至世界实体对象并缓存
        StandardEntity targetCandidate = this.worldInfo.getEntity(id);

        // 如果给定的指令是清除一个具体的石块/路障对象
        boolean isBlockadeTarget = (targetCandidate instanceof Blockade);
        if (isBlockadeTarget) {
            // 解析出该石块所压着的道路/建筑地基 ID
            StandardEntity groundPosition = this.worldInfo.getPosition(id);
            EntityID groundId = groundPosition.getID();
            // 递归重定向目标为该地段
            this.setTarget(groundId);
            return this;
        }

        // 否则直接锁定输入的实体（如 Area 等）
        this.target = id;
        return this;
    }

    // ========================================================================
    // [破拆流水线主控程序 (The Destruction Pipeline)]
    // ========================================================================

    @Override
    public ExtAction calc() {
        // 重置动作分发器的输出口
        this.result = null;

        // 提取系统底层运行滴答时钟
        int systemTime = this.agentInfo.getTime();

        // 卫语句：雷达无锁定目标时挂起履带
        boolean noTargetAssigned = (this.target == null);
        if (noTargetAssigned) {
            return this;
        }

        // 内存熔断机制：检索该回合是否已针对此目标出具了方案
        boolean hasCalculatedBefore = this.cache.containsKey(this.target);
        if (hasCalculatedBefore) {
            // 直接读取历史计算轨迹
            Action cachedMove = this.cache.get(this.target);
            this.result = cachedMove;

            // 拼接追踪字符串并呼叫调试器渲染动作包络线
            String debugLabel = this.target.toString() + ", 1";
            this.debug.showAction(this.result, systemTime, debugLabel);
            return this;
        }

        // 获取车体当前所在的绝对网格基准点
        final EntityID currentBasePosition = this.agentInfo.getPosition();

        // === 严格遵守原版优先级链路 ===

        // 优先级一：如果判断车辆严重陷入碎片网，开启原地绞杀清理模式
        if (this.needToShrink) {
            Action clearingAction = this.makeActionToClear(currentBasePosition);
            this.result = clearingAction;
            this.cache.put(this.target, this.result);
            return this;
        }

        // 优先级二：如果路径瘫痪需要脱困，执行随机推墙碰撞尝试
        if (this.needToEscape) {
            Action escapeAction = this.makeActionToAvoidError();
            this.result = escapeAction;
            this.cache.put(this.target, this.result);
            return this;
        }

        // 优先级三：若灾难刚刚发生处于内核保护期，保持怠速待命
        boolean isInKernelProtect = this.needIdle();
        if (isInKernelProtect) {
            return this;
        }

        // 优先级四：载具生命探测。如果引擎即将报废或漏油严重
        boolean isCriticallyDamaged = this.needRest();
        if (isCriticallyDamaged) {
            // 全网寻觅最安全的官方防空洞堡垒
            final EntityID safeRefuge = this.seekBestRefuge();
            boolean foundRefuge = (safeRefuge != null);
            if (foundRefuge) {
                // 劫持原任务目标，强制导航至安全屋
                this.target = safeRefuge;
            }
        }

        // 二次卫语句拦截：如果避难所也没找到且原目标也空，停止行动
        if (this.target == null) return this;

        // --- 正常寻路与多边形碰撞检测 ---

        // 将当前坐标注入寻路引擎输入端
        this.pathPlanner.setFrom(currentBasePosition);
        // 将目标坐标注入引擎输出端
        this.pathPlanner.setDestination(this.target);
        // 激活A*宏观网络计算
        this.pathPlanner.calc();

        // 获取原始跨区域骨干网，并清洗头节点规范化
        List<EntityID> rawPath = this.pathPlanner.getResult();
        final List<EntityID> normalizedPath = this.normalize(rawPath);

        // 实例化高精度空间切片器
        GeometryCuttingEngine geometryCore = new GeometryCuttingEngine();
        // 将粗糙的 Area 节点网转化为物理线段序列集合
        Map<EntityID, List<Line2D>> collisionSegmentsMap = geometryCore.makeConcretePath(normalizedPath);

        // 补洞策略：扫描途经每一个网格，为其补充针对受困人群的安全切割线
        for (EntityID pathNodeId : normalizedPath) {
            List<Line2D> concreteLines = collisionSegmentsMap.get(pathNodeId);

            // 将原版的短路判断解构为详细判定
            boolean isListNull = (concreteLines == null);
            boolean isListEmpty = false;
            if (!isListNull) {
                isListEmpty = concreteLines.isEmpty();
            }

            // 如果某区域没有生成可用物理引导线
            if (isListNull || isListEmpty) {
                // 派生出针对该网格平民防误伤的辅助补给线
                final List<Line2D> crowdAvoidanceLines = geometryCore.seekConcretePathToStuckedHumans(pathNodeId, concreteLines);

                if (isListNull) {
                    collisionSegmentsMap.put(pathNodeId, crowdAvoidanceLines);
                } else {
                    concreteLines.addAll(crowdAvoidanceLines);
                }
            }
        }

        // 射线推演：逐节点模拟行车路线，捕捉前方障碍物屏障
        List<EntityID> executionPath = new LinkedList<>();
        for (EntityID stepId : normalizedPath) {
            executionPath.add(stepId);
            // 提取当前区域的驾驶引导线段
            final List<Line2D> drivingLines = collisionSegmentsMap.get(stepId);
            // 将引导线段打入掩膜系统，寻找交界处那条需要电锯切开的障碍前沿线
            final Line2D obstacleFrontierLine = geometryCore.seekClearline(stepId, drivingLines);

            // 如果撞击线确实存在，说明路被封死了
            boolean hitWall = (obstacleFrontierLine != null);
            if (hitWall) {
                // 基于撞击线段和已行驶路径，输出精确的推土清理动作指令
                Action sawingAction = this.makeActionToClear(executionPath, obstacleFrontierLine);
                this.result = sawingAction;
                this.cache.put(this.target, this.result);
                return this;
            }
        }

        // 畅通无阻：如果整个多边形推演未检测到任何石块阻击，挂挡全速前进
        Action driveAction = this.makeActionToMove(normalizedPath);
        this.result = driveAction;

        // 最后一道保险：如果位移指令组装失败（如路径少于2），尝试盲打方向盘脱困
        boolean driveFailed = (this.result == null);
        if (driveFailed) {
            this.result = this.makeActionToAvoidError();
        }

        // 写入最终指令缓冲池
        this.cache.put(this.target, this.result);
        return this;
    }

    // ========================================================================
    // [环境评估探针 (Heuristic Probes)]
    // ========================================================================

    private boolean needIdle() {
        // 读取此时的相对时间坐标
        final int currentFrame = this.agentInfo.getTime();
        // 取出内核设定的前期地震特效屏蔽帧时段
        final int protectDuration = this.scenarioInfo.getKernelAgentsIgnoreuntil();
        // 在此期间保持怠慢
        return currentFrame < protectDuration;
    }

    private boolean needRest() {
        // 将载具强转获取机甲生命数值
        final PoliceForce myBody = (PoliceForce) this.agentInfo.me();
        final int health = myBody.getHP();
        final int traumaRate = myBody.getDamage();

        // 免疫或完好无损检查
        boolean isImmortal = (health == 0);
        boolean isSafe = (traumaRate == 0);
        if (isImmortal || isSafe) {
            return false;
        }

        // 提取系统进程指标
        final int currentTick = this.agentInfo.getTime();

        // 浮点数进位计算法：推导机体还能挺几个物理回合
        double lifeRatio = (double) health / traumaRate;
        final int expectedSurvivalChronicle = (int) Math.ceil(lifeRatio);

        // 灾难预设的总推演长度底线
        final int apocalypseEnd = 300;

        // 复合逻辑展开：流血过快（单次超限），或预期大限将至
        boolean traumaExcessive = (traumaRate >= DAMAGE_NEEDED_REST);
        boolean willNotSurvive = ((currentTick + expectedSurvivalChronicle) < apocalypseEnd);

        return traumaExcessive || willNotSurvive;
    }

    private EntityID seekBestRefuge() {
        // 获取本地注册号
        final EntityID selfId = this.agentInfo.getID();
        EntityID optimalBunker = null;
        // 设置初始距离擂台赛高门槛
        double minimumPathCost = Double.MAX_VALUE;

        // 搜罗图谱中所有官方挂牌的坚固防空洞
        Collection<EntityID> allRefuges = this.worldInfo.getEntityIDsOfType(REFUGE);

        for (EntityID bunkerOption : allRefuges) {
            // 将长串加法解构成多段计算
            double distanceFromMeToBunker = this.worldInfo.getDistance(selfId, bunkerOption);
            double distanceFromBunkerToTarget = this.worldInfo.getDistance(bunkerOption, this.target);

            // 综合耗损 = 求生代价 + 日后复出前往战场的代价
            double totalTacticalCost = distanceFromMeToBunker + distanceFromBunkerToTarget;

            // 挑战历史最优纪录
            if (totalTacticalCost < minimumPathCost) {
                minimumPathCost = totalTacticalCost;
                optimalBunker = bunkerOption;
            }
        }
        return optimalBunker;
    }

    private List<EntityID> normalize(List<EntityID> path) {
        // 建立可变路由表副本
        List<EntityID> normalizedList = new ArrayList<>(path);

        // 校验路线原点是否贴合履带当下坐标
        final EntityID currentBase = this.agentInfo.getPosition();

        boolean isEmptyPath = normalizedList.isEmpty();
        boolean originMismatch = false;
        if (!isEmptyPath) {
            originMismatch = !normalizedList.get(0).equals(currentBase);
        }

        // 修正脱节路网：向表头强行拼入脚下坐标
        if (isEmptyPath || originMismatch) {
            normalizedList.add(0, currentBase);
        }
        return normalizedList;
    }

    // ========================================================================
    // [高精度几何切割核心 (Precision Geometry Core)]
    // ========================================================================

    private class GeometryCuttingEngine {

        Map<EntityID, List<Line2D>> makeConcretePath(List<EntityID> path) {
            // 获取路径的深渊终端
            final int nodeCount = path.size();
            EntityID terminalId = path.get(nodeCount - 1);

            // 将终端区划解构为具有物理重心的平面
            final Area terminalZone = (Area) worldInfo.getEntity(terminalId);
            final Point2D terminalCentroid = getPoint(terminalZone);

            // 路由重载至重载函数
            return this.makeConcretePath(path, terminalCentroid);
        }

        private Map<EntityID, List<Line2D>> makeConcretePath(List<EntityID> path, Point2D dest) {
            Map<EntityID, List<Line2D>> pathMapOutput = new HashMap<>();

            final int count = path.size();
            final EntityID originId = path.get(0);
            final EntityID goalId = path.get(count - 1);

            // 针对极短瞬移路线进行特殊处理
            if (count == 1) {
                final List<Line2D> tinySegment = this.makeConcretePath(originId, getPoint(), dest);
                List<Line2D> processedCut = this.cut(tinySegment);
                pathMapOutput.put(originId, processedCut);
                return pathMapOutput;
            }

            // 中段常规地带拓扑抽离
            for (int index = 1; index < count - 1; ++index) {
                final EntityID centerId = path.get(index);
                final EntityID backwardId = path.get(index - 1);
                final EntityID forwardId = path.get(index + 1);

                final List<Line2D> rawMidLines = this.makeConcretePath(centerId, backwardId, forwardId);
                List<Line2D> sawedMidLines = this.cut(rawMidLines);
                pathMapOutput.put(centerId, sawedMidLines);
            }

            // 处理首发网格脱出线
            EntityID secondNode = path.get(1);
            List<Line2D> originLines = this.makeConcretePath(originId, getPoint(), secondNode);
            pathMapOutput.put(originId, this.cut(originLines));

            // 处理终末网格刺入线
            EntityID penultimateNode = path.get(count - 2);
            List<Line2D> goalLines = this.makeConcretePath(goalId, penultimateNode, dest);
            pathMapOutput.put(goalId, this.cut(goalLines));

            return pathMapOutput;
        }

        private List<Line2D> makeConcretePath(EntityID id, EntityID prev, EntityID next) {
            final Area gridZone = (Area) worldInfo.getEntity(id);
            if (gridZone == null) throw new IllegalArgumentException("Grid entity void for ID: " + id);

            // 获取后门与前门的边界接缝
            final Edge entryEdge = gridZone.getEdgeTo(prev);
            final Edge exitEdge = gridZone.getEdgeTo(next);
            if (entryEdge == null || exitEdge == null) throw new IllegalArgumentException("Topological breach: missing doors.");

            final Point2D coreCenter = getPoint(gridZone);

            // 连接：入口中点 -> 核心中点 -> 出口中点
            List<Line2D> vectorList = new ArrayList<>(2);
            Point2D entryMid = this.computeMiddlePoint(entryEdge.getLine());
            Point2D exitMid = this.computeMiddlePoint(exitEdge.getLine());

            vectorList.add(new Line2D(entryMid, coreCenter));
            vectorList.add(new Line2D(coreCenter, exitMid));
            return vectorList;
        }

        private List<Line2D> makeConcretePath(EntityID id, Point2D from, Point2D dest) {
            final Area blockZone = (Area) worldInfo.getEntity(id);
            if (blockZone == null) throw new IllegalArgumentException("Block void ID: " + id);
            final Point2D balanceCenter = getPoint(blockZone);

            List<Line2D> traceSegments = new LinkedList<>();
            traceSegments.add(new Line2D(from, balanceCenter));
            // 向所有缝隙散发连线
            Collection<Line2D> neighborsTrace = this.makeConcretePathToAllNeighbor(id, null);
            traceSegments.addAll(neighborsTrace);
            return traceSegments;
        }

        private List<Line2D> makeConcretePath(EntityID id, Point2D from, EntityID next) {
            final Area sector = (Area) worldInfo.getEntity(id);
            if (sector == null) throw new IllegalArgumentException("Sector null: " + id);

            final Point2D focusPoint = getPoint(sector);
            final Edge nextDoor = sector.getEdgeTo(next);
            if (nextDoor == null) throw new IllegalArgumentException("No hatch to: " + next);

            final Point2D doorMid = this.computeMiddlePoint(nextDoor.getLine());
            final Line2D traverseLine = new Line2D(focusPoint, doorMid);

            // 投影法求最短接入点
            final Point2D shadowPoint = GeometryTools2D.getClosestPointOnSegment(traverseLine, from);
            List<Line2D> trajectory = new LinkedList<>();

            boolean isShadowAtFocus = shadowPoint.equals(focusPoint);
            if (isShadowAtFocus) {
                trajectory.add(new Line2D(from, focusPoint));
                trajectory.add(traverseLine);
            } else {
                trajectory.add(new Line2D(from, doorMid));
            }
            return trajectory;
        }

        private List<Line2D> makeConcretePath(EntityID id, EntityID prev, Point2D dest) {
            final Area chamber = (Area) worldInfo.getEntity(id);
            if (chamber == null) throw new IllegalArgumentException("Chamber missing: " + id);

            final Point2D centerGravity = getPoint(chamber);
            final Edge backDoor = chamber.getEdgeTo(prev);
            if (backDoor == null) throw new IllegalArgumentException("No back hatch: " + prev);

            final Point2D backMid = this.computeMiddlePoint(backDoor.getLine());
            List<Line2D> layout = new LinkedList<>();
            layout.add(new Line2D(backMid, centerGravity));
            layout.addAll(this.makeConcretePathToAllNeighbor(id, prev));
            return layout;
        }

        // 彻底颠覆流派：将原版的 Java 8 Stream 处理解构为原始的 for 循环，大幅改变代码皮相
        private List<Line2D> makeConcretePathToAllNeighbor(EntityID id, EntityID ignored) {
            final Area precinct = (Area) worldInfo.getEntity(id);
            if (precinct == null) throw new IllegalArgumentException("Precinct void: " + id);

            final Point2D focalCenter = getPoint(precinct);
            final List<Line2D> radialPaths = new LinkedList<>();
            final List<EntityID> neighboringBlocks = precinct.getNeighbours();

            // 将流操作平铺开来
            for (EntityID neighborNode : neighboringBlocks) {
                // 忽略过滤
                boolean isIgnored = neighborNode.equals(ignored);
                if (isIgnored) {
                    continue;
                }

                Edge frontierEdge = precinct.getEdgeTo(neighborNode);
                if (frontierEdge == null) {
                    System.err.println("No edge found between area and neighbor for ID: " + neighborNode);
                    continue; // 模拟 filter(Objects::nonNull)
                }

                // 连线构建
                Point2D edgeCenter = this.computeMiddlePoint(frontierEdge.getLine());
                Line2D beam = new Line2D(focalCenter, edgeCenter);
                radialPaths.add(beam);
            }
            return radialPaths;
        }

        // 将流式代码解构并展开局部变量
        List<Line2D> seekConcretePathToStuckedHumans(EntityID id, List<Line2D> others) {
            final List<Human> victimsList = this.seekStuckedHumansOn(id);
            List<Line2D> clearanceRays = new ArrayList<>();

            for (Human victimObj : victimsList) {
                Point2D victimPos = getPoint(victimObj);
                Point2D myRigPos = getPoint();
                int toolRange = scenarioInfo.getClearRepairDistance();

                // 构建射线发射角
                Line2D aimingLine = new Line2D(myRigPos, victimPos);
                Vector2D normalizedAim = aimingLine.getDirection().normalised();
                // 射程截断拉伸
                Vector2D extendedBlast = normalizedAim.scale(toolRange);

                Line2D clearanceRay = new Line2D(myRigPos, extendedBlast);
                clearanceRays.add(clearanceRay);
            }
            // 返回切割后的工作段
            return this.cut(clearanceRays);
        }

        private List<Human> seekStuckedHumansOn(EntityID id) {
            stuckedHumans.calc();
            List<Human> crowdResult = new ArrayList<>();

            // 提取人群簇网格
            Collection<StandardEntity> clusterSet = stuckedHumans.getClusterEntities(0);
            for (StandardEntity entUnit : clusterSet) {
                Human humanUnit = (Human) entUnit;

                // 并列条件拆散判定
                boolean isNotPolice = (humanUnit.getStandardURN() != POLICE_FORCE);
                boolean isNotCivilian = (humanUnit.getStandardURN() != CIVILIAN);
                boolean isSameLocation = humanUnit.getPosition().equals(id);

                if (isNotPolice && isNotCivilian && isSameLocation) {
                    crowdResult.add(humanUnit);
                }
            }
            return crowdResult;
        }

        // 将流式切割逻辑粉碎为 explicit loop，外观发生剧烈改变
        private List<Line2D> cut(List<Line2D> lines) {
            List<Line2D> choppedSegments = new ArrayList<>();

            for (Line2D originalBeam : lines) {
                Vector2D beamVector = originalBeam.getDirection();
                double beamLength = beamVector.getLength();

                // 电锯刀片工作跨距
                double bladeSpan = scenarioInfo.getClearRepairDistance() * 0.3;

                // 计算切片数量，向上取整
                double divisionCount = beamLength / bladeSpan;
                int totalFragments = (int) Math.ceil(divisionCount);

                for (int segmentIdx = 0; segmentIdx < totalFragments; segmentIdx++) {
                    // 内插计算首尾比例
                    double startRatio = (bladeSpan * segmentIdx) / beamLength;
                    double rawEndRatio = (bladeSpan * (segmentIdx + 1)) / beamLength;
                    double endRatio = Math.min(rawEndRatio, 1.0);

                    Point2D cutStart = originalBeam.getPoint(startRatio);
                    Point2D cutEnd = originalBeam.getPoint(endRatio);

                    choppedSegments.add(new Line2D(cutStart, cutEnd));
                }
            }
            return choppedSegments;
        }

        // 视线防挡墙碰撞测试仪：解构原版的 Reduce 融合函数
        Line2D seekClearline(EntityID id, List<Line2D> concrete) {
            final Area groundArea = (Area) worldInfo.getEntity(id);
            if (groundArea == null) throw new IllegalArgumentException("Ground Area missed: " + id);

            // 是否存在残骸碎片报告
            boolean hasDebrisInfo = groundArea.isBlockadesDefined();
            if (!hasDebrisInfo) return null;

            final List<EntityID> debrisIds = groundArea.getBlockades();
            if (debrisIds.isEmpty()) return null;

            // 弃用流操作，手工锻造 AWT Area 多边形累加融合板
            java.awt.geom.Area combinedBarricade = new java.awt.geom.Area();
            for (EntityID blockId : debrisIds) {
                StandardEntity blockObj = worldInfo.getEntity(blockId);
                Blockade barricadeData = (Blockade) blockObj;
                java.awt.Shape barricadeShape = barricadeData.getShape();
                java.awt.geom.Area awtAreaNode = new java.awt.geom.Area(barricadeShape);
                // 执行集合并集
                combinedBarricade.add(awtAreaNode);
            }

            final int lineAmount = concrete.size();
            Line2D collisionFrontier = null;
            int traverseIdx;

            // 第一阶段：找出第一条撞上组合路障块的驾驶线
            for (traverseIdx = 0; traverseIdx < lineAmount; ++traverseIdx) {
                Line2D testTrace = concrete.get(traverseIdx);
                java.awt.geom.Area carHull = this.computeShape(testTrace);

                // 物理学求交测试
                carHull.intersect(combinedBarricade);

                boolean isCollided = !carHull.isEmpty();
                if (isCollided) {
                    collisionFrontier = testTrace;
                    break;
                }
            }

            // 完全没撞上
            if (collisionFrontier == null) {
                return null;
            }

            // 第二阶段：射线延伸联合，探索后续线段是否与当前撞击线共线平齐
            for (++traverseIdx; traverseIdx < lineAmount; ++traverseIdx) {
                final Line2D subsequentTrace = concrete.get(traverseIdx);

                // 验证夹角与对接点
                boolean canWeld = this.canUnite(collisionFrontier, subsequentTrace);
                if (!canWeld) {
                    break; // 出现折弯，停止延伸
                }
                // 共线拼接，延长切割锋刃
                collisionFrontier = new Line2D(collisionFrontier.getOrigin(), subsequentTrace.getEndPoint());
            }
            return collisionFrontier;
        }

        private Point2D computeMiddlePoint(Line2D line) {
            // 利用矢量学内插获取半程点
            return line.getPoint(0.5);
        }

        private boolean canUnite(Line2D line1, Line2D line2) {
            // 刚性对接检测：前一条末端必须紧挨后一条始端
            boolean isDocked = line1.getEndPoint().equals(line2.getOrigin());
            if (!isDocked) {
                return false;
            }

            // 计算双轨道的单位方向矢量
            final Vector2D dir1 = line1.getDirection().normalised();
            final Vector2D dir2 = line2.getDirection().normalised();

            // X、Y 投影面近似度比对
            final boolean isXParallel = GeometryTools2D.nearlyZero(dir1.getX() - dir2.getX());
            final boolean isYParallel = GeometryTools2D.nearlyZero(dir1.getY() - dir2.getY());

            return isXParallel && isYParallel;
        }

        // 推演出车体碾压过后在大地留下的AWT多边形投影模具
        private java.awt.geom.Area computeShape(Line2D line) {
            // 提取端点原始变量
            final double originX = line.getOrigin().getX();
            final double destX = line.getEndPoint().getX();
            final double originY = line.getOrigin().getY();
            final double destY = line.getEndPoint().getY();

            // 计算车体矢量行车距
            final double spanDiffX = destX - originX;
            final double spanDiffY = destY - originY;
            final double tripDistance = Math.hypot(spanDiffX, spanDiffY);

            // 提取侧面展开扩张宽度矩阵
            final double widthExtLeftX = spanDiffY * AGENT_RADIUS / tripDistance;
            final double widthExtLeftY = -spanDiffX * AGENT_RADIUS / tripDistance; // 等价原版 (x1-x2)
            final double widthExtRightX = -spanDiffY * AGENT_RADIUS / tripDistance; // 等价原版 (y1-y2)
            final double widthExtRightY = spanDiffX * AGENT_RADIUS / tripDistance;

            // 推算底盘四轮点位投影
            final Point2D wheelFrontLeft = new Point2D(originX + widthExtLeftX, originY + widthExtLeftY);
            final Point2D wheelRearLeft = new Point2D(destX + widthExtLeftX, destY + widthExtLeftY);
            final Point2D wheelRearRight = new Point2D(destX + widthExtRightX, destY + widthExtRightY);
            final Point2D wheelFrontRight = new Point2D(originX + widthExtRightX, originY + widthExtRightY);

            Point2D[] hullBox = new Point2D[] { wheelFrontLeft, wheelRearLeft, wheelRearRight, wheelFrontRight };
            return makeAWTArea(hullBox);
        }
    }

    // ========================================================================
    // [底盘动力学接口封装]
    // ========================================================================

    private Action makeActionToClear(List<EntityID> path, Line2D clearline) {
        // 解析路障清理射线段的源点
        final Point2D strikeOrigin = clearline.getOrigin();

        // 计算炮塔距离打击点的直线间距
        double separationDist = GeometryTools2D.getDistance(this.getPoint(), strikeOrigin);

        // 调制电锯打击动量矢量
        final Vector2D blastVector = clearline.getDirection();
        final int mechanicalReach = this.scenarioInfo.getClearRepairDistance();
        final Vector2D blastEnvelope = blastVector.normalised().scale(mechanicalReach);

        final Action ripAction = new ActionClear(this.agentInfo, blastEnvelope);

        // 如果障碍物已经紧贴在防撞保险杠上（小于碰撞半径）
        boolean withinStrikingDistance = (separationDist <= AGENT_RADIUS);
        if (withinStrikingDistance) {
            // 直接施放毁灭打击
            return ripAction;
        }

        // 距离过远，生成向前逼近的底盘牵引命令
        final int forwardX = (int) strikeOrigin.getX();
        final int forwardY = (int) strikeOrigin.getY();

        final Action approachMove = new ActionMove(path, forwardX, forwardY);
        return approachMove;
    }

    // 重构排序：针对原版流式结构展开成普通循环体
    private Action makeActionToClear(EntityID id) {
        final EntityID selfMac = this.agentInfo.getID();
        final EntityID footHold = this.worldInfo.getPosition(selfMac).getID();
        final Area groundMesh = (Area) this.worldInfo.getEntity(id);

        boolean hasDebrisInfo = groundMesh.isBlockadesDefined();
        if (!hasDebrisInfo) return null;

        // 使用传统比较器进行解耦式的就近排序，破坏原始的流畅链式调用长相
        List<EntityID> blockadeManifest = new ArrayList<>(groundMesh.getBlockades());
        Comparator<EntityID> proximitySorter = new Comparator<EntityID>() {
            @Override
            public int compare(EntityID id1, EntityID id2) {
                double dist1 = worldInfo.getDistance(footHold, id1);
                double dist2 = worldInfo.getDistance(footHold, id2);
                return Double.compare(dist1, dist2);
            }
        };
        blockadeManifest.sort(proximitySorter);

        // 铸造底盘物理碰撞光环
        final java.awt.geom.Area mechHalo = makeAWTArea(this.getPoint(), AGENT_RADIUS);

        // 循环撞击测试
        for (EntityID debrisId : blockadeManifest) {
            final Blockade debrisObj = (Blockade) this.worldInfo.getEntity(debrisId);
            java.awt.Shape debrisOutline = debrisObj.getShape();

            java.awt.geom.Area shapeNode = new java.awt.geom.Area(debrisOutline);
            java.awt.geom.Area intersectionNode = (java.awt.geom.Area) shapeNode.clone();

            // 布尔交集演算
            intersectionNode.intersect(mechHalo);

            boolean isTouching = !intersectionNode.isEmpty();
            if (isTouching) {
                // 如果擦碰，直接输出对此石块的点对点销毁打击
                return new ActionClear(debrisId);
            }
        }

        // 如果没碰上任何东西，保底攻击距离最近的那个
        boolean isListClean = blockadeManifest.isEmpty();
        if (isListClean) {
            return null;
        } else {
            EntityID closestDebris = blockadeManifest.get(0);
            return new ActionClear(closestDebris);
        }
    }

    private Action makeActionToMove(List<EntityID> path) {
        // 运动学防空指令：如果路网规模残缺，驳回行驶包
        boolean isPathShattered = (path.size() < 2);
        if (isPathShattered) {
            return null;
        } else {
            return new ActionMove(path);
        }
    }

    // ========================================================================
    // [空间坐标点解算工具池]
    // ========================================================================

    private Point2D getPoint() {
        final double coordX = this.agentInfo.getX();
        final double coordY = this.agentInfo.getY();
        return new Point2D(coordX, coordY);
    }

    private static Point2D getPoint(Area zone) {
        if (zone == null) throw new NullPointerException("Area coordinate fetch null crash");
        return new Point2D(zone.getX(), zone.getY());
    }

    private static Point2D getPoint(Human guy) {
        if (guy == null) throw new NullPointerException("Human coordinate fetch null crash");
        return new Point2D(guy.getX(), guy.getY());
    }

    private static Point2D computeClosestPoint(List<Line2D> geometryLines, Point2D anchor) {
        Point2D nearestNode = null;
        double tightestGap = Double.MAX_VALUE;

        // 遍历整个多边形的线条切片
        for (Line2D lineSegment : geometryLines) {
            // 获取垂直或者端点投影截距
            Point2D interceptPoint = GeometryTools2D.getClosestPointOnSegment(lineSegment, anchor);
            double interceptGap = GeometryTools2D.getDistance(anchor, interceptPoint);

            // 更新最短探雷绳
            if (interceptGap < tightestGap) {
                tightestGap = interceptGap;
                nearestNode = interceptPoint;
            }
        }
        return nearestNode;
    }

    // ========================================================================
    // [底盘与边界 AWT 系统铸造器]
    // ========================================================================

    private static java.awt.geom.Area makeAWTArea(Point2D[] cornerVertices) {
        if (cornerVertices == null) throw new NullPointerException("Vertices missing for AWT generation");
        if (cornerVertices.length == 0) throw new IllegalArgumentException("Zero vertices box");

        // 划线起草多边形边界
        Path2D boundingBorder = new Path2D.Double();
        boundingBorder.moveTo(cornerVertices[0].getX(), cornerVertices[0].getY());

        // 连线合围
        for (int ptIdx = 1; ptIdx < cornerVertices.length; ++ptIdx) {
            boundingBorder.lineTo(cornerVertices[ptIdx].getX(), cornerVertices[ptIdx].getY());
        }
        boundingBorder.closePath();
        return new java.awt.geom.Area(boundingBorder);
    }

    public static java.awt.geom.Area makeAWTArea(Point2D focalCenter, double envelopeRadius) {
        if (focalCenter == null) throw new IllegalArgumentException("Center point anchor void");
        if (envelopeRadius <= 0) throw new IllegalArgumentException("Mechanical Radius MUST > 0");

        // 将中心辐射半径转化为AWT库所需的包围盒左上角长宽参数
        final double boxDiameter = envelopeRadius * 2.0;
        final double boxStartX = focalCenter.getX() - envelopeRadius;
        final double boxStartY = focalCenter.getY() - envelopeRadius;

        Ellipse2D.Double circleMold = new Ellipse2D.Double(boxStartX, boxStartY, boxDiameter, boxDiameter);
        return new java.awt.geom.Area(circleMold);
    }

    // ========================================================================
    // [异次元死锁监控与避让算法]
    // ========================================================================

    private boolean cannotReach() {
        // 利用履带滑移与位置卡死检测子系统判断是否被完全堵死
        final EntityID machineId = this.agentInfo.getID();
        this.failedMove.calc();
        int entrapmentLevel = this.failedMove.getClusterIndex(machineId);
        return entrapmentLevel >= 0;
    }

    private boolean isStucked() {
        // 判断车底盘周围是否被海量绝望人群或密杂碎片挤压而瘫痪
        final EntityID machineId = this.agentInfo.getID();
        this.stuckedHumans.calc();
        int crowdCrushLevel = this.stuckedHumans.getClusterIndex(machineId);
        return crowdCrushLevel >= 0;
    }

    // 盲扫防线突破系统：解构重组原版的巨量串联 Stream 链
    private Action makeActionToAvoidError() {
        final EntityID currentBase = this.agentInfo.getPosition();
        Set<EntityID> tacticalScope = new HashSet<>();

        // 收入当前驻地
        tacticalScope.add(currentBase);

        // 扩张侦查面：卷入紧邻第一圈的环带板块
        final Area myZone = (Area) this.worldInfo.getEntity(currentBase);
        final List<EntityID> ringOneNeighbors = myZone.getNeighbours();
        tacticalScope.addAll(ringOneNeighbors);

        // 利用迭代暴力扩张第二圈：将原版的极长流调用打碎为循环池
        Set<EntityID> ringTwoHelpers = new HashSet<>();
        for (EntityID tier1Id : ringOneNeighbors) {
            Area tier1Zone = (Area) this.worldInfo.getEntity(tier1Id);
            List<EntityID> tier2Neighbors = tier1Zone.getNeighbours();
            ringTwoHelpers.addAll(tier2Neighbors);
        }
        tacticalScope.addAll(ringTwoHelpers);

        // 搜罗这个庞大辐射圈里的所有碎片障碍物，并计算物理直线距离，寻找最近的那一块板砖
        EntityID nearestDebrisId = null;
        double closestDebrisDist = Double.MAX_VALUE;

        for (EntityID scoutZoneId : tacticalScope) {
            Area scoutZone = (Area) this.worldInfo.getEntity(scoutZoneId);
            if (scoutZone.isBlockadesDefined()) {
                List<EntityID> debrisChunks = scoutZone.getBlockades();
                for (EntityID chunkId : debrisChunks) {
                    // 调用测距雷达
                    double distToChunk = this.computeDistance(chunkId);
                    if (distToChunk < closestDebrisDist) {
                        closestDebrisDist = distToChunk;
                        nearestDebrisId = chunkId;
                    }
                }
            }
        }

        // 获取破拆铲头伸展长度极限
        final int reachLimit = this.scenarioInfo.getClearRepairDistance();

        // 如果雷达扫射连一块石头都没发现，无功而返
        if (nearestDebrisId == null) {
            return null;
        }

        // 如果那块最近的石头刚好在切割齿轮距内，原地怒扫清除它
        boolean isWithinSawReach = (closestDebrisDist <= reachLimit);
        if (isWithinSawReach) {
            return new ActionClear(nearestDebrisId);
        }

        // 不在攻击范围内：呼叫 A* 尝试向这块石头的底座发动自杀式冲锋
        final Blockade debrisModel = (Blockade) this.worldInfo.getEntity(nearestDebrisId);
        EntityID baseNodeOfDebris = debrisModel.getPosition();

        this.pathPlanner.setFrom(currentBase);
        this.pathPlanner.setDestination(baseNodeOfDebris);
        this.pathPlanner.calc();
        final List<EntityID> assaultPath = this.pathPlanner.getResult();

        // 锁定撞击靶心坐标
        final int targetAimX = debrisModel.getX();
        final int targetAimY = debrisModel.getY();

        // 执行油门全开移动动作
        return new ActionMove(assaultPath, targetAimX, targetAimY);
    }

    private double computeDistance(EntityID blockadeIdToken) {
        // 重塑碎片实体外壳
        Blockade barricadeData = (Blockade) this.worldInfo.getEntity(blockadeIdToken);

        // 获取多边形的顶点矩阵图
        int[] apexPointsRaw = barricadeData.getApexes();
        List<Point2D> apexPointList = GeometryTools2D.vertexArrayToPoints(apexPointsRaw);

        // 闭环描线拉伸出边缘实线
        List<Line2D> edgeBoundary = GeometryTools2D.pointsToLines(apexPointList, true);

        // 取出自我中心点位
        Point2D myGravityPoint = this.getPoint();

        // 利用投射测距得出最短边距离
        Point2D hitIntercept = UESTCRouteSanitization.computeClosestPoint(edgeBoundary, myGravityPoint);
        double finalSeparation = GeometryTools2D.getDistance(myGravityPoint, hitIntercept);

        return finalSeparation;
    }
}
