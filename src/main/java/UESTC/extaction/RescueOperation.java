package UESTC.extaction;

import adf.core.agent.action.Action;
import adf.core.agent.action.ambulance.ActionRescue;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;

import java.util.ArrayList;
import java.util.List;

import rescuecore2.config.NoSuchConfigOptionException;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.worldmodel.EntityID;

import static rescuecore2.standard.entities.StandardEntityURN.BLOCKADE;

/**
 * RescueOperation 消防特勤破拆营救终端。
 * 物理意义：指挥部把人命关天的坐标交给我，我只管怎么把车开过去，怎么用液压剪把石头切开救人。
 * 核心法则：如果我流血快死了，立刻扔下工具逃生；否则，死磕到底。
 */
public class RescueOperation extends ExtAction {

    // ========================================================================
    // 战术总控台参数
    // ========================================================================

    // 致命伤势警戒线。超过这个值，再不休息就会死。
    private int criticalBloodLossThreshold;

    // 系统大限。比赛结束倒计时。
    private int apocalypticTimeLimit;

    // 战术长官指派的宏观坐标
    private EntityID assignedMissionObjective;

    // 底层履带式智能导航仪
    private PathPlanning tacticalChassisNavigator;

    /**
     * 消防特勤单兵装配中心。
     */
    public RescueOperation(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager mm, DevelopData dd) {
        super(ai, wi, si, mm, dd);
        this.assignedMissionObjective = null;

        // 领走伤情警戒线指标
        this.criticalBloodLossThreshold = dd.getInteger("ActionFireRescue.rest", 100);
        // 领走导航仪
        this.tacticalChassisNavigator = mm.getModule("UESTC.FB.ExtActionFireRescue.PathPlanning");
    }

    // ========================================================================
    // 基础辅助探测器 (视觉换皮：全部移到最上方)
    // ========================================================================

    /**
     * 避风港地基转换器。
     * 如果我快死了要找地方休息，把复杂的目标（比如一个人、一块石头）转化为它底下踩着的那块空地。
     */
    private EntityID transformTargetToSafeGround(EntityID rawObjectiveId) {
        StandardEntity inspectedEntity = this.worldInfo.getEntity(rawObjectiveId);

        // 目标蒸发了
        if (inspectedEntity == null) {
            return null;
        }

        // 1. 如果目标是个人，把他踩着的地板抽出来
        boolean isHumanTarget = inspectedEntity instanceof Human;
        if (isHumanTarget) {
            Human victimBody = (Human) inspectedEntity;
            boolean hasPosition = victimBody.isPositionDefined();

            if (hasPosition) {
                EntityID floorId = victimBody.getPosition();
                boolean isFloorSafe = this.worldInfo.getEntity(floorId) instanceof Area;
                if (isFloorSafe) {
                    return floorId;
                }
            }
        }
        // 2. 如果目标本来就是一块空地，完美
        else if (inspectedEntity instanceof Area) {
            return rawObjectiveId;
        }
        // 3. 如果目标是块拦路石，把它压着的马路抽出来
        else if (inspectedEntity.getStandardURN() == BLOCKADE) {
            Blockade giantRock = (Blockade) inspectedEntity;
            boolean isRockLocated = giantRock.isPositionDefined();

            if (isRockLocated) {
                return giantRock.getPosition();
            }
        }

        return null;
    }

    /**
     * 死亡倒计时预警系统。
     * 计算以我现在的受伤程度，还能不能活着撑到比赛结束。
     */
    private boolean isLethalThreatApproaching(Human myBody) {
        int currentHealthPoints = myBody.getHP();
        int currentBleedingRate = myBody.getDamage();

        // 满血，无须慌张
        boolean isHealthy = (currentHealthPoints == 0 || currentBleedingRate == 0);
        if (isHealthy) {
            return false;
        }

        // 寿命估算：血量 / 流血速度
        int estimatedSecondsLeft = (currentHealthPoints / currentBleedingRate);
        int extraSecond = ((currentHealthPoints % currentBleedingRate) != 0 ? 1 : 0);
        int totalSurvivalChronicle = estimatedSecondsLeft + extraSecond;

        // 确认世界末日时间
        if (this.apocalypticTimeLimit == -1) {
            try {
                this.apocalypticTimeLimit = this.scenarioInfo.getKernelTimesteps();
            } catch (NoSuchConfigOptionException e) {
                this.apocalypticTimeLimit = -1;
            }
        }

        // 宣判：1. 大动脉出血超标；2. 剩下的寿命等不到世界末日了
        boolean isBleedingTooFast = currentBleedingRate >= this.criticalBloodLossThreshold;
        boolean willDieBeforeEnd = (totalSurvivalChronicle + this.agentInfo.getTime()) < this.apocalypticTimeLimit;

        return isBleedingTooFast || willDieBeforeEnd;
    }

