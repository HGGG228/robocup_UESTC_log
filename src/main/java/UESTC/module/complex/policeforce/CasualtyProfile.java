package UESTC.module.complex.policeforce;

import UESTC.module.complex.common.ObjectScreener;
import UESTC.module.complex.common.SurvivalChronicle;
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

import java.util.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

/**
 * CasualtyProfile 警察部队战地难民与战损情报处理中枢。
 * 物理意义：由于警察拿着电锯在全城开路，他们往往能比其他兵种更早地发现被废墟掩埋的平民。
 * 虽然警察自己不能挖掘和治病，但他们必须充当“战地眼线”，负责：
 * 1. 收集视野内的所有被埋人员，按兵种（平民、战友）登记造册。
 * 2. 清洗失效档案（死者或已经被救出的人）。
 * 3. 通过大喊（Voice）或电报（Radio）向附近的消防车和救护车通报目标位置，呼叫增援！
 */
public class CasualtyProfile extends AbstractModule {

  /**
   * 自身瘫痪标志。
   * 物理意义：如果警察自己都被倒塌的建筑压住了，就无心管别人了。
   */
  private boolean isMyselfEntrappedState = false;
  
  /**
   * 待挖掘平民名册。
   * 物理意义：被埋在废墟里，亟需消防队过来挖的平民。
   */
  private Set<EntityID> buriedCivilianLedger = new HashSet<>();
  
  /**
   * 待挖掘战友名册。
   * 物理意义：被埋的同行（消防、警察、救护）。战友获救能极大提升团队战斗力，优先级极高。
   */
  private Set<EntityID> buriedTeammateLedger = new HashSet<>();
  
  /**
   * 待装载平民名册。
   * 物理意义：没被埋但受了伤、流血不止的平民，需要呼叫救护车来接。
   */
  private Set<EntityID> bleedingCivilianLedger = new HashSet<>();
  
  /**
   * 已通报电台的平民名单。
   * 物理意义：为了防止重复刷屏发报，记录那些我已经向指挥部报告过的平民。
   */
  private Set<EntityID> voiceBroadcastedCivilians = new HashSet<>();

  // 固化通信频段标志（语音大喊）
  private final boolean VOICE_CHANNEL = false;

  /**
   * 情报中枢构造器。
   */
  public CasualtyProfile(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager mm,
      DevelopData dd) {
    super(ai, wi, si, mm, dd);
    this.worldInfo = wi;
    this.agentInfo = ai;
  }

  /**
   * 情报库更新生命周期方法。
   * 物理意义：每回合固定执行：收报 -> 洗牌 -> 发报。
   */
  @Override
  public AbstractModule updateInfo(MessageManager mm) {
    this.processIncomingTelegrams(mm);
    this.purgeInvalidRosters();
    this.broadcastCooperativeCommands(mm);
    return this;
  }

  /**
   * 全城检伤分类推演引擎。
   * 物理意义：每秒钟看一眼地图上发生变化的所有人。
   * 把他们分别塞进不同的救援需求库中。
   */
  @Override
  public AbstractModule calc() {
    final EntityID mySelfId = this.agentInfo.getID();
    
    // 伤情自检：如果我被埋了，直接罢工
    this.isMyselfEntrappedState = this.evaluateIfNeedsDigging(mySelfId);
    if (this.isMyselfEntrappedState) {
      return this;
    }

    Set<EntityID> visuallyChangedEntities = this.worldInfo.getChanged().getChangedEntities();
    visuallyChangedEntities.add(mySelfId);
    
    // 建立临时分类档案柜，用于记录各个兵种的位置
    Map<EntityID, Set<EntityID>> civLocationMap = new HashMap<>();
    Map<EntityID, Set<EntityID>> ambLocationMap = new HashMap<>();
    Map<EntityID, Set<EntityID>> fbLocationMap = new HashMap<>();

    // 逐一检阅视野中发生了变化的东西
    for (EntityID currentId : visuallyChangedEntities) {
      StandardEntity rawEntity = this.worldInfo.getEntity(currentId);
      // 警察的情报系统只关心人类死活
      if (!(rawEntity instanceof Human)) {
        continue;
      }
      
      StandardEntity safeHouseLocation = this.worldInfo.getPosition(currentId);
      // 安全屋豁免：已经在避难所里的绝对安全
      if (safeHouseLocation.getStandardURN().equals(REFUGE)) {
        continue;
      }
      
      // 检伤分类
      boolean requiresDigging = this.evaluateIfNeedsDigging(currentId);
      boolean requiresLoading = this.evaluateIfNeedsLoading(currentId);
      
      StandardEntityURN entityProfession = rawEntity.getStandardURN();
      EntityID physicalLocationId = safeHouseLocation.getID();
      
      // 按照兵种，将其分拨到不同的档案柜
      if (entityProfession.equals(AMBULANCE_TEAM)) {
        if (requiresDigging) {
          // 救护车被埋，拉响最高警报
          this.buriedTeammateLedger.add(currentId);
        } else {
          // 没被埋的救护车，记录位置
          Set<EntityID> squad = ambLocationMap.getOrDefault(physicalLocationId, new HashSet<>());
          squad.add(currentId);
          ambLocationMap.put(physicalLocationId, squad);
        }
      } else if (entityProfession.equals(FIRE_BRIGADE)) {
        if (requiresDigging) {
          this.buriedTeammateLedger.add(currentId);
        } else {
          Set<EntityID> squad = fbLocationMap.getOrDefault(physicalLocationId, new HashSet<>());
          squad.add(currentId);
          fbLocationMap.put(physicalLocationId, squad);
        }
      } else if (entityProfession.equals(POLICE_FORCE)) {
        if (requiresDigging) {
          this.buriedTeammateLedger.add(currentId);
        }
      } else if (entityProfession.equals(CIVILIAN)) {
        if (requiresDigging) {
          Set<EntityID> squad = civLocationMap.getOrDefault(physicalLocationId, new HashSet<>());
          squad.add(currentId);
          civLocationMap.put(physicalLocationId, squad);
          // 平民被埋，列入平民急救名单
          this.buriedCivilianLedger.add(currentId);
        } else if (requiresLoading) {
          // 没被埋但流血的平民，列入救护车装载名单
          this.bleedingCivilianLedger.add(currentId);
        }
      }
    }
    return this;
  }

