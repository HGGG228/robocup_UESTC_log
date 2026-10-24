package UESTC.module.complex.ambulanceteam; 


import java.util.*;


import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.CommandAmbulance;
import adf.core.agent.communication.standard.bundle.centralized.CommandFire;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.communication.standard.bundle.information.MessagePoliceForce;

import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.AbstractModule;


import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;


import static rescuecore2.standard.entities.StandardEntityURN.*;


import UESTC.module.complex.common.ObjectScreener;

/**
 * CitizenCommProtocol 类是救护车团队的“通信兵”与“任务调度台”。
 * 它的核心职责是：
 * 1. 监听和解析来自平民（Civilian）的求救电报。
 * 2. 观察四周，把那些需要挖掘（Rescue）或需要抬上车（Load）的人分类记在小本本上。
 * 3. 呼叫附近的消防员来帮忙挖人，或者呼叫其他救护车来帮忙拉人。
 */
public class CitizenCommProtocol extends AbstractModule {

  // 红色警报标志：用来记录“我这辆救护车自己是不是被埋在废墟里需要别人来救？”
  private boolean needRescueForMe = false;
  
  // 挖土任务白板：记录了所有身上压着废墟（Buriedness > 0），必须先挖出来才能救的平民 ID
  private Set<EntityID> rescueCivTask = new HashSet<>();
  
  // 友军遇难白板：记录了那些同样被废墟压住的消防员、警察或救护车同行 ID，他们优先度往往更高
  private Set<EntityID> rescueAgentTask = new HashSet<>();
  
  // 装车任务白板：记录了身上没压着土（Buriedness == 0）但受了伤，可以直接用担架抬上车的平民 ID
  private Set<EntityID> loadCivTask = new HashSet<>();
  
  // 电报发射记录册：记录已经向外发送过求救坐标的平民 ID，避免反复发送造成信道拥堵
  private Set<EntityID> sendTarget = new HashSet<>();
  
  // 协同搬运池：记录需要呼叫其他救护车同伴来帮忙搬运的任务清单
  private Set<EntityID> receiveLoadTask = new HashSet<>();

  // 物理声波常量：代表不使用无线电，而是用大喇叭进行近距离广播（不会占用服务器带宽）
  private final boolean VOICE = false;

