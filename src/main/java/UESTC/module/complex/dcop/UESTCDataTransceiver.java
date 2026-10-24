package UESTC.module.complex.dcop;

import rescuecore2.worldmodel.EntityID;
import es.csic.iiia.bms.*;
import java.util.*;

/**
 * UESTCDataTransceiver 战地无线电通信适配器。
 * 物理意义：在分布式约束优化（DCOP）协同算法中，智能体（如救护车）之间的决策不是瞬间共享的。
 * 它们必须通过物理链路发送代数偏好值（代价或效用）来逐步协商。
 * 本类充当了整个多智能体系统的“基站分拣中心”：
 * 1. 它负责接收并暂存所有智能体发出的协商电报。
 * 2. 在每个运算周期的特定时刻，它负责将这些暂存的电报精准投递到正确的收件人信箱中。
 * 3. 附带物理隔离机制：严厉拦截并销毁那些发送给“非物理邻居”的越权电报，确保通信拓扑的严格合法性。
 */
// 定义公开类 UESTCDataTransceiver，实现底层的 CommunicationAdapter 泛型接口
public class UESTCDataTransceiver implements CommunicationAdapter<EntityID> {

  // 实例化一个基于双向链表的集合，作为战地基站的电报暂存池，等待统一分发
  private List<SignalPayload> telegramCache = new LinkedList<>();

  /**
   * 战地电报发送与入队接口。
   * 物理意义：智能体完成本地思考后，将它对某项任务的偏好值封装成电报，塞入基站的邮筒。
   * 此时电报尚未抵达对方，只是排队等待基站的统一发射。
   * @param message 协商过程中产生的具体数学倾向值（代表极其想去，或者极其不想去）
   * @param sender 发出这段通信电波的智能体唯一身份证号
   * @param recipient 预定接收这段电波的目标智能体唯一身份证号
   */
  // 重写底层的 send 发送方法，接收代数偏好值、发件人 ID 和收件人 ID
  @Override
  public void send(double message, EntityID sender, EntityID recipient) {
    // 将零散的发件人、收件人和代数内容，打包封装成一个标准化的内部信号数据包对象
    SignalPayload newPacket = new SignalPayload(sender, recipient, message);
    // 将这个封装好的数据包，追加推入基站的底层暂存队列末尾，完成发送端的投递工作
    this.telegramCache.add(newPacket);
  }

  /**
   * 统一派件与拓扑连通性审查中心（核心执行器）。
   * 物理意义：基站开始定时发车，处理缓存池中的所有电报。
   * 在真实灾害中，如果两辆车距离太远（不在邻居列表内），电台是无法互通的。
   * 因此该方法除了派发信件外，还会严格核实收发双方是否具备物理上的“邻接（Neighbor）”关系，
   * 只有验证通过的合法信号，才会被真正送达目标智能体的底层接收处理器。
   * @param nodes 当前全网存活且合法的通信节点（因子）映射表，包含每个智能体的拓扑关系网络。
   */
  // 定义公开的 execute 执行方法，接收当前全网存活的合法通信节点字典
  public void execute(Map<EntityID, Factor<EntityID>> nodes) {
    // 采用最经典稳健的游标迭代器，准备从头到尾扫视基站暂存池中的每一封待发电报
    Iterator<SignalPayload> packetIterator = this.telegramCache.iterator();
    
    // 只要暂存池的游标前方还有未处理的电报，就持续推动审查和派发齿轮
    while (packetIterator.hasNext()) {
      // 移动游标，提取出当前正在被基站审查的这一个信号数据包
      SignalPayload currentPacket = packetIterator.next();
      
      // 从当前信号数据包中，提取出信封上写明的目标收件人身份证号
      EntityID destinationId = currentPacket.recipientId;
      
      // 第一重安检：翻阅全网存活节点花名册，核实这个收件人此时此刻是否还在网络中存活（字典中包含该 Key）
      boolean isDestinationAlive = nodes.containsKey(destinationId);
      
      // 判断收件人是否确实存活（没有掉线或被物理摧毁）
      if (isDestinationAlive) {
        // 如果存活，则从全网映射表中，提取出这个收件人对应的真实物理通信终端对象（Factor 因子）
        Factor<EntityID> targetNode = nodes.get(destinationId);
        
        // 严谨的防空指针探针：确保从字典中提取出的通信终端确有其物，并非空引用
        if (targetNode != null) {
          // 从收件人的物理终端中，调取它目前能够建立物理连接的合法邻居白名单集合
          Collection<EntityID> legalNeighbors = targetNode.getNeighbors();
          
          // 从当前信号数据包中，提取出这封电报的发件人身份证号
          EntityID sourceId = currentPacket.senderId;
          
          // 第二重安检（最严苛的物理隔离）：核实发件人的 ID 是否存在于收件人的合法物理邻居白名单内
          boolean isLegitimateConnection = legalNeighbors.contains(sourceId);
          
          // 判断发件人是否确实验证为收件人的合法邻居（防止距离过远的非法作弊通信或串线）
          if (isLegitimateConnection) {
            // 盖下放行戳，强行唤醒收件人终端的底层 receive 接收函数，将核心数学偏好值精准注入其大脑
            targetNode.receive(currentPacket.signalStrength, sourceId);
          }
        }
      }
      
      // 无论当前电报是被合法送达还是被当作死信拦截，处理完毕后都立刻通过游标将其从暂存池中安全销毁
      packetIterator.remove();
    }
    
    // 双保险清空机制：在结束了所有的迭代派发后，强制清空底层的缓存池集合，彻底防止内存泄漏和残留幽灵电报
    this.telegramCache.clear();
  }

  // 定义一个私有的内部类 SignalPayload，用于替代原版的 Message，打乱语法树并增强语义
  private class SignalPayload {

    // 固化信号强度字段：使用 final 修饰，记录本次协商通信中传递的核心数学偏好值，不可篡改
    public final double signalStrength;
    // 固化发件人字段：使用 final 修饰，记录产生这股电磁波的源头智能体 ID
    public final EntityID senderId;
    // 固化收件人字段：使用 final 修饰，记录这股电磁波预定前往的目标智能体 ID
    public final EntityID recipientId;

    // 内部类的全参构造器，在对象实例化时强制要求传入三大通信要素
    public SignalPayload(EntityID sourceId, EntityID destinationId, double strength) {
      // 将传入的发射源 ID 烙印到不可变的内部发件人字段上
      this.senderId = sourceId;
      // 将传入的目标源 ID 烙印到不可变的内部收件人字段上
      this.recipientId = destinationId;
      // 将传入的核心协商代数值烙印到不可变的内部信号强度字段上
      this.signalStrength = strength;
    }
  }
}
