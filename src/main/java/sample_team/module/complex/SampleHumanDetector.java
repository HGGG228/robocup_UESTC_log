package sample_team.module.complex;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import UESTC.module.util.AgentTelemetry;
import adf.core.agent.action.ambulance.ActionLoad;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.complex.HumanDetector;
import adf.core.debug.DefaultLogger;
import org.apache.log4j.Logger;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

public class SampleHumanDetector extends HumanDetector {

    private static final int DIRECT_LOAD_BURIEDNESS_THRESHOLD = 15;
    private static final int FB_ASSIST_BURIEDNESS_THRESHOLD = 25;
    private static final int AGENT_MOVEMENT_ESTIMATE = 7000;

    private static final int SMALL_CLUSTER_SIZE = 50;
    private static final int LARGE_CLUSTER_SIZE = 120;

    private Clustering clustering;
    private EntityID result;
    private MessageManager messageManager;
    private Logger logger;

    private Set<EntityID> wrongCivilians;
    private Map<EntityID, Integer> knownCivilians;

    private int lastLoggedModeTime = -1;
    private String decisionSource = "not_calculated";
    private int candidateCount = -1;
    private int clusterCandidateCount = -1;
    private int rejectedInvalidCount = 0;
    private int rejectedWrongCount = 0;
    private int rejectedWaitingCount = 0;
    private int rejectedClusterCount = 0;
    private int rejectedInfeasibleCount = 0;