  /**
   * 结果提取口（占位）。
   * 物理意义：本模块只做情报处理，不决策动作，所以返回 null。
   */
  public EntityID getResult() {
    return null;
  }

  /**
   * 挖掘需求评估仪。
   * 物理意义：被压在石头下（埋藏度 > 0）且还有一口气（HP > 0），才需要动用铁锹。
   */
  private boolean evaluateIfNeedsDigging(EntityID suspectId) {
    final Human victimBody = (Human) this.worldInfo.getEntity(suspectId);
    final int currentHp = victimBody.getHP();
    final int rubbleDepth = victimBody.getBuriedness();
    return (currentHp * rubbleDepth != 0);
  }

  /**
   * 担架装载需求评估仪。
   * 物理意义：没被石头压住（埋藏度 == 0），但血没满（受伤了），说明他需要上救护车。
   */
  private boolean evaluateIfNeedsLoading(EntityID suspectId) {
    final Human victimBody = (Human) this.worldInfo.getEntity(suspectId);
    final int currentHp = victimBody.getHP();
    final int rubbleDepth = victimBody.getBuriedness();
    return (currentHp > 0 && rubbleDepth == 0);
  }

  /**
   * 存活概率预判器（供外部参考）。
   * 物理意义：用寿命公式算一算，在这个人死前，n 辆消防车能不能把他挖出来？
   */
  private boolean isSalvable(EntityID victimId, int assignedWorkforce) {
    final StandardEntity rawEntity = this.worldInfo.getEntity(victimId);
    Human humanBody = (Human) rawEntity;
    
    if (humanBody.getBuriedness() == 0 || humanBody.getHP() == 0 || humanBody.getDamage() == 0) {
      return false;
    }
    
    final SurvivalChronicle medicalAnalyzer = new SurvivalChronicle(humanBody);
    final int estimatedSurvivalChronicle = medicalAnalyzer.getLifeXpectancy();
    return (estimatedSurvivalChronicle - this.calculateDiggingTime(victimId, assignedWorkforce) > 0);
  }

  /**
   * 物理挖掘耗时测算。
   */
  private int calculateDiggingTime(EntityID victimId, int assignedWorkforce) {
    return ((Human) this.worldInfo.getEntity(victimId)).getBuriedness() / assignedWorkforce;
  }

  /**
   * 平民求救电报解密器。
   */
  private void interpretCivilianTelegram(MessageCivilian msg) {
    final EntityID callerId = msg.getAgentID();
    if (this.evaluateIfNeedsDigging(callerId)) {
      this.buriedCivilianLedger.add(callerId);
    } else {
      this.buriedCivilianLedger.remove(callerId);
    }
  }

  /**
   * 电报分拣台。
   */
  private void processIncomingTelegrams(MessageManager mm) {
    List<CommunicationMessage> civMails = mm.getReceivedMessageList(MessageCivilian.class);
    for (CommunicationMessage rawMsg : civMails) {
      this.interpretCivilianTelegram((MessageCivilian) rawMsg);
    }
  }

