package UESTC.module.comm;

import java.util.*;
import adf.core.component.communication.ChannelSubscriber;
import rescuecore2.standard.entities.*;
import adf.core.agent.communication.MessageManager;
import static rescuecore2.standard.entities.StandardEntityURN.*;
import adf.core.agent.info.*;

public class UESTCBandListener extends ChannelSubscriber {
    // 声明通用通信信道订阅器类，继承并实现底层频段分配框架组件接口。

    // ========================================================================
    // [SECTION 1] 频段映射路由与算法分配器 (Band Mapping & Allocation Algorithms)
    // ========================================================================

    public static int assignChannel(StandardEntityURN entityClassification, int subscriptionIndex, int totalAvailableBands) {
        // 提供静态的底层频段分配映射算法，基于实体类别、当前订阅索引与网络总频宽执行模运算分配。

        final int totalFactionsCount = FACTION_REGISTRY_MATRIX.length;
        // 获取系统内建的所有标准实体阵营枚举注册表的总规模长度。

        final int classificationPointer = Arrays.asList(FACTION_REGISTRY_MATRIX).indexOf(entityClassification);
        // 检索并定位当前传入的特定实体类型枚举在全局阵营注册表中的精准一维索引偏移量。

        final int factionMultiplier = totalFactionsCount / 2;
        // 计算阵营系数乘子，通过对总体阵营数量进行二分降维折叠，以区分行动队伍与指挥中心序列。

        final int factionOrderOffset = classificationPointer / 2;
        // 根据类别索引计算并剥离出跨兵种的顺位偏移量，保障不同兵种基站与队伍间的交错排布。

        final int idealFrequencyIndex = factionMultiplier * subscriptionIndex + factionOrderOffset;
        // 汇算推导该单位理论上在无干扰无限频宽理想态下的基础基准通讯频段标号。

        int boundChannelAddress;
        // 声明整数局部变量，用以承载经频宽约束映射裁剪后的最终合法物理频道地址。

        if (totalAvailableBands > 0) {
            // 校验沙盒场景提供的可用无线电频带总数是否超越零阈值边界。

            boundChannelAddress = idealFrequencyIndex % totalAvailableBands + 1;
            // 对理论索引施加模运算截断以适应真实物理带宽，加一补齐索引偏移生成确切物理波段标识。
        } else {
            // 若遭逢极端场景设定下彻底丧失无线电频段资源的情况。

            boundChannelAddress = 0;
            // 强制退回并锁定至全网唯一的兜底频道（通常对应零号灾害语音播报信道）维持最低联络。
        }
        // 物理频带截断容错路由分配逻辑闭环。

        return boundChannelAddress;
        // 返回计算妥当并经验证的可用信道号，投递至上游信令监听组件。
    }
    // 底层阵营绑定式静电频带配发核心机能方法封端。

    private static int assignChannel(AgentInfo contextInfo, int subscriptionIndex, int totalAvailableBands) {
        // 提供重载版本的便捷分配方法，直接解析智能体环境元数据以抽离其物理形态标识。

        final StandardEntityURN nativeEntityIdentity = contextInfo.me().getStandardURN();
        // 自当前执行体的信息总线快照中剖析提取出该单位原生的硬件类别统一资源枚举定位码。

        return assignChannel(nativeEntityIdentity, subscriptionIndex, totalAvailableBands);
        // 将萃取出的本体类别类型连同频宽配额参数转抛投喂至底层的通用计算重载主干中。
    }
    // 带有环境元数据解析自适应能力的信道指派接口适配完成。

    // ========================================================================
    // [SECTION 2] 信道载荷量评估与极限核算器 (Capacity Evaluation & Limit Calculus)
    // ========================================================================

    public static int computeMaxChannels(StandardEntityURN entityClassification, int platoonAllowance, int officeAllowance) {
        // 提供静态的信道负荷容量评估算法，依实体形态分类计算其被获准订阅的极限量额指标。

        int classificationPointer = Arrays.asList(FACTION_REGISTRY_MATRIX).indexOf(entityClassification);
        // 遍历全局硬编码阵营序列字典，定锚捕获该目标枚举属性的确切驻留列位。

        boolean isMobilePlatoonUnit = (classificationPointer % 2) == 0;
        // 依托相邻兵种交替排列之约定规律，利用奇偶校验核实该单位是否隶属于高机动作战编队（而非静止中心）。

        int authorizedMaxBands;
        // 分配承载缓冲位，预留容纳依据实测类别判明后的最终授权承载订阅极值上限。

        if (isMobilePlatoonUnit) {
            // 开辟布尔裁决流，针对一线战区高频机动兵种单位施加核算规则。

            authorizedMaxBands = platoonAllowance;
            // 强行挂载适配于野战小队层级的场景环境上限宽容度参数。
        } else {
            // 进入补集分支，针对后方常驻型通讯联络中心或办公设施。

            authorizedMaxBands = officeAllowance;
            // 绑定套用场景规范中专门为中枢型基站开放的专属宽带容量预置极值。
        }
        // 基于职能身份分类的双轨制带宽容量定额判定落幕。

        return authorizedMaxBands;
        // 掷出敲定的系统级信道承载最大饱和限度授权数值。
    }
    // 基础型授权容量解算模块机能锁止。

