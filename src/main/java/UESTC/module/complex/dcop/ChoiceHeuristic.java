package UESTC.module.complex.dcop;

import es.csic.iiia.bms.MaxOperator;
import es.csic.iiia.bms.factors.SelectorFactor;
import java.util.*;

/**
 * ChoiceHeuristic 排他性选择因子。
 * 物理意义：在灾难救援的分布式约束优化（DCOP）算法中，某些资源的分配具有“唯一排他性”。
 * 例如：一辆救护车在同一时刻只能运送【一个】平民；或者一个平民只能被【一辆】车装载。
 * SelectorFactor 的核心作用就是强制实施这种“一对一”或“多选一”的硬性物理约束。
 * 本类对底层算法进行了特化升级，专门拦截并处理代表“强制锁定”或“绝对拒绝”的极端信号（无穷大），
 * 一旦促成唯一匹配，就会立刻切断与其他候选项的通信，极大降低系统的运算负担。
 */
public class ChoiceHeuristic<T> extends SelectorFactor<T> {

  /**
   * 终身绑定伴侣（私有变量）。
   * 物理意义：一旦这辆救护车通过算法彻底确认了它要救的那个唯一伤员，
   * 这个变量就会将该伤员的 ID 死死记录下来。在此之后，这辆车将对其他所有求救信号充耳不闻。
   */
  private T lockedTargetNode = null;

  /**
   * 因子心跳广播发射器。
   * 物理意义：在每一次通信周期中，因子需要向周围的邻居广播自己的“招募意向”或“拒绝信”。
   * 如果还没有找到心仪的对象，就让底层框架去正常计算概率并广播；
   * 如果已经名花有主（lockedTargetNode 不为空），就直接向外群发决断信。
   * @return 本回合成功发送的广播电报数量
   */
  @Override
  public long run() {
    // 检查自己是否还是单身（未绑定状态）
    if (this.lockedTargetNode == null) {
      // 如果还没绑定，按照常规的 Max-Sum 算法公式进行概率计算和广播
      return super.run();
    }

    // 提取系统当前的基准评估算子（通常是寻找最大收益或最小代价）
    MaxOperator defaultOperator = this.getMaxOperator();
    // 获取当前与我物理连通的所有邻居名单（候选人名单）
    final Collection<T> connectedNeighbors = this.getNeighbors();

    // 经典的遍历发报逻辑，逐个通知我的候选人们
    for (T currentNeighbor : connectedNeighbors) {
      // 声明一个临时算子，用于针对当前这个特定的邻居制定回复策略
      MaxOperator specificOperator = defaultOperator;

      // 身份核实：这个邻居是不是我已经锁死的那个唯一真爱？
      boolean isTheChosenOne = currentNeighbor.equals(this.lockedTargetNode);

      // 如果是自己人，反转评估算子的逻辑（比如把“最差”变成“最好”），发出热烈欢迎的信号
      if (isTheChosenOne) {
        specificOperator = defaultOperator.inverse();
      }

      // 生成最终要发送的信号代价（对于非绑定者，发送的永远是 WorstValue 最差惩罚值，即绝对拒绝）
      double signalValueToSend = specificOperator.getWorstValue();

      // 将决断电报发射给该邻居
      this.send(signalValueToSend, currentNeighbor);
    }

    // 返回本次发送广播的总人数
    return connectedNeighbors.size();
  }

