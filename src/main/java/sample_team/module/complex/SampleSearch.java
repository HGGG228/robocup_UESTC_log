package sample_team.module.complex;

import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_CENTRE;
import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_TEAM;
import static rescuecore2.standard.entities.StandardEntityURN.BUILDING;
import static rescuecore2.standard.entities.StandardEntityURN.FIRE_BRIGADE;
import static rescuecore2.standard.entities.StandardEntityURN.FIRE_STATION;
import static rescuecore2.standard.entities.StandardEntityURN.GAS_STATION;
import static rescuecore2.standard.entities.StandardEntityURN.POLICE_FORCE;
import static rescuecore2.standard.entities.StandardEntityURN.POLICE_OFFICE;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.Search;
import adf.core.debug.DefaultLogger;

import java.util.*;

import org.apache.log4j.Logger;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

public class SampleSearch extends Search {

  private PathPlanning pathPlanning;
  private Clustering clustering;

  private EntityID result;
  private Collection<EntityID> unsearchedBuildingIDs;

  private Logger logger;

  public SampleSearch(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    logger = DefaultLogger.getLogger(agentInfo.me());
    this.unsearchedBuildingIDs = new HashSet<>();

    StandardEntityURN agentURN = ai.me().getStandardURN();
    if (agentURN == AMBULANCE_TEAM) {
      this.pathPlanning = moduleManager.getModule(
              "UESTC.AT.HumanDetector.PathPlanning ",
              "adf.impl.module.algorithm.DijkstraPathPlanning");
      this.clustering = moduleManager.getModule(
              "UESTC.AT.Search.Clustering ",
              "adf.impl.module.algorithm.KMeansClustering");
    } else if (agentURN == FIRE_BRIGADE) {
      this.pathPlanning = moduleManager.getModule(
              "UESTC.FB.ExtActionMove.PathPlanning",
              "adf.impl.module.algorithm.DijkstraPathPlanning");
      this.clustering = moduleManager.getModule("UESTC.FB.Search.Clustering",
              "adf.impl.module.algorithm.KMeansClustering");
    } else if (agentURN == POLICE_FORCE) {
      this.pathPlanning = moduleManager.getModule(
              "UESTC.PF.RoadDetector.PathPlanning ",
              "adf.impl.module.algorithm.DijkstraPathPlanning");
      this.clustering = moduleManager.getModule(
              "UESTC.PF.Search.Clustering ",
              "adf.impl.module.algorithm.KMeansClustering");
    }
    registerModule(this.clustering);
    registerModule(this.pathPlanning);
  }


  @Override
  public Search updateInfo(MessageManager messageManager) {
    logger.debug("Time:" + agentInfo.getTime());
    super.updateInfo(messageManager);
    updateMessage(messageManager);
    this.unsearchedBuildingIDs
            .removeAll(this.worldInfo.getChanged().getChangedEntities());
    if (this.unsearchedBuildingIDs.isEmpty()) {
      this.reset();
      this.unsearchedBuildingIDs
              .removeAll(this.worldInfo.getChanged().getChangedEntities());
    }
    return this;
  }


  @Override
  public Search calc() {
    this.result = null;
    if (unsearchedBuildingIDs.isEmpty())
      return this;

    logger.debug("unsearchedBuildingIDs: " + unsearchedBuildingIDs);
    Map<EntityID, Integer> areaCost = new HashMap<>();
    for (EntityID singleBuilding : unsearchedBuildingIDs) {
      int distance = this.worldInfo.getDistance(singleBuilding, this.agentInfo.me().getID());
      areaCost.put(singleBuilding, distance);
    }
    ArrayList<EntityID> target = new ArrayList<>();
    List<Map.Entry<EntityID, Integer>> cost = new ArrayList<>(areaCost.entrySet());
    cost.sort(Comparator.comparingInt(Map.Entry::getValue));
    if (cost.size() >= 5) {
      target.add(cost.get((int) (Math.random() * 5)).getKey());
    } else {
      target.add(cost.get((int) (Math.random() * cost.size())).getKey());
    }
    this.pathPlanning.setFrom(this.agentInfo.getPosition());
    this.pathPlanning.setDestination(target);
    List<EntityID> path = this.pathPlanning.calc().getResult();
    logger.debug("best path is: " + path);
    if (path != null && path.size() > 2) {
      this.result = path.get(path.size() - 3);
    } else if (path != null && !path.isEmpty()) {
      this.result = path.get(path.size() - 1);
    }
    logger.debug("chose: " + result);
    return this;
  }


  private void reset() {
    this.unsearchedBuildingIDs.clear();
    int clusterIndex = this.clustering.getClusterIndex(this.agentInfo.getID());
    Collection<StandardEntity> clusterEntities = this.clustering
            .getClusterEntities(clusterIndex);
    if (clusterEntities != null && !clusterEntities.isEmpty()) {
      for (StandardEntity entity : clusterEntities) {
        if (entity instanceof Building && entity.getStandardURN() != REFUGE) {
          this.unsearchedBuildingIDs.add(entity.getID());
        }
      }
    } else {
      this.unsearchedBuildingIDs
              .addAll(this.worldInfo.getEntityIDsOfType(BUILDING, GAS_STATION,
                      AMBULANCE_CENTRE, FIRE_STATION, POLICE_OFFICE));
    }
  }


  @Override
  public EntityID getTarget() {
    return this.result;
  }


  private void updateMessage(MessageManager messageManager)
  {
    Set<EntityID> changedEntities = this.worldInfo.getChanged().getChangedEntities();
    changedEntities.add(this.agentInfo.getID());
    int time = this.agentInfo.getTime();

    for(CommunicationMessage message : messageManager.getReceivedMessageList()) {
      Class<? extends CommunicationMessage> messageClass = message.getClass();
      if(messageClass == MessageBuilding.class) {
        MessageBuilding mb = (MessageBuilding)message;
        if(!changedEntities.contains(mb.getBuildingID())) {
          MessageUtil.reflectMessage(this.worldInfo, mb);
        }
      } else if(messageClass == MessageRoad.class) {
        MessageRoad mr = (MessageRoad)message;
        if(mr.isBlockadeDefined() && !changedEntities.contains(mr.getBlockadeID())) {
          MessageUtil.reflectMessage(this.worldInfo, mr);
        }

      } else if(messageClass == MessageCivilian.class) {
        MessageCivilian mc = (MessageCivilian) message;
        if(!changedEntities.contains(mc.getAgentID())){
          MessageUtil.reflectMessage(this.worldInfo, mc);
        }

      } else if(messageClass == MessageAmbulanceTeam.class) {
        MessageAmbulanceTeam mat = (MessageAmbulanceTeam)message;
        if(!changedEntities.contains(mat.getAgentID())) {
          MessageUtil.reflectMessage(this.worldInfo, mat);
        }

      } else if(messageClass == MessageFireBrigade.class) {
        MessageFireBrigade mfb = (MessageFireBrigade) message;
        if(!changedEntities.contains(mfb.getAgentID())) {
          MessageUtil.reflectMessage(this.worldInfo, mfb);
        }

      } else if(messageClass == MessagePoliceForce.class) {
        MessagePoliceForce mpf = (MessagePoliceForce) message;
        if(!changedEntities.contains(mpf.getAgentID())) {
          MessageUtil.reflectMessage(this.worldInfo, mpf);
        }

      }
    }
  }

}