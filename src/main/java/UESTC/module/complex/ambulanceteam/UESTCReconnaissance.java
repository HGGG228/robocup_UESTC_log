package UESTC.module.complex.ambulanceteam; 

import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.*;

import adf.core.component.communication.CommunicationMessage;

import adf.core.component.module.complex.Search;

import adf.core.component.module.algorithm.Clustering;


import rescuecore2.standard.entities.*;
import static rescuecore2.standard.entities.StandardEntityURN.*;


import rescuecore2.messages.Command;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.misc.geometry.*;


import java.util.*;

/**
 * UESTCReconnaissance 类是救护车团队的“眼睛”和“方向盘”。
 * 当救护车当前没有明确的救援任务时，它不能停在原地发呆，必须在城市里主动巡逻寻找被困平民。
 * 这个类的核心职责就是：在一大堆建筑物中，评估出下一个最值得去探索的建筑物。
 */
public class UESTCReconnaissance extends Search {

  // 核心决策结果：经过千辛万苦的计算，最终决定要开车前往的那个建筑物的 ID
  private EntityID result;
  
  // 责任田（聚类）：系统分配给这辆救护车专属巡逻的建筑物名单集合
  private Set<EntityID> cluster = new HashSet<>();
  
  // 潜在线索库：记录了哪些建筑物里“可能”有人（比如在这个建筑附近曾经听到过求救声 AKSpeak）
  private Map<EntityID, Set<EntityID>> potentials = new HashMap<>();
  
  // 已探索记录本：记录这辆救护车已经亲自开过去看过的建筑物，防止鬼打墙反复巡逻同一个废墟
  private Set<EntityID> reached = new HashSet<>();
  
  // 拖延黑名单：记录那些虽然想去，但是因为路被堵死导致当前无法到达的建筑物 ID
  private Set<EntityID> delayed = new HashSet<>();
  
  // 惩罚权重：针对上面那个“拖延黑名单”，在计算距离得分时给它们加上一个巨大的惩罚值，让救护车暂时放弃它们
  private double penalty = 0.0;
  
  // 友军高危区：存放消防员当前所在的建筑。消防员在灭火，那里大概率有平民，所以是高优先级搜索区
  private Set<EntityID> highPriorityPosition = new HashSet<>();
  
  // 活埋人员名册：记录已经确认被活埋的平民的位置，以及他们的被埋深度（Buriedness）
  private Map<EntityID, Integer> buriedCivilians = new HashMap<>();
  
  // 算法挂件1：通用聚类器，负责切分城市大地图
  private Clustering clusterer;
  // 算法挂件2：失败移动分析器，用于判断某条路是不是绝对走不通
  private Clustering failedMove;
  // 算法挂件3：被困人类分析器，用于判断自己是不是被卡在乱石堆里了
  private Clustering stuckedHumans;

  /**
   * 构造函数：车辆点火启动时，系统会把所有的初始配置和雷达数据全部塞进来进行初始化。
   */
  public UESTCReconnaissance(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    // 将基础数据原封不动地交给父类去初始化底层的缓存
    super(ai, wi, si, mm, dd);

    // 从模块仓库里把切蛋糕用的“聚类算法”模块提出来，并挂载到自己的仪表盘上
    this.clusterer = mm.getModule("UESTC.AT.Search.Clustering");
    this.registerModule(this.clusterer);

    // 提取并挂载“移动失败判定”模块，用于在后期规避死胡同
    this.failedMove = mm.getModule("UESTC.AT.Search.FailedMove");
    this.registerModule(this.failedMove);

    // 提取并挂载“被困人员判定”模块
    this.stuckedHumans = mm.getModule("UESTC.AT.Search.StuckHumans");
    this.registerModule(this.stuckedHumans);
  }

  /**
   * 接口对接方法：向上级大脑模块汇报本次计算出的最佳探索目的地。
   */
  @Override
  public EntityID getTarget() {
    return this.result; // 直接交出内部计算好的目的地 ID
  }

