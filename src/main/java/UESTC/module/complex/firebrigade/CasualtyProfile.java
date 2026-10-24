package UESTC.module.complex.firebrigade;

import UESTC.module.complex.common.ObjectScreener;
import UESTC.module.complex.common.SurvivalChronicle;
import adf.core.agent.action.Action;
import adf.core.agent.action.fire.ActionRescue;
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
import rescuecore2.misc.Pair;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

/**
 * CasualtyProfile 战地难民与战损情报中枢。
 * 物理意义：作为一个独立的底层情报模块，它专门负责收集、分类和维护所有人员的“伤亡及受困档案”。
 * 它不负责下达具体的动作指令（那是 Allocator 和 Search 模块干的），
 * 它的职责仅仅是“记账”和“发报”：
 * 1. 记账：把全城所有活着但被埋的人，按兵种（平民、战友）分类存在不同的名册里。
 * 2. 清洗：把死人或已经被挖出来的人从名册里划掉。
 * 3. 呼叫增援：如果有别的消防车在我旁边，我就用无线电或嗓子指挥他们过来一起挖！
 */
public class CasualtyProfile extends AbstractModule {

    // ========================================================================
    // 指挥部核心状态与名册
    // ========================================================================

    // 自身救援需求标志。如果我这辆消防车自己都被埋了，标志位置为 true。
    private boolean isMyselfBuried = false;
    
    // 战地分类名册（垃圾桶）：分门别类记录当前急需处理的人员
    private final Set<EntityID> civilianDiggingRoster = new HashSet<>(); // 待挖掘平民
    private final Set<EntityID> teammateDiggingRoster = new HashSet<>(); // 待挖掘战友（各路同行）
    private final Set<EntityID> civilianLoadingRoster = new HashSet<>(); // 待装车平民（没被埋但受伤）
    
    // 广播备忘录
    private final Set<EntityID> voiceBroadcastedTargets = new HashSet<>(); // 已经用嗓子喊过话的求救目标，防刷屏
    private final Set<EntityID> cooperativeRescueRequests = new HashSet<>(); // 收到的要求我过去协同挖掘的高级指令

    // 固化通信频段标志（语音大喊，只能让周围近距离的人听见）
    private final boolean VOICE_CHANNEL = false;

    /**
     * 情报中枢构造器。开局把系统大沙盘（WorldInfo）接管过来。
     */
    public CasualtyProfile(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager mm, DevelopData dd) {
        super(ai, wi, si, mm, dd);
        this.worldInfo = wi;
        this.agentInfo = ai;
    }

    // ========================================================================
    // 管线 1：情报更新与广播循环 (Information Update Cycle)
    // ========================================================================

    /**
     * 情报库更新循环（生命周期方法）。
     * 物理意义：每回合固定执行：收电报、洗账本、发求援。
     */
    @Override
    public AbstractModule updateInfo(MessageManager mm) {
        // 聘请专属通信兵来处理繁杂的电报业务
        CommunicationsOfficer commsOfficer = new CommunicationsOfficer();
        
        // 管线 1.1：处理外来的求救电报，有救的登记，死了的拉黑
        commsOfficer.processIncomingTelegrams(mm);
        
        // 管线 1.2：清理死亡或获救人员的档案，保持花名册的纯净
        this.purgeInvalidRosters();
        
        // 管线 1.3：呼叫附近的同行来帮忙挖人（协同作战机制）
        commsOfficer.broadcastCooperativeCommands(mm);
        
        return this;
    }

    // ========================================================================
    // 管线 2：全城检伤分类推演引擎 (Triage Engine)
    // ========================================================================

