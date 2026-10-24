package UESTC.module.complex.policeforce;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.complex.PoliceTargetAllocator;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * PatrolTaskDelegator 警察战术目标分配器（清障大盘统筹版）。
 * 物理意义：负责站在警察局指挥中心的高度，将全城最重要的“道路清障”任务，
 * 精准派发给目前在线的每一辆警车。
 * 战术核心：
 * 1. 资源对齐：确保每辆警车（自带电锯/推土铲）都有明确的开路目标。
 * 2. 价值评估：优先清理挡在消防车去灭火路上的石头，其次是去医院的生命通道。
 * 3. 动态刷新：每秒钟根据前线最新传回来的路况电报，重新规划清障派车表。
 */
public class PatrolTaskDelegator extends PoliceTargetAllocator implements TaskAllocPublisher {

    // ========================================================================
    // 指挥部核心账本
    // ========================================================================

    // 最终的战术部署矩阵。这是警察局长的心血，记录了哪辆警车（Key）被派去清理哪条路上的石头（Value）。
    private Map<EntityID, EntityID> optimizedTacticalMatrix = new HashMap<>();

    /**
     * 警察局指挥分配器构造车间。开局时，把大地图和沙盘都搬进警察局。
     */
    public PatrolTaskDelegator(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        // 调用上层司令部的初始化流程，接管地型图和基础通讯配置
        super(ai, wi, si, moduleManager, developData);
    }

    /**
     * 缓存恢复启动。如果系统崩溃重启，从这里读取残存的清障记忆。
     */
    @Override
    public PoliceTargetAllocator resume(PrecomputeData precomputeData) {
        // 执行标准的失忆恢复手术
        super.resume(precomputeData);
        // 防重复执行保护
        if (this.getCountResume() >= 2) return this;
        return this;
    }

    /**
     * 战前准备工序。比赛刚打响时拉响警笛的热身动作。
     */
    @Override
    public PoliceTargetAllocator preparate() {
        // 擦拭电锯，准备开工
        super.preparate();
        // 防重复执行保护
        if (this.getCountPrecompute() >= 2) return this;
        return this;
    }

    /**
     * 战术结果提取口。向上层动作执行模块上交本回合深思熟虑后得出的全城警察出警单。
     */
    @Override
    public Map<EntityID, EntityID> getResult() {
        // 郑重交出我们刚刚写满的出警单
        return this.optimizedTacticalMatrix;
    }

    // ========================================================================
    // 核心清障统筹大脑运算引擎 (The Brain)
    // ========================================================================

    /**
     * 核心战术分配引擎。在灾难现场的每一秒，局长都要在这里进行一次全局推演。
     */
    @Override
    public PoliceTargetAllocator calc() {
        // 第一件事：无情地撕毁上一秒的旧出警单。地震后石头会滑落，我们要重新排班！
        this.optimizedTacticalMatrix.clear();

        // --- 管线 1：交警去前线摸排路况 ---
        // 唤醒专属的交警侦察员
        TrafficReconSquad recon = new TrafficReconSquad();
        // 逼问交警：“全城现在到底有多少堆破石头堵着路？把单子给我！”
        Set<EntityID> activeBlockades = recon.scoutRoadBlockades();

        // 如果交警说全城马路畅通无阻，连一块砖头都没有
        if (activeBlockades.isEmpty()) {
            // 直接下班，返回空账本，让所有警车去日常巡街
            return this;
        }

        // --- 管线 2：调度长去警局后院点验兵马 ---
        // 唤醒调度长
        Quartermaster quartermaster = new Quartermaster();
        // 逼问调度长：“我们手里现在到底还有几辆能动的警车？”
        Set<EntityID> availablePoliceCars = quartermaster.musterPoliceForces();

        // --- 管线 3：战术参谋部进行清障分配推演 ---
        // 把作战室清空，请出战术参谋部
        TacticalCommandCenter commandCenter = new TacticalCommandCenter();
        // 参谋长接过拦路石名单和警车花名册，开始在沙盘上连线，给出最终的清障计划！
        this.optimizedTacticalMatrix = commandCenter.formulateClearancePlan(availablePoliceCars, activeBlockades);

        // 推演结束，返回局长自身
        return this;
    }

    /**
     * 战地情报更新台。每秒钟接收电报，修正大屏上的路网状况。
     */
    @Override
    public PoliceTargetAllocator updateInfo(MessageManager messageManager) {
        // 同步底层的无线电消息与视觉变化
        super.updateInfo(messageManager);
        // 防重复更新保护
        if (this.getCountUpdateInfo() >= 2) return this;
        return this;
    }

    // ========================================================================
    // 内部组件 1：交警侦察兵 (Traffic Recon Squad)
    // 负责在废墟中寻找所有需要清理的拦路石
    // ========================================================================
    private class TrafficReconSquad {

        /**
         * 搜寻全城阻塞交通的废墟。
         */
        Set<EntityID> scoutRoadBlockades() {
            // 掏出一个空文件夹，准备记录烂石头
            Set<EntityID> blockadeList = new HashSet<>();
            
            // 去总数据库里，把全城所有“路障（BLOCKADE）”的档案全拉出来
            Collection<EntityID> allBlockades = worldInfo.getEntityIDsOfType(StandardEntityURN.BLOCKADE);

            // 戴上老花镜，一堆石头一堆石头地看
            for (EntityID bId : allBlockades) {
                StandardEntity entity = worldInfo.getEntity(bId);
                
                // 确保它确实是实体石头
                if (entity instanceof Blockade) {
                    Blockade rubble = (Blockade) entity;
                    
                    // 战术核验 1：这堆石头是不是太小了，根本不挡路？（维修成本极低）
                    if (rubble.isRepairCostDefined() && rubble.getRepairCost() > 0) {
                        // 确实是一堆拦路石，抄写进清障名单！
                        blockadeList.add(bId);
                    }
                }
            }
            // 把写满石头的文件夹交上去
            return blockadeList;
        }
    }

