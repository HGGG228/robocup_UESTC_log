package UESTC.module.complex.dcop;

import es.csic.iiia.bms.factors.CardinalityFactor;

/**
 * SetSizeMultiplier 是分布式约束优化问题（DCOP）中的核心数学计算因子。
 * 物理意义：在多智能体协同（例如多辆救护车分配给多个伤员，或多个消防员扑灭同一栋建筑）时，
 * 系统必须对资源的“基数（Cardinality）”进行硬性限制（比如一个避难所最多容纳 N 人，或一个任务最多分配 M 人）。
 * 该因子节点的作用就是评估当前的分配方案，并对超出限制的方案施加严重的数学惩罚（Cost）。
 * 本类基于底层算法进行了特化，专门用于拦截和处理代表“绝对不可行”或“绝对必然”的无穷大（Infinite）消息，
 * 从而在发生硬性死锁或确定性决策时快速裁剪拓扑网络，加速 Max-Sum 算法的收敛。
 */
public class SetSizeMultiplier<T> extends CardinalityFactor<T> {

  /**
   * 消息接收与分流中枢。
   * 在 DCOP 的 Max-Sum 或类似算法中，智能体之间通过在因子图（Factor Graph）上不断传递代数消息来协商决策。
   * 此方法负责拦截传来的偏好值消息，并判定其是否为极端的“无穷大”边界值。
   * 如果是极端值，则交由特化逻辑处理以削减计算图；否则，放行至底层的标准接收器进行常规概率迭代。
   * @param message 邻居传来的代数偏好值或惩罚值
   * @param sender 发送这条电报消息的拓扑邻居节点
   */
  @Override
  public void receive(double message, T sender) {
    // 提取并校验是否为极端锁定信号，如果不是，则走常规处理通道
    boolean isInfinityIntercepted = this.interceptInfinitySignal(message, sender);
    if (isInfinityIntercepted == false) {
        this.routeToStandardProcessor(message, sender);
    }
  }

  /**
   * 极端信号拦截器（私有）。
   * 物理意义：无穷大消息代package UESTC.module.complex.dcop;

import es.csic.iiia.bms.MaxOperator;
import es.csic.iiia.bms.factors.SelectorFactor;
import java.util.*;

public class ChoiceHeuristic<T> extends SelectorFactor<T> {

  private T fixedneighbor = null;

  @Override
  public long run() {
    if (this.fixedneighbor == null) {
      return super.run();
    }

    MaxOperator op = this.getMaxOperator();
    final Collection<T> neighbors = this.getNeighbors();

    for (T neighbor : neighbors) {
      if (neighbor.equals(this.fixedneighbor)) {
        op = op.inverse();
      }
      this.send(op.getWorstValue(), neighbor);
    }
    return neighbors.size();
  }

  @Override
  public void receive(double message, T sender) {
    if (!Double.isInfinite(message)) {
      super.receive(message, sender);
      return;
    }

    if (this.getNeighbors().contains(sender)) {
      final double worst = this.getMaxOperator().getWorstValue();
      boolean shouldReduce = worst != message;
      this.reduce(sender, shouldReduce);
      this.send(message, sender);
    }
  }
//receive方法修改于2024/9/28

  @Override
  public T select() {
    return this.fixedneighbor == null ? super.select() : this.fixedneighbor;
  }

  @Override
  public String toString() {
    return "Selector[" + this.getIdentity() + "]";
  }

  private void reduce(T neighbor, boolean active) {
    if (active) {
      this.fixedneighbor = neighbor;
    }
    this.removeNeighbor(neighbor);

    if (this.fixedneighbor == null) {
      final List<T> neighbors = this.getNeighbors();
      final int nRemainings = neighbors.size();
      if (nRemainings == 1) {
        this.fixedneighbor = neighbors.get(0);
      }
    }
  }
}表了一个邻居智能体已经做出了不可更改的死局决策（比如它由于没油了绝对去不了某个地方）。
   * 该方法用于筛选出这些极端信号，并确认发送者确实是我们的直接相连邻居。
   * @param incomingMsg 传入的偏好值电报
   * @param msgSender 电报发送方
   * @return 如果成功拦截并处理了无穷大消息，返回 true；否则返回 false
   */
  private boolean interceptInfinitySignal(double incomingMsg, T msgSender) {
    // 运用 Double 包装类判定数值是否触及正负无穷大边界
    boolean isExtremeValue = Double.isInfinite(incomingMsg);
    if (isExtremeValue == false) {
        return false;
    }
    
    // 检查发送方是否在我们的物理通信拓扑白名单内
    boolean isDirectNeighbor = this.getNeighbors().contains(msgSender);
    if (isDirectNeighbor) {
        // 确认身份和极端值后，启动图网络裁剪程序
        this.executeHardConstraintReduction(incomingMsg, msgSender);
        return true;
    }
    return false;
  }