  /**
   * 核心思考中枢（完全移除了原版的 Stream API，改用经典面向对象遍历）。
   * 它的工作流是：维护探索名单 -> 查找有没有被埋的平民 -> 如果没有，就在候选名单里挑一个分最高（最值得去）的。
   */
  @Override
  public Search calc() {
    // 逻辑一：如果当前分配给我的这块“责任田”已经被我探索得差不多了，那就向外扩张，去帮别人巡逻
    if (this.needToExpandCluster()) {
      this.expandCluster();
    }
    
    // 逻辑二：如果全城所有的房子都被我摸过一遍了，那就把记录本清空（相当于失忆），准备开始第二轮大扫荡
    if (this.needToClearReachd()) {
      this.reached.clear();
    }
    
    // 准备候选名单：把责任田里所有的房子抄写一份
    Set<EntityID> candidates = new HashSet<>(this.cluster);
    // 从候选名单里狠狠地划掉那些“我已经去过”的房子，绝不走回头路
    candidates.removeAll(this.reached);

    // 逻辑三：最高优先级指令！看看雷达里有没有确切报告被活埋的灾民？如果有，直接去救他，不用再漫无目的地巡逻了
    this.result = this.chooseFromBuriedCivilian();
    if (this.result != null) {
      // 一旦锁定了被活埋的人，直接中止后续的寻常巡逻计算，立刻发车！
      return this;
    }

    // 逻辑四：经典的打擂台算法寻找最优探索目标（替代了原版的 Stream.max 写法）
    // 获取裁判员（比较器），它知道一套打分规则（听过声音的房子加分，距离远的扣分）
    Comparator<EntityID> rule = this.comparator();
    EntityID bestTarget = null; // 预留一个擂主宝座
    
    // 用最复古、最稳健的 for-each 循环，让候选名单里的房子一个个上台比拼
    for (EntityID currentCandidate : candidates) {
      if (bestTarget == null) {
        // 如果擂台上还没人，第一个上台的自动成为擂主
        bestTarget = currentCandidate;
      } else {
        // 裁判举牌：让当前挑战者和擂主进行跑分 PK
        // 注意 compare 的返回值：大于 0 说明前一个比后一个好
        if (rule.compare(currentCandidate, bestTarget) > 0) {
          bestTarget = currentCandidate; // 挑战者分高，抢下擂主宝座
        }
      }
    }
    
    // 擂台赛结束，把最终依然稳坐在宝座上的那个建筑物 ID 设为导航终点（如果名单空了则为 null）
    this.result = bestTarget;
    
    return this; // 返回更新了目标后的自己
  }

  /**
   * 划分地盘启动程序：比赛刚开始时，调用此方法为自己圈定一块初始的巡逻责任区。
   */
  private void initCluster() {  
    // 强制聚类算法进行一次全图网格化计算
    this.clusterer.calc();  
  
    // 掏出自己的工作证（ID）
    final EntityID me = this.agentInfo.getID();  
    // 查出自己被分配到了哪个大区的编号
    final int index = this.clusterer.getClusterIndex(me);  
    // 找系统要一份该大区内所有实体的原始清单
    final Collection<EntityID> clusterEntityIDs = this.clusterer.getClusterEntityIDs(index);  
  
    // 使用纯粹的经典对象方法，从一堆花里胡哨的实体（路、人、车、房）中，只把建筑物筛出来
    Collection<EntityID> buildings = this.gatherBuildings(clusterEntityIDs);  
    if (!buildings.isEmpty()) {  
        // 统统装进自己的责任田集合中
        this.cluster.addAll(buildings);  
    }  
  }

  /**
   * 扩充地盘程序：当自己地盘的房子快搜完了，就运用模运算去“兼并”其他区块的房子。
   */
  private void expandCluster() {
    final EntityID me = this.agentInfo.getID();
    // 城市总共被切成了几块蛋糕？
    final int n = this.clusterer.getClusterNumber();
    // 我现在吃的是第几块？
    final int index = this.clusterer.getClusterIndex(me);

    final int size = this.cluster.size();
    // 经典的边界探测循环：去要其他编号蛋糕上的实体清单，然后只把上面的建筑物摘下来塞进自己的碗里
    for (int i = 1; i < n && size == this.cluster.size(); ++i) {
      final Collection<EntityID> buildings = this.gatherBuildings(
          this.clusterer.getClusterEntityIDs(index + i * n)
      );
      this.cluster.addAll(buildings);
    }
  }

