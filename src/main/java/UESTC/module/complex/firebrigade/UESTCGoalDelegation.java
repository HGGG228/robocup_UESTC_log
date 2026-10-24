package UESTC.module.complex.firebrigade;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.complex.FireTargetAllocator;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * UESTCGoalDelegation 消防战术目标分配器（大盘统筹版）。
 * 物理意义：负责站在上帝视角（通常是消防指挥中心），将全城最紧迫的火灾建筑，
 * 精准分配给目前在线的每一辆消防车，确保“车尽其用，火无遗漏”。
 * 战术核心：
 * 1. 资源对齐：确保每一辆消防车都有一个明确的奔赴目标，杜绝摸鱼。
 * 2. 价值评估：距离越近、火势越猛且尚未烧塌（Fieryness < 4）的建筑，救援性价比越高。
 * 3. 动态刷新：每秒钟根据最新的火情电报，推翻重来，重新校准派车计划。
 */
public class UESTCGoalDelegation extends FireTargetAllocator {

    // ========================================================================
    // 指挥部核心账本
    // ========================================================================

    // 最终的战术部署矩阵。这是指挥部的心血，记录了哪辆消防车（Key）被派去扑灭哪栋楼（Value）。
    private Map<EntityID, EntityID> optimizedTacticalMatrix = new HashMap<>();

    /**
     * 指挥分配器构造车间。在开局时，把大地图和沙盘都搬进指挥部。
     */
    public UESTCGoalDelegation(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        // 调用上层司令部的初始化流程，把地型图（wi）、剧本（si）等都接管过来
        super(ai, wi, si, moduleManager, developData);
    }

    /**
     * 缓存恢复启动。如果系统崩溃重启，从这里读取残存的记忆。
     */
    @Override
    public FireTargetAllocator resume(PrecomputeData precomputeData) {
        // 执行标准的失忆恢复手术
        super.resume(precomputeData);
        return this;
    }

    /**
     * 战前准备工序。比赛刚打响时的热身动作。
     */
    @Override
    public FireTargetAllocator preparate() {
        // 擦拭机器，准备开工
        super.preparate();
        return this;
    }

    /**
     * 战术结果提取口。向上层动作执行模块上交本回合深思熟虑后得出的全城消防派车单。
     */
    @Override
    public Map<EntityID, EntityID> getResult() {
        // 郑重交出我们刚刚写满的派车单
        return this.optimizedTacticalMatrix;
    }

    // ========================================================================
    // 核心统筹大脑运算引擎 (The Brain)
    // ========================================================================

    /**
     * 核心战术分配引擎。在灾难现场的每一秒，指挥官都要在这里进行一次全局推演。
     */
    @Override
    public FireTargetAllocator calc() {
        // 第一件事：无情地撕毁上一秒的旧派车单，战场瞬息万变，我们要重新排兵布阵！
        this.optimizedTacticalMatrix.clear();

        // --- 管线 1：侦察兵去前线摸排火情 ---
        // 唤醒专属的战地侦察兵
        ReconnaissanceSquad recon = new ReconnaissanceSquad();
        // 逼问侦察兵：“全城现在到底有几栋楼还在冒火？把名单给我！”
        Set<EntityID> activeFireZones = recon.scoutBurningBuildings();

        // 如果侦察兵说全城太平，连一丁点火星子都没有了
        if (activeFireZones.isEmpty()) {
            // 直接下班，返回空账本，让所有消防车原地待命休息
            return this;
        }

        // --- 管线 2：军需官去兵营点验兵马 ---
        // 唤醒军需官
        Quartermaster quartermaster = new Quartermaster();
        // 逼问军需官：“我们手里现在到底还有几辆能喘气的消防车？”
        Set<EntityID> availableTrucks = quartermaster.musterFireBrigades();

        // --- 管线 3：战术参谋部进行兵棋推演 ---
        // 把作战室清空，请出战术参谋部
        TacticalCommandCenter commandCenter = new TacticalCommandCenter();
        // 参谋长接过火场名单和兵力花名册，开始在沙盘上连线，给出最终的作战计划！
        this.optimizedTacticalMatrix = commandCenter.formulateBattlePlan(availableTrucks, activeFireZones);

        // 推演结束，返回指挥官自身
        return this;
    }

    /**
     * 战地情报更新台。
     */
    @Override
    public FireTargetAllocator updateInfo(MessageManager messageManager) {
        // 查收来自四面八方的电报，在世界地图上把着火的楼标红
        super.updateInfo(messageManager);
        return this;
    }

    // ========================================================================
    // 内部组件 1：战地侦察兵 (Reconnaissance Squad)
    // 负责在废墟中寻找值得抢救的火场
    // ========================================================================
    private class ReconnaissanceSquad {

