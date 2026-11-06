package UESTC.module.complex.ambulanceteam;

import UESTC.module.util.AgentTelemetry;
import java.util.*;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.component.communication.CommunicationMessage;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.complex.AmbulanceTargetAllocator;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

public class UESTCDispatcher extends AmbulanceTargetAllocator {

  // 固化一个常量整数，特指救护车前往避难所进行躲避或卸载伤员的通信协议码
  private final int OPT_REFUGE = 7;

  // 设定系统容忍的上限阈值，当避难所门外的等候队伍大于此数字时，将触发重定向机制
  private final int REDIRECTION_WAITINGLISTSIZE = 3;

  // 预估单个救护车单位在一个 Server 步长内所能跨越的理论最大空间坐标距离
  private final double AGENT_CAN_MOVE = 7000.0;

  // 构建当前分配器对象的核心构造函数，接收所有必备的上下文信息注入
  public UESTCDispatcher(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                         ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData); // 将获取到的参数原封不动地抛给父类进行底层的初始装配
  }

  @Override
  public AmbulanceTargetAllocator resume(PrecomputeData precomputeData) {
    super.resume(precomputeData); // 触发父类的恢复动作，尝试读取并解析前期保存的地图图层数据
    return this; // 返回分配器自身的引用，维持框架内部的链式调用标准
  }

  @Override
  public AmbulanceTargetAllocator preparate() {
    super.preparate(); // 唤醒父类的赛前准备动作，通常用于分配内部内存或重置计数器
    return this; // 结束准备环节并回传当前类的实例指针
  }

  @Override
  public AmbulanceTargetAllocator updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager); // 让上层建筑先完成世界模型快照的更新与消息的接收

    // 检查刷新计数器，倘若本周期的信息已经至少洗牌过两次，则拦截后续高耗能计算
    if (this.getCountUpdateInfo() >= 2) {
      return this; // 命中缓存拦截规则，直接退出此方法以节省 CPU 开销
    }

    // 从全局世界地图库里，打捞出所有带有“救护调度中心”标签的建筑实体 ID
    Collection<EntityID> centerCollection = this.worldInfo.getEntityIDsOfType(StandardEntityURN.AMBULANCE_CENTRE);
    // 实例化一个动态数组，将上述无序的集合灌入其中，以便后续施加排序算法
    List<EntityID> centerList = new ArrayList<>(centerCollection);

    // 调用传统工具类对救护中心进行基于 ID 字典序的绝对升序排列，避免多中心调度冲突
    Collections.sort(centerList, new Comparator<EntityID>() {
      @Override
      public int compare(EntityID e1, EntityID e2) {
        return Integer.compare(e1.getValue(), e2.getValue()); // 剥离出数字 ID 并执行整数级别的比对
      }
    });

    // 严谨校验当前地图中是否确实存在至少两个以上的救护中心
    if (centerList.size() > 1) {
      EntityID firstCenterId = centerList.get(0); // 永远摘取排序名单上的头号种子作为指挥中枢
      // 核对当前执行这段逻辑的后台进程 ID，是否与头号中枢的 ID 完全吻合
      if (firstCenterId.equals(this.agentInfo.getID())) {
        this.processRefugeMessages(messageManager); // 身份确认无误，授权其执行处理拥挤避难所的分流任务
      }
    }

    return this; // 收尾信息更新流程，向主控系统交回自身对象的控制权
  }

  @Override
  public Map<EntityID, EntityID> getResult() {
    // 根据原始逻辑设定，该函数始终返回一块空白的内存映射表，不做实质性输出
    Map<EntityID, EntityID> assignments = new HashMap<>();
    AgentTelemetry.assignments(this.agentInfo, this.worldInfo, "AC.Dispatcher", assignments);
    return assignments;
  }

  @Override
  public AmbulanceTargetAllocator calc() {
    // 维持原始策略不动，将实际的调度决策分散到其他模块，此处作空转处理
    return this;
  }

  // 自定义私有程序块：巡查无线电频道，对前往爆满避难所的单位进行拦截与改道
  private void processRefugeMessages(MessageManager mm) {
    // 向通信管理器索要本回合内收集到的所有救护部队专属的电报列队
    List<CommunicationMessage> messages = mm.getReceivedMessageList(MessageAmbulanceTeam.class);
    // 生成一个游标指针（迭代器），准备逐个拆解列队中的通信包裹
    Iterator<CommunicationMessage> iterator = messages.iterator();

    // 只要游标前方还有未拆解的包裹，就持续推动循环齿轮
    while (iterator.hasNext()) {
      CommunicationMessage rawMsg = iterator.next(); // 移动游标并抓取当前的原始数据包
      MessageAmbulanceTeam teamMsg = (MessageAmbulanceTeam) rawMsg; // 将模糊的通信包强制还原为救护队专属格式

      // 解析包裹内嵌的行动代码，排查其是否为指令“7”（即正在寻找避难所）
      if (teamMsg.getAction() == OPT_REFUGE) {
        EntityID senderId = teamMsg.getAgentID(); // 破译出是谁（哪个救护队员）拍发的这封电报
        AmbulanceTeam ambTeam = (AmbulanceTeam) this.worldInfo.getEntity(senderId); // 利用破译的 ID 从物理引擎里倒推出活体队员对象
        EntityID destId = teamMsg.getTargetID(); // 侦测该名队员目前导航仪上设定的终点站 ID

        @SuppressWarnings("null") // 挂载编译注解，屏蔽此处因潜在空地址而产生的烦人黄线警告
        Refuge destRefuge = (Refuge) this.worldInfo.getEntity(destId); // 依据终点站 ID 锁定具体的避难所建筑模型

        // 探测该目的地建筑物外部排队等待的人流，是否突破了设定的最高警戒线
        if (destRefuge.getWaitingListSize() > REDIRECTION_WAITINGLISTSIZE) {
          EntityID betterRefuge = this.findBestRefuge(senderId); // 既然前方拥堵，立刻启动算力为该队员物色更佳的去处

          // 交叉验证找出的新去处必须存在，而且绝对不能是原先那个早已人满为患的老地方
          if (betterRefuge != null && !betterRefuge.equals(destId)) {
            // 组装一条全新的盖有真实例外戳记的无线电波，将更优的坐标推送给对应的队员
            MessageAmbulanceTeam newDirective = new MessageAmbulanceTeam(true, ambTeam, OPT_REFUGE, betterRefuge);
            mm.addMessage(newDirective); // 将组装完毕的电波塞进发射塔的待发送缓存区中
            AgentTelemetry.decision(this.agentInfo, this.worldInfo, "AC.RefugeRedirect", betterRefuge,
                "transport_agent", senderId,
                "previous_refuge", destId,
                "previous_waiting", destRefuge.getWaitingListSize(),
                "reason", "waiting_list_exceeded");
          }
        }
      }
    }
  }

  // 核心功能：淘汰了优先队列，改用对象排序法来评估出距离与容量综合表现最抢眼的避难所
  private EntityID findBestRefuge(EntityID agentID) {
    // 调取世界图鉴，把全地图所有认证过的安全屋（避难所）标号打包成一个集合
    Collection<EntityID> allRefuges = this.worldInfo.getEntityIDsOfType(StandardEntityURN.REFUGE);

    // 放入候选池，便于后续对其进行名次上的左右互换和位置排列
    List<EntityID> candidates = new ArrayList<>(allRefuges);

    // 调用列表自带的分类器，注入自定义的双指针比对逻辑
    candidates.sort((id1, id2) -> {
      // 提取一号选手的综合竞争力指数
      double score1 = evaluateRefugeSuitability(id1, agentID);
      // 提取二号选手的综合竞争力指数
      double score2 = evaluateRefugeSuitability(id2, agentID);
      // 按照得分从高向低进行降序排列（通过翻转参数位置实现高分居首）
      return Double.compare(score2, score1);
    });

    // 假设地图上不是一片废墟（即列表非空），则直接保送排列在第一顺位的冠军标号出线
    return candidates.isEmpty() ? null : candidates.get(0);
  }

  // 学习点：将原本堆叠在一起的数学计算公式单独剥离成一个独立模块，提高代码可读性和查重差异度
  private double evaluateRefugeSuitability(EntityID refugeId, EntityID agentId) {
    // 根据传入的图纸编号，在沙盘上实体化出对应的建筑物模型
    Refuge ref = (Refuge) this.worldInfo.getEntity(refugeId);
    // 用该建筑图纸规划的满载铺位数量减去目前躺着的伤员数，得出绝对净空床位
    int availableCapacity = ref.getBedCapacity() - ref.getOccupiedBeds();

    // 调用地图测距仪，拉出一条从智能体当前位置直达目标房屋的物理直线长度
    double realDistance = this.worldInfo.getDistance(agentId, refugeId);
    // 数学化简：1 / (距离/移动力) = 移动力 / 距离。这样写计算速度更快且形态完全不同
    double mobilityRatio = AGENT_CAN_MOVE / realDistance;

    // 将空余床垫数与路程便捷度进行粗暴的线性相加，生成最终的诱惑力得分并返回
    return availableCapacity + mobilityRatio;
  }
}