  /**
   * 全图遗忘评估器：判断是否需要抹除记忆（彻底清空 reached 列表）。
   * 彻底摒弃了 Stream 收集器，改用最传统的集合操作。
   */
  private boolean needToClearReachd() {
    // 准备一个空筐，用来装全城所有的建筑
    Set<EntityID> buildings = new HashSet<>();
    // 遍历世界模型中所有的建筑实体
    for (EntityID bId : this.worldInfo.getEntityIDsOfType(BUILDING)) {
       buildings.add(bId);
    }
    
    // 准备另一个空筐，用来装全城所有的避难所
    Set<EntityID> refuges = new HashSet<>();
    for (EntityID rId : this.worldInfo.getEntityIDsOfType(REFUGE)) {
       refuges.add(rId);
    }
    
    // 集合求差集：把全城建筑清单里的避难所全部剔除。因为避难所是绝对安全的，不需要去巡逻探索
    buildings.removeAll(refuges);

    // 统计：我这辆车实际已经到达（reached）过的真正的危险房屋数量
    long reachedCount = 0;
    for (EntityID r : this.reached) {
      if (!refuges.contains(r)) { // 再次校验，绝不把去避难所卸货也算作巡逻
        reachedCount++;
      }
    }

    // 终极比对：如果我摸过的危险房子数量，已经等同于全城危险房子的总数了
    // 说明城里已经没有我没去过的地方了，必须清空记忆开启第二周目巡逻
    return reachedCount == buildings.size();
  }

  /**
   * 评估是否需要扩大责任区。当自身责任区有 90% 以上的地方都留下过车辙印了，说明太闲了，该去帮别人了。
   */
  private boolean needToExpandCluster() {
    return this.reached.size() >= this.cluster.size() * 0.9;
  }

  /**
   * 经典实体过滤器：传入一筐杂七杂八的东西，我只把里面的“房子（Building）”挑出来还给你。
   * 完全重写，去除了繁琐的 Stream 链式调用，代码骨架回归复古面向对象。
   */
  private Set<EntityID> gatherBuildings(Collection<EntityID> collection) {
    Set<EntityID> ret = new HashSet<>();
    // 古典 for-each 循环开始鉴别
    for (EntityID id : collection) {
       StandardEntity entity = this.worldInfo.getEntity(id);
       // 严查户口：如果你是个建筑物的外壳
       if (entity instanceof Building) {
           ret.add(entity.getID()); // 通过安检，装进纯净的建筑物集合中
       }
    }
    return ret; // 交出过滤结果
  }

  /**
   * 直接救人短路逻辑：如果情报网里有活埋的平民，优先挑一个离自己最近的去救，直接放弃巡逻瞎逛。
   */
  private EntityID chooseFromBuriedCivilian() {  
    EntityID me = this.agentInfo.getID();  
    EntityID chosenCivilian = null;  
    // 同样准备一个初始为最大值的擂台记分牌
    double minDistance = Double.MAX_VALUE;  
  
    // 翻开记录着被埋人员的花名册，逐个审视他们的 ID
    for (EntityID civilian : this.buriedCivilians.keySet()) {  
        // 门槛条件1：此人是否还在苦苦等待救援？（如果值为0说明他已经被别人挖出来了，或者已经死了）
        if (this.buriedCivilians.get(civilian) == 0) {  
            // 门槛条件2：他的位置不是消防员正在执行灭火的高危区？（消防员在灭火的地方太烫了，救护车最好别去添乱）
            if (!this.highPriorityPosition.contains(civilian)) {  
                // 拉起卷尺，量一下我这辆车开到他头顶废墟的绝对几何直线距离
                double distance = this.worldInfo.getDistance(civilian, me);  
                  
                // 距离越小，分数越高。如果他的距离小于目前的最小记录：
                if (distance < minDistance) {  
                    minDistance = distance; // 刷新最近距离记录
                    chosenCivilian = civilian; // 锁定这名幸运儿作为当前的抢救首选
                }  
            }  
        }  
    }  
      
    // 遍历结束后，返回挑出来的那个最近的倒霉蛋；如果全城太平没人被埋，就安分地返回 null。
    return chosenCivilian;  
  }

