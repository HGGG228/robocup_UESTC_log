package UESTC.extaction;

import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.Refuge;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.worldmodel.EntityID;

/**
 * 灾难响应战术机动模块 (Tactical Shift System)
 * 背景设定：城市在强震后陷入瘫痪，道路被建筑残骸阻断。
 * 本模块负责智能体在执行转移任务时的生存状态监控、避难所回撤决策，
 * 以及在废墟中移动受阻时呼叫消防重装部队(FireBrigade)的调度逻辑。
 */
public class TacticalDisplacement extends ExtAction {

    // --- 核心子系统 ---
    private final PathPlanning navigationModule;
    private final Clustering entrapmentDetector;

    // --- 灾难环境与生存阈值 ---
    private final int criticalDamageThreshold;
    private int simulationCycles = -1;
    private EntityID designatedZone = null;

    // --- 呼叫救援与阻塞追踪状态 ---
    private boolean requiresExtractionTeam = false;
    private final int extractionCooldownDuration;
    private int extractionCooldownLock = -1;

    private Action previousAction = null;
    private Point2D previousCoordinate = null;

    public TacticalDisplacement(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager mm, DevelopData dd) {
        super(ai, wi, si, mm, dd);
        this.navigationModule = moduleManager.getModule("UESTC.FB.ExtActionMove.PathPlanning");
        this.entrapmentDetector = moduleManager.getModule("UESTC.FB.ExtActionMove.StuckHumans");
        this.criticalDamageThreshold = 100;
        this.extractionCooldownDuration = 5; // 呼叫消防车破障的冷却周期
    }

    // ========================================================================
    // [战术指令输入区]
    // ========================================================================

    @Override
    public ExtAction setTarget(EntityID id) {
        // 使用 Java 8 Optional 流水线过滤非法区域，彻底消除原生 if 嵌套
        this.designatedZone = Optional.ofNullable(id)
                .map(this.worldInfo::getEntity)
                .filter(Area.class::isInstance)
                .map(StandardEntity::getID)
                .orElse(this.designatedZone);
        return this;
    }

    // ========================================================================
    // [核心机动推演区] (通过拆分和 AOP 思想重构)
    // ========================================================================

    @Override
    public ExtAction calc() {
        this.result = null;
        StandardEntity operator = this.agentInfo.me();

        // 优先级 1：生命体征处于危机状态，强行中止任务，启动避难所回撤协议
        if (this.evaluateCriticalTrauma(operator)) {
            Action retreatAction = this.planEvacuationRoute(operator, this.designatedZone);
            if (retreatAction != null) {
                return this.commitAction(retreatAction);
            }
        }

        // 优先级 2：体征平稳，若未指派目标区域则挂机待命
        if (this.designatedZone == null) {
            return this;
        }

        // 优先级 3：执行正常的战术机动寻路
        this.navigationModule.setFrom(this.worldInfo.getPosition(operator.getID()).getID());
        this.navigationModule.setDestination(this.designatedZone);
        List<EntityID> route = this.navigationModule.calc().getResult();

        if (route != null && !route.isEmpty()) {
            return this.commitAction(new ActionMove(route));
        }

        // 寻路失败，记录静态状态
        return this.commitAction(null);
    }

    /**
     * 动作提交流水线（拦截器）：统一处理移动阻塞判定与状态快照保存
     * 彻底消除了原版中在多个 if 分支里冗余的 isStuckMoving 判断和赋值代码。
     */
    private ExtAction commitAction(Action actionToExecute) {
        this.result = actionToExecute;

        // 判定在废墟中是否发生“原地踏步”的死锁，若是则拉响请求消防车开路的警报
        if (actionToExecute != null && this.detectMovementDeadlock(actionToExecute)) {
            this.requiresExtractionTeam = true;
        }

        // 留存环境快照，供下一仿真帧对比
        this.previousAction = this.result;
        this.previousCoordinate = new Point2D(this.agentInfo.getX(), this.agentInfo.getY());
        return this;
    }

    // ========================================================================
    // [启发式状态评估算法]
    // ========================================================================

    /**
     * 伤情监测：评估当前出血/烧伤速率是否会导致在仿真周期结束前阵亡
     */
    private boolean evaluateCriticalTrauma(StandardEntity agent) {
        if (!(agent instanceof Human)) return false;

        Human human = (Human) agent;
        int stamina = human.getHP();
        int trauma = human.getDamage();

        if (stamina <= 0 || trauma <= 0) return false;

        // 数学等价替换：(stamina / trauma) + 取模判断 -> 向上取整的精确计算法
        int projectedSurvivalChronicle = (int) Math.ceil((double) stamina / trauma);

        boolean isLethalTrauma = trauma > this.criticalDamageThreshold;
        boolean willPerishBeforeEnd = (projectedSurvivalChronicle + this.agentInfo.getTime()) < this.simulationCycles;

        return isLethalTrauma || willPerishBeforeEnd;
    }

