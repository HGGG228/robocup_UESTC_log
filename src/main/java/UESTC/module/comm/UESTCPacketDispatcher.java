package UESTC.module.comm;

import UESTC.module.util.AgentTelemetry;
import java.util.*;
import static java.util.Comparator.*;
import adf.core.agent.info.*;
import adf.core.agent.communication.standard.bundle.*;
import adf.core.component.communication.*;
import adf.core.agent.communication.MessageManager;
import java.util.stream.*;
import static rescuecore2.standard.entities.StandardEntityURN.*;
import rescuecore2.standard.entities.StandardEntityURN;

public class UESTCPacketDispatcher extends MessageCoordinator {
    // 声明负责对多源通信报文执行统一调度、过滤与频段打包注入的中央级消息协调器类。

    // ========================================================================
    // [SECTION 1] 带宽评估与信道最优化算法 (Bandwidth & Link Optimization)
    // ========================================================================

    private static int seekBestChannel(int payloadSize, int[] remainingCapacities) {
        // 提供私有的静态寻优算子，旨在为特定体积的报文封包甄选当前物理剩余带宽高位的最优注入频段索引。

        final int totalBandsCount = remainingCapacities.length;
        // 精准丈量输入参数矩阵的长度，以界定可用物理频段的扫描总跨度界限。

        final int optimalIndex = IntStream.range(0, totalBandsCount)
                // 开启整数流管道，自底向上遍历所有挂载频段的原始一维数组引脚。
                .boxed().max(comparingInt(bandCursor -> remainingCapacities[bandCursor])).orElse(-1);
                // 将原生态数据流装箱并通过比较器裁决出当前负载宽裕度最充沛的黄金频道槽位，如全线崩溃则抛出失效码 -1。

        return optimalIndex < 0 || remainingCapacities[optimalIndex] < payloadSize ? -1 : optimalIndex;
        // 实施二重关卡阻断：若寻优失效或即便是最优信道的冗余宽带仍无法承载该报文体积，则一律打回退稿。
    }
    // 封包注入频段甄别及物理承载力核验逻辑闭门。

    private static int gainBandwidth(int channelIdentity, ScenarioInfo scenarioConfig) {
        // 定义私有的频宽核查探测器，依据绝对物理频道序号动态读取沙盒下发的额定最大吞吐量指标。

        if (channelIdentity == 0) {
            // 核实目标询问信道是否为硬性保留的零号裸语音无差别广播公频。
            return scenarioConfig.getVoiceMessagesSize();
            // 针对零号公频执行专线查询法则，返回其特批的声学报文限定体积。
        }
        // 公用声波载荷特判放行。

        return scenarioConfig.getCommsChannelBandwidth(channelIdentity);
        // 对待标准数字无线电频段则按图索骥，直接抽调场景脚本规定的相应吞吐流峰值上限。
    }
    // 物理信道宽带承载能力实机探测功能收缩。

    // ========================================================================
    // [SECTION 2] 分拣打包与负载注入处理流 (Message Triage & Payload Injection)
    // ========================================================================

    private static void assignMessageOnResult(
            LinkPort physicalBand,
            StandardMessage validatedPayload,
            List<List<CommunicationMessage>> transmissionBlueprint,
            int[] remainingBytes,
            List<Set<String>> sentMessages) {
        final int[] channelAddresses = IntStream.of(physicalBand.getNumbers()).distinct().toArray();
        final int requiredBytes = validatedPayload.getByteArraySize();
        final String key = messageKey(validatedPayload);
        // All logical bands share the same physical-channel budget. Do not send
        // the same message twice when two faction bands map to that channel.
        int[] availableBytes = IntStream.of(channelAddresses)
                .map(channel -> sentMessages.get(channel).contains(key)
                        ? -1 : remainingBytes[channel])
                .toArray();
        final int bestInjectionSlot = seekBestChannel(requiredBytes, availableBytes);
        if (bestInjectionSlot < 0) {
            // 该消息放不下时，调用方继续尝试后续更小的消息。
            return;
        }
        int channel = channelAddresses[bestInjectionSlot];
        remainingBytes[channel] -= requiredBytes;
        sentMessages.get(channel).add(key);
        transmissionBlueprint.get(channel).add(validatedPayload);
    }

