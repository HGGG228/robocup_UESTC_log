package UESTC.module.complex.dcop;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.*;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.communication.standard.bundle.centralized.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.complex.PoliceTargetAllocator;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import rescuecore2.misc.geometry.*;
import es.csic.iiia.bms.*;
import es.csic.iiia.bms.factors.*;
import es.csic.iiia.bms.factors.CardinalityFactor.CardinalityFunction;
import java.util.*;

/**
 * OptimumPatrolAssigner 警察部队分布式目标分配器（基于 Max-Sum 算法）。
 * 物理意义：地震后全城道路被碎石封锁，消防车和救护车寸步难行。
 * 警察部队的任务是拿着电锯去清理这些堵塞的路段（Road）。
 * 这是一个典型的多对多资源分配问题：
 * 1. 变量节点：每名警察，一次只能去清理一条路。
 * 2. 因子节点：每条被堵的道路，对警察的数量有基数需求。
 * 3. 惩罚代价：道路面积越大、距离越远，清理的优先级越不同。如果是有别的兵种专门求救打通的道路，则拥有最高优先级。
 * 本类通过 DCOP 算法让警察互相博弈，达成全城清障的最优宏观调度。
 */
public class OptimumPatrolAssigner extends PoliceTargetAllocator {

  /**
   * 官方警察局实体标识。
   * 物理意义：警察指挥中心的 URN，用于推举全城清障行动的总指挥。
   */
  private final static StandardEntityURN HEADQUARTERS_URN = POLICE_OFFICE;
  
  /**
   * 官方警察兵种实体标识。
   * 物理意义：一线作战警察的 URN，用于统计全城可用警力。
   */
  private final static StandardEntityURN OFFICER_URN = POLICE_FORCE;
  
  /**
   * 算法协商最大迭代次数。
   * 物理意义：各名警察之间通过电报互发清障意向、讨价还价的轮数，保证数学模型收敛。
   */
  private final static int MAX_NEGOTIATION_ROUNDS = 100;
  
  /**
   * 道路堵塞基础惩罚值。
   * 物理意义：如果一条道路被堵死且没人去管，系统默认承受 300.0 的交通瘫痪惩罚。
   */
  private final static double DEFAULT_BLOCK_PENALTY = 300.0;
  
  /**
   * 虚拟闲逛任务。
   * 物理意义：当全城道路畅通时，赋予警察一个虚拟任务，防止算法因无事可做而崩溃。
   */
  private final static EntityID IDLE_PATROL_TASK = new EntityID(-1);

  /**
   * 最终清障派发单。
   * 物理意义：算法收敛后的最终决定，记录了具体的警察 ID（Key）被派往了哪条道路 ID（Value）去清障。
   */
  private final Map<EntityID, EntityID> finalAllocationMap = new HashMap<>();
  
  /**
   * 全城警力作战序列。
   * 物理意义：记录所有可以参与本次统筹调度的警察身份证号。
   */
  private Set<EntityID> activeOfficers = new HashSet<>();
  
  /**
   * 待清理道路清单。
   * 物理意义：记录所有存在碎石路障，需要派人去清理的道路 ID 集合。
   */
  private final Set<EntityID> clearingTargets = new HashSet<>();
  
  /**
   * 畅通道路黑名单。
   * 物理意义：从汇报电报中得知已经清理干净的道路，从清障名单中剔除。
   */
  private final Set<EntityID> clearedRoads = new HashSet<>();

  /**
   * DCOP 分布式因子图网络。
   * 物理意义：承载着所有警察大脑（变量）和待清理道路（因子）的内存推演沙盘。
   */
  private final Map<EntityID, Factor<EntityID>> dcopNetworkNodes = new HashMap<>();
  
  /**
   * 战地局域网通信基站。
   * 物理意义：用于在各个警察节点之间中转协商报价单。
   */
  private final UESTCDataTransceiver radioStation;

  /**
   * 道路面积权重比率映射表。
   * 物理意义：路越宽、面积越大，清理起来越费劲。这个表记录了全城每条路的面积占最大面积的百分比，用于计算惩罚系数。
   */
  private final Map<EntityID, Double> roadAreaRatios = new HashMap<>();
  