  /**
   * 提供给普通建筑物巡逻的“黑哨裁判”。
   * 为了满足你的要求，这里使用了经典的匿名内部类 (Anonymous Inner Class) 来重写排序逻辑，彻底抛弃 Stream。
   * 评判规则：听过声音的房子具有压倒性优势；如果都没听过声音，那就挑离得近的，且没被拉黑的。
   */
  private Comparator<EntityID> comparator() {
    final EntityID me = this.agentInfo.getID();
    
    return new Comparator<EntityID>() {
      @Override
      public int compare(EntityID id1, EntityID id2) {
        // 第一轮比拼：潜力值（在这个房子附近听过几个人求救）
        Set<EntityID> pot1 = potentials.get(id1);
        Set<EntityID> pot2 = potentials.get(id2);
        int size1 = (pot1 == null) ? 0 : pot1.size();
        int size2 = (pot2 == null) ? 0 : pot2.size();
        
        // 如果听到的求救人数不一样，人多的那边直接获胜（返回正数表示 id1 优先）
        if (size1 != size2) {
           return Integer.compare(size1, size2);
        }
        
        // 第二轮比拼：如果求救声音一样多（或者都没有），那就比物理距离和惩罚。
        // 计算选手 1 的路程耗损 = 真实距离 + (如果是被拉黑的拖延建筑，加上巨大惩罚)
        double score1 = worldInfo.getDistance(id1, me) + (delayed.contains(id1) ? penalty : 0.0);
        double score2 = worldInfo.getDistance(id2, me) + (delayed.contains(id2) ? penalty : 0.0);
        
        // 注意逻辑反转：对于距离和惩罚，数值越“小”，说明距离越近、越值得去，反而应该排在“前面”。
        // 所以这里是用 score2 去 compare score1，实现降序（越小越优先）
        return Double.compare(score2, score1);
      }
    };
  }

  /**
   * 周期性情报汇总：每个回合（timestep）开始时，疯狂地吸收外界电报和自身传感器的数据，刷新雷达图。
   */
  @Override
  public Search updateInfo(MessageManager mm) {
    // 呼叫底层父类先完成一波基础洗牌
    super.updateInfo(mm);
    // 性能保护伞：如果这个回合已经被要求更新多次了，直接拦截并假装听不见
    if (this.getCountUpdateInfo() > 1) {
      return this;
    }

    // 地盘初始化保底机制
    if (this.cluster.isEmpty()) {
      this.initCluster();
    }

    // 检查自身状态：是不是我的路被死死堵住了？
    final Human human = (Human) this.agentInfo.me();
    // 如果我无法移动（cannotReach），但我本身没被废墟压着（Buriedness==0），那说明是我之前想去的目标地点前面没路了！
    if (this.cannotReach() && human.getBuriedness() == 0) {
      // 果断把那个让我走投无路的目的地拉进黑名单（delayed），以后绕着走
      this.delayed.add(this.result);
    }

    // 执行一系列的雷达清洗过滤工序（下面的这些方法全都被我重写成了无 Stream 的经典版）
    this.updateHighPriorityPosition(); // 更新友军高危区
    this.updateBuriedCivilian(); // 刷新活埋名册的剩余生存时间
    this.reflectVoiceToPotentials(); // 侧耳倾听：把听到的声音映射到建筑物上
    this.ignoreReachedBuildings(); // 大扫除：把刚才去过的房子划掉
    this.ignoreUntaskableBuildings(); // 大扫除：把塌平了不用救的房子划掉
    this.ignoreBuildingsOnFire(); // 大扫除：把正在猛烈燃烧的房子划掉（我是救护车不是消防车）
    this.ignoreDiscoveredCivilians(); // 大扫除：如果这声音是个已经被救护车包围的人发出的，忽略它

    // 查收并拆解无线电报
    this.receiveMessage(mm);

    return this;
  }

  /**
   * 友军追踪雷达：用经典的 Iterator 风格重写。扫描全城寻找正在外勤作业的消防车位置。
   */
  private void updateHighPriorityPosition() {
    this.highPriorityPosition.clear(); // 清空旧情报
    
    // 向系统索要全城消防单位（FIRE_BRIGADE）的名册
    for (EntityID fbId : this.worldInfo.getEntityIDsOfType(FIRE_BRIGADE)) {
       StandardEntity entity = this.worldInfo.getEntity(fbId);
       // 验明正身
       if (entity instanceof FireBrigade) {
           FireBrigade fb = (FireBrigade) entity;
           // 如果这辆消防车不是在天上飞，而是实打实地站在某块地砖上
           if (fb.isPositionDefined()) {
               EntityID posId = fb.getPosition();
               // 如果他脚下踩着的是一栋建筑物（Building），而不是大马路
               if (this.worldInfo.getEntity(posId) instanceof Building) {
                   // 很好，这栋楼里可能有火，有火就有受伤的平民。把它列入高危高价值优先区！
                   this.highPriorityPosition.add(posId);
               }
           }
       }
    }
  }