    /** Use the payload, not object identity or entity-only check keys. */
    private static String messageKey(StandardMessage message) {
        // Priority controls which identical copy is kept (deliveries are sorted
        // first); TTL and transport remain part of the transmitted identity.
        return message.getClass().getName() + ':' + message.isRadio() + ':' + message.getTTL()
                + ':' + Base64.getEncoder().encodeToString(message.toByteArray());
    }

    private static void assignMessagesOnResult(
            List<LinkPort> bands, List<List<CommunicationMessage>> result,
            int[] remainingBytes, List<Set<String>> sentMessages) {
        List<Map.Entry<LinkPort, StandardMessage>> deliveries = new ArrayList<>();
        for (LinkPort band : bands) {
            for (StandardMessage message : band.getMessages()) {
                deliveries.add(new AbstractMap.SimpleImmutableEntry<>(band, message));
            }
        }
        // A low-priority delivery on an earlier faction band must not consume a
        // shared channel before a high-priority delivery on another band.
        deliveries.sort(Comparator.comparing(
                (Map.Entry<LinkPort, StandardMessage> delivery) -> delivery.getValue().getSendingPriority()).reversed());
        for (Map.Entry<LinkPort, StandardMessage> delivery : deliveries) {
            assignMessageOnResult(delivery.getKey(), delivery.getValue(), result, remainingBytes, sentMessages);
        }
    }

    // 消息装箱至最终发射载体阵列管线的核查装载任务结案。

    private static int computeMaxChannels(StandardEntityURN entityType, ScenarioInfo scenarioConfig) {
        // 提供私有包装转发器，简化针对特定兵种实体可持握的最大授权并发频道配额数量的测算请求口。

        final int platoonQuota = scenarioConfig.getCommsChannelsMaxPlatoon();
        // 自演习配置文件参数中剥离出关于一线特遣作战小队编制的并发频宽限制常量。

        final int officeQuota = scenarioConfig.getCommsChannelsMaxOffice();
        // 同源摘录剥离针对后方调度总台防区级指挥设施所专设的并发通信路数顶流峰值。

        return UESTCBandListener.computeMaxChannels(entityType, platoonQuota, officeQuota);
        // 合并提取的系统常量参数群，一并向频带订阅服务主干部件透传求解极值请求。
    }
    // 频带额度解算前置代理包装层合拢。

    private static int[] gatherChannels(StandardEntityURN targetFaction, ScenarioInfo scenarioConfig) {
        // 构建依据特定阵营属性逆向组装其实际授权持握可用通讯频道集合列阵的方法。

        final int totalAvailableBands = scenarioConfig.getCommsChannelsCount() - 1;
        // 校准并锁定当前大沙盒场景支持开启的纯数据无线电发信频段总体规模宽度（滤除零号）。

        if (totalAvailableBands <= 0) return new int[0];
        final int allowedQuota = Math.max(0, computeMaxChannels(targetFaction, scenarioConfig));
        // 推算并框定目标阵营单位依法被获准挂靠注册的高频并发通信线路最大条数。

        final IntStream resolvedBandStream = IntStream.range(0, allowedQuota)
                // 铺设从零跃迁至额定配额终点的数字流水线车间。
                .map(quotaCursor -> UESTCBandListener.assignChannel(targetFaction, quotaCursor, totalAvailableBands));
                // 把流水线上的游标标号混编入阵营属性，经由底端算法映射兑换出真实的可用物理频道号码。

        return resolvedBandStream.distinct().toArray();
        // 将数字流水线出产的数据强制压缩固化为纯正一维整数形态矩阵供系统底层派用。
    }
    // 基于阵营特征推演物理信道归属数组矩阵逻辑收束。