    /**
     * 全城检伤分类推演引擎。
     * 物理意义：每回合看一眼地图上发生变化的所有人。把他们分别塞进平民库、战友库。
     */
    @Override
    public AbstractModule calc() {
        final EntityID mySelfId = this.agentInfo.getID();
        
        // 聘请战地军医来进行生死判定
        BattlefieldMedic medic = new BattlefieldMedic();
        
        // 自身体检：如果我自己都被埋了，直接躺平，停止情报收集工作
        this.isMyselfBuried = medic.evaluateNeedsDigging(mySelfId);
        if (this.isMyselfBuried) return this;

        // 获取视野中所有发生了状态改变的实体，把自己也加进去兜底
        Set<EntityID> visibleEntities = this.worldInfo.getChanged().getChangedEntities();
        visibleEntities.add(mySelfId);

        // 建立三个临时分类档案柜，记录他们在地图上的具体位置
        Map<EntityID, Set<EntityID>> civDistributionMap = new HashMap<>();
        Map<EntityID, Set<EntityID>> ambDistributionMap = new HashMap<>();
        Map<EntityID, Set<EntityID>> fbDistributionMap = new HashMap<>();

        // 挨个把伤员拉过来检伤
        for (EntityID currentId : visibleEntities) {
            StandardEntity rawEntity = this.worldInfo.getEntity(currentId);
            
            // 不是人（含载具）的直接略过
            if (!(rawEntity instanceof Human)) continue;
            
            StandardEntity locationEntity = this.worldInfo.getPosition(currentId);
            // 安全屋豁免：如果这个人已经在避难所（医院）里了，那是绝对安全的，跳过
            if (locationEntity.getStandardURN().equals(REFUGE)) continue;
            
            // 军医做出诊断：这人需要挖吗？需要抬走吗？
            boolean needsDigging = medic.evaluateNeedsDigging(currentId);
            boolean needsLoading = medic.evaluateNeedsLoading(currentId);
            
            StandardEntityURN entityType = rawEntity.getStandardURN();
            EntityID locationId = locationEntity.getID();
            
            // 按兵种登记造册
            if (entityType.equals(AMBULANCE_TEAM)) {
                if (needsDigging) {
                    // 救护车兄弟被埋，列入十万火急战友急救名单！
                    this.teammateDiggingRoster.add(currentId);
                } else {
                    // 没被埋的健康战友，只记录他现在的坐标位置
                    Set<EntityID> squad = ambDistributionMap.getOrDefault(locationId, new HashSet<>());
                    squad.add(currentId);
                    ambDistributionMap.put(locationId, squad);
                }
            } else if (entityType.equals(FIRE_BRIGADE)) {
                if (needsDigging) {
                    // 消防车兄弟被埋！
                    this.teammateDiggingRoster.add(currentId);
                } else {
                    Set<EntityID> squad = fbDistributionMap.getOrDefault(locationId, new HashSet<>());
                    squad.add(currentId);
                    fbDistributionMap.put(locationId, squad);
                }
            } else if (entityType.equals(POLICE_FORCE)) {
                if (needsDigging) {
                    // 警察同事被埋！
                    this.teammateDiggingRoster.add(currentId);
                }
            } else if (entityType.equals(CIVILIAN)) {
                if (needsDigging) {
                    // 记录平民的位置
                    Set<EntityID> squad = civDistributionMap.getOrDefault(locationId, new HashSet<>());
                    squad.add(currentId);
                    civDistributionMap.put(locationId, squad);
                    
                    // 平民被埋，列入平民挖掘日常名单
                    this.civilianDiggingRoster.add(currentId);
                } else if (needsLoading) {
                    // 受伤但没被埋的平民，列入担架名单（这本账主要是整理给救护车看的）
                    this.civilianLoadingRoster.add(currentId);
                }
            }
        }
        return this;
    }

    /**
     * 对外暴露接口的占位符。
     * 物理意义：由于该模块只负责记账，不负责做战略决策，故永远返回 null。
     */
    public EntityID getResult() {
        return null;
    }

    // ========================================================================
    // 内部类 1：战地军医 (Battlefield Medic)
    // 负责医学层面的生死和需求判定
    // ========================================================================
    private class BattlefieldMedic {

        /**
         * 挖掘需求评估仪。
         * 物理意义：判断这个人是否需要被铁锹挖。活人才值得挖！
         */
        boolean evaluateNeedsDigging(EntityID suspectId) {
            Human victim = (Human) worldInfo.getEntity(suspectId);
            int hp = victim.getHP();
            int buriedDepth = victim.getBuriedness();
            // 只有当血量（HP）和埋藏度（Buriedness）都不为 0 时，乘积才不为 0
            return (hp * buriedDepth != 0);
        }

        /**
         * 装载需求评估仪。
         * 物理意义：没被埋（Buriedness=0）但还活着（HP>0），需要被抬上担架。
         */
        boolean evaluateNeedsLoading(EntityID suspectId) {
            Human victim = (Human) worldInfo.getEntity(suspectId);
            if (victim == null) return false;
            
            int hp = victim.getHP();
            int buriedDepth = victim.getBuriedness();
            return (hp > 0 && buriedDepth == 0);
        }
    }

    // ========================================================================
    // 内部类 2：通信兵 (Communications Officer)
    // 负责解析收到的电报，以及向外发送求援指令
    // ========================================================================
    private class CommunicationsOfficer {