    /**
     * 阻塞侦测网络：通过布尔代数化简了原先冗长的 4 级 if 嵌套防御语句
     */
    private boolean detectMovementDeadlock(Action currentAction) {
        // 判断条件：当前操作为移动且历史操作也为移动，未被大规模路障物理掩埋，且空间坐标及路线高度重合
        return (currentAction instanceof ActionMove) &&
               (this.previousAction instanceof ActionMove) &&
               !this.isEntangledInDebris() &&
               new Point2D(this.agentInfo.getX(), this.agentInfo.getY()).equals(this.previousCoordinate) &&
               ((ActionMove) currentAction).getPath().equals(((ActionMove) this.previousAction).getPath());
    }

    private boolean isEntangledInDebris() {
        return this.entrapmentDetector.calc().getClusterIndex(this.agentInfo.getID()) == 0;
    }

    /**
     * 避难所路由算法：在周围坍塌的环境中遍历寻找一条安全的求生通道
     */
    private Action planEvacuationRoute(StandardEntity agent, EntityID ultimateObjective) {
        StandardEntity currentLoc = this.worldInfo.getPosition(agent.getID());

        // 已抵达安全屋
        if (currentLoc instanceof Refuge) {
            return new ActionRest();
        }

        Collection<EntityID> safeZones = this.worldInfo.getEntityIDsOfType(REFUGE);
        int priorZoneCount = safeZones.size();
        List<EntityID> survivalRoute = null;

        // 迭代寻找最优避难所：此处的控制流逻辑完全遵守 ADF 寻路模块的底层状态依赖
        while (!safeZones.isEmpty()) {
            this.navigationModule.setFrom(currentLoc.getID());
            this.navigationModule.setDestination(safeZones);
            List<EntityID> routeToZone = this.navigationModule.calc().getResult();

            if (routeToZone != null && !routeToZone.isEmpty()) {
                if (ultimateObjective == null) {
                    survivalRoute = routeToZone;
                    break;
                }

                // 检验避难所到终极目标是否有路可走
                EntityID intermediateSafeZone = routeToZone.get(routeToZone.size() - 1);
                this.navigationModule.setFrom(intermediateSafeZone);
                this.navigationModule.setDestination(ultimateObjective);
                List<EntityID> routeToObjective = this.navigationModule.calc().getResult();

                if (routeToObjective != null && !routeToObjective.isEmpty()) {
                    survivalRoute = routeToZone;
                    break;
                }

                // 此路不通，剔除该避难所并重试
                safeZones.remove(intermediateSafeZone);
                if (priorZoneCount == safeZones.size()) break; // 防死循环安全阀
                priorZoneCount = safeZones.size();

            } else {
                break; // 周围已被废墟彻底堵死
            }
        }

        return survivalRoute != null ? new ActionMove(survivalRoute) : null;
    }

    // ========================================================================
    // [灾难仿真引擎生命周期同步区]
    // ========================================================================

    @Override
    public ExtAction precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        return this;
    }

    @Override
    public ExtAction resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        // 逆向重构了控制流，将 count > 1 的判断翻转为 <= 1 时的行为
        if (this.getCountResume() <= 1) {
            this.preparate();
        }
        return this;
    }

    @Override
    public ExtAction preparate() {
        super.preparate();
        if (this.getCountPreparate() <= 1) {
            this.navigationModule.preparate();
            this.entrapmentDetector.preparate();
            try {
                this.simulationCycles = this.scenarioInfo.getKernelTimesteps();
            } catch (Exception e) {
                this.simulationCycles = -1;
                e.printStackTrace();
            }
        }
        return this;
    }

    @Override
    public ExtAction updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() <= 1) {
            this.navigationModule.updateInfo(messageManager);
            this.entrapmentDetector.updateInfo(messageManager);

            // 处理无线电通信网络受损导致的信息延迟：执行对消防大队的呼叫冷却判定
            if (this.requiresExtractionTeam && this.extractionCooldownLock > this.agentInfo.getTime()) {
                this.extractionCooldownLock = this.agentInfo.getTime() + this.extractionCooldownDuration;
                this.requiresExtractionTeam = false;
            }
        }
        return this;
    }
}