  /**
   * 构造兵站：系统启动时，把地图、电台配置等所有必要物资交接给这位通信兵。
   */
  public CitizenCommProtocol(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd); // 基础物资上交给底层框架保管
    this.worldInfo = wi; // 留下地图复印件
    this.agentInfo = ai; // 留下自身档案复印件
  }

  /**
   * 每个时间步（Timestep）都会触发的生命周期函数：负责查收信件、整理账本、向外发报。
   */
  @Override
  public AbstractModule updateInfo(MessageManager mm) {
    this.receiveMessage(mm); // 第一步：打开电台，听取外面的求救信号
    this.updateTask();       // 第二步：拿出小本本，用橡皮擦掉那些已经死掉或者已经获救的任务
    this.sendMessage(mm);    // 第三步：拿起麦克风，向附近的友军呼叫增援
    return this;             // 汇报工作完毕，交还控制权
  }

  /**
   * 核心情报分析大脑：睁开眼睛观察周围发生改变的世界，把人们分发到不同的任务白板上。
   * 此方法通过庞大的条件分支网络完成对所有视野内活体的归类。
   */
  @SuppressWarnings("null")
  @Override
  public AbstractModule calc() {
    // 提取自己的工牌号码
    final EntityID me = this.agentInfo.getID();
    // 优先体检：问问自己是不是被房子砸到了需要挖土（Rescue）？
    this.needRescueForMe = this.rescueTarget(me);
    if (this.needRescueForMe) {
      // 泥菩萨过江自身难保，立刻停止一切调度计算工作，躺平呼救
      return this;
    }

    // 从视觉雷达中提取出这一秒钟以内所有状态发生改变的物体名单
    Set<EntityID> changes = this.worldInfo.getChanged().getChangedEntities();
    // 把自己也强行加入分析名单，以防遗漏自身的状态变更
    changes.add(me);
    
    // 准备三个临时的文件夹，用来暂时存放发现了但不需要挖土的人员位置信息
    Map<EntityID, Set<EntityID>> civPos = new HashMap<>(); // 平民位置文件夹
    Map<EntityID, Set<EntityID>> ambPos = new HashMap<>(); // 救护车位置文件夹
    Map<EntityID, Set<EntityID>> fbPos = new HashMap<>();  // 消防车位置文件夹

    // 启动经典的游标遍历，挨个审查这些发生了改变的物体
    for (EntityID e : changes) {
      // 通过实体档案库还原出这个物体的物理原貌
      final StandardEntity se = this.worldInfo.getEntity(e);
      // 物种隔离：如果这不是一个拥有生命体征的人类（比如它是房子或者路障），跳过不看
      if (!(se instanceof Human)) {
        continue;
      }
      
      // 调取卫星定位，看这个人类目前脚下踩着的地砖到底是个啥建筑
      final StandardEntity targetPosition = this.worldInfo.getPosition(e);
      // 安全区豁免权：如果这个人此刻正舒舒服服地待在绝对安全的避难所（REFUGE）里
      if (targetPosition.getStandardURN().equals(REFUGE)) {
        continue; // 避难所里的人不需要任何救援，直接无视他
      }
      
      // 医疗门诊双重诊断：
      // 1. 他身上压着废墟吗？（需要叫人来挖）
      final boolean rescueTarget = this.rescueTarget(e);
      // 2. 他虽然没被压但还在流血吗？（可以直接抬上车）
      final boolean loadTarget = this.loadTarget(e);
      
      // 提取其官方身份编码，并提取他所在建筑物的门牌号
      final StandardEntityURN targetURN = se.getStandardURN();
      final EntityID targetPositionID = targetPosition.getID();
      
      // ----------- 身份鉴别与任务分发流水线 -----------
      
      // 情况 A：这倒霉蛋是我的同行，另一辆救护车（AmbulanceTeam）
      if (targetURN.equals(AMBULANCE_TEAM)) {
        if (rescueTarget) {
          this.rescueAgentTask.add(e); // 同行被压，十万火急，计入友军遇难白板
        } else {
          // 没被压，只是路过，记录下他的位置以备后用
          Set<EntityID> AMBs = ambPos.get(targetPositionID);
          if (AMBs == null) AMBs = new HashSet<>();
          AMBs.add(e);
          ambPos.put(targetPositionID, AMBs);
        }
      } 
      // 情况 B：这倒霉蛋是拿着水枪的消防员（FireBrigade）
      else if (targetURN.equals(FIRE_BRIGADE)) {
        if (rescueTarget) {
          this.rescueAgentTask.add(e); // 消防员被压，计入友军遇难白板
        } else {
          // 记录位置，以后可能求他帮忙挖土
          Set<EntityID> FBs = fbPos.get(targetPositionID);
          if (FBs == null) FBs = new HashSet<>();
          FBs.add(e);
          fbPos.put(targetPositionID, FBs);
        }
      } 
      // 情况 C：这是个拿警棍的警察（PoliceForce）
      else if (targetURN.equals(POLICE_FORCE)) {
        if (rescueTarget) {
          this.rescueAgentTask.add(e); // 警察被压，计入友军遇难白板
        }
      } 
      // 情况 D：这是最普通的平民老百姓（Civilian）
      else if (targetURN.equals(CIVILIAN)) {
        if (rescueTarget) {
          // 老百姓被压住了，极其危险！
          Set<EntityID> CIVs = civPos.get(targetPositionID);
          if (CIVs == null) CIVs = new HashSet<>();
          CIVs.add(e);
          civPos.put(targetPositionID, CIVs);
          this.rescueCivTask.add(e); // 正式列入挖土任务白板
        } else if (loadTarget) {
          // 没压住，可以直接搬
          this.loadCivTask.add(e); // 列入装车任务白板
        }
      }
    }
    return this; // 返回更新后的大脑分析器
  }

  // 默认提供空的任务指派，该类侧重于通信而非直接下发指令
  public EntityID getResult() {
    return null;
  }

  /**
   * 诊断器 A：判断这人是否需要“挖掘（Rescue）”。
   * 物理意义：在灾难中，楼塌了会把人埋在地下，产生 Buriedness 值。
   * 必须先把土挖开（让值清零），救护车才能拉人。
   */
  private boolean rescueTarget(EntityID e) {  
    final Human h = (Human) this.worldInfo.getEntity(e);  
    // 如果系统报告这是个“假死”任务（比如由于网络错乱导致人其实已经在避难所了）
    if (this.isFailedTask(h)) {  
        return false; // 假警报，不用救
    }  
    // 抽取生命值（HP）和埋藏深度（Buriedness）
    final int healthPoints = h.getHP();  
    final int buriedLevel = h.getBuriedness();  
    
    // 逻辑判定：只有当人还没死（HP不是0），且确实被土压着（Buriedness不是0）时，才需要挖掘。
    if (healthPoints == 0) {
        return false; // 人死了，不用挖了
    }
    if (buriedLevel == 0) {
        return false; // 没土压着，不用挖，直接拿担架抬
    }
    return true; // 既活着又被压，确诊需要挖掘！
  }

  /**
   * 诊断器 B：判断这人是否具备“装车（Load）”条件。
   * 物理意义：土已经被消防员挖干净了，伤员裸露在地表，可以直接抱上救护车。
   */
  private boolean loadTarget(EntityID e) {
    final Human h = (Human) this.worldInfo.getEntity(e);
    if (h == null) {
      return false; // 查无此人
    }
    final int hp = h.getHP();
    final int buried = h.getBuriedness();
    // 活着（血量大于0），且身上绝对干干净净没有一克废墟泥土压着（埋藏为0）
    if (hp > 0 && buried == 0) {
        return true;
    }
    return false;
  }

  /**
   * 电报分拣工：审阅单张电报，并调整任务白板。
   */
  private void handleMessage(MessageCivilian msg) {
    // 提取电报发信人的真实身份证号
    final EntityID targetID = msg.getAgentID();
    // 远程复诊：这发报人现在还需要被挖吗？
    if (this.rescueTarget(targetID)) {
      this.rescueCivTask.add(targetID); // 需要挖，挂上白板
    } else {
      this.rescueCivTask.remove(targetID); // 不需要挖了（可能死了或被救了），从白板上撕掉
    }
  }

  /**
   * 电台监听站：从混乱的无线电波中截取平民频道的消息。
   */
  private void receiveMessage(MessageManager mm) {
    // 获取所有的平民电波包裹
    final List<CommunicationMessage> cmsg = mm.getReceivedMessageList(MessageCivilian.class);
    // 经典迭代，逐一拆包分析
    for (CommunicationMessage tmp : cmsg) {
      final MessageCivilian message = (MessageCivilian) tmp;
      this.handleMessage(message); // 扔给分拣工作业
    }
  }

  /**
   * 指挥官喊话器：发现目标后，大声呼叫附近的警力、消防或救护同行来配合干活。
   */
  private void sendMessage(MessageManager mm) {
    // 派出私家侦探去扫视周围的实体
    final ObjectScreener filter = new ObjectScreener(this.worldInfo, this.agentInfo);
    // 第一眼：看看周围有没有提着水枪的消防员（FB）？消防员是挖废墟的主力！
    final EntityID FB = filter.getNearyFB();
    
    // 如果有消防员在旁边，并且咱们的任务白板上有被埋的倒霉同行（rescueAgentTask）
    if (FB != null && !this.rescueAgentTask.isEmpty()) {
      // 取出名册上的第一个倒霉同行
      final Iterator<EntityID> i = this.rescueAgentTask.iterator();
      final EntityID e = i.next();
      final StandardEntity se = this.worldInfo.getEntity(e);
      
      // 时分复用算法(TDM)防拥塞：利用周期取模运算，只在特定周期才通过电磁波(Radio)发信号，防止频道被挤爆
      final boolean sendRadio = this.agentInfo.getID().getValue() % 10 == this.agentInfo.getTime() % 10;
      
      // 针对不同兵种，下发不同格式的求援电报
      if (se instanceof AmbulanceTeam) {
        // 第一遍：用物理喇叭吼叫（VOICE），不用占电台
        mm.addMessage(new MessageAmbulanceTeam(VOICE, (AmbulanceTeam) se, CommandAmbulance.ACTION_MOVE, e));
        if (sendRadio) { // 如果恰好赶上发送窗口
          // 第二遍：为了保证绝对送达，连按两次发送按钮产生双重回声
          mm.addMessage(new MessageAmbulanceTeam(VOICE, (AmbulanceTeam) se, CommandAmbulance.ACTION_MOVE, e));
        }
      } else if (se instanceof FireBrigade) {
        mm.addMessage(new MessageFireBrigade(VOICE, (FireBrigade) se, CommandFire.ACTION_MOVE, e));
        if (sendRadio) {
          mm.addMessage(new MessageFireBrigade(VOICE, (FireBrigade) se, CommandFire.ACTION_MOVE, e));
        }
      } else if (se instanceof PoliceForce) {
        mm.addMessage(new MessagePoliceForce(VOICE, (PoliceForce) se, CommandPolice.ACTION_MOVE, e));
        if (sendRadio) {
          mm.addMessage(new MessagePoliceForce(VOICE, (PoliceForce) se, CommandPolice.ACTION_MOVE, e));
        }
      }
      i.remove(); // 已经呼叫过援兵了，把这任务划掉，免得下个回合重复喊
      return; // 这一回合喊话够累了，剩下的下回再说
    }

    // 重点重构区：安全无并发修改的经典搬运呼叫器
    // 如果有协同搬运任务（说明伤员太多我这辆车拉不下）
    if (!this.receiveLoadTask.isEmpty()) {  
      // 雷达搜索：附近有没有空闲的救护车兄弟（AT）？
      final EntityID AT = filter.getNearyAT();  
      if (AT != null) {  
          // 使用最稳健的游标迭代器（Iterator），绝不使用容易崩溃的 for-each 或 removeIf
          Iterator<EntityID> loadTaskIterator = this.receiveLoadTask.iterator();
          if (loadTaskIterator.hasNext()) { // 只要名单上还有人
              EntityID targetID = loadTaskIterator.next(); // 把这个重伤员的坐标抓出来
              // 对着那辆救护车兄弟大喊：“兄弟，快开车(ACTION_MOVE)去这个坐标(targetID)接人！”
              mm.addMessage(new CommandAmbulance(VOICE, AT, targetID, CommandAmbulance.ACTION_MOVE));  
              // 大喊完毕，安全地按下自毁按钮，把这个任务从名单里扣掉
              loadTaskIterator.remove(); 
          }
          return;  // 指挥完毕，收工
      }  
    }

    // 民事广播：如果没啥同行要救，就在系统频道里向大家通报我发现的平民坐标
    Set<EntityID> targets = new HashSet<>(this.rescueCivTask);
    targets.removeAll(this.sendTarget); // 过滤掉已经通报过的，防止成了复读机
    if (targets.isEmpty()) {
      targets = new HashSet<>(this.sendTarget); // 实在没事干，就把旧记录翻出来再喊一遍
    }
    if (targets.isEmpty()) {
      return; // 彻底无事可做
    }
    
    // 取出名单上的第一个老百姓
    final Civilian c = (Civilian) this.worldInfo.getEntity(targets.iterator().next());
    // 拟定一份关于这个老百姓的新闻通稿，用大喇叭播报出去
    mm.addMessage(new MessageCivilian(VOICE, c));
    this.sendTarget.add(c.getID()); // 盖上戳，标记“已发报”
  }

  /**
   * 账本大清洗中心（极其重要的重构：彻底摒弃 Java 8 函数式编程）
   * 每过一秒钟，战场环境瞬息万变，我们必须用最原始、最安全的橡皮擦把过期任务擦掉。
   */
  private void updateTask() {   
    // 1. 清洗平民挖掘账本
    Iterator<EntityID> civRescueIt = this.rescueCivTask.iterator();
    while (civRescueIt.hasNext()) { // 游标不断向下摸索
        EntityID id = civRescueIt.next();
        // 复查：这人还值得我费劲挖吗？
        if (!this.rescueTarget(id)) {
            civRescueIt.remove(); // 不值得了，用橡皮安全擦除
        }
    }

    // 2. 清洗同行挖掘账本
    Iterator<EntityID> agentRescueIt = this.rescueAgentTask.iterator();
    while (agentRescueIt.hasNext()) {
        EntityID id = agentRescueIt.next();
        if (!this.rescueTarget(id)) {
            agentRescueIt.remove(); 
        }
    }

    // 3. 清洗装车搬运账本
    Iterator<EntityID> loadIt = this.loadCivTask.iterator();
    while (loadIt.hasNext()) {
        EntityID id = loadIt.next();
        // 复查：这人现在还能直接抬上车吗？（说不定就在刚才这一秒，房子又塌了把他压住了，得改去挖土了）
        if (!this.loadTarget(id)) {
            loadIt.remove(); 
        }
    }

    // 4. 清洗已发送电报记录本（如果这人都不需要救了，那之前的通报记录也可以销毁了）
    Iterator<EntityID> sendIt = this.sendTarget.iterator();
    while (sendIt.hasNext()) {
        EntityID id = sendIt.next();
        if (!this.rescueTarget(id)) {
            sendIt.remove(); 
        }
    }
  }  

  /**
   * 错乱系统纠偏器：判断这个平民是不是因为地图 Bug 而产生的“假装需要救援”的幻象。
   */
  private boolean isFailedTask(Human civilian) {
    // 连位置都在地球上找不到的幽灵，肯定是假任务
    if (civilian.getPosition() == null) {
      return false;
    }
    // 如果这个人脚下踩的建筑物竟然是绝对安全的“避难所(REFUGE)”
    if (!this.worldInfo.getEntity(civilian.getPosition()).getStandardURN().equals(REFUGE)) {
      return false; // 他在野外或者危楼，那是真任务
    }
    // 避难所里是不可能压着人的，如果有，那是底层的锅，我们认定为失败/无效任务
    return true; 
  }
}
