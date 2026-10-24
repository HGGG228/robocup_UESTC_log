package UESTC.centralized;

import rescuecore2.worldmodel.EntityID;
import java.util.*;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.agent.communication.standard.bundle.centralized.*;
import rescuecore2.standard.entities.*;
import adf.core.component.extaction.ExtAction;
import adf.core.agent.action.common.*;
import adf.core.agent.info.*;
import static rescuecore2.standard.entities.StandardEntityURN.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.communication.MessageManager;
import adf.core.component.centralized.CommandExecutor;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.agent.action.Action;
import adf.core.agent.develop.DevelopData;

public class AmbulanceDispatcher extends CommandExecutor<CommandAmbulance> {
    // 声明救护车中央指令执行器类，继承并泛型化标准指令执行组件。

    // ========================================================================
    // [SECTION 1] 核心任务推演与指派中枢 (Core Task Calculus & Assignment)
    // ========================================================================

    @Override
    public CommandExecutor calc() {
        // 覆写本执行器内核动作推演算法的主驱动中枢口方法。

        this.result = null;
        // 发车前强行销毁擦除上一步周期运算滞留遗留下的动作演算废料结果集。

        if (this.assignedObjective == null) {
            // 行前安全防御排爆，探查目前是否处于空无目标的盲目状态。
            return this;
            // 未承载实体目标则判定丧失演算意义停止逻辑推敲交出系统锁。
        }
        // 作战对象挂载实体合法存在确认。

        if (this.instructionCategory == UNDEFINED_INSTRUCTION) {
            // 二度查验分析当前持有的指令类别标识是否依然陷于未能明朗的缺省未定态。
            return this;
            // 当不知需作何举措时拒绝盲动盲行强行回撤打断推导进程。
        }
        // 行为准则类别参数明确明了。

        if (this.agentInfo.someoneOnBoard() != null) {
            // 调取探针核实检测当下救护车自身内部载员货舱是否已经塞填装载着获救受难民众。
            return this;
            // 秉持绝不中途丢弃伤员的铁律，于运载途中决不承接任何外部新派杂务强行截停返回。
        }
        // 运输仓空闲且允许受领任务调遣的状态验证全线放行。

        this.emergencyExtAction.setTarget(this.assignedObjective);
        // 将已锚定的解救对象移花接木定向投喂给麾下的扩展动作执行模块子级单元。

        this.emergencyExtAction.calc();
        // 擂动动作执行模块子级引擎的独立算力核心去进行其专属域内的微观战术推演解算。

        this.result = this.emergencyExtAction.getAction();
        // 截获拾取子层引擎推导完备的具体输出策略动作集并将其扶正挂载为全系统最终执行级结果。

        return this;
        // 中央指令动作逻辑拆解分发与终局行动决策研判全面收官并输出结果引用。
    }
    // 中央救护任务算力运算与响应部署总控进程谢幕。

    @Override
    public CommandExecutor setCommand(CommandAmbulance command) {
        // 重写中央指令设置关卡，负责解析中央信道降维投送来的具体动作命令组。

        final EntityID egoIdentifier = this.agentInfo.getID();
        // 抓取自身智能体的独特硬件标牌作为比对参照物。

        if (!command.isToIDDefined()) {
            // 核实下发的指令包裹上是否印刷了明确清晰的收件人标识码。
            return this;
            // 若发信人未指定明确的执行兵卒，则抛弃该公共废弃指令回归待命。
        }
        // 指令定向接收人完整性核验过关。

        if (!egoIdentifier.equals(command.getToID())) {
            // 交叉核对当前单位自身标识与包裹上印制的收件人编码是否精准吻合一致。
            return this;
            // 判定此指令实为误投发送至别家单位的信件，故置之不理。
        }
        // 专属派件身份精准匹配确权成功。

        this.assignedObjective = command.getTargetID();
        // 正式剥离包裹，将指令中内附的具体营救对象印刻入本组件状态库。

        this.instructionCategory = command.getAction();
        // 将指令包裹中要求的动作范式规范代码抽离记载于内部记录字典。

        this.dispatchingAuthority = command.getSenderID();
        // 将该道命令的原生发信指挥方ID登记在案以备后效查询审计。

        return this;
        // 指令装载接管事务宣告办结转交本体索引引线。
    }
    // 中央总台调遣指令拆包加载操作封闭。