    private static List<StandardMessage> gatherStandardMessages(List<CommunicationMessage> rawIncomingQueue) {
        // 设立纯净数据提取关卡，主司从泛型的多源混合消息堆栈中过滤榨取纯正的标准包裹构件。

        List<StandardMessage> purifiedBuffer = new ArrayList<>();
        // 提前支好一个初始容量待扩充的规整对象阵列池以备承载即将剥离而出的标准封包。

        for (CommunicationMessage genericPayload : rawIncomingQueue) {
            // 对外部野蛮推送的无状态混合大消息源堆集启动全量逐帧循环扫查。

            if (genericPayload instanceof StandardMessage) {
                // 架设极其苛刻的类型安检站，断然拦截所有非标变异报文数据的通过意图。

                purifiedBuffer.add((StandardMessage) genericPayload);
                // 施展强制类型降维打击将抽象泛型包裹还原为标装物件后稳妥汇入预留好的纯净缓存池。
            }
            // 单封信息数据包格式鉴识及洗炼剥除操作终了。
        }
        // 全盘多源混杂初始信件堆积库强筛行动宣告落幕。

        return purifiedBuffer;
        // 把提取纯化毫无杂质的标准通信封包集合全盘呈上转交主审流程。
    }
    // 标准信道规范数据萃取漏斗工具封闭。

    // ========================================================================
    // [SECTION 3] 顶层生命周期控制统筹 (Top Lifecycle Coordinate Dispatch)
    // ========================================================================

    @Override
    public void coordinate(
            AgentInfo contextAgent, WorldInfo envModel, ScenarioInfo scenarioConfig, MessageManager commManager,
            ArrayList<CommunicationMessage> incomingRawMessages, List<List<CommunicationMessage>> finalTransmissionMatrix) {
        AgentTelemetry.tick(contextAgent, envModel);
        // 覆写生命周期内极为核心的通讯指控调度钩子，统率每一回合前夕繁复的发报决策前置动作。

        final List<StandardMessage> standardizedPayloads = UESTCPacketDispatcher.gatherStandardMessages(incomingRawMessages);
        // 调遣过滤漏斗从泥沙俱下的生糙报文原矿堆里提纯析出架构指定的纯净规范化标准信标集群。

        int[] remainingBytes = new int[finalTransmissionMatrix.size()];
        List<Set<String>> sentMessages = new ArrayList<>();
        for (int channel = 0; channel < remainingBytes.length; channel++) {
            // This prevents double accounting across this agent's logical bands;
            // the server still enforces the aggregate limit across all agents.
            remainingBytes[channel] = gainBandwidth(channel, scenarioConfig);
            Set<String> sent = new HashSet<>();
            for (CommunicationMessage message : finalTransmissionMatrix.get(channel)) {
                if (message instanceof StandardMessage standard) {
                    remainingBytes[channel] -= standard.getByteArraySize();
                    sent.add(messageKey(standard));
                }
            }
            sentMessages.add(sent);
        }

        List<LinkPort> physicalBandAllocators = new LinkedList<>();
        // 新修筑一条松耦合架构的管道链表，用作依次挂载后续针对不同兵种介质配置的多路发射器探针通道。

        physicalBandAllocators.add(new VoiceRelayLink(new int[]{0}));
        // 第一顺位首先牢牢强绑定绝对不可或缺的零号模拟原始声学近场咆哮发信裸频接口。

        physicalBandAllocators.add(new BlazeRescueLink(contextAgent, UESTCPacketDispatcher.gatherChannels(FACTION_FIRE_BRIGADE, scenarioConfig)));
        // 组装并强行压入专门为一线灭火干预防爆作战小队单独订制割裂出的数字频带发射接收监听槽道。

        physicalBandAllocators.add(new MedicRescueLink(contextAgent, UESTCPacketDispatcher.gatherChannels(FACTION_AMBULANCE_TEAM, scenarioConfig)));
        // 接力配置承接针对抢险卫勤转运医疗队伍特权免审频宽矩阵架构而单开的通道隔离监听单元板。

        physicalBandAllocators.add(new RoadClearanceLink(contextAgent, UESTCPacketDispatcher.gatherChannels(FACTION_POLICE_FORCE, scenarioConfig)));
        // 最终合拢拼装专为安保路障强拆机动大队架设独立物理通信规约防线的警察专用调度收发列阵信道。

        physicalBandAllocators.stream().forEach(bandAllocator -> standardizedPayloads.forEach(standardPayload -> bandAllocator.addWithFilter(standardPayload)));
        // 指挥各路挂载的专属物理频段过滤器网格对洗炼出的全量纯净消息堆发起双重嵌套交叉矩阵盲投试探与筛选截留。

        assignMessagesOnResult(physicalBandAllocators, finalTransmissionMatrix, remainingBytes, sentMessages);

        logTransmission(contextAgent, incomingRawMessages, finalTransmissionMatrix);
    }