        /**
         * 战地电报分拣中枢。
         */
        void processIncomingTelegrams(MessageManager mm) {
            // 从邮差（MessageManager）手里接过四大兵种的信件堆
            List<CommunicationMessage> civMails = mm.getReceivedMessageList(MessageCivilian.class);
            for (CommunicationMessage rawMsg : civMails) interpretCivilianTelegram((MessageCivilian) rawMsg);
            
            List<CommunicationMessage> fbMails = mm.getReceivedMessageList(MessageFireBrigade.class);
            for (CommunicationMessage rawMsg : fbMails) interpretTeammateTelegram((MessageFireBrigade) rawMsg);
            
            List<CommunicationMessage> ambMails = mm.getReceivedMessageList(MessageAmbulanceTeam.class);
            for (CommunicationMessage rawMsg : ambMails) interpretTeammateTelegram((MessageAmbulanceTeam) rawMsg);
            
            List<CommunicationMessage> pfMails = mm.getReceivedMessageList(MessagePoliceForce.class);
            for (CommunicationMessage rawMsg : pfMails) interpretTeammateTelegram((MessagePoliceForce) rawMsg);
            
            // 查收长官下达的高级调度指令
            List<CommunicationMessage> cmdMails = mm.getReceivedMessageList(CommandFire.class);
            for (CommunicationMessage rawMsg : cmdMails) interpretCommandTelegram((CommandFire) rawMsg);
        }

        /**
         * 处理外来平民求救电报。
         * 如果还有救就登记在账，如果发现这人已经死了，就从名册里划掉。
         */
        private void interpretCivilianTelegram(MessageCivilian msg) {
            EntityID callerId = msg.getAgentID();
            BattlefieldMedic medic = new BattlefieldMedic();
            if (medic.evaluateNeedsDigging(callerId)) {
                civilianDiggingRoster.add(callerId);
            } else {
                civilianDiggingRoster.remove(callerId);
            }
        }

        /**
         * 处理外来战友求救电报。
         */
        private void interpretTeammateTelegram(CommunicationMessage msg) {
            EntityID callerId = null;
            if (msg instanceof MessageFireBrigade) callerId = ((MessageFireBrigade) msg).getAgentID();
            else if (msg instanceof MessageAmbulanceTeam) callerId = ((MessageAmbulanceTeam) msg).getAgentID();
            else if (msg instanceof MessagePoliceForce) callerId = ((MessagePoliceForce) msg).getAgentID();
            
            if (callerId == null) return;

            BattlefieldMedic medic = new BattlefieldMedic();
            if (medic.evaluateNeedsDigging(callerId)) {
                teammateDiggingRoster.add(callerId);
            } else {
                teammateDiggingRoster.remove(callerId);
            }
        }

        /**
         * 消防长官调度指令接收器。
         * 如果收到高级指令要求我协同去某个地点救人，我得把这个指令存进特快通道。
         */
        private void interpretCommandTelegram(CommandFire msg) {
            // 检查信封：这个命令是不是发给我的？
            if (msg.getToID() != null && msg.getToID().getValue() != agentInfo.getID().getValue()) {
                return; // 寄错人了，丢弃
            }
            
            // 战术防御：如果我上一秒本来就在执行 ActionRescue (我正在埋头挖人)，
            // 我走不开，不能马上响应。把这个请求先记在小本本里，以免丢包。
            Action lastAction = agentInfo.getExecutedAction(agentInfo.getTime() - 1);
            if (lastAction != null && lastAction.getClass().equals(ActionRescue.class)) {
                cooperativeRescueRequests.add(msg.getTargetID());
            }
        }