    public AmbulanceDispatcher(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager mm, DevelopData dd) {
        // 构筑暴露于最外层接受仿真沙盒顶层设计初始化系统主总线环境大礼包的装配车间大门。

        super(ai, wi, si, mm, dd);
        // 将系统配发的所有场景级世界参数宏包全盘提交给承袭的基准父组件实施底面管线铺建维护。

        this.instructionCategory = UNDEFINED_INSTRUCTION;
        // 于开局出厂预设阶段向指令记录指针写入安全合规的空置无任务标记状态防伪码。

        this.emergencyExtAction = mm.getExtAction("UESTC.AT.CommandExecutorAmbulance.ExtActionTransport");
        // 沿用强制命名的魔法字符串向中央注册表搜寻绑架专为本救护流程裁切的扩展动作分析处理套件下属板卡。

        // 【修复编译错误：此处已同步修改为 routingModule】
        this.routingModule = mm.getModule("UESTC.AT.CommandExecutorAmbulance.PathPlanning");
        // 通过系统预留接口强行征调获取为载具移动提供空间位移智囊辅助判定的路桥规划导航指引仪组件载体。
    }
    // 救护体系中心控制指令处理架构主体类构建拼接挂靠工程完结。

    // ========================================================================
    // [SECTION 2] 逆向生命周期钩子管理 (Reverse Lifecycle Management)
    // ========================================================================

    @Override
    public CommandExecutor updateInfo(MessageManager mm) {
        // 重写全局信息更迭监听管道，接受总线派发的通信报文与外部异动情报汇编。

        super.updateInfo(mm);
        // 第一优先级委派超类架构消解统合通信渠道公共层的标准化拆包解密事务。

        if (this.getCountUpdateInfo() >= 2) {
            // 设置同步更新指令拦截滤网，阻止在单一仿真毫秒刻度内触发的非线性多重刷新。
            return this;
            // 拒止无效更新事件以避免状态雪崩，撤回原指针。
        }
        // 更新期并发刷新重影重组抑制安防审查通过。

        this.emergencyExtAction.updateInfo(mm);
        // 通传环境突变态势至紧急行为子模块，责令其刷新内部情景感知缓冲池。

        this.routingModule.updateInfo(mm);
        // 指引网域导航系统接纳全图变异特征点并重建空间路线避障拓扑连通性能。

        if (this.assignedObjective != null && this.evaluateCommandFulfillment()) {
            // 执行条件复合验证：确保当前存在有效任务且通过内部评估已确诊任务达标闭环。

            mm.addMessage(new MessageReport(true, true, true, this.assignedObjective));
            // 向中央指挥通信中枢打包投递一份宣告阶段性救援任务圆满建功的标准通信捷报。

            this.assignedObjective = null;
            // 收工清场，将内部记录目标导向锚点废除置空，释放系统资源追踪占位。

            this.instructionCategory = UNDEFINED_INSTRUCTION;
            // 抹除当前指令类型记忆槽位，强制回退至默认的安全闲置等候未知期属性。

            this.dispatchingAuthority = null;
            // 注销发布该指令的高层指挥官的记忆关联引用信息。
        }
        // 中央任务全流程生命收束并反馈总部的处理流收尾。

        return this;
        // 当前游戏帧次的所有事件响应通报收纳处理完美歇工交权。
    }
    // 外界态势更新感知及中枢回执报告流程告结。

    @Override
    public CommandExecutor preparate() {
        // 重写运行准备阶段钩子，服务于规避无离线数据库介入下的实时冷启动场景。

        super.preparate();
        // 遵从架构协议向基底对象报备，协同处理开局常量数据抓取初始化作业。

        if (this.getCountPreparate() >= 2) {
            // 布设频次校验关卡，预防首帧循环周期内的高密度无效重复演算开销。
            return this;
            // 实施短路防护截断异常调度意图返回自身对象级索引引用。
        }
        // 强实时冷发车资源调度请求频限排查合格。

        this.emergencyExtAction.preparate();
        // 点对点透传就绪指令告知下游动作控制单元执行其所属逻辑沙盘实时搭建。

        this.routingModule.preparate();
        // 要求下挂路径规划支援模块启动其实时计算资源测绘扫描与空间划界。

        return this;
        // 当局系统执行引擎的开场环境探测统筹搭建任务通关交付权柄。
    }
    // 无预置底数据环境实盘动态就绪演算程序停机。