  /**
   * 常规消息放行通道（私有）。
   * 物理意义：对于普通的有限数值（代表正常的倾向性或代价），直接交给底层框架去进行常规的求和或求最值迭代。
   * @param normalMsg 普通的有限约束代数值
   * @param msgSender 消息发送方
   */
  private void routeToStandardProcessor(double normalMsg, T msgSender) {
    // 直接呼叫父类（底层框架）的标准接收器，不进行任何阻拦
    super.receive(normalMsg, msgSender);
  }

  /**
   * 硬约束降维执行器（私有）。
   * 物理意义：既然收到了某个邻居的极端不可更改信号，我们就必须根据我们的评估体系，
   * 判定该邻居的最终状态是“绝对激活”还是“绝对放弃”。
   * 随后调用网络裁剪功能，把这个已经没有悬念的邻居从协商网络中剔除，并把最后通牒发送回去。
   * @param infMsg 极端的代数值
   * @param msgSender 发送该极端值的邻居
   */
  private void executeHardConstraintReduction(double infMsg, T msgSender) {
    // 向系统的算子索要当前能容忍的最差惩罚值上限
    final double lowestUtilityLimit = this.getMaxOperator().getWorstValue();
    // 判定逻辑：如果邻居发来的无穷大不等于我们定义的最差极限，说明该邻居处于“绝对强制激活”状态
    boolean isDecisionActive = (lowestUtilityLimit != infMsg);
    
    // 启动网络拓扑图的裁剪手术
    this.reduce(msgSender, isDecisionActive);
    // 回传确认电波，终止双方在该维度的后续无意义协商
    this.send(infMsg, msgSender);
  }

  /**
   * 因子身份标识序列化方法。
   * 主要用于在底层的控制台日志、调试追踪或拓扑图可视化工具中，
   * 将当前基数因子输出为具有高辨识度的字符串签名。
   * @return 格式化后的基数因子字符串标签
   */
  @Override
  public String toString() {
    // 提取系统分配给本因子的唯一 UUID，并拼接上功能前缀
    String factorIdentity = String.valueOf(this.getIdentity());
    return "Cardinality[" + factorIdentity + "]";
  }

  /**
   * 拓扑裁剪与降维引擎。
   * 物理意义：当确定某个邻居智能体已经做出了“绝对激活”或“绝对放弃”的死局决策时，
   * 整个网络就不需要再在未来的回合中与它进行概率协商了。
   * 系统会强行切除与该邻居的通信连线，从而极大地降低联合概率分布的计算维度（从 O(N^2) 降维）。
   * 同时，如果它是以“激活”状态退出的，本节点的剩余容量就会永久减一（通过修改成本函数实现）。
   * @param targetNeighbor 需要被彻底切除通信连线的邻居节点
   * @param isConfirmedActive 该邻居退出时是否处于强制激活（占用资源）状态
   */
  public void reduce(T targetNeighbor, boolean isConfirmedActive) {
    // 如果邻居在退出时带走了一个名额（比如成功抢到了一个床位）
    if (isConfirmedActive == true) {
        // 触发内部评估函数的平移调整，使剩余资源的门槛提高
        this.shiftCostEvaluationFunction(isConfirmedActive);
    }
    // 物理切断：从拓扑图相邻节点列表中将其永久删除
    this.removeNeighbor(targetNeighbor);
  }

  /**
   * 成本评估函数平移器（私有）。
   * 物理意义：假设这是一个避难所的容量因子，总共能装 5 个人。
   * 如果刚才有个伤员（邻居）强制以“激活”状态住进来了，那我们以后的计算公式就必须变了。
   * 原来的评估函数是 f(x)，现在必须变成 f(x+1)，因为基础负荷已经永久增加了 1。
   * @param activationStatus 状态参数（此处仅作结构保留标记）
   */
  private void shiftCostEvaluationFunction(boolean activationStatus) {
    // 备份当前正在使用的原始评估基准函数
    CardinalityFunction originalEvaluationFunction = this.getFunction();
    
    // 利用匿名内部类，构建一个具有偏移量的新评估函数
    CardinalityFunction shiftedFunction = new CardinalityFunction() {
        @Override
        public double getCost(int currentActiveCount) {
            // 将输入的激活变量数量强制加 1，以抵消那个已经退出但占用了名额的邻居
            int shiftedCount = currentActiveCount + 1;
            return originalEvaluationFunction.getCost(shiftedCount);
        }
    };
    
    // 用偏移后的新函数覆盖旧函数，完成系统状态的不可逆推进
    this.setFunction(shiftedFunction);
  }

}