        /**
         * 搜寻全城的高危火场。
         */
        Set<EntityID> scoutBurningBuildings() {
            // 掏出一个空文件夹，准备记录火场
            Set<EntityID> burningList = new HashSet<>();
            
            // 去总数据库里，把全城所有的建筑档案全拉出来
            Collection<EntityID> allBuildings = worldInfo.getEntityIDsOfType(StandardEntityURN.BUILDING);

            // 戴上老花镜，一栋楼一栋楼地看
            for (EntityID bldId : allBuildings) {
                StandardEntity entity = worldInfo.getEntity(bldId);
                
                // 确保它确实是个实体建筑
                if (entity instanceof Building) {
                    Building bld = (Building) entity;
                    
                    // 战术核验 1：这栋楼现在燃起来了吗？
                    if (bld.isOnFire()) {
                        // 战术核验 2：它是不是已经烧成一地黑灰了？（Fieryness 等于 4 就是彻底没救的废墟）
                        if (bld.getFieryness() < 4) {
                            // 既着了火，又还有抢救的价值，立刻抄写进高危火场名单！
                            burningList.add(bldId);
                        }
                    }
                }
            }
            // 把写满危楼的文件夹交上去
            return burningList;
        }
    }

    // ========================================================================
    // 内部组件 2：军需官 (Quartermaster)
    // 负责清点我们可以调派的兵力
    // ========================================================================
    private class Quartermaster {

        /**
         * 点验在线的消防车队伍。
         */
        Set<EntityID> musterFireBrigades() {
            // 去上帝视角的地图里，把拥有消防车标识的所有实体捞出来
            Collection<EntityID> brigades = worldInfo.getEntityIDsOfType(StandardEntityURN.FIRE_BRIGADE);
            // 组装成纯净的兵力集合返回
            return new HashSet<>(brigades);
        }
    }

    // ========================================================================
    // 内部组件 3：战术参谋部 (Tactical Command Center)
    // 负责进行极其硬核的数学博弈与目标撮合
    // ========================================================================
    private class TacticalCommandCenter {

        /**
         * 制定最终的作战派发计划。
         * 物理意义：让每一辆车去跟所有的火场“相亲”，评估彩礼（代价）和嫁妆（价值），找出最般配的那对。
         */
        Map<EntityID, EntityID> formulateBattlePlan(Set<EntityID> trucks, Set<EntityID> fires) {
            // 摊开一张崭新的排班表
            Map<EntityID, EntityID> battlePlan = new HashMap<>();
            
            // 请出冷酷无情的价值评估师
            ValueAssessor assessor = new ValueAssessor();

            // 挨个点名排好队的消防车
            for (EntityID truckId : trucks) {
                
                // 默认这辆车还没找到目标
                EntityID bestFireTarget = null;
                // 记录这辆车目前遇到的最高得分（默认设为一个极小的负数兜底）
                double highestPriorityScore = -Double.MAX_VALUE;

                // 针对这辆特定的车，拉出所有的火场，挨个让它挑
                for (EntityID fireId : fires) {
                    
                    // 评估师按计算器：算算这辆车跑去灭这个火，能拿多少性价比分？
                    double currentScore = assessor.evaluateTargetScore(truckId, fireId);
                    
                    // 打擂台！如果这个火场的得分，比之前见过的所有火场都要高！
                    if (currentScore > highestPriorityScore) {
                        // 刷新最高分记录
                        highestPriorityScore = currentScore;
                        // 锁定这栋楼，暂定为本车的最佳选择！
                        bestFireTarget = fireId;
                    }
                }

                // 一圈相亲转下来，如果这辆车确实找到了心仪的火场
                if (bestFireTarget != null) {
                    // 盖章！把这对“车-火”组合强行绑定，写入排班表
                    battlePlan.put(truckId, bestFireTarget);
                }
            }

            // 把大功告成的排班表递给指挥官
            return battlePlan;
        }
    }

    // ========================================================================
    // 内部组件 4：价值评估师 (Value Assessor)
    // 包含物理距离与热力学损失的评分模型
    // ========================================================================
    private class ValueAssessor {

        /**
         * 目标综合价值计分仪。
         * 物理意义：分数越高，代表这栋楼越急迫，越值得这辆车跑过去救。
         */
        double evaluateTargetScore(EntityID truckId, EntityID buildingId) {
            
            // 1. 物理距离阻力：拉起皮尺，量出车和火场之间的真实物理距离
            double distance = worldInfo.getDistance(truckId, buildingId);
            
            // 物理防线：如果车刚好就停在火场里面（距离为0），为了防止除数为0导致宇宙爆炸，强行设为1毫米
            if (distance <= 0) {
                distance = 1.0;
            }

            // 2. 火情烈度诱惑：提取这栋建筑目前的火势等级
            Building bld = (Building) worldInfo.getEntity(buildingId);
            // 看看它烧到几级了？（1冒烟，2小火，3大火）
            int fieryness = bld.isFierynessDefined() ? bld.getFieryness() : 1;
            
            // 3. 楼宇体积乘数：越大的楼越值钱，不能让大商场被烧了去救小茅房
            int buildingArea = bld.isGroundAreaDefined() ? bld.getGroundArea() : 100;

            // 终极战术引力公式：(火势烈度 * 建筑面积 * 百万级放大系数) / 距离的平方
            // 物理含义：火势越大、面积越大的楼，对消防车产生的引力越大；但这种引力会随着距离的拉远，呈平方级快速衰减！
            double ultimateScore = (double) (fieryness * buildingArea * 10000.0) / (distance * distance);

            // 向上交出这栋楼在这辆车心中的最终份量
            return ultimateScore;
        }
    }
}