    /** Summarize proposed and actually packed messages without changing allocation. */
    private static void logTransmission(AgentInfo agent, List<CommunicationMessage> proposed,
                                        List<List<CommunicationMessage>> packed) {
        try {
            Map<String, Integer> proposedTypes = new TreeMap<>();
            Map<String, Integer> packedTypes = new TreeMap<>();
            Map<Integer, Integer> channelBytes = new TreeMap<>();
            Set<CommunicationMessage> packedUnique = Collections.newSetFromMap(new IdentityHashMap<>());
            int packedCopies = 0;
            for (CommunicationMessage message : proposed) {
                if (message != null) proposedTypes.merge(message.getClass().getSimpleName(), 1, Integer::sum);
            }
            for (int channel = 0; channel < packed.size(); channel++) {
                int bytes = 0;
                for (CommunicationMessage message : packed.get(channel)) {
                    if (message == null) continue;
                    packedCopies++;
                    packedUnique.add(message);
                    packedTypes.merge(message.getClass().getSimpleName(), 1, Integer::sum);
                    if (message instanceof StandardMessage standard) bytes += standard.getByteArraySize();
                }
                if (bytes > 0) channelBytes.put(channel, bytes);
            }
            int notPacked = 0;
            for (CommunicationMessage message : proposed) {
                if (message != null && !packedUnique.contains(message)) notPacked++;
            }
            AgentTelemetry.event(agent, "communication", "UESTCPacketDispatcher",
                    "proposed", proposed.size(), "packed_copies", packedCopies,
                    "packed_unique", packedUnique.size(), "not_packed", notPacked,
                    "proposed_types", proposedTypes, "packed_types", packedTypes,
                    "channel_bytes", channelBytes);
        } catch (RuntimeException ignored) {
            // Diagnostics must not interrupt message delivery.
        }
    }
    // 跨频段通信调度与频宽最优拟合装填发送主流程完美截断歇工。

    // ========================================================================
    // [SECTION 4] 静态常量与标识字典沉底区 (Sunk Constants & Identifiers)
    // ========================================================================

    private static final StandardEntityURN FACTION_POLICE_FORCE = POLICE_FORCE;
    // 声明不可变的内部私有静态常量挂载点，持久化硬重命名关联警务治安暴力维稳实体专属阵营代字信标，极限下沉。

    private static final StandardEntityURN FACTION_AMBULANCE_TEAM = AMBULANCE_TEAM;
    // 声明不可变的内部私有静态常量挂载点，固化承接急救转运后勤保障大队专属统一实体身份标牌密码，极限下沉。

    private static final StandardEntityURN FACTION_FIRE_BRIGADE = FIRE_BRIGADE;
    // 声明不可变的内部私有静态常量挂载点，锚定映射消防火场扑灭一线尖刀班排专属物理世界识别代号字，极限下沉。

}