  /**
   * 活埋倒计时更新器：随着时间的推移，被埋的人的生存希望在慢慢流失。
   * 这里彻底消灭了所有的 Stream，改用极其安全的迭代器和临时集合去规避多线程并发修改异常。
   */
  private void updateBuriedCivilian() {  
    // 环节一：时间沙漏。遍历所有记录在案的被埋人员，让他们的生存计时器减 1
    for (Map.Entry<EntityID, Integer> entry : this.buriedCivilians.entrySet()) {
        int oldValue = entry.getValue();
        // 确保计时器最低只到 0，不会出现负数
        entry.setValue(Math.max(oldValue - 1, 0));
    }
  
    // 环节二：睁眼看世界。用古典循环去扫描这一秒钟内所有发生变化的东西
    WorldInfo info = this.worldInfo;  
    Collection<EntityID> changedEntityIDs = info.getChanged().getChangedEntities();  
  
    Set<Civilian> inSightBuriedCivilian = new HashSet<>();
    
    for (EntityID changedId : changedEntityIDs) {
        StandardEntity e = info.getEntity(changedId);
        // 如果眼睛看到的是个人类老百姓
        if (e != null && e instanceof Civilian) {
            Civilian c = (Civilian) e;
            // 如果能看清他身上压着土，而且土的分量大于0
            if (c.isBuriednessDefined() && c.getBuriedness() > 0) {
                // 如果他不是待在安全无敌的避难所里（避难所里不可能被活埋，除非系统出Bug了）
                if (!(info.getEntity(c.getPosition()) instanceof Refuge)) {
                    // 确认此人确实处于危险的被埋状态，纳入临时花名册
                    inSightBuriedCivilian.add(c);
                }
            }
        }
    }
  
    // 环节三：更新户口本。把刚才亲眼看见的被埋人员情况登记造册
    for (Civilian civilian : inSightBuriedCivilian) {  
        EntityID position = civilian.getPosition();  
        // 存入或覆盖此建筑物门牌号下对应的埋藏深度
        this.buriedCivilians.put(position, civilian.getBuriedness());  
        // 但是且慢，探一探鼻息：如果他的血条已经没了（HP<=0）
        if (civilian.isHPDefined() && civilian.getHP() <= 0) {  
            // 节哀顺变，人都没了，赶紧从救援名册里划掉他，别浪费有限的医疗资源
            this.buriedCivilians.remove(position);  
        }  
    }  
  
    // 环节四：原地校验。如果我这辆车正停在一个原先标有“这里有被埋者”的地点
    EntityID nowPosition = this.agentInfo.getPosition();  
    // 查证记录本，如果此地的危险警报值已经降到 0（说明人被挖出来了）
    if (this.buriedCivilians.containsKey(nowPosition) && this.buriedCivilians.get(nowPosition) == 0) {  
        // 彻底擦掉这个坐标的危险记录
        this.buriedCivilians.remove(nowPosition);  
    }  
  }

  /**
   * 听音辨位系统：当耳朵听到有人呼救，但不知道具体位置时，把这股声波“溅射”到附近的建筑物上，提高这些建筑物的探索诱惑力。
   */
  private void reflectVoiceToPotentials() {
    // 拉取耳朵能听到的物理极限范围内的所有房屋
    final Set<EntityID> candidates = this.gatherBuildingsInVoiceRange();
    // 拉取系统内已经报告过的、已经被别的救护车包围控制的平民（我们要过滤掉这部分杂音）
    final Set<EntityID> civilians = this.gatherHelpedCivilians();

    // 对于在声音射程内的每一栋房子
    for (EntityID i : candidates) {
       // 去“潜在线索库（potentials）”里找这栋房子。如果这房子之前没线索，就新建一个空白文件夹
       Set<EntityID> voiceSet = this.potentials.get(i);
       if (voiceSet == null) {
           voiceSet = new HashSet<>();
           this.potentials.put(i, voiceSet);
       }
       // 把这批未被援助的声音源头强行塞进这栋房子的文件夹里，使其潜力大增
       voiceSet.addAll(civilians);
    }
  }