    @Override
    public CommandExecutor resume(PrecomputeData pd) {
        // 重写恢复期生命周期接口，承担系统唤醒时的持久化缓存数据反序列化任务。

        super.resume(pd);
        // 通道上抛接力，优先允许基类完成共通环境属性的热加载复原动作。

        if (this.getCountResume() >= 2) {
            // 同步架设调用次数锁屏屏障，以防止重入异常导致内存覆写污染。
            return this;
            // 阻断非法多线程复苏指派，原样递交组件管理上下文指针。
        }
        // 系统热苏醒防重复灌注保护规则审计完成无虞。

        this.emergencyExtAction.resume(pd);
        // 将恢复权柄下放至扩展动作构件，令其重构先前保存的行动上下文环境。

        this.routingModule.resume(pd);
        // 同源指派路由规划引擎实施历史拓扑缓存字典的还原提取操作工作流。

        return this;
        // 复苏期全链路反序列化唤醒流水线顺利打通运转收官。
    }
    // 算法模块脱机状态复苏装配执行逻辑闭环。

    @Override
    public CommandExecutor precompute(PrecomputeData pd) {
        // 重写预计算生命周期阶段方法，处理离线状态下的环境资源预配置。

        super.precompute(pd);
        // 向上调用父类实现，确保底层中央执行器的基础管线成功奠基。

        if (this.getCountPrecompute() >= 2) {
            // 建立防并发安全卡口，侦测并拦截针对预计算接口的重复冗余调用。
            return this;
            // 若判定为超频指令，则剥离执行链并交还当前执行实例环境句柄。
        }
        // 离线预计算作业并发安全与时序控制阀门检测机制通过。

        this.emergencyExtAction.precompute(pd);
        // 向下分发预计算指令，驱使紧急动作模块完成自身的离线数据载入。

        this.routingModule.precompute(pd);
        // 继续下沉指令，指示路径导航模块执行其内部图论网格的初始化建构。

        return this;
        // 本级联控组件离线装配配置链条全面定稿，释放同步执行锁。
    }
    // 组件系统预计算装填周期操作声明结束。

    // ========================================================================
    // [SECTION 3] 内部状态评估与辅助判定算子 (Internal State Evaluation)
    // ========================================================================

    private boolean evaluateCommandFulfillment() {
        // 声明私有评估算子，用于核验当前被指派的救援指令是否已达成终结条件。

        if (this.evaluateIdleRequirement()) {
            // 优先调用挂机保护期评估函数。
            return false;
            // 若处于静默期，则一律视作任务未开始，拒绝判定为完成。
        }
        // 静默期状态屏障验证通过。

        final Set<EntityID> environmentalMutations = this.worldInfo.getChanged().getChangedEntities();
        // 访问世界模型变更池，提取当前周期内发生状态变异的全部实体标识集合。

        if (!environmentalMutations.contains(this.assignedObjective)) {
            // 检查目标实体是否包含在本周期的状态变异集合中。
            return false;
            // 若目标未发生任何状态变动，则意味着救援行为尚未取得进展。
        }
        // 目标状态活跃度监听校验完成。

        final StandardEntity objectiveEntity = this.worldInfo.getEntity(this.assignedObjective);
        // 利用全局标识符自世界模型中反向映射出目标的具体物理实体实例。

        if (!Human.class.isInstance(objectiveEntity)) {
            // 校验该目标对象是否隶属于具备生命特征的 Human 基类。
            return true;
            // 若目标并非人类实体（如发生异常），则强制宣告该救援任务终结。
        }
        // 目标实体类型合法性多态验证结束。

        final Human humanoidTarget = (Human) objectiveEntity;
        // 执行向下类型转换，将泛型实体复原为具备生理状态接口的 Human 对象。

        final boolean burialStatus = humanoidTarget.isBuriednessDefined() && humanoidTarget.getBuriedness() > 0;
        // 提取目标的埋压属性定义，并量化评估其埋压深度是否大于零。

        if (humanoidTarget.getStandardURN() != CIVILIAN) {
            // 检查该目标人员的枚举类型是否非平民（即同为救援人员）。
            return !burialStatus;
            // 针对受困的救援人员，仅需解除其埋压状态即视为救援任务完成。
        }
        // 针对非平民受灾目标的特殊判定逻辑流转完毕。

        final boolean injuryStatus = humanoidTarget.isDamageDefined() && humanoidTarget.getDamage() > 0;
        // 提取平民目标的伤害值属性，判定其是否处于持续掉血的受伤状态。

        final StandardEntity currentLocationEntity = this.worldInfo.getEntity(humanoidTarget.getPosition());
        // 提取该平民当前所处物理坐标的承载面域实体对象。

        final boolean refugeContainmentStatus = currentLocationEntity.getStandardURN() == REFUGE;
        // 校验平民当前所在的承载面域是否为绝对安全的避难所建筑。

        return !burialStatus && !injuryStatus || refugeContainmentStatus;
        // 综合评估：当平民既无埋压又无伤害，或已成功转移至避难所内，即宣告任务竣工。
    }
    // 中央指令完成度深度评估机制执行终了。