  /**
   * 联合行动指挥发报机。
   * 物理意义：如果我这个警察发现了有人被埋，而恰巧旁边就停着一辆闲置的消防车，
   * 我就会大喊（Voice）或者发报（Radio），强行命令他过来挖人！
   */
  private void broadcastCooperativeCommands(MessageManager mm) {
    ObjectScreener visualFilter = new ObjectScreener(this.worldInfo, this.agentInfo);
    // 探明我附近有没有别的闲散消防兄弟
    final EntityID nearbyFireTruckId = visualFilter.getNearyFB();
    
    // 场景 1：旁边有消防车，且我有战友被埋名单！
    if (nearbyFireTruckId != null && !this.buriedTeammateLedger.isEmpty()) {
      Iterator<EntityID> iterator = this.buriedTeammateLedger.iterator();
      EntityID EntrappedStateVictimId = iterator.next();
      StandardEntity victimEntity = this.worldInfo.getEntity(EntrappedStateVictimId);
      
      // 无线电静默策略：利用 ID 和时间的模运算来降低全网电波拥堵
      boolean shouldTransmitRadio = (this.agentInfo.getID().getValue() % 10) == (this.agentInfo.getTime() % 10);
      
      // 根据倒霉蛋的兵种，生成强制移动指令发给那辆消防车
      if (victimEntity instanceof AmbulanceTeam) {
        mm.addMessage(new MessageAmbulanceTeam(VOICE_CHANNEL, (AmbulanceTeam) victimEntity, CommandAmbulance.ACTION_MOVE, EntrappedStateVictimId));
        if (shouldTransmitRadio) {
          mm.addMessage(new MessageAmbulanceTeam(VOICE_CHANNEL, (AmbulanceTeam) victimEntity, CommandAmbulance.ACTION_MOVE, EntrappedStateVictimId));
        }
      } else if (victimEntity instanceof FireBrigade) {
        mm.addMessage(new MessageFireBrigade(VOICE_CHANNEL, (FireBrigade) victimEntity, CommandFire.ACTION_MOVE, EntrappedStateVictimId));
        if (shouldTransmitRadio) {
          mm.addMessage(new MessageFireBrigade(VOICE_CHANNEL, (FireBrigade) victimEntity, CommandFire.ACTION_MOVE, EntrappedStateVictimId));
        }
      } else if (victimEntity instanceof PoliceForce) {
        mm.addMessage(new MessagePoliceForce(VOICE_CHANNEL, (PoliceForce) victimEntity, CommandPolice.ACTION_MOVE, EntrappedStateVictimId));
        if (shouldTransmitRadio) {
          mm.addMessage(new MessagePoliceForce(VOICE_CHANNEL, (PoliceForce) victimEntity, CommandPolice.ACTION_MOVE, EntrappedStateVictimId));
        }
      }
      
      // 呼叫完了，就从我自己的呼叫队列里划掉
      iterator.remove();
      return; // 一次只发一条，防止刷屏
    }

    // 场景 2：常规平民呼喊
    Set<EntityID> unsentCivilianTargets = new HashSet<>(this.buriedCivilianLedger);
    // 从我的平民名单中，剥离掉那些我已经广播过的人
    unsentCivilianTargets.removeAll(this.voiceBroadcastedCivilians);
    
    // 如果所有的平民我都喊过了，但是名单里还有人没被挖走，那就循环往复再喊一遍
    if (unsentCivilianTargets.isEmpty()) {
      unsentCivilianTargets = new HashSet<>(this.voiceBroadcastedCivilians);
    }
    
    if (unsentCivilianTargets.isEmpty()) {
      return; // 太平无事
    }
    
    // 扯开嗓门喊，让周围的人都听到这个平民的存在
    final Civilian targetedCiv = (Civilian) this.worldInfo.getEntity(unsentCivilianTargets.iterator().next());
    mm.addMessage(new MessageCivilian(VOICE_CHANNEL, targetedCiv));
    
    // 记在小本本上，这人我喊过了
    this.voiceBroadcastedCivilians.add(targetedCiv.getID());
  }

  /**
   * 无效任务清洗机。
   * 物理意义：死人、已经被挖出来的人，统统从各大名册里划掉。
   */
  private void purgeInvalidRosters() {
    Iterator<EntityID> iterator = this.buriedCivilianLedger.iterator();
    while (iterator.hasNext()) {
      if (!this.evaluateIfNeedsDigging(iterator.next())) {
        iterator.remove();
      }
    }
    
    iterator = this.buriedTeammateLedger.iterator();
    while (iterator.hasNext()) {
      if (!this.evaluateIfNeedsDigging(iterator.next())) {
        iterator.remove();
      }
    }
    
    iterator = this.bleedingCivilianLedger.iterator();
    while (iterator.hasNext()) {
      if (!this.evaluateIfNeedsLoading(iterator.next())) {
        iterator.remove();
      }
    }
    
    iterator = this.voiceBroadcastedCivilians.iterator();
    while (iterator.hasNext()) {
      if (!this.evaluateIfNeedsDigging(iterator.next())) {
        iterator.remove();
      }
    }
  }

}