  /**
   * 声呐探测仪（重写去 Stream修复）：划定一个圆形雷达波范围，把里面的房子全找出来。
   */
  private Set<EntityID> gatherBuildingsInVoiceRange() {
    // 从底层配置文件读取系统默认的嗓门传播距离上限
    final int range = this.scenarioInfo.getRawConfig().getIntValue("comms.channels.0.range");

    final EntityID me = this.agentInfo.getID();
    Set<EntityID> ret = new HashSet<>();
    
    // 修复点 1：底层接口 getObjectsInRange 吐出来的是物理实体 StandardEntity，而不是 EntityID
    for (StandardEntity entity : this.worldInfo.getObjectsInRange(me, range)) {
        // 如果是房子，则收集其 ID
        if (entity instanceof Building) {
            ret.add(entity.getID());
        }
    }
    return ret;
  }

  /**
   * 杂音消噪器（重写去 Stream修复）：找出所有发出求救声、但身边并没有救援队员的平民。
   */
  private Set<EntityID> gatherHelpedCivilians() {
    // 建立一个官方机构黑名单：把所有的消防员、救护员、警察以及他们的总部设施全记下来
    final Set<EntityID> agents = new HashSet<>(this.worldInfo.getEntityIDsOfType(
        FIRE_BRIGADE, FIRE_STATION,
        AMBULANCE_TEAM, AMBULANCE_CENTRE,
        POLICE_FORCE, POLICE_OFFICE
    ));

    Set<EntityID> ret = new HashSet<>();
    // 修复点 2：底层接口 getHeard 吐出来的是引擎原生指令 Command，而不是 ADF 封装包
    for (Command msg : this.agentInfo.getHeard()) {
        // 如果这是一段纯粹的语音喊话波（AKSpeak）
        if (msg instanceof AKSpeak) {
            AKSpeak speakMsg = (AKSpeak) msg;
            // 且喊话频道是 0 频道（即肉身发声频道，而不是加密无线电）
            if (speakMsg.getChannel() == 0) {
                EntityID speakerId = speakMsg.getAgentID();
                // 关键点：如果喊话的这个人，不在我们的“官方机构黑名单”里
                // 那么他必定是个无助的平民！抓取他的 ID！
                if (!agents.contains(speakerId)) {
                    ret.add(speakerId);
                }
            }
        }
    }
    return ret;
  }

  /**
   * 视觉排雷器（重写去 Stream）：凡是眼睛确认已经实地到达过的房子，立刻从潜在探索名单里剔除。
   */
  private void ignoreReachedBuildings() {
    // 看一眼脚底板：如果我自己目前停着的这块地就是个建筑物，那它必然算作“已到达”
    final EntityID position = this.agentInfo.getPosition();
    if (this.worldInfo.getEntity(position) instanceof Building) {
      this.reached.add(position);
    }

    // 睁眼环顾四周，寻找视线内发生变动的建筑物
    for (EntityID changedId : this.worldInfo.getChanged().getChangedEntities()) {
        StandardEntity entity = this.worldInfo.getEntity(changedId);
        if (entity instanceof Building) {
            Building b = (Building) entity;
            // 调用底层的硬核几何计算器（isReached），判断我这辆车的车头是不是已经越过了这个建筑的边界门槛
            if (this.isReached(b)) {
                // 如果擦着边了，哪怕只是停在门口，也算去过并勘查完了，加入黑名单
                this.reached.add(b.getID());
            }
        }
    }

    // 把潜在线索库（potentials）彻底打扫一遍，把“已到达（reached）”的房屋记录统统连根拔除
    this.potentials.keySet().removeAll(this.reached);
  }