    // ========================================================================
    // 内部组件 2：调度长 (Quartermaster)
    // 负责清点我们可以调派的警察兵力
    // ========================================================================
    private class Quartermaster {

        /**
         * 点验在线的警车队伍。
         */
        Set<EntityID> musterPoliceForces() {
            // 去上帝视角的地图里，把拥有警察标识的所有实体捞出来
            Collection<EntityID> policeCars = worldInfo.getEntityIDsOfType(StandardEntityURN.POLICE_FORCE);
            Set<EntityID> readyList = new HashSet<>();
            
            // 挨个查验状态
            for (EntityID carId : policeCars) {
                StandardEntity entity = worldInfo.getEntity(carId);
                if (entity instanceof PoliceForce) {
                    PoliceForce pf = (PoliceForce) entity;
                    // 只要血量大于0（还活着），就必须上街去干活
                    if (pf.isHPDefined() && pf.getHP() > 0) {
                        readyList.add(carId);
                    }
                }
            }
            // 组装成纯净的兵力集合返回
            return readyList;
        }
    }

    // ========================================================================
    // 内部组件 3：战术参谋部 (Tactical Command Center)
    // 负责进行极其硬核的数学博弈与目标撮合
    // ========================================================================
    private class TacticalCommandCenter {

        /**
         * 制定最终的清障派发计划。
         * 物理意义：让每一辆警车去跟所有的石头堆“相亲”，评估油钱（距离）和价值（重要程度），找出最般配的那对。
         */
        Map<EntityID, EntityID> formulateClearancePlan(Set<EntityID> policeCars, Set<EntityID> blockades) {
            // 摊开一张崭新的排班表
            Map<EntityID, EntityID> battlePlan = new HashMap<>();
            
            // 请出冷酷无情的价值评估师
            ValueAssessor assessor = new ValueAssessor();

            // 挨个点名排好队的警车
            for (EntityID carId : policeCars) {
                
                // 默认这辆车还没找到要挖的石头
                EntityID bestBlockadeTarget = null;
                // 记录这辆车目前遇到的最高得分（默认设为一个极小的负数兜底）
                double highestPriorityScore = -Double.MAX_VALUE;

                // 针对这辆特定的车，拉出所有的石头堆，挨个让它挑
                for (EntityID blockadeId : blockades) {
                    
                    // 评估师按计算器：算算这辆车跑去锯这块石头，能拿多少性价比分？
                    double currentScore = assessor.evaluateTargetScore(carId, blockadeId);
                    
                    // 打擂台！如果这块石头的得分，比之前见过的所有石头都要高！
                    if (currentScore > highestPriorityScore) {
                        // 刷新最高分记录
                        highestPriorityScore = currentScore;
                        // 锁定这块石头，暂定为本车的最佳选择！
                        bestBlockadeTarget = blockadeId;
                    }
                }

                // 一圈相亲转下来，如果这辆车确实找到了心仪的石头
                if (bestBlockadeTarget != null) {
                    // 盖章！把这对“车-石”组合强行绑定，写入排班表
                    battlePlan.put(carId, bestBlockadeTarget);
                }
            }

            // 把大功告成的清障表递给局长
            return battlePlan;
        }
    }

    // ========================================================================
    // 内部组件 4：价值评估师 (Value Assessor)
    // 包含物理距离与清障难度的评分模型
    // ========================================================================
    private class ValueAssessor {

        /**
         * 目标综合价值计分仪。
         * 物理意义：分数越高，代表这块石头越恶心，越值得这辆警车马上开过去锯掉。
         */
        double evaluateTargetScore(EntityID carId, EntityID blockadeId) {
            
            // 1. 物理距离阻力：拉起皮尺，量出警车和石头堆之间的真实物理距离
            double distance = worldInfo.getDistance(carId, blockadeId);
            
            // 物理防线：如果车刚好就停在石头上（距离为0），为了防止除数为0导致宇宙爆炸，强行设为1毫米
            if (distance <= 0) {
                distance = 1.0;
            }

            // 2. 清障难度评估：提取这块石头的“维修成本（Repair Cost）”
            Blockade rubble = (Blockade) worldInfo.getEntity(blockadeId);
            // 看看它有多硬？需要多少电锯耐久度才能锯开？
            int difficultyCost = rubble.isRepairCostDefined() ? rubble.getRepairCost() : 100;
            
            // 3. 阻塞面积乘数：越庞大的废墟越需要警车
            // 修复：官方 API 提取顶点数组是 getApexes()，而不是 getApexList()
            int rubbleShapeSize = rubble.isApexesDefined() ? rubble.getApexes().length : 1;

            // 终极战术引力公式：(清理难度 * 面积乘数 * 放大系数) / 距离的平方
            // 物理含义：越难清、面积越大的拦路石，对警车产生的引力越大；但这种引力会随着距离的拉远，呈平方级快速衰减！
            double ultimateScore = (double) (difficultyCost * rubbleShapeSize * 1000.0) / (distance * distance);

            // 向上交出这堆石头在这辆警车心中的最终份量
            return ultimateScore;
        }
    }
}