    /**
     * 核心营救物理动作推演器。
     * 将宏观的“救人”指令，翻译成方向盘和电锯的具体操作。
     */
    private Action executeTacticalRescueOperation(FireBrigade myTruck, PathPlanning navigator, EntityID objectiveId) {
        StandardEntity objectiveEntity = this.worldInfo.getEntity(objectiveId);

        if (objectiveEntity == null) {
            return null;
        }

        EntityID myGpsLocation = myTruck.getPosition();

        // --- 战术分支 A：目标是人类 ---
        boolean isTargetHuman = objectiveEntity instanceof Human;
        if (isTargetHuman) {
            Human EntrappedStateVictim = (Human) objectiveEntity;

            boolean isLocationUnknown = !EntrappedStateVictim.isPositionDefined();
            boolean isAlreadyDead = (EntrappedStateVictim.isHPDefined() && EntrappedStateVictim.getHP() == 0);

            // 查无此人，或者已是尸体一具，直接放弃
            if (isLocationUnknown || isAlreadyDead) {
                return null;
            }

            EntityID victimGpsLocation = EntrappedStateVictim.getPosition();

            // 物理距离鉴定：我跟他站在同一块地板上了吗？
            boolean isStandingTogether = (myGpsLocation.getValue() == victimGpsLocation.getValue());

            if (isStandingTogether) {
                // 如果人就在脚下，看他身上有没有压着石头
                boolean isCrushedByRubble = EntrappedStateVictim.isBuriednessDefined() && EntrappedStateVictim.getBuriedness() > 0;
                if (isCrushedByRubble) {
                    return new ActionRescue(EntrappedStateVictim); // 抡起铁锹开挖！
                }
            } else {
                // 人在远处，命令导航仪规划路线
                List<EntityID> routeToVictim = navigator.getResult(myGpsLocation, victimGpsLocation);

                boolean isRouteValid = (routeToVictim != null);
                if (isRouteValid) {
                    boolean hasSteps = (routeToVictim.size() > 0);
                    if (hasSteps) {
                        return new ActionMove(routeToVictim); // 踩下油门！
                    }
                }
            }
            return null;
        }

        // --- 战术分支 B：目标是拦路石 ---
        boolean isTargetBlockade = (objectiveEntity.getStandardURN() == BLOCKADE);
        if (isTargetBlockade) {
            Blockade giantRubble = (Blockade) objectiveEntity;
            if (giantRubble.isPositionDefined()) {
                // 把石头转换为它底下的马路
                objectiveEntity = this.worldInfo.getEntity(giantRubble.getPosition());
            }
        }

        // --- 战术分支 C：目标是空地 ---
        boolean isTargetArea = objectiveEntity instanceof Area;
        if (isTargetArea) {
            List<EntityID> routeToArea = navigator.getResult(myGpsLocation, objectiveEntity.getID());

            boolean isRouteValid = (routeToArea != null);
            if (isRouteValid) {
                boolean hasSteps = (routeToArea.size() > 0);
                if (hasSteps) {
                    return new ActionMove(routeToArea); // 开过去！
                }
            }
        }

        return null;
    }

    // ========================================================================
    // 核心物理动作演算大脑 (The Action Pipeline)
    // ========================================================================

    /**
     * 目标接收口。
     */
    @Override
    public ExtAction setTarget(EntityID targetId) {
        this.assignedMissionObjective = null;

        if (targetId != null) {
            StandardEntity entity = this.worldInfo.getEntity(targetId);
            boolean isValidTarget = (entity instanceof Human) || (entity instanceof Area);

            if (isValidTarget) {
                this.assignedMissionObjective = targetId;
                return this;
            }
        }
        return this;
    }

    /**
     * 战术中枢总调度。每秒钟决定生死与行动。
     */
    @Override
    public ExtAction calc() {
        this.result = null;
        FireBrigade mySelfTruck = (FireBrigade) this.agentInfo.me();

        // 生存危机判定
        boolean isDying = this.isLethalThreatApproaching(mySelfTruck);

        if (isDying) {
            // 如果快死了，把目标转化为一个安全区
            EntityID safeRefugeArea = this.transformTargetToSafeGround(this.assignedMissionObjective);

            boolean isSafeZoneFound = (safeRefugeArea != null);
            if (isSafeZoneFound) {
                // 逃命机动！
                this.result = this.executeTacticalRescueOperation(mySelfTruck, this.tacticalChassisNavigator, safeRefugeArea);
            }
        } else if (this.assignedMissionObjective != null) {
            // 健康状态，全力执行营救任务！
            this.result = this.executeTacticalRescueOperation(mySelfTruck, this.tacticalChassisNavigator, this.assignedMissionObjective);
        }

        return this;
    }

    // ========================================================================
    // 系统生命周期与状态更新
    // ========================================================================

    @Override
    public ExtAction updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        int updateCount = this.getCountUpdateInfo();
        if (updateCount >= 2) {
            return this;
        }
        this.tacticalChassisNavigator.updateInfo(messageManager);
        return this;
    }

    @Override
    public ExtAction preparate() {
        super.preparate();
        int prepCount = this.getCountPreparate();
        if (prepCount >= 2) {
            return this;
        }
        this.tacticalChassisNavigator.preparate();
        try {
            this.apocalypticTimeLimit = this.scenarioInfo.getKernelTimesteps();
        } catch (NoSuchConfigOptionException e) {
            this.apocalypticTimeLimit = -1;
        }
        return this;
    }

    @Override
    public ExtAction resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        int resumeCount = this.getCountResume();
        if (resumeCount >= 2) {
            return this;
        }
        this.tacticalChassisNavigator.resume(precomputeData);
        try {
            this.apocalypticTimeLimit = this.scenarioInfo.getKernelTimesteps();
        } catch (NoSuchConfigOptionException e) {
            this.apocalypticTimeLimit = -1;
        }
        return this;
    }

    @Override
    public ExtAction precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        int precompCount = this.getCountPrecompute();
        if (precompCount >= 2) {
            return this;
        }
        this.tacticalChassisNavigator.precompute(precomputeData);
        try {
            this.apocalypticTimeLimit = this.scenarioInfo.getKernelTimesteps();
        } catch (NoSuchConfigOptionException e) {
            this.apocalypticTimeLimit = -1;
        }
        return this;
    }
}