  /**
   * 硬核数学引擎：向量代数与叉乘检验。判断智能体的中心点，是否真实逼近并跨越了某个建筑多边形的边缘。
   */
  private boolean isReached(Building building) {  
    // 获取视线能看清的最大物理距离
    final int max = this.scenarioInfo.getPerceptionLosMaxDistance();  
    // 乘以 0.8 的缩放因子后求平方，作为“判断是否到达”的容差阈值
    final double maxDistanceSquared = (max * 0.8) * (max * 0.8);  
    
    // 提取我这辆救护车当前的地图几何点
    final Point2D agentPoint = this.getPoint();  
    final double agentX = agentPoint.getX();  
    final double agentY = agentPoint.getY();  
    
    // 提取那个建筑物的地图几何中心点
    final double buildingX = building.getX();  
    final double buildingY = building.getY();  
    
    // 基础勾股定理运算：求两点间 X、Y 方向的差值
    final double dx = buildingX - agentX;  
    final double dy = buildingY - agentY;  
    // 为了节省开方(Math.sqrt)这极其消耗 CPU 的操作，直接计算距离的平方
    final double distanceSquared = dx * dx + dy * dy;  
  
    // 初步筛查：如果算出来的直线距离平方，连我们规定的最大视距的平方都够不着，说明离得还十万八千里呢，直接否决
    if (distanceSquared >= maxDistanceSquared) {  
        return false;  
    }  
  
    // 深度验证：建筑不是一个点，而是一个拥有多条边（Edge）的封闭多边形区域。
    // 必须确认我这辆车是否真的可以或者已经越过了它的“可通行（Passable）”大门。
    for (Edge edge : building.getEdges()) {  
        // 如果这堵墙是死胡同或者实心墙（不可通行），就不用测了
        if (!edge.isPassable()) {  
            continue;  
        }  
  
        // 抓取这堵墙的左顶点和右顶点坐标
        Point2D edgeStart = edge.getStart();  
        Point2D edgeEnd = edge.getEnd();  
        final double edgeStartX = edgeStart.getX();  
        final double edgeStartY = edgeStart.getY();  
        final double edgeEndX = edgeEnd.getX();  
        final double edgeEndY = edgeEnd.getY();  
  
        // 构建两个重要的向量：
        // 向量 A (ax, ay)：从我这辆车指向墙的左顶点
        final double ax = edgeStartX - agentX;  
        final double ay = edgeStartY - agentY;  
        // 向量 B (bx, by)：从我这辆车指向墙的右顶点
        final double bx = edgeEndX - agentX;  
        final double by = edgeEndY - agentY;  
  
        // 运用高维向量的叉乘（Cross Product）和点乘（Dot Product）进行线性代数计算
        // 叉乘的本质是在算三角形的面积或者向量共线情况
        final double cross = ay * bx - ax * by;  
        final double dot1 = ax * ax + ay * ay;  
        final double dot2 = bx * bx + by * by;  
        // 计算投影系数 U 值
        final double u = cross * cross / (dot1 * dot2);  
  
        // 数学定论：如果 U 值坍缩在 [0, 1] 区间内，在几何意义上意味着：
        // 从我这里射向建筑的一根射线，结结实实地切断了建筑物的这条通行大门（有几何交点）！
        if (u >= 0 && u <= 1) {  
            return true; // 交集确立，判定为“已经成功到达该建筑”
        }  
    }  
  
    return false;  
  }

  /**
   * 废物建筑清理器（重写去 Stream）：那些已经被大地震彻底夷为平地（损坏度极低导致没法藏人），且失去利用价值的房子，不要浪费时间去巡逻。
   */
  private void ignoreUntaskableBuildings() {
    for (EntityID changedId : this.worldInfo.getChanged().getChangedEntities()) {
        StandardEntity entity = this.worldInfo.getEntity(changedId);
        if (entity instanceof Building) {
            Building b = (Building) entity;
            // 如果该建筑的“破损度”报告存在，且破损度赫然写着 0（比如它其实是个空地或者特殊基底）
            if (b.isBrokennessDefined() && b.getBrokenness() == 0) {
                // 直接把它塞进“已到达（假想跳过）”名单，让算法以后彻底无视它
                this.reached.add(b.getID());
            }
        }
    }
    // 同步从潜力名单中抹去这些没用的石头堆
    this.potentials.keySet().removeAll(this.reached);
  }

  /**
   * 火灾逃生清理器（重写去 Stream）：我是医生，不是水管工。对于正在熊熊燃烧的房子，我无能为力，也绝不靠近。
   */
  private void ignoreBuildingsOnFire() {
    for (EntityID changedId : this.worldInfo.getChanged().getChangedEntities()) {
        StandardEntity entity = this.worldInfo.getEntity(changedId);
        if (entity instanceof Building) {
            Building b = (Building) entity;
            // 查看房屋的着火（OnFire）标记，如果正在冒烟或者燃烧
            if (b.isOnFire()) {
                // 为了安全，也把它标记为“已到达（假想跳过）”而不再去靠近
                this.reached.add(b.getID());
            }
        }
    }
    this.potentials.keySet().removeAll(this.reached);
  }