  /**
   * 战地电报接收中枢。
   * 物理意义：负责接听其他智能体发来的意向电报。
   * 绝大多数时候，大家都在发送有限的概率数值进行反复拉扯（讨价还价）。
   * 但如果有人发来了无穷大（Infinity），代表他下达了“非你不可”或“绝对不行”的最后通牒。
   * 此时必须立刻启动最高级别的拓扑裁剪手术。
   * @param incomingUtility 接收到的代数偏好值（效益或惩罚）
   * @param sourceNode 发出这封电报的邻居智能体
   */
  @Override
  public void receive(double incomingUtility, T sourceNode) {
    // 利用包装类工具，判定这封电报是否是极端的“无穷大”通牒
    boolean isExtremeValue = Double.isInfinite(incomingUtility);

    // 如果只是一封普通的讨价还价电报（有限值）
    if (isExtremeValue == false) {
      // 直接移交给底层车间进行常规的数学求和或极值迭代
      super.receive(incomingUtility, sourceNode);
      // 常规处理完毕，安全退出
      return;
    }

    // 如果收到了极端通牒，先核实对方是否真的是我的直接连通邻居
    boolean isDirectNeighbor = this.getNeighbors().contains(sourceNode);

    // 只有合法的邻居发来的通牒才予以处理
    if (isDirectNeighbor) {
      // 向系统索要我们目前能容忍的最烂底线（最差惩罚值）
      final double lowestUtilityLimit = this.getMaxOperator().getWorstValue();

      // 判定意图：如果对方发来的无穷大不等于我们的最差底线，说明对方是强烈的正向激活（强制绑定我）
      boolean isForcedActive = (lowestUtilityLimit != incomingUtility);

      // 既然对方已经摊牌，我们就立刻启动局部拓扑切割手术
      this.reduce(sourceNode, isForcedActive);

      // 回复确认电报，终结这个维度上的后续废话协商
      this.send(incomingUtility, sourceNode);
    }
  }

  /**
   * 最终决断提取接口。
   * 物理意义：当所有的协商尘埃落定后，车辆的大脑会调用此方法来询问：“我到底该去救谁？”
   * @return 最终选定的那个唯一目标实体
   */
  @Override
  public T select() {
    // 优雅的双分支判定（彻底打乱了原版的三元运算符 AST 结构）
    if (this.lockedTargetNode == null) {
        // 如果没人对我下达强制绑定通牒，那我就靠底层算法的概率收敛结果，自己挑一个最好的
        return super.select();
    } else {
        // 如果早就名花有主了，直接返回锁死的那个人
        return this.lockedTargetNode;
    }
  }

  /**
   * 因子身份烙印输出。
   * 物理意义：在控制台打印排错或者绘制神经网络图时，将自己高亮标记为排他性选择节点。
   * @return 格式化后的身份字符串
   */
  @Override
  public String toString() {
    // 提取底层的唯一识别码并组装
    String uniqueIdentity = String.valueOf(this.getIdentity());
    return "Selector[" + uniqueIdentity + "]";
  }

  /**
   * 局部拓扑切除手术刀（私有方法）。
   * 物理意义：既然已经确定了某个邻居的“去留”，为了节省宝贵的算力，
   * 必须将其从当前的联络名单中彻底删掉（物理断连）。
   * 更有趣的是，如果剪到最后发现我只剩下一个邻居了，那没得挑，只能强制跟他绑定！
   * @param targetNode 已经表明最后立场的那个邻居
   * @param isForcedActive 他的立场是否是“强制占有我”
   */
  private void reduce(T targetNode, boolean isForcedActive) {
    // 如果这个邻居说“我非你不可”
    if (isForcedActive) {
      // 那就恭敬不如从命，立刻将他登记为我终身绑定的唯一伴侣
      this.lockedTargetNode = targetNode;
    }

    // 不管他是占有我还是拒绝我，既然关系已经彻底明朗，就剪断和他的协商连线
    this.removeNeighbor(targetNode);

    // 回头检查：如果我目前还是单身（还没人强制占有我）
    if (this.lockedTargetNode == null) {
      // 盘点一下我通讯录里还剩几个可以联系的候选人
      final List<T> remainingCandidates = this.getNeighbors();
      int remainingCount = remainingCandidates.size();

      // 绝境逼定法则：如果通讯录里被删得只剩下最后 1 个人了
      if (remainingCount == 1) {
        // 别无选择，直接将这最后的独苗锁死为终身伴侣
        this.lockedTargetNode = remainingCandidates.get(0);
      }
    }
  }
}