        /**
         * 联合行动指挥发报机。
         * 物理意义：这是消防车主动发起协同作战的核心机制！
         * 发现旁边有闲散兄弟，大喊一声，强行命令他们过来跟我一起挖！
         */
        void broadcastCooperativeCommands(MessageManager mm) {
            ObjectScreener visualFilter = new ObjectScreener(worldInfo, agentInfo);
            // 探明我附近有没有别的消防兄弟停着
            final EntityID nearbyFireTruckId = visualFilter.getNearyFB();

            // 场景 1：旁边有兄弟，且我手头压着待挖的【战友名单】
            if (nearbyFireTruckId != null && !teammateDiggingRoster.isEmpty()) {
                // 抽出名单里的第一个倒霉蛋
                Iterator<EntityID> iterator = teammateDiggingRoster.iterator();
                EntityID EntrappedStateVictimId = iterator.next();
                StandardEntity victimEntity = worldInfo.getEntity(EntrappedStateVictimId);
                
                // 无线电静默策略：不每秒发电报，利用模运算随机限制频率，防刷屏
                boolean shouldTransmitRadio = (agentInfo.getID().getValue() % 10) == (agentInfo.getTime() % 10);
                
                // 根据倒霉蛋的兵种，生成“强制移动”指令甩给旁边的兄弟
                if (victimEntity instanceof AmbulanceTeam) {
                    mm.addMessage(new MessageAmbulanceTeam(VOICE_CHANNEL, (AmbulanceTeam) victimEntity, CommandAmbulance.ACTION_MOVE, EntrappedStateVictimId));
                    if (shouldTransmitRadio) mm.addMessage(new MessageAmbulanceTeam(VOICE_CHANNEL, (AmbulanceTeam) victimEntity, CommandAmbulance.ACTION_MOVE, EntrappedStateVictimId)); 
                } else if (victimEntity instanceof FireBrigade) {
                    mm.addMessage(new MessageFireBrigade(VOICE_CHANNEL, (FireBrigade) victimEntity, CommandFire.ACTION_MOVE, EntrappedStateVictimId));
                    if (shouldTransmitRadio) mm.addMessage(new MessageFireBrigade(VOICE_CHANNEL, (FireBrigade) victimEntity, CommandFire.ACTION_MOVE, EntrappedStateVictimId));
                } else if (victimEntity instanceof PoliceForce) {
                    mm.addMessage(new MessagePoliceForce(VOICE_CHANNEL, (PoliceForce) victimEntity, CommandPolice.ACTION_MOVE, EntrappedStateVictimId));
                    if (shouldTransmitRadio) mm.addMessage(new MessagePoliceForce(VOICE_CHANNEL, (PoliceForce) victimEntity, CommandPolice.ACTION_MOVE, EntrappedStateVictimId));
                }
                
                // 喊完了，从我自己的呼叫队列里划掉
                iterator.remove();
                return; // 一次只发一条大指令
            }

            // 场景 2：旁边有兄弟，且我手头积压了长官下达的【高级协同指令】
            if (nearbyFireTruckId != null && !cooperativeRescueRequests.isEmpty()) {
                Iterator<EntityID> iterator = cooperativeRescueRequests.iterator();
                EntityID urgentTargetId = iterator.next();
                
                // 直接做二传手，把这个任务甩给旁边的兄弟，让他去挖！
                mm.addMessage(new CommandFire(VOICE_CHANNEL, nearbyFireTruckId, urgentTargetId, CommandFire.ACTION_RESCUE));
                iterator.remove();
                return;
            }

            // 场景 3：常规平民呼喊 (在没有战友和高级指令的情况下)
            // 剥离掉我已经广播过的人，防重发
            Set<EntityID> unsentCivilianTargets = new HashSet<>(civilianDiggingRoster);
            unsentCivilianTargets.removeAll(voiceBroadcastedTargets);
            
            // 如果手头所有的平民我都喊过了，那就从头再来，循环复读
            if (unsentCivilianTargets.isEmpty()) {
                unsentCivilianTargets = new HashSet<>(voiceBroadcastedTargets);
            }
            
            // 如果确实有人需要救，就扯开嗓门喊！
            if (!unsentCivilianTargets.isEmpty()) {
                EntityID targetedCivilianId = unsentCivilianTargets.iterator().next();
                Civilian civEntity = (Civilian) worldInfo.getEntity(targetedCivilianId);
                mm.addMessage(new MessageCivilian(VOICE_CHANNEL, civEntity));
                
                // 记在小本本上：这个人我已经广播过了
                voiceBroadcastedTargets.add(civEntity.getID());
            }
        }
    }

    // ========================================================================
    // 基础维护功能
    // ========================================================================

    /**
     * 无效任务清洗机。
     * 物理意义：死人、已经被挖出来生龙活虎的人，统统从四大名册里划掉！
     */
    private void purgeInvalidRosters() {
        BattlefieldMedic medic = new BattlefieldMedic();
        
        Iterator<EntityID> iterator = this.civilianDiggingRoster.iterator();
        while (iterator.hasNext()) {
            if (!medic.evaluateNeedsDigging(iterator.next())) iterator.remove();
        }
        
        iterator = this.teammateDiggingRoster.iterator();
        while (iterator.hasNext()) {
            if (!medic.evaluateNeedsDigging(iterator.next())) iterator.remove();
        }
        
        iterator = this.civilianLoadingRoster.iterator();
        while (iterator.hasNext()) {
            if (!medic.evaluateNeedsLoading(iterator.next())) iterator.remove();
        }
        
        iterator = this.voiceBroadcastedTargets.iterator();
        while (iterator.hasNext()) {
            if (!medic.evaluateNeedsDigging(iterator.next())) iterator.remove();
        }
    }
}