    public SampleHumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                               ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);

        this.logger = DefaultLogger.getLogger(agentInfo.me());
        this.clustering = moduleManager.getModule(
                "UESTC.AT.HumanDetector.Clustering",
                "adf.impl.module.algorithm.KMeansClustering"
        );
        registerModule(this.clustering);

        this.wrongCivilians = new HashSet<>();
        this.knownCivilians = new HashMap<>();
    }

    @Override
    public HumanDetector updateInfo(MessageManager messageManager) {
        logger.debug("Time:" + agentInfo.getTime());
        super.updateInfo(messageManager);

        this.messageManager = messageManager;

        updateWrongCivilians();
        cleanupWrongCivilians();

        updateKnownCiviliansFromVision();
        updateKnownCiviliansFromMessage(messageManager);
        cleanupKnownCivilians();

        logClusterModeOncePerTime();

        logger.debug("[UpdateSummary] time=" + agentInfo.getTime()
                + ", agent=" + agentInfo.getID()
                + ", pos=" + agentInfo.getPosition()
                + ", knownCivilians=" + knownCivilians.size()
                + ", wrongCivilians=" + wrongCivilians.size()
                + ", clusterSize=" + getClusterSize()
                + ", clusterMode=" + getClusterModeName());

        return this;
    }

    @Override
    public HumanDetector calc() {
        decisionSource = "no_target";
        candidateCount = -1;
        clusterCandidateCount = -1;
        rejectedInvalidCount = 0;
        rejectedWrongCount = 0;
        rejectedWaitingCount = 0;
        rejectedClusterCount = 0;
        rejectedInfeasibleCount = 0;
        logger.debug("----- HumanDetector start -----");
        logger.debug("[CalcStart] time=" + agentInfo.getTime()
                + ", agent=" + agentInfo.getID()
                + ", pos=" + agentInfo.getPosition()
                + ", known=" + knownCivilians.size()
                + ", wrong=" + wrongCivilians.size());

        Human transportHuman = this.agentInfo.someoneOnBoard();
        if (transportHuman != null) {
            decisionSource = "on_board";
            logger.debug("[Calc] someoneOnBoard: " + describeHuman(transportHuman));
            this.result = transportHuman.getID();
            logSelectedTargetDetail(this.result);
            return this;
        }

        Human localTarget = findLoadableHumanAtCurrentPosition();
        if (localTarget != null) {
            decisionSource = "local_load";
            this.result = localTarget.getID();
            logger.debug("[Calc] local load target selected: " + describeHuman(localTarget)
                    + ", score=" + computeRescueScore(localTarget));
            logSelectedTargetDetail(this.result);
            return this;
        }

        this.result = calcTarget();
        logSelectedTargetDetail(this.result);
        logger.debug("the result:" + this.result);
        return this;
    }

    @Override
    public EntityID getTarget() {
        AgentTelemetry.decision(this.agentInfo, this.worldInfo, "AT.HumanDetector", this.result,
                "source", decisionSource,
                "known", knownCivilians.size(),
                "wrong", wrongCivilians.size(),
                "candidates", candidateCount,
                "cluster_candidates", clusterCandidateCount,
                "cluster_size", getClusterSize(),
                "cluster_mode", getClusterModeName(),
                "rejected_invalid", rejectedInvalidCount,
                "rejected_wrong", rejectedWrongCount,
                "rejected_waiting", rejectedWaitingCount,
                "rejected_cluster", rejectedClusterCount,
                "rejected_infeasible", rejectedInfeasibleCount);
        return this.result;
    }

    private int getClusterSize() {
        try {
            int clusterIndex = clustering.getClusterIndex(this.agentInfo.getID());
            Collection<StandardEntity> entities = clustering.getClusterEntities(clusterIndex);
            return entities == null ? 0 : entities.size();
        } catch (Exception e) {
            logger.debug("[ClusterMode] getClusterSize exception: " + e.getMessage());
            return 0;
        }
    }

    private int getClusterIndexSafe() {
        try {
            return clustering.getClusterIndex(this.agentInfo.getID());
        } catch (Exception e) {
            logger.debug("[ClusterMode] getClusterIndex exception: " + e.getMessage());
            return -1;
        }
    }

    private boolean isLargeMapLike() {
        return getClusterSize() >= LARGE_CLUSTER_SIZE;
    }

    private boolean isSmallMapLike() {
        int size = getClusterSize();
        return size > 0 && size <= SMALL_CLUSTER_SIZE;
    }

    private String getClusterModeName() {
        if (isLargeMapLike()) {
            return "LARGE_CLUSTER";
        }
        if (isSmallMapLike()) {
            return "SMALL_CLUSTER";
        }
        return "MIDDLE_CLUSTER";
    }

    private void logClusterModeOncePerTime() {
        int now = this.agentInfo.getTime();
        if (lastLoggedModeTime == now) {
            return;
        }
        lastLoggedModeTime = now;

        logger.debug("[ClusterMode] time=" + now
                + ", agent=" + agentInfo.getID()
                + ", clusterIndex=" + getClusterIndexSafe()
                + ", clusterSize=" + getClusterSize()
                + ", mode=" + getClusterModeName()
                + ", staleTime=" + getStaleCivilianTime()
                + ", agePenalty=" + getAgePenaltyPerTurn()
                + ", urgentMaxDistance=" + getUrgentTargetMaxDistance()
                + ", buriednessWeight=" + getBuriednessWeight()
                + ", damageWeight=" + getDamageWeight());
    }

    private int getStaleCivilianTime() {
        if (isLargeMapLike()) {
            return 7;
        }
        if (isSmallMapLike()) {
            return 12;
        }
        return 10;
    }

    private int getAgePenaltyPerTurn() {
        if (isLargeMapLike()) {
            return 180;
        }
        if (isSmallMapLike()) {
            return 50;
        }
        return 100;
    }

    private int getUrgentTargetMaxDistance() {
        if (isLargeMapLike()) {
            return 45000;
        }
        if (isSmallMapLike()) {
            return 70000;
        }
        return 55000;
    }

    private double getBuriednessWeight() {
        if (isLargeMapLike()) {
            return 130.0;
        }
        if (isSmallMapLike()) {
            return 180.0;
        }
        return 160.0;
    }

    private double getDamageWeight() {
        if (isLargeMapLike()) {
            return 5.0;
        }
        if (isSmallMapLike()) {
            return 8.0;
        }
        return 6.0;
    }

    private void updateWrongCivilians() {
        int time = agentInfo.getTime();
        if (time < 2) {
            return;
        }

        if (this.agentInfo.getExecutedAction(time - 1) instanceof ActionLoad
                && this.agentInfo.getExecutedAction(time - 2) instanceof ActionLoad) {

            ActionLoad actionLoad1 = (ActionLoad) this.agentInfo.getExecutedAction(time - 1);
            ActionLoad actionLoad2 = (ActionLoad) this.agentInfo.getExecutedAction(time - 2);

            if (actionLoad1.getTarget().equals(actionLoad2.getTarget())) {
                StandardEntity entity = this.worldInfo.getEntity(actionLoad1.getTarget());
                if (entity instanceof Human) {
                    Human badHuman = (Human) entity;
                    logger.debug("[WrongHuman] add by repeated ActionLoad failure: "
                            + describeHuman(badHuman));
                    wrongCivilians.add(badHuman.getID());
                }
            }
        }
    }

    private void cleanupWrongCivilians() {
        Set<EntityID> toRemove = new HashSet<>();

        int invalid = 0;
        int deadOrNoPos = 0;
        int refugeOrAT = 0;

        for (EntityID id : wrongCivilians) {
            StandardEntity entity = worldInfo.getEntity(id);
            if (!(entity instanceof Human)) {
                toRemove.add(id);
                invalid++;
                continue;
            }

            Human human = (Human) entity;
            if (!human.isHPDefined() || human.getHP() <= 0 || !human.isPositionDefined()) {
                toRemove.add(id);
                deadOrNoPos++;
                continue;
            }

            StandardEntity position = worldInfo.getPosition(human);
            if (position == null) {
                toRemove.add(id);
                deadOrNoPos++;
                continue;
            }

            StandardEntityURN urn = position.getStandardURN();
            if (urn == REFUGE || urn == AMBULANCE_TEAM) {
                toRemove.add(id);
                refugeOrAT++;
            }
        }

        if (!toRemove.isEmpty()) {
            logger.debug("[WrongHumanCleanup] removed=" + toRemove.size()
                    + ", invalid=" + invalid
                    + ", deadOrNoPos=" + deadOrNoPos
                    + ", refugeOrAT=" + refugeOrAT
                    + ", before=" + wrongCivilians.size());
        }

        wrongCivilians.removeAll(toRemove);
    }

    private void updateKnownCiviliansFromVision() {
        int now = agentInfo.getTime();
        int added = 0;
        int updated = 0;
        int changedCivilian = 0;

        for (EntityID changedId : worldInfo.getChanged().getChangedEntities()) {
            StandardEntity entity = worldInfo.getEntity(changedId);
            if (entity instanceof Civilian) {
                changedCivilian++;
                if (knownCivilians.containsKey(changedId)) {
                    updated++;
                } else {
                    added++;
                }
                knownCivilians.put(changedId, now);

                logger.debug("[KnownVision] observe civilian: " + describeHuman((Human) entity));
            }
        }

        if (changedCivilian > 0) {
            logger.debug("[KnownVision] changedCivilian=" + changedCivilian
                    + ", added=" + added
                    + ", updated=" + updated
                    + ", knownTotal=" + knownCivilians.size());
        }
    }

    private void updateKnownCiviliansFromMessage(MessageManager messageManager) {
        Set<EntityID> changedEntities = this.worldInfo.getChanged().getChangedEntities();

        int messageCivilianCount = 0;
        int reflected = 0;
        int added = 0;
        int updated = 0;
        int skippedByChanged = 0;
        int notCivilianAfterReflect = 0;

        for (CommunicationMessage message : messageManager.getReceivedMessageList()) {
            if (message instanceof MessageCivilian) {
                messageCivilianCount++;
                MessageCivilian messageCivilian = (MessageCivilian) message;

                if (changedEntities.contains(messageCivilian.getAgentID())) {
                    skippedByChanged++;
                    continue;
                }

                StandardEntity entity = MessageUtil.reflectMessage(this.worldInfo, messageCivilian);
                reflected++;

                if (entity instanceof Civilian) {
                    if (knownCivilians.containsKey(entity.getID())) {
                        updated++;
                    } else {
                        added++;
                    }
                    knownCivilians.put(entity.getID(), agentInfo.getTime());

                    logger.debug("[KnownMessage] reflect civilian: id=" + entity.getID()
                            + ", msgBuriedness=" + messageCivilian.getBuriedness()
                            + ", msgPosition=" + messageCivilian.getPosition()
                            + ", entity=" + describeHuman((Human) entity));
                } else {
                    notCivilianAfterReflect++;
                }
            }
        }

        if (messageCivilianCount > 0) {
            logger.debug("[KnownMessage] messages=" + messageCivilianCount
                    + ", reflected=" + reflected
                    + ", added=" + added
                    + ", updated=" + updated
                    + ", skippedByChanged=" + skippedByChanged
                    + ", notCivilianAfterReflect=" + notCivilianAfterReflect
                    + ", knownTotal=" + knownCivilians.size());
        }
    }

    private void cleanupKnownCivilians() {
        int now = agentInfo.getTime();
        Set<EntityID> toRemove = new HashSet<>();

        int invalidType = 0;
        int deadOrNoHP = 0;
        int noPosition = 0;
        int nullPosition = 0;
        int refugeOrAT = 0;
        int stale = 0;

        for (Map.Entry<EntityID, Integer> entry : knownCivilians.entrySet()) {
            EntityID id = entry.getKey();
            int lastSeenTime = entry.getValue();

            StandardEntity entity = worldInfo.getEntity(id);
            if (!(entity instanceof Civilian)) {
                toRemove.add(id);
                invalidType++;
                continue;
            }

            Civilian civilian = (Civilian) entity;
            if (!civilian.isHPDefined() || civilian.getHP() <= 0) {
                toRemove.add(id);
                deadOrNoHP++;
                logger.debug("[KnownCleanupDetail] remove dead/noHP: " + describeHuman(civilian));
                continue;
            }

            if (!civilian.isPositionDefined()) {
                toRemove.add(id);
                noPosition++;
                logger.debug("[KnownCleanupDetail] remove noPosition: id=" + id);
                continue;
            }

            StandardEntity position = worldInfo.getPosition(civilian);
            if (position == null) {
                toRemove.add(id);
                nullPosition++;
                logger.debug("[KnownCleanupDetail] remove nullPosition: " + describeHuman(civilian));
                continue;
            }

            StandardEntityURN urn = position.getStandardURN();
            if (urn == REFUGE || urn == AMBULANCE_TEAM) {
                toRemove.add(id);
                refugeOrAT++;
                logger.debug("[KnownCleanupDetail] remove safe position: " + describeHuman(civilian)
                        + ", urn=" + urn);
                continue;
            }

            if (now - lastSeenTime > getStaleCivilianTime()) {
                toRemove.add(id);
                stale++;
                logger.debug("[KnownCleanupDetail] remove stale: " + describeHuman(civilian)
                        + ", age=" + (now - lastSeenTime)
                        + ", staleLimit=" + getStaleCivilianTime());
            }
        }

        if (!toRemove.isEmpty()) {
            logger.debug("[KnownCleanup] removed=" + toRemove.size()
                    + ", invalidType=" + invalidType
                    + ", deadOrNoHP=" + deadOrNoHP
                    + ", noPosition=" + noPosition
                    + ", nullPosition=" + nullPosition
                    + ", refugeOrAT=" + refugeOrAT
                    + ", stale=" + stale
                    + ", before=" + knownCivilians.size()
                    + ", staleLimit=" + getStaleCivilianTime());
        }

        knownCivilians.keySet().removeAll(toRemove);
    }

    private Human findLoadableHumanAtCurrentPosition() {
        EntityID agentPosition = this.agentInfo.getPosition();
        Human best = null;
        double bestScore = Double.MAX_VALUE;

        int checked = 0;
        int invalid = 0;
        int wrong = 0;
        int notSamePosition = 0;
        int buriednessTooHigh = 0;
        int atWait = 0;
        int accepted = 0;

        for (StandardEntity standardEntity : this.worldInfo.getEntitiesOfType(CIVILIAN)) {
            checked++;

            if (!isValidHuman(standardEntity)) {
                invalid++;
                continue;
            }

            Human h = (Human) standardEntity;
            if (wrongCivilians.contains(h.getID())) {
                wrong++;
                continue;
            }
            if (!agentPosition.equals(h.getPosition())) {
                notSamePosition++;
                continue;
            }
            if (h.getBuriedness() >= DIRECT_LOAD_BURIEDNESS_THRESHOLD) {
                buriednessTooHigh++;
                continue;
            }
            if (hasATWait(h)) {
                atWait++;
                continue;
            }

            accepted++;

            double score = computeRescueScore(h);
            logger.debug("[LocalLoad] candidate=" + describeHuman(h)
                    + ", score=" + score);

            if (score < bestScore) {
                bestScore = score;
                best = h;
            }
        }

        if (accepted > 0 || checked > 0) {
            logger.debug("[LocalLoad] checked=" + checked
                    + ", accepted=" + accepted
                    + ", invalid=" + invalid
                    + ", wrong=" + wrong
                    + ", notSamePosition=" + notSamePosition
                    + ", buriednessTooHigh=" + buriednessTooHigh
                    + ", atWait=" + atWait
                    + ", selected=" + (best == null ? "null" : best.getID())
                    + ", bestScore=" + bestScore);
        }

        return best;
    }

    private EntityID calcTarget() {
        logger.debug("----- Human calc targets start -----");

        List<Human> rescueTargets = buildRescueTargets();
        List<Human> rescueTargetsInCluster = filterInCluster(rescueTargets);
        candidateCount = rescueTargets.size();
        clusterCandidateCount = rescueTargetsInCluster.size();

        logger.debug("[TargetPool] known=" + knownCivilians.size()
                + ", rescueTargets=" + rescueTargets.size()
                + ", inCluster=" + rescueTargetsInCluster.size()
                + ", clusterSize=" + getClusterSize()
                + ", mode=" + getClusterModeName());

        List<Human> primaryTargets = rescueTargetsInCluster.isEmpty()
                ? rescueTargets
                : rescueTargetsInCluster;

        Human primary = selectBestPrimaryTarget(primaryTargets);
        if (primary != null) {
            decisionSource = rescueTargetsInCluster.isEmpty() ? "primary_all" : "primary_cluster";
            logger.debug("[TargetSelect] primary selected=" + describeHuman(primary)
                    + ", score=" + computeRescueScore(primary)
                    + ", source=" + (rescueTargetsInCluster.isEmpty() ? "ALL" : "CLUSTER"));
            return primary.getID();
        }

        Human urgent = selectBestUrgentTarget(rescueTargets);
        if (urgent != null) {
            decisionSource = "urgent";
            logger.debug("[TargetSelect] urgent selected=" + describeHuman(urgent)
                    + ", score=" + computeRescueScore(urgent)
                    + ", remainHp=" + estimateRemainHpAfterArrival(urgent));
            return urgent.getID();
        }

        logger.debug("[TargetSelect] no target selected");
        return null;
    }

    private List<Human> buildRescueTargets() {
        List<Human> rescueTargets = new ArrayList<>();

        int invalidType = 0;
        int invalidHuman = 0;
        int wrong = 0;
        int atWait = 0;
        int atCluster = 0;
        int infeasible = 0;
        int accepted = 0;

        for (EntityID id : knownCivilians.keySet()) {
            StandardEntity entity = worldInfo.getEntity(id);
            if (!(entity instanceof Human)) {
                invalidType++;
                continue;
            }

            Human h = (Human) entity;

            if (!isValidHuman(h)) {
                invalidHuman++;
                logger.debug("[BuildTargetsDetail] reject invalidHuman: " + describeHuman(h)
                        + ", reason=" + getInvalidHumanReason(h));
                continue;
            }
            if (wrongCivilians.contains(h.getID())) {
                wrong++;
                logger.debug("[BuildTargetsDetail] reject wrongHuman: " + describeHuman(h));
                continue;
            }
            if (hasATWait(h)) {
                atWait++;
                logger.debug("[BuildTargetsDetail] reject hasATWait: " + describeHuman(h));
                continue;
            }
            if (hasATinCluster(h)) {
                atCluster++;
                logger.debug("[BuildTargetsDetail] reject hasATinCluster: " + describeHuman(h));
                continue;
            }
            if (!isTargetFeasible(h)) {
                infeasible++;
                logger.debug("[BuildTargetsDetail] reject infeasible: " + describeHuman(h));

                if (messageManager != null && h instanceof Civilian) {
                    messageManager.addMessage(new MessageCivilian(
                            false,
                            StandardMessagePriority.HIGH,
                            (Civilian) h
                    ));
                    logger.debug("[BuildTargetsDetail] send HIGH MessageCivilian for infeasible target: "
                            + h.getID());
                }
                continue;
            }

            rescueTargets.add(h);
            accepted++;
            logger.debug("[BuildTargetsDetail] accepted: " + describeHuman(h)
                    + ", score=" + computeRescueScore(h)
                    + ", remainHp=" + estimateRemainHpAfterArrival(h));
        }

        logger.debug("[BuildTargets] known=" + knownCivilians.size()
                + ", accepted=" + accepted
                + ", invalidType=" + invalidType
                + ", invalidHuman=" + invalidHuman
                + ", wrong=" + wrong
                + ", atWait=" + atWait
                + ", atCluster=" + atCluster
                + ", infeasible=" + infeasible);

        rejectedInvalidCount = invalidType + invalidHuman;
        rejectedWrongCount = wrong;
        rejectedWaitingCount = atWait;
        rejectedClusterCount = atCluster;
        rejectedInfeasibleCount = infeasible;
        return rescueTargets;
    }

    private List<Human> filterInCluster(Collection<? extends StandardEntity> entities) {
        int clusterIndex = clustering.getClusterIndex(this.agentInfo.getID());
        Set<StandardEntity> inCluster = new HashSet<>(clustering.getClusterEntities(clusterIndex));

        List<Human> filter = new ArrayList<>();
        int noPosition = 0;
        int nullPosition = 0;
        int outside = 0;
        int accepted = 0;

        for (StandardEntity next : entities) {
            if (!(next instanceof Human)) {
                continue;
            }

            Human h = (Human) next;
            if (!h.isPositionDefined()) {
                noPosition++;
                continue;
            }

            StandardEntity position = this.worldInfo.getPosition(h);
            if (position == null) {
                nullPosition++;
                continue;
            }
            if (!inCluster.contains(position)) {
                outside++;
                continue;
            }

            filter.add(h);
            accepted++;
        }

        logger.debug("[ClusterFilter] input=" + entities.size()
                + ", accepted=" + accepted
                + ", outside=" + outside
                + ", noPosition=" + noPosition
                + ", nullPosition=" + nullPosition
                + ", clusterIndex=" + clusterIndex
                + ", clusterSize=" + inCluster.size());

        return filter;
    }

    private Human selectBestPrimaryTarget(List<Human> targets) {
        Human best = null;
        double bestScore = Double.MAX_VALUE;

        for (Human h : targets) {
            double score = computeRescueScore(h);
            logger.debug("[PrimaryScoreDetail] target=" + describeHuman(h)
                    + ", score=" + score
                    + ", remainHp=" + estimateRemainHpAfterArrival(h));

            if (score < bestScore) {
                bestScore = score;
                best = h;
            }
        }

        logger.debug("[PrimaryScore] candidates=" + targets.size()
                + ", selected=" + (best == null ? "null" : best.getID())
                + ", bestScore=" + bestScore);

        return best;
    }

    private Human selectBestUrgentTarget(List<Human> targets) {
        Human best = null;
        double bestScore = Double.MAX_VALUE;

        int tooFar = 0;
        int deadBeforeArrival = 0;
        int notUrgent = 0;
        int accepted = 0;

        for (Human h : targets) {
            int distance = this.worldInfo.getDistance(this.agentInfo.getID(), h.getID());
            if (distance > getUrgentTargetMaxDistance()) {
                tooFar++;
                logger.debug("[UrgentFilterDetail] reject tooFar: " + describeHuman(h)
                        + ", distance=" + distance
                        + ", maxDistance=" + getUrgentTargetMaxDistance());
                continue;
            }

            double remainHp = estimateRemainHpAfterArrival(h);
            if (remainHp <= 0) {
                deadBeforeArrival++;
                logger.debug("[UrgentFilterDetail] reject deadBeforeArrival: " + describeHuman(h)
                        + ", remainHp=" + remainHp);
                continue;
            }

            if (!isUrgentHuman(h, remainHp)) {
                notUrgent++;
                logger.debug("[UrgentFilterDetail] reject notUrgent: " + describeHuman(h)
                        + ", remainHp=" + remainHp);
                continue;
            }

            accepted++;

            double score = computeRescueScore(h);
            logger.debug("[UrgentScoreDetail] accepted urgent: " + describeHuman(h)
                    + ", score=" + score
                    + ", remainHp=" + remainHp);

            if (score < bestScore) {
                bestScore = score;
                best = h;
            }
        }

        logger.debug("[UrgentFilter] input=" + targets.size()
                + ", accepted=" + accepted
                + ", tooFar=" + tooFar
                + ", deadBeforeArrival=" + deadBeforeArrival
                + ", notUrgent=" + notUrgent
                + ", maxDistance=" + getUrgentTargetMaxDistance()
                + ", selected=" + (best == null ? "null" : best.getID())
                + ", bestScore=" + bestScore);

        return best;
    }

    private boolean isUrgentHuman(Human human, double remainHp) {
        if (!human.isHPDefined() || !human.isDamageDefined()) {
            return false;
        }
        if (human.getDamage() <= 0) {
            return false;
        }

        if (isSmallMapLike()) {
            return remainHp < human.getHP() * 0.65;
        }
        if (isLargeMapLike()) {
            return remainHp < human.getHP() * 0.45;
        }
        return remainHp < human.getHP() * 0.55;
    }

    private double computeRescueScore(Human h) {
        int distance = this.worldInfo.getDistance(this.agentInfo.me(), h);
        int buriedness = h.isBuriednessDefined() ? h.getBuriedness() : 1000;
        int damage = h.isDamageDefined() ? h.getDamage() : 0;

        int agePenalty = 0;
        Integer lastSeenTime = knownCivilians.get(h.getID());
        if (lastSeenTime != null) {
            int age = this.agentInfo.getTime() - lastSeenTime;
            agePenalty = age * getAgePenaltyPerTurn();
        }

        return distance
                + buriedness * getBuriednessWeight()
                - damage * getDamageWeight()
                + agePenalty;
    }

    private boolean isTargetFeasible(Human human) {
        if (!isValidHuman(human)) {
            logger.debug("[Feasible] reject invalid: " + describeHuman(human)
                    + ", reason=" + getInvalidHumanReason(human));
            return false;
        }

        double remainHp = estimateRemainHpAfterArrival(human);
        if (remainHp <= 0) {
            logger.debug("[Feasible] reject remainHp: " + describeHuman(human)
                    + ", remainHp=" + remainHp);
            return false;
        }

        if (human.getBuriedness() < DIRECT_LOAD_BURIEDNESS_THRESHOLD) {
            logger.debug("[Feasible] accept direct load threshold: " + describeHuman(human)
                    + ", threshold=" + DIRECT_LOAD_BURIEDNESS_THRESHOLD);
            return true;
        }

        int fireBrigadeCount = getFireBrigadeRescueCount(human);
        boolean fbAssist = fireBrigadeCount > 0 && human.getBuriedness() <= FB_ASSIST_BURIEDNESS_THRESHOLD;

        if (fbAssist) {
            logger.debug("[Feasible] accept fb assist: " + describeHuman(human)
                    + ", fbCount=" + fireBrigadeCount
                    + ", threshold=" + FB_ASSIST_BURIEDNESS_THRESHOLD);
            return true;
        }

        logger.debug("[Feasible] reject buriedness/fb: " + describeHuman(human)
                + ", fbCount=" + fireBrigadeCount
                + ", directThreshold=" + DIRECT_LOAD_BURIEDNESS_THRESHOLD
                + ", fbAssistThreshold=" + FB_ASSIST_BURIEDNESS_THRESHOLD);

        return false;
    }

    private double estimateRemainHpAfterArrival(Human human) {
        int hp = human.isHPDefined() ? human.getHP() : 0;
        int damage = human.isDamageDefined() ? human.getDamage() : 0;
        int distance = this.worldInfo.getDistance(this.agentInfo.getID(), human.getID());

        double travelTime = Math.ceil((double) Math.max(distance, 0) / AGENT_MOVEMENT_ESTIMATE);
        return hp - damage * travelTime;
    }

    private int getFireBrigadeRescueCount(Human human) {
        if (messageManager == null) {
            return 0;
        }

        int count = 0;
        for (CommunicationMessage cm : messageManager.getReceivedMessageList(MessageFireBrigade.class)) {
            MessageFireBrigade messageFireBrigade = (MessageFireBrigade) cm;
            if (messageFireBrigade.getAction() == MessageFireBrigade.ACTION_RESCUE
                    && messageFireBrigade.getTargetID().equals(human.getID())) {
                count++;
            }
        }

        if (count > 0) {
            logger.debug("[FBRescueCount] human=" + human.getID()
                    + ", fbRescueCount=" + count);
        }

        return count;
    }

    private boolean isValidHuman(StandardEntity entity) {
        if (!(entity instanceof Human)) {
            return false;
        }

        Human target = (Human) entity;
        if (!target.isHPDefined() || target.getHP() <= 0) {
            return false;
        }
        if (!target.isPositionDefined()) {
            return false;
        }
        if (!target.isDamageDefined() || target.getDamage() <= 0) {
            return false;
        }
        if (!target.isBuriednessDefined()) {
            return false;
        }

        StandardEntity position = worldInfo.getPosition(target);
        if (position == null) {
            return false;
        }

        StandardEntityURN positionURN = position.getStandardURN();
        return positionURN != REFUGE && positionURN != AMBULANCE_TEAM;
    }

    private String getInvalidHumanReason(Human target) {
        if (target == null) {
            return "null";
        }
        if (!target.isHPDefined()) {
            return "HP_UNDEFINED";
        }
        if (target.getHP() <= 0) {
            return "HP_LE_ZERO";
        }
        if (!target.isPositionDefined()) {
            return "POSITION_UNDEFINED";
        }
        if (!target.isDamageDefined()) {
            return "DAMAGE_UNDEFINED";
        }
        if (target.getDamage() <= 0) {
            return "DAMAGE_LE_ZERO";
        }
        if (!target.isBuriednessDefined()) {
            return "BURIEDNESS_UNDEFINED";
        }

        StandardEntity position = worldInfo.getPosition(target);
        if (position == null) {
            return "POSITION_ENTITY_NULL";
        }

        StandardEntityURN positionURN = position.getStandardURN();
        if (positionURN == REFUGE) {
            return "IN_REFUGE";
        }
        if (positionURN == AMBULANCE_TEAM) {
            return "IN_AMBULANCE_TEAM";
        }

        return "VALID";
    }

    private String describeHuman(Human h) {
        if (h == null) {
            return "null";
        }

        int hp = h.isHPDefined() ? h.getHP() : -1;
        int damage = h.isDamageDefined() ? h.getDamage() : -1;
        int buriedness = h.isBuriednessDefined() ? h.getBuriedness() : -1;
        int distance = h.isPositionDefined()
                ? this.worldInfo.getDistance(this.agentInfo.getID(), h.getID())
                : -1;

        Integer lastSeen = knownCivilians.get(h.getID());
        int age = lastSeen == null ? -1 : this.agentInfo.getTime() - lastSeen;

        double remainHp = -1;
        if (h.isHPDefined() && h.isDamageDefined() && h.isPositionDefined()) {
            remainHp = estimateRemainHpAfterArrival(h);
        }

        EntityID pos = h.isPositionDefined() ? h.getPosition() : null;
        StandardEntity posEntity = pos == null ? null : worldInfo.getEntity(pos);
        String posURN = posEntity == null ? "null" : String.valueOf(posEntity.getStandardURN());

        return "id=" + h.getID()
                + ", pos=" + (pos == null ? "undef" : pos)
                + ", posURN=" + posURN
                + ", hp=" + hp
                + ", damage=" + damage
                + ", buriedness=" + buriedness
                + ", distance=" + distance
                + ", age=" + age
                + ", remainHp=" + remainHp;
    }

    private void logSelectedTargetDetail(EntityID targetID) {
        if (targetID == null) {
            logger.debug("[TargetDetail] target=null"
                    + ", agentPos=" + this.agentInfo.getPosition()
                    + ", known=" + knownCivilians.size()
                    + ", wrong=" + wrongCivilians.size());
            return;
        }

        StandardEntity entity = this.worldInfo.getEntity(targetID);
        if (!(entity instanceof Human)) {
            logger.debug("[TargetDetail] target=" + targetID
                    + ", entity=" + entity
                    + ", agentPos=" + this.agentInfo.getPosition());
            return;
        }

        Human h = (Human) entity;
        logger.debug("[TargetDetail] targetHuman=" + describeHuman(h)
                + ", score=" + (isValidHuman(h) ? computeRescueScore(h) : "invalid:" + getInvalidHumanReason(h)));
    }

    public boolean hasATWait(Human human) {
        int numOfHuman = 0;
        int numOfAmbulanceTeam = 0;

        for (StandardEntity se : this.worldInfo.getEntitiesOfType(CIVILIAN)) {
            if (!(se instanceof Civilian)) {
                continue;
            }

            Civilian civilian = (Civilian) se;
            if (!civilian.isPositionDefined()) {
                continue;
            }
            if (!civilian.isHPDefined() || civilian.getHP() <= 0) {
                continue;
            }
            if (civilian.getPosition().equals(human.getPosition())) {
                numOfHuman++;
            }
        }

        if (this.agentInfo.getPosition().equals(human.getPosition())) {
            for (StandardEntity standardEntity : this.worldInfo.getEntitiesOfType(AMBULANCE_TEAM)) {
                AmbulanceTeam ambulanceTeam = (AmbulanceTeam) standardEntity;

                if (this.agentInfo.getID().getValue() >= ambulanceTeam.getID().getValue()) {
                    continue;
                }
                if (ambulanceTeam.isPositionDefined()
                        && ambulanceTeam.getPosition().equals(human.getPosition())) {
                    numOfAmbulanceTeam++;
                }
            }
        } else {
            for (StandardEntity standardEntity : this.worldInfo.getEntitiesOfType(AMBULANCE_TEAM)) {
                AmbulanceTeam ambulanceTeam = (AmbulanceTeam) standardEntity;
                if (ambulanceTeam.isPositionDefined()
                        && ambulanceTeam.getPosition().equals(human.getPosition())) {
                    numOfAmbulanceTeam++;
                }
            }
        }

        boolean wait = numOfAmbulanceTeam >= Math.max(1, numOfHuman);
        if (wait) {
            logger.debug("[ATWait] true, human=" + describeHuman(human)
                    + ", numOfHumanAtPos=" + numOfHuman
                    + ", numOfATAtPos=" + numOfAmbulanceTeam);
            return true;
        }

        if (numOfAmbulanceTeam > 0) {
            logger.debug("[ATWait] false, human=" + describeHuman(human)
                    + ", numOfHumanAtPos=" + numOfHuman
                    + ", numOfATAtPos=" + numOfAmbulanceTeam);
        }

        return false;
    }

    public boolean hasATinCluster(Human human) {
        if (messageManager == null || !human.isPositionDefined()) {
            return false;
        }

        int humanIndex = clustering.getClusterIndex(human.getPosition());
        int meIndex = clustering.getClusterIndex(this.agentInfo.getPosition());

        if (humanIndex != meIndex) {
            int counter = 0;
            for (CommunicationMessage communicationMessage
                    : messageManager.getReceivedMessageList(MessageAmbulanceTeam.class)) {

                MessageAmbulanceTeam mat = (MessageAmbulanceTeam) communicationMessage;
                int matIndex = clustering.getClusterIndex(mat.getPosition());

                if (mat.getTargetID().equals(human.getPosition())
                        || mat.getTargetID().equals(human.getID())) {
                    if (matIndex == humanIndex) {
                        counter++;
                    }
                    if (counter > 2) {
                        logger.debug("[ATCluster] true external cluster, human=" + describeHuman(human)
                                + ", counter=" + counter
                                + ", humanIndex=" + humanIndex
                                + ", meIndex=" + meIndex);
                        return true;
                    }
                }
            }
        } else {
            int counter = 0;
            for (CommunicationMessage communicationMessage
                    : messageManager.getReceivedMessageList(MessageAmbulanceTeam.class)) {

                MessageAmbulanceTeam mat = (MessageAmbulanceTeam) communicationMessage;
                if (!(this.worldInfo.getEntity(mat.getTargetID()) instanceof Human)) {
                    continue;
                }

                int matIndex = clustering.getClusterIndex(mat.getPosition());
                if (matIndex != humanIndex) {
                    continue;
                }
                if (!(mat.getTargetID().equals(human.getPosition())
                        || mat.getTargetID().equals(human.getID()))) {
                    continue;
                }
                if (mat.getAgentID().getValue() > this.agentInfo.getID().getValue()) {
                    counter++;
                }
                if (counter > 3) {
                    logger.debug("[ATCluster] true same cluster, human=" + describeHuman(human)
                            + ", counter=" + counter
                            + ", humanIndex=" + humanIndex
                            + ", meIndex=" + meIndex);
                    return true;
                }
            }
        }
        return false;
    }
}