    private static int computeMaxChannels(AgentInfo contextInfo, ScenarioInfo scenarioConfig) {
        // 暴露受限私有重载计算口，利用仿真沙盒两大总线集聚器快速计算当前单位带宽极限。

        final StandardEntityURN nativeEntityIdentity = contextInfo.me().getStandardURN();
        // 深入代理本体数据库检索其初始孵化之硬件规范识别字串。

        final int platoonAllowance = scenarioConfig.getCommsChannelsMaxPlatoon();
        // 自世界场景脚本配置文件摘录准许一线小分队抢占通信信道的核心极值约束量。

        final int officeAllowance = scenarioConfig.getCommsChannelsMaxOffice();
        // 横向提取出允许联络司令塔并连挂接的并发频带配额顶峰上限阀值。

        return computeMaxChannels(nativeEntityIdentity, platoonAllowance, officeAllowance);
        // 合并三大元数据特征参量，击传交付底层甄选方法作最终核实比对算计。
    }
    // 参数自适应型信道峰值容量快速求解封装层。

    // ========================================================================
    // [SECTION 3] 顶层生命周期调度入口 (Lifecycle Dispatch Entry)
    // ========================================================================

    @Override
    public void subscribe(AgentInfo contextInfo, WorldInfo envModel, ScenarioInfo scenarioConfig, MessageManager commManager) {
        // 覆写生命周期内频段订阅初始钩子，主导系统接入无线电阵列前夕的关键初始化作业。

        final int availableRadioFrequencies = scenarioConfig.getCommsChannelsCount() - 1;
        // 查验沙盒场景限定的物理通信频段全频宽额度，并强制剔除用作灾难广播语音通讯预留的 0 号根通道。

        final int capacityLimit = computeMaxChannels(contextInfo, scenarioConfig);
        // 推导发掘当前执行特工体所享有的极限并行通信频段持握权限峰值指标。

        List<Integer> subscribedBandRegistry = new ArrayList<>(Collections.nCopies(capacityLimit, 0));
        // 开辟等长于授权峰值的动态线性缓冲序列表，并将其预置充填缺省的安全 0 频段占位符掩码。

        for (int idxPointer = 0; idxPointer < capacityLimit; ++idxPointer) {
            // 部署定长约束游标轮询迭代器，逐一填补配发并注册所属容量范围内的合法频带标号。

            subscribedBandRegistry.set(idxPointer, assignChannel(contextInfo, idxPointer, availableRadioFrequencies));
            // 调用底层布号算子换算当前游标顺位对应的真实物理频谱值，更新写入至暂存注册簿。
        }
        // 基于定额核准的频段逐槽分配解析流水线闭合。

        int[] finalSubscriptionMatrix = subscribedBandRegistry.stream().mapToInt(boxElement -> boxElement).toArray();
        // 强行驱动内部存储转换流，将封箱包装型整型数据群落降维压缩萃取为高性能轻量态原生基础数据类型的标量一维矩阵序列。

        commManager.subscribeToChannels(finalSubscriptionMatrix);
        // 利用底层通讯总控调度台投递已完全封装修缮的物理矩阵串，向底层伺服宣告批量频段占用挂载订阅意图。
    }
    // 监听网络初始挂靠搭桥铺路之入口操作逻辑大盘告终结案。

    // ========================================================================
    // [SECTION 4] 静态字典注册表沉底寄存区 (Sunk Registry Constants)
    // ========================================================================

    final static StandardEntityURN[] FACTION_REGISTRY_MATRIX = {
        // 声明硬编码类级不可变静态字典总目，用于锁定枚举注册全局交替序列分布阵营特性的系统统一模型命名符，极致沉底。
        FIRE_BRIGADE, FIRE_STATION,
        // 火警机动队及其固定司令调度据点兵种登记映射组合对。
        POLICE_FORCE, POLICE_OFFICE,
        // 治安特警小分队及其安保防爆调集中心基站序列登记组合对。
        AMBULANCE_TEAM, AMBULANCE_CENTRE,
        // 急救转运先锋医护人员组同其伤患卫勤总台大本营类别名录。
    };
    // 系统兵种基建派系总名册表封存定谳。

}
