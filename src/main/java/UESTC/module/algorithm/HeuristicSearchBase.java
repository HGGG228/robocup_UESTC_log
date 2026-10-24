package UESTC.module.algorithm;

import rescuecore2.worldmodel.EntityID;
import java.util.*;

public class HeuristicSearchBase extends ComputationCore {
    // 声明 HeuristicSearchBase 类并继承底层算法基类以获得标准化支持。

    public abstract static class HeuristicNode {
        // 定义表示空间网格代价的静态抽象内部类结构，将其提升至最前端优先编译。

        public HeuristicNode(EntityID spatialAnchor, EntityID predecessorRef, double historicalWeight, double heuristicForecast) {
            // 声明该启发式节点的全生命周期参数装配构造函数。
            this.spatialAnchor = spatialAnchor;
            // 绑定外部传入的自身实体主键。
            this.predecessorRef = predecessorRef;
            // 绑定外部传入的父级回溯路线指针。
            this.historicalWeight = historicalWeight;
            // 锁定该节点目前被核算出的已发生移动成本。
            this.heuristicForecast = heuristicForecast;
            // 锁定该节点被估算出的待发生启发预测成本。
        }
        // 结束内部启发节点类的构造流程配置。

        public final EntityID getAncestor() {
            // 提供读取节点历史溯源父级指针的公共访问器。
            return this.predecessorRef;
            // 直接对外暴露只读的拓扑属性 predecessorRef。
        }
        // 结束父级溯源指针访问器定义。

        public final double getFvalue() {
            // 提供获取节点总寻路权重的快速计算接口。
            return this.historicalWeight + this.heuristicForecast;
            // 直接将实际代价与预估代价相加，得出调度优先级核心参数。
        }
        // 权重评估计算方法定义结束。

        public abstract Collection<HeuristicNode> gatherNeighbors();
        // 留下抽象契约，供具体的环境子类去实现各自的邻居拓扑抓取。

        public final EntityID getID() {
            // 提供读取该节点自身物理实体标识的公共访问器。
            return this.spatialAnchor;
            // 直接对外暴露只读的物理属性 spatialAnchor。
        }
        // 结束本体标识访问器定义。

        public final double getHvalue() {
            // 提供读取节点剩余启发预估成本的公共访问器。
            return this.heuristicForecast;
            // 直接对外暴露只读属性 heuristicForecast。
        }
        // 结束预估代价访问器定义。

        public final double getGvalue() {
            // 提供读取节点累加实际移动成本的公共访问器。
            return this.historicalWeight;
            // 直接对外暴露只读属性 historicalWeight。
        }
        // 结束实际代价访问器定义。

        private final EntityID predecessorRef;
        // 声明用于指向上一步来路节点的拓扑回溯记忆指针，沉底排版。

        private final EntityID spatialAnchor;
        // 声明绑定该节点具体物理空间位置的不可变实体标识，沉底排版。

        private final double heuristicForecast; 
        // 声明用于存放向终点方向预估剩余启发距离的只读变量，沉底排版。

        private final double historicalWeight; 
        // 声明用于存放从起点累积真实物理移动损耗的只读变量，沉底排版。
    }
    // 封闭内部代价节点类的边界结构。

    @SuppressWarnings("rawtypes")
    // 压制底层的泛型未检查警告，以兼容老架构配置字典接口。
    public HeuristicSearchBase(Map configurationDictionary) {
        // 提供通过配置字典实例化启发式搜索模块的构造器入口。
        super(configurationDictionary);
        // 向上委托给基类，去完成基础环境变量的装配。
    }
    // 构造器初始化流程结束。

    public static List<EntityID> find(HeuristicNode originVertex, EntityID destinationIdentifier) {
        // 定义静态核心寻路算法入口，下沉至类结构末尾提供全局服务调度。

        if (originVertex == null || destinationIdentifier == null) {
            // 对传入的参数进行空值安全校验，避免无效计算。
            return null;
            // 若参数不合法则直接短路，返回空对象交差。
        }
        // 结束安全拦截判断模块。

        Map<EntityID, HeuristicNode> exploredNodesMap = new HashMap<>();
        // 初始化哈希表，用于充当已处理节点（闭合集）的注册表。

        Comparator<HeuristicNode> weightRanker = Comparator.comparingDouble(HeuristicNode::getFvalue);
        // 定义优先比较器，基于节点的综合代价值（F值）进行升序排序。
        
        Queue<HeuristicNode> openDiscoveryQueue = new PriorityQueue<>(weightRanker);
        // 实例化优先队列，作为 A* 算法向外辐射的开放探测边界。

        openDiscoveryQueue.add(originVertex);
        // 将寻路的初始原点压入探测队列中，正式启动算法流程。

        while (!openDiscoveryQueue.isEmpty() && !exploredNodesMap.containsKey(destinationIdentifier)) {
            // 开启主循环，持续探索直到队列穷尽或成功触达目标节点。
            
            HeuristicNode pivotVertex = openDiscoveryQueue.poll();
            // 从优先队列中弹出当前总体代价最低的节点作为激活态。
            
            EntityID pivotId = pivotVertex.getID();
            // 提取该激活节点对应的物理空间唯一标识符。

            if (exploredNodesMap.containsKey(pivotId)) {
                // 检查该物理节点是否已经在之前的更优路径中被处理并闭合。
                continue;
                // 如果已闭合，则直接跳过当前的重复状态延展操作。
            }
            // 完成对冗余循环节点处理的拦截与过滤。

            exploredNodesMap.put(pivotId, pivotVertex);
            // 确认该节点为当前最优状态，并将其正式注册到闭合集合中。

            pivotVertex.gatherNeighbors().stream()
                    // 收集当前节点的所有可达邻居，并转化为流水线数据流。
                    .filter(connectedPeer -> !exploredNodesMap.containsKey(connectedPeer.getID()))
                    // 通过流式过滤，剔除掉那些已经处于闭合态的陈旧邻居节点。
                    .forEach(openDiscoveryQueue::add);
                    // 将剩余合格的新邻居统一推送至优先队列，等待后续评估。
        }
        // 寻路状态树的空间扩展循环在此处正式结束。

        LinkedList<EntityID> resolvedRoute = new LinkedList<>();
        // 准备一个双向链表，专门用于装载最终的回溯路径结果。
        
        HeuristicNode lineageTracer = exploredNodesMap.get(destinationIdentifier);
        // 提取终点节点对象，将其作为路径逆推的回溯游标起点。

        while (lineageTracer != null) {
            // 开启回溯循环，直到游标触及没有父节点的起始锚点。
            
            resolvedRoute.addFirst(lineageTracer.getID());
            // 采用头插法，将当前游标代表的实体标识写入路径链表首部。
            
            lineageTracer = exploredNodesMap.get(lineageTracer.getAncestor());
            // 将游标顺着内部记录的血统指针，向上移动到其父节点位置。
        }
        // 结束路径序列的链式回溯与重构过程。

        return resolvedRoute;
        // 将拼装好的最终导航路线序列返回给上层调用者。
    }
    // 寻路主入口方法执行完毕。
}
// 封闭整个 A* 算法调度引擎的类作用域。