  /**
   * 紧急求救清障名单。
   * 物理意义：如果有救护车或消防车发来急电说“我被堵住了，快来救我”，这些道路将被列入此最高优先级名单。
   */
  private final Set<EntityID> emergencyRequestedRoads = new HashSet<>();

  /**
   * 本回合通信确认名单。
   * 物理意义：记录本回合成功连接电台的警察，用于校验网络是否完全连通。
   */
  private final Set<EntityID> acknowledgedOfficers = new HashSet<>();

  /**
   * 警察统筹指挥官构造器。
   */
  public OptimumPatrolAssigner(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);
    this.radioStation = new UESTCDataTransceiver();
  }

  /**
   * 战术结果提取口。
   * 物理意义：向上层动作执行模块上交本回合深思熟虑后得出的全城清障派工单。
   */
  @Override
  public Map<EntityID, EntityID> getResult() {
    return this.finalAllocationMap;
  }

  /**
   * 核心清障统筹大脑运算引擎。
   * 物理意义：每一秒钟，警察指挥官都要审时度势。通过构建图论网络，让警察和道路在虚拟沙盘中进行百轮砍价。
   */
  @Override
  public OptimumPatrolAssigner calc() {
    // 销毁上一秒的过期派工单
    this.finalAllocationMap.clear();
    
    // 如果作战序列空空如也，立即去系统花名册里把全城的警察抓取过来
    if (this.activeOfficers.isEmpty()) {
      this.initializeAgents();
    }
    
    // 评估是否有必要进行全城大统筹
    boolean isAllocationNeeded = this.have2allocate();
    if (isAllocationNeeded == false) {
      return this;
    }

    // 第一阶段：战情勘察。筛选全城需要清理的道路，并特别关照被紧急求救的区域。
    this.initializeTasks();
    
    // 第二阶段：搭建推演沙盘。为警察和道路建立数学节点并连线。
    this.initializeFactorGraph();

    // 第三阶段：沙盘推演（Max-Sum 消息传递）
    for (int currentRound = 0; currentRound < MAX_NEGOTIATION_ROUNDS; ++currentRound) {
      for (Factor<EntityID> nodeBrain : this.dcopNetworkNodes.values()) {
        nodeBrain.run();
      }
      this.radioStation.execute(this.dcopNetworkNodes);
    }

    // 第四阶段：收集推演决议，签发真实派工单
    for (EntityID officerId : this.activeOfficers) {
      Factor<EntityID> brainNode = this.dcopNetworkNodes.get(officerId);
      EntityID chosenRoad = selectTask((ProxyFactor<EntityID>) brainNode);
      
      if (chosenRoad.equals(IDLE_PATROL_TASK)) {
        chosenRoad = null;
      }
      this.finalAllocationMap.put(officerId, chosenRoad);
    }

    // 战报清点：数一数到底有多少警察被派去干活了
    long dispatchedCount = 0;
    for (EntityID officerId : this.activeOfficers) {
        if (this.finalAllocationMap.get(officerId) != null) {
            dispatchedCount++;
        }
    }
    System.out.println("POLICE ALLOCATOR -> 成功分派清障任务的警察数量: " + dispatchedCount);

    return this;
  }

  /**
   * 战地情报汇总台。
   * 物理意义：负责监听来自前线的电报，修正路网堵塞情况，并接收兄弟部队的求救信号。
   */
  @Override
  public PoliceTargetAllocator updateInfo(MessageManager mm) {
    super.updateInfo(mm);
    if (this.getCountUpdateInfo() >= 2) {
      return this;
    }

    this.acknowledgedOfficers.clear();

    // 1. 查收并处理【路况勘探报告】
    Collection<CommunicationMessage> roadMessages = mm.getReceivedMessageList(MessageRoad.class);
    for (CommunicationMessage rawMsg : roadMessages) {
        MessageRoad roadMsg = (MessageRoad) rawMsg;
        MessageUtil.reflectMessage(this.worldInfo, roadMsg);
    }

    // 2. 查收并处理【警察同行定位报告】
    Collection<CommunicationMessage> pfMessages = mm.getReceivedMessageList(MessagePoliceForce.class);
    for (CommunicationMessage rawMsg : pfMessages) {
        MessagePoliceForce pfMsg = (MessagePoliceForce) rawMsg;
        MessageUtil.reflectMessage(this.worldInfo, pfMsg);
        
        EntityID senderId = pfMsg.getAgentID();
        Human officerBody = (Human) this.worldInfo.getEntity(senderId);
        
        if (officerBody != null) {
            officerBody.undefineX();
            officerBody.undefineY();
        }
        this.acknowledgedOfficers.add(senderId);
    }

    // 3. 查收并处理【紧急清障求救指令】
    // 物理意义：其他兵种（如救护车）发现路被堵死过不去时，会向系统大喊“清除这条路！”
    Collection<CommunicationMessage> pfCommands = mm.getReceivedMessageList(CommandPolice.class);
    for (CommunicationMessage rawMsg : pfCommands) {
      CommandPolice command = (CommandPolice) rawMsg;
      
      // 必须是全网广播的紧急指令
      if (!command.isBroadcast()) {
        continue;
      }
      // 必须明确指定了是哪条路被堵了
      if (!command.isTargetIDDefined()) {
        continue;
      }
      // 指令类型必须明确是“请求清除（ACTION_CLEAR）”
      if (command.getAction() != CommandPolice.ACTION_CLEAR) {
        continue;
      }

      // 将这条路列入最高优先级的“紧急求救清障名单”
      this.emergencyRequestedRoads.add(command.getTargetID());
    }

    // 4. 查收并处理【已清空路段结案报告】
    Collection<CommunicationMessage> repMessages = mm.getReceivedMessageList(MessageReport.class);
    for (CommunicationMessage rawMsg : repMessages) {
        MessageReport repMsg = (MessageReport) rawMsg;
        if (repMsg.isFromIDDefined()) {
            this.clearedRoads.add(repMsg.getFromID());
        }
    }

    return this;
  }

  /**
   * 灾前数据预处理（断点续传恢复）。
   * 物理意义：在比赛开始前的系统预计算阶段，提前算出全城所有道路的面积比例，免得比赛中实时算卡死。
   */
  @Override
  public PoliceTargetAllocator resume(PrecomputeData pd) {
    super.resume(pd);
    if (this.getCountResume() >= 2) {
        return this;
    }

    this.calculateRoadAreaRatios();
    return this;
  }

  /**
   * 灾前数据预处理（现场计算）。
   * 物理意义：如果没有缓存文件，就在开局第一秒当场算出全城道路面积比例。
   */
  @Override
  public PoliceTargetAllocator preparate() {
    super.preparate();
    if (this.getCountPreparate() >= 2) {
        return this;
    }

    this.calculateRoadAreaRatios();
    return this;
  }

  /**
   * 道路面积归一化引擎（私有提取）。
   * 物理意义：遍历全城的马路和消防栓区域，算出它们物理面积占全城最大马路的比例。
   * 这个比例在后面算惩罚时，直接充当权重系数（大路比小路更难清，也更值得清）。
   */
  private void calculateRoadAreaRatios() {
    final Map<EntityID, Double> absoluteAreas = new HashMap<>();
    Collection<StandardEntity> roadEntities = this.worldInfo.getEntitiesOfType(ROAD, HYDRANT);
    
    // 逐个丈量面积
    for (StandardEntity entity : roadEntities) {
        Area mapArea = (Area) entity; 
        absoluteAreas.put(mapArea.getID(), computeArea(mapArea));
    }

    // 找出全城最宽阔的那片区域
    double maxArea = 1.0;
    for (Double currentArea : absoluteAreas.values()) {
        if (currentArea > maxArea) {
            maxArea = currentArea;
        }
    }

    // 计算每条路相对于最大路的百分比率
    for (EntityID id : absoluteAreas.keySet()) {
        double currentArea = absoluteAreas.get(id);
        this.roadAreaRatios.put(id, currentArea / maxArea);
    }
  }

  /**
   * 几何面积换算器。
   * 物理意义：将底层的不规则多边形顶点数组，代入测绘公式计算出绝对面积数值。
   */
  private static double computeArea(Area area) {
    int[] rawVertices = area.getApexList();
    // 修复：原版库支持接收顶点数组，但如果必须转 List，可以直接传给 computeArea
    // 确保与底层 GeometryTools2D.computeArea 的签名一致
    List<Point2D> geometricPoints = GeometryTools2D.vertexArrayToPoints(rawVertices);
    return GeometryTools2D.computeArea(geometricPoints);
  }

  /**
   * 全局统筹必要性评估器。
   * 物理意义：如果指挥网络瘫痪，或者当前负责运算的并非“警察总局”，则立即放权，让一线警察自由除障。
   */
  private boolean have2allocate() {
    boolean isNetworkComplete = this.allCentersExists();
    if (isNetworkComplete == false) {
      return false;
    }
    
    int onlineCount = this.acknowledgedOfficers.size();
    int rosterCount = this.activeOfficers.size();
    if (onlineCount != rosterCount) {
      return false;
    }

    int minimumCenterValue = Integer.MAX_VALUE; 
    Collection<EntityID> centerIds = this.worldInfo.getEntityIDsOfType(HEADQUARTERS_URN);
    for (EntityID currentId : centerIds) {
        int idNumericValue = currentId.getValue();
        if (idNumericValue < minimumCenterValue) {
            minimumCenterValue = idNumericValue;
        }
    }

    final int mySelfId = this.agentInfo.getID().getValue();
    final int currentTimestep = this.agentInfo.getTime();
    final int ignoreThresholdTimestep = this.scenarioInfo.getKernelAgentsIgnoreuntil();
    
    boolean isReady = (currentTimestep >= ignoreThresholdTimestep);
    boolean isTheCommander = (mySelfId == minimumCenterValue);
    
    return isReady && isTheCommander;
  }

  /**
   * 联合指挥体系探针。
   */
  private boolean allCentersExists() {
    final int fsQuota = this.scenarioInfo.getScenarioAgentsFs();
    final int poQuota = this.scenarioInfo.getScenarioAgentsPo();
    final int acQuota = this.scenarioInfo.getScenarioAgentsAc();
    return fsQuota > 0 && poQuota > 0 && acQuota > 0;
  }

  /**
   * 警力档案入库。
   */
  private void initializeAgents() {
    Collection<EntityID> rawOfficers = this.worldInfo.getEntityIDsOfType(OFFICER_URN);
    this.activeOfficers = new HashSet<>(rawOfficers);
  }

  /**
   * 待清道路初筛漏斗。
   * 物理意义：全城的路很多，警察不能瞎跑。我们首先要求每个警察把他“脚底下”正踩着的路设为优先目标，
   * 其次把被紧急求救的路也加进来。最后剔除掉那些已经通畅的路。
   */
  private void initializeTasks() {
    this.clearingTargets.clear();
    Collection<StandardEntity> allPoliceEntities = this.worldInfo.getEntitiesOfType(POLICE_FORCE);
    List<EntityID> feetUnderTasks = new ArrayList<>();

    for (StandardEntity baseEntity : allPoliceEntities) {
        Human policeOfficer = (Human) baseEntity;  
        // 如果该警察确切知道自己在哪
        if (policeOfficer.isPositionDefined()) {
            EntityID currentPosition = policeOfficer.getPosition();
            // 提取他脚底下及附近的道路，列为他的初始分管责任区
            List<EntityID> extractedRoads = this.extractTasks(currentPosition);
            if (extractedRoads != null) {
                feetUnderTasks.addAll(extractedRoads);
            }
        }
    }

    // 汇总：将脚底下的路、和别人求救的路，统一列为全城的待清理沙盘目标
    this.clearingTargets.addAll(feetUnderTasks);
    this.clearingTargets.addAll(this.emergencyRequestedRoads);
    
    // 划掉已经清理完的通途
    this.clearingTargets.removeAll(this.clearedRoads);
  }

  /**
   * 物理坐标解析转换器。
   * 物理意义：人类所在的坐标（Position）有可能是一栋楼，但警察清理的是外面的路。
   * 所以如果警察身陷在一栋已经被清理干净的废墟里，或者身陷在一栋房子里，
   * 系统需要顺藤摸瓜，找出这栋房子大门外连接着的那些马路，作为真正的清障目标。
   */
  private List<EntityID> extractTasks(EntityID currentPosition) {
    StandardEntity locationEntity = this.worldInfo.getEntity(currentPosition);
    
    // 如果这个人站的地方连个能站脚的面积都没有，无法分析
    if (!(locationEntity instanceof Area)) {
      return null;
    }
    
    // 如果他站的地方不是马路（说明在房子里），或者他脚下这条马路已经清理干净了（在黑名单里）
    if (!(locationEntity instanceof Road) || this.clearedRoads.contains(currentPosition)) {
      Area buildingArea = (Area) locationEntity;
      List<EntityID> adjacentRoads = new ArrayList<>();
      
      // 遍历这栋房子的四周，寻找连通的马路
      for (EntityID neighborId : buildingArea.getNeighbours()) {
          StandardEntity neighborEntity = this.worldInfo.getEntity(neighborId);
          // 只要大门外连接的是马路，就把它作为这名警察的清障出口
          if (neighborEntity instanceof Road) {
              adjacentRoads.add(neighborId);
          }
      }
      return adjacentRoads;
    }

    // 如果他正正当当地站在一条还没清理干净的马路上，那目标就是他脚下这条路
    List<EntityID> directRoad = new ArrayList<>();
    directRoad.add(currentPosition);
    return directRoad;
  }
  
  /**
   * DCOP 神经沙盘铺设车间。
   */
  private void initializeFactorGraph() {
    this.initializeVariableNodes(this.activeOfficers);
    this.initializeFactorNodes(this.clearingTargets);
    this.connectNodes(this.activeOfficers, this.clearingTargets);
  }

  /**
   * 创建警察大脑节点（排他选择器）。
   */
  private void initializeVariableNodes(Collection<EntityID> officerIds) {
    for (EntityID currentOfficerId : officerIds) {
        Factor<EntityID> innerSelector = new ChoiceHeuristic<>();
        WeightingFactor<EntityID> brainNode = new WeightingFactor<>(innerSelector);
        
        brainNode.setMaxOperator(new Minimize());
        brainNode.setIdentity(currentOfficerId);
        brainNode.setCommunicationAdapter(this.radioStation);
        
        this.dcopNetworkNodes.put(currentOfficerId, brainNode);
    }
  }

  /**
   * 创建道路呼救器节点（容量因子）。
   */
  private void initializeFactorNodes(Collection<EntityID> roadIds) {
    for (EntityID currentRoadId : roadIds) {
      CardinalityFactor<EntityID> roadNode = new SetSizeMultiplier<>();
      
      CardinalityFunction dynamicBlockFunc = new CardinalityFunction() {
          @Override
          public double getCost(int activeOfficerCount) {
              return computePenalty(currentRoadId, activeOfficerCount);
          }
      };
      
      roadNode.setFunction(dynamicBlockFunc);
      roadNode.setMaxOperator(new Minimize());
      roadNode.setIdentity(currentRoadId);
      roadNode.setCommunicationAdapter(this.radioStation);
      
      this.dcopNetworkNodes.put(currentRoadId, roadNode);
    }
  }

  /**
   * 物理连线织网工。
   * 物理意义：在沙盘上把人和路连起来。通过距离排序，截断过于遥远的连接以节省算力。
   */
  private void connectNodes(Collection<EntityID> variableNodeIds, Collection<EntityID> factorNodeIds) {
    for (EntityID vNodeId : variableNodeIds) {
      
      List<EntityID> sortedRoads = new ArrayList<>(factorNodeIds);
      Collections.sort(sortedRoads, new Comparator<EntityID>() {
          @Override
          public int compare(EntityID road1, EntityID road2) {
              if (road1.equals(IDLE_PATROL_TASK)) return -1;
              if (road2.equals(IDLE_PATROL_TASK)) return 1;
              
              double dist1 = worldInfo.getDistance(road1, vNodeId);
              double dist2 = worldInfo.getDistance(road2, vNodeId);
              return Double.compare(dist1, dist2);
          }
      });

      for (EntityID fNodeId : sortedRoads) {
        WeightingFactor<EntityID> vNode = (WeightingFactor<EntityID>) this.dcopNetworkNodes.get(vNodeId);
        Factor<EntityID> fNode = (Factor<EntityID>) this.dcopNetworkNodes.get(fNodeId);
        
        vNode.addNeighbor(fNodeId);
        fNode.addNeighbor(vNodeId);
        
        double distancePenalty = this.computePenalty(vNodeId, fNodeId);
        vNode.setPotential(fNodeId, distancePenalty);
      }
    }
  }

  /**
   * 大脑开颅手术刀。
   */
  private static EntityID selectTask(ProxyFactor<EntityID> proxyShell) {
    SelectorFactor<EntityID> innerCore = (SelectorFactor<EntityID>) proxyShell.getInnerFactor();
    return innerCore.select();
  }

  /**
   * 距离惩罚计算仪（人与路之间）。
   * 物理意义：去某条路清障，路上耗费的时间就是惩罚。
   */
  private double computePenalty(EntityID policeAgent, EntityID targetRoad) {
    if (targetRoad.equals(IDLE_PATROL_TASK)) {
      return 0.0;
    }

    final double rawDistance = this.worldInfo.getDistance(policeAgent, targetRoad);
    return rawDistance / (42000.0 / 1.5);
  }

  /**
   * 拥堵危害惩罚计算仪（路面容量代价）。
   * 物理意义：这条路该不该清，优先级多高，全看这里！
   * 如果没人去清，基础扣 300 分。但是这 300 分还要乘以一个极具战术意义的比率（rate）：
   * 1. 基础情况：按照这条路的面积百分比给分。大马路权重大，小胡同权重小。
   * 2. 战术加成 1：如果这条马路直接连着一栋建筑物（大门入口），系数强行飙升到 1.5 倍！因为堵住了大门，里面的人就出不来，救护车也进不去。
   * 3. 战术加成 2：如果这条路是被系统专门点名“求救（requested）”的，系数无条件飙升至最高 2.0 倍！
   */
  private double computePenalty(EntityID targetRoad, int assignedOfficerCount) {
    if (targetRoad.equals(IDLE_PATROL_TASK)) {
      return 0.0;
    }

    final Road roadEntity = (Road) this.worldInfo.getEntity(targetRoad);
    if (assignedOfficerCount == 0) {
      return DEFAULT_BLOCK_PENALTY;
    }

    // 默认一条路派 1 名警察足矣
    final double minimumRequired = 1.0;
    final double saturationRatio = Math.min((double) assignedOfficerCount, minimumRequired) / minimumRequired;

    // 调取在开局就计算好的本条道路面积百分比权重
    double tacticalRate = this.roadAreaRatios.getOrDefault(targetRoad, 1.0);

    // 战术侦测：检查这条路的尽头是否直接连着一栋房子
    boolean isBuildingEntrance = false;
    for (EntityID neighborId : roadEntity.getNeighbours()) {
        StandardEntity neighborEntity = this.worldInfo.getEntity(neighborId);
        if (neighborEntity instanceof Building) {
            isBuildingEntrance = true;
            break;
        }
    }

    // 战术提权 1：如果是建筑出口命脉，权重暴涨至 1.5
    if (isBuildingEntrance) {
      tacticalRate = 1.5;
    }
    // 战术提权 2：如果是被紧急求救打通的生命线，权重暴涨至 2.0 绝对优先
    if (this.emergencyRequestedRoads.contains(targetRoad)) {
      tacticalRate = 2.0;
    }

    // 综合结算本条道路的拥堵惩罚值，运用饱和度抛物线模型计算最终损失
    return DEFAULT_BLOCK_PENALTY * tacticalRate * (1.0 - Math.pow(saturationRatio, 2.0));
  }
}