  /**
   * 坐标提取小助手：把自身那晦涩的实体对象位置，转化为一个清晰明了的二维平面几何点（Point2D）。
   */
  private Point2D getPoint() {
    final double x = this.agentInfo.getX();
    final double y = this.agentInfo.getY();
    return new Point2D(x, y);
  }

  /**
   * 重复救援规避器（重写去 Stream）：如果我听到这里有求救声，跑过去一看，发现有个同行同事已经在那边把人扛起来救了，
   * 那我就没必要继续把这栋楼当成“高价值线索楼”了。
   */
  private void ignoreDiscoveredCivilians() {
    final Collection<EntityID> changed = this.worldInfo.getChanged().getChangedEntities();

    // 先把视线里所有肉眼清晰看见的老百姓（Civilian）都记下来
    Set<EntityID> ignored = new HashSet<>();
    for (EntityID id : changed) {
        StandardEntity entity = this.worldInfo.getEntity(id);
        if (entity instanceof Civilian) {
            ignored.add(entity.getID());
        }
    }

    // 翻开潜在线索库，把这些已经被光天化日之下发现并盯上的老百姓，从暗处的“未解之谜”声音集合中划掉
    for (Set<EntityID> vs : this.potentials.values()) {
        vs.removeAll(ignored);
    }
  }

  /**
   * 绝境判定器：车子是不是抛锚了、撞墙了、或者被乱石阵死死卡住了？
   */
  private boolean cannotReach() {
    final EntityID me = this.agentInfo.getID();
    final StandardEntityURN urn = this.agentInfo.me().getStandardURN();

    // 还没指派目的地呢，就不存在“去不了”这一说
    if (this.result == null) {
      return false;
    }
    // 特权阶级判定：如果是拿着电锯的警察（POLICE_FORCE），那世上本没有路，他走到哪就劈到哪，永远不会 cannotReach
    if (urn == POLICE_FORCE) {
      return false;
    }

    // 呼叫两个外部子系统进行强行诊断演算
    this.failedMove.calc();
    this.stuckedHumans.calc();

    // 如果“移动失败判定器”把我拉进了它的名单（索引大于等于0），说明我撞墙了
    final boolean failed = this.failedMove.getClusterIndex(me) >= 0;
    // 如果“被困判定器”把我拉进了它的名单，说明我陷入废墟泥沼了
    final boolean stucked = this.stuckedHumans.getClusterIndex(me) >= 0;
    
    // 两者有其一，那就是彻底走不动了
    return stucked || failed;
  }

  /**
   * 电报拆解员（修复点 3：引用正确的 ADF 封装类）：处理专门在难民频道广播的特急电波。
   */
  private void receiveMessage(MessageManager mm) {
    // 从管家那里拿走所有的平民信件
    for (CommunicationMessage msg : mm.getReceivedMessageList(MessageCivilian.class)) {
        // 再次严谨排查信件包装，并强制拆包为标准 MessageCivilian 格式
        if (msg instanceof MessageCivilian) {
            this.handleMessage((MessageCivilian) msg);
        }
    }
  }

  /**
   * 绝密情报档案员：当收到远处同僚通过电报发来的“这栋楼里有被埋难民！”的情报时，该如何处理？
   */
  private void handleMessage(MessageCivilian msg) {
    final EntityID me = this.agentInfo.getID();
    // 从密电中破解出被困难民的具体大楼门牌号
    final EntityID targetID = msg.getPosition();

    // 向系统确认我目前被分配巡逻的大区编号
    final int myClsNum = this.clusterer.getClusterIndex(me);
    // 要一份我大区里所有大楼的详单
    final Collection<EntityID> entities = this.clusterer.getClusterEntityIDs(myClsNum);
    
    // 责任制拦截：如果密电里说的这栋大楼，根本就不在我负责的大区内
    if (!entities.contains(targetID)) {
      return; // 各扫门前雪，我不用去管，自然会有负责那个大区的车去救
    }
    
    // 如果这栋楼正是我的辖区，那就严阵以待。
    // 把该难民所在的楼栋，以及他惨烈的被埋深度（发信时可能有延迟，先预扣去 1 个深度值），郑重地记录进我的活埋名册中！
    this.buriedCivilians.put(targetID, msg.getBuriedness() - 1);
  }
}