    private boolean evaluateActionEmptiness(Action evaluatedAction) {
        // 声明私有验证方法，核查生成的动作对象是否为空操作或冗余操作。

        if (evaluatedAction == null) {
            // 首先执行空指针安全校验。
            return true;
            // 若动作对象为空，则直接判定为无意义的空操作。
        }
        // 空指针拦截校验通过。

        if (evaluatedAction instanceof ActionRest) {
            // 校验该动作对象是否属于原地的静止休息动作类型。
            return true;
            // 若为静止动作，则同样判定其未产生实际的物理位移或状态改变。
        }
        // 休息动作类型排查完毕。

        if (evaluatedAction instanceof ActionMove) {
            // 校验动作对象是否为物理移动指令。

            final ActionMove movementAction = (ActionMove) evaluatedAction;
            // 将抽象动作对象向下安全转型为具体的移动动作实例。

            final int currentXCoordinate = (int) this.agentInfo.getX();
            // 提取当前智能体所处物理空间位置的 X 轴绝对坐标。

            final int currentYCoordinate = (int) this.agentInfo.getY();
            // 提取当前智能体所处物理空间位置的 Y 轴绝对坐标。

            final int targetXCoordinate = movementAction.getPosX();
            // 提取移动指令预期抵达目标位置的 X 轴设定坐标。

            final int targetYCoordinate = movementAction.getPosY();
            // 提取移动指令预期抵达目标位置的 Y 轴设定坐标。

            return currentXCoordinate == targetXCoordinate && currentYCoordinate == targetYCoordinate;
            // 严苛比对起始与目标坐标，若完全重合则判定为原地踏步的冗余指令。
        }
        // 移动动作类型的冗余校验逻辑执行结束。

        return false;
        // 若上述所有拦截规则均未触发，则确认该动作为合法的有效输出。
    }
    // 动作有效性评估机制判定完毕。

    private boolean evaluateIdleRequirement() {
        // 声明私有评估方法，判定当前智能体是否处于系统强制忽略的挂机保护期。

        final int currentSimulationTick = this.agentInfo.getTime();
        // 获取当前仿真周期的绝对时间戳刻度。

        final int systemIgnoredThreshold = this.scenarioInfo.getKernelAgentsIgnoreuntil();
        // 读取系统配置，提取内核设定的智能体全局静默期阈值。

        return currentSimulationTick < systemIgnoredThreshold;
        // 对比当前时间与静默阈值，若未越过则返回真值以强制挂机。
    }
    // 挂机保护期评估逻辑执行结束。

    // ========================================================================
    // [SECTION 4] 沉底架构状态字典与外部依赖 (Sunk State & Dependencies)
    // ========================================================================

    private ExtAction emergencyExtAction;
    // 声明私有组件句柄，用于挂载外部注入的紧急救援扩展动作模块，结构下沉。

    private PathPlanning routingModule;
    // 声明私有组件句柄，用于挂载外部注入的导航与路径规划模块，结构下沉。

    private EntityID dispatchingAuthority;
    // 声明私有字段，用于溯源并记录下达本次指令的中央指挥官实体标识，结构下沉。

    private int instructionCategory;
    // 声明私有字段，用于记录接收到的具体操作指令类别编码，结构下沉。

    private EntityID assignedObjective;
    // 声明私有字段，用于固化当前指派需救援或处理的目标实体标识，结构下沉。

    private static final int UNDEFINED_INSTRUCTION = -1;
    // 声明常量，用于标识当前处于未分配或未知的指令类型状态，结构沉底至极限。

}
