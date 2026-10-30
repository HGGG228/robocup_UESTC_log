package UESTC.module.complex.common;

import java.util.Set;
import java.util.HashSet;

import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;


import rescuecore2.misc.Pair;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;


import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

/**
 * ObjectScreener 实体过滤器（雷达扫描仪）。
 * 物理意义：地震发生后，世界一片混乱。每一秒钟，救护车的视野里都会出现很多新东西（Changed Entities）。
 * 这个类的作用就是拿个放大镜，把这些新出现的东西分门别类地挑出来。
 * 比如：“把所有路障挑出来”、“找出离我最近的那个平民”等。
 */
public class ObjectScreener {

  // 挂载世界地图信息（包含所有静态建筑和动态人员）
  private WorldInfo wi;
  // 挂载我这辆救护车自己的档案信息
  private AgentInfo ai;
  // 挂载比赛的全局规则设定（比如通信距离有多远）
  private ScenarioInfo si;
  // 雷达屏幕：存放当前这个时间步（Timestep）内，视线里所有发生了变动的物体集合
  private Set<EntityID> changed = new HashSet<>();

  // 记录我自己的绝对物理横坐标 X
  private double x = 0.0;
  // 记录我自己的绝对物理纵坐标 Y
  private double y = 0.0;

  /**
   * 构造函数 1：全参装配
   */
  public ObjectScreener(WorldInfo wi, AgentInfo ai, ScenarioInfo si) {
    this.wi = wi;
    this.ai = ai;
    this.si = si;
    this.x = ai.getX(); // 读取我的当前经度
    this.y = ai.getY(); // 读取我的当前纬度
    // 开启雷达，抓取这一秒内视线中所有的变动物体
    this.changed = this.wi.getChanged().getChangedEntities();
  }

  /**
   * 构造函数 2：缺省配置装配（不传入 ScenarioInfo）
   */
  public ObjectScreener(WorldInfo wi, AgentInfo ai) {
    this.wi = wi;
    this.ai = ai;
    this.x = ai.getX();
    this.y = ai.getY();
    this.changed = this.wi.getChanged().getChangedEntities();
  }

  /**
   * 雷达屏幕刷新器：在每个新回合开始时，雷达必须重新扫描环境。
   */
  public void update(WorldInfo wi, AgentInfo ai) {
    this.wi = wi;
    this.ai = ai;
    this.x = ai.getX();
    this.y = ai.getY();
    // 清除上一秒的残影，抓取最新的雷达变动数据
    this.changed = this.wi.getChanged().getChangedEntities();
  }

  /**
   * 万能查询分发台（查 ID）：你只要告诉我你想找什么类型的东西的门牌号，我就给你返回一堆门牌号。
   */
  public Set<EntityID> getEntityIDs(StandardEntityURN entityType) {
    switch (entityType) {
      case CIVILIAN:       return this.getCivilianIDs();
      case AMBULANCE_TEAM: return this.getAmbulanceTeamIDs();
      case FIRE_BRIGADE:   return this.getFireBrigadeIDs();
      case POLICE_FORCE:   return this.getPoliceForceIDs(); // 修复：原代码这里错写成了 getFireBrigadeIDs()
      case BLOCKADE:       return this.getBlockadeIDs();
      case ROAD:           return this.getRoadIDs();
      case BUILDING:       return this.getBuildingIDs();
      case REFUGE:         return this.getRefugeIDs();
      default:             return new HashSet<>();
    }
  }

  /**
   * 万能查询分发台（查实体）：你只要告诉我找什么类型的东西，我把实体对象全抓过来给你。
   */
  public Set<?> getEntities(StandardEntityURN entityType) {
    switch (entityType) {
      case CIVILIAN:       return this.getCivilians();
      case AMBULANCE_TEAM: return this.getAmbulanceTeams();
      case FIRE_BRIGADE:   return this.getFireBrigades();
      case POLICE_FORCE:   return this.getPoliceForces(); // 修复：原代码这里也错写成了 getFireBrigades()
      case BLOCKADE:       return this.getBlockades();
      case ROAD:           // 道路、建筑、避难所在原代码中未实现返回具体实体集合，保持原样防错
      case BUILDING:
      case REFUGE:
      default:             return new HashSet<>();
    }
  }

  // ==================== 寻找最近的友军和老百姓 (经典擂台算法重写) ====================

  /**
   * 找最近的救护车兄弟（Ambulance Team）
   */
  public EntityID getNearyAT() {
    double minDistance = Double.MAX_VALUE; // 擂台初始最高分
    EntityID nearestID = null; // 冠军宝座

    // 经典遍历雷达上的所有物体
    for (EntityID id : this.changed) {
        StandardEntity entity = this.wi.getEntity(id);
        // 如果扫描到的是个救护车同行
        if (entity instanceof AmbulanceTeam) {
            // 计算他离我有多远
            double dist = this.distance(id, this.ai.getID());
            // 如果比当前冠军还近，就换他当冠军
            if (dist < minDistance) {
                minDistance = dist;
                nearestID = id;
            }
        }
    }
    return nearestID;
  }

  public Set<AmbulanceTeam> getAmbulanceTeams() {
    Set<AmbulanceTeam> results = new HashSet<>();
    for (EntityID id : this.changed) {
        StandardEntity entity = this.wi.getEntity(id);
        if (entity instanceof AmbulanceTeam) {
            results.add((AmbulanceTeam) entity);
        }
    }
    return results;
  }

  public Set<EntityID> getAmbulanceTeamIDs() {
    Set<EntityID> results = new HashSet<>();
    for (AmbulanceTeam at : this.getAmbulanceTeams()) {
        results.add(at.getID());
    }
    return results;
  }

  /**
   * 找最近的消防车（Fire Brigade）
   */
  public EntityID getNearyFB() {
    double minDistance = Double.MAX_VALUE;
    EntityID nearestID = null;

    for (EntityID id : this.changed) {
        StandardEntity entity = this.wi.getEntity(id);
        if (entity instanceof FireBrigade) {
            double dist = this.distance(id, this.ai.getID());
            if (dist < minDistance) {
                minDistance = dist;
                nearestID = id;
            }
        }
    }
    return nearestID;
  }

  public Set<FireBrigade> getFireBrigades() {
    Set<FireBrigade> results = new HashSet<>();
    for (EntityID id : this.changed) {
        StandardEntity entity = this.wi.getEntity(id);
        if (entity instanceof FireBrigade) {
            results.add((FireBrigade) entity);
        }
    }
    return results;
  }

  public Set<EntityID> getFireBrigadeIDs() {
    Set<EntityID> results = new HashSet<>();
    for (FireBrigade fb : this.getFireBrigades()) {
        results.add(fb.getID());
    }
    return results;
  }

  /**
   * 找最近的警察（Police Force）
   */
  public EntityID getNearyPF() {
    double minDistance = Double.MAX_VALUE;
    EntityID nearestID = null;

    for (EntityID id : this.changed) {
        StandardEntity entity = this.wi.getEntity(id);
        if (entity instanceof PoliceForce) {
            double dist = this.distance(id, this.ai.getID());
            if (dist < minDistance) {
                minDistance = dist;
                nearestID = id;
            }
        }
    }
    return nearestID;
  }

  public Set<PoliceForce> getPoliceForces() {
    Set<PoliceForce> results = new HashSet<>();
    for (EntityID id : this.changed) {
        StandardEntity entity = this.wi.getEntity(id);
        if (entity instanceof PoliceForce) {
            results.add((PoliceForce) entity);
        }
    }
    return results;
  }

  public Set<EntityID> getPoliceForceIDs() {
    Set<EntityID> results = new HashSet<>();
    for (PoliceForce pf : this.getPoliceForces()) {
        results.add(pf.getID());
    }
    return results;
  }

  /**
   * 找最近的平民老百姓（Civilian）
   */
  public EntityID getNearyCivilianID() {
    double minDistance = Double.MAX_VALUE;
    EntityID nearestID = null;

    for (EntityID id : this.changed) {
        StandardEntity entity = this.wi.getEntity(id);
        if (entity instanceof Civilian) {
            double dist = this.distance(id, this.ai.getID());
            if (dist < minDistance) {
                minDistance = dist;
                nearestID = id;
            }
        }
    }
    return nearestID;
  }

  public Set<Civilian> getCivilians() {
    Set<Civilian> results = new HashSet<>();
    for (EntityID id : this.changed) {
        StandardEntity entity = this.wi.getEntity(id);
        if (entity instanceof Civilian) {
            results.add((Civilian) entity);
        }
    }
    return results;
  }

  public Set<EntityID> getCivilianIDs() {
    Set<EntityID> results = new HashSet<>();
    for (Civilian civ : this.getCivilians()) {
        results.add(civ.getID());
    }
    return results;
  }

  // ==================== 语音电波范围内（Voice Range）的雷达搜索 ====================
  // 物理意义：有时候不用无线电，全靠嗓子喊。这就要求对方必须在物理声波传播范围内，且最好是在空旷的道路上（Road）回音小。

  /**
   * 找嗓门能喊到的最近的救护车兄弟
   */
  public EntityID getNearyATinVoiceRange() {
    // 查阅服务器设定的“人声传播最大半径”
    int voiceRange = this.si.getRawConfig().getIntValue("comms.channels.0.range");
    double minSortDistance = Double.MAX_VALUE;
    EntityID nearestID = null;

    for (EntityID id : this.changed) {
        StandardEntity entity = this.wi.getEntity(id);
        if (entity instanceof AmbulanceTeam) {
            AmbulanceTeam at = (AmbulanceTeam) entity;
            // 战术约束：对方必须正处于一条道路上（Road），在房子里喊话不一定能听清
            if (this.wi.getPosition(at.getID()) instanceof Road) {
                // 校验：他离我的真实物理距离，是否在声波射程之内？
                double actualDist = this.wi.getDistance(at.getPosition(), this.ai.getID());
                if (actualDist < voiceRange) {
                    // 如果在射程内，再用建筑坐标计算排序距离，选出一个最近的
                    double sortDist = this.wi.getDistance(at.getPosition(), this.ai.getPosition());
                    if (sortDist < minSortDistance) {
                        minSortDistance = sortDist;
                        nearestID = at.getID();
                    }
                }
            }
        }
    }
    return nearestID;
  }

  /**
   * 找嗓门能喊到的最近的消防员
   */
  public EntityID getNearyFBinVoiceRange() {
    int voiceRange = this.si.getRawConfig().getIntValue("comms.channels.0.range");
    double minSortDistance = Double.MAX_VALUE;
    EntityID nearestID = null;

    for (EntityID id : this.changed) {
        StandardEntity entity = this.wi.getEntity(id);
        if (entity instanceof FireBrigade) {
            FireBrigade fb = (FireBrigade) entity;
            if (this.wi.getPosition(fb.getID()) instanceof Road) {
                double actualDist = this.wi.getDistance(fb.getPosition(), this.ai.getID());
                if (actualDist < voiceRange) {
                    double sortDist = this.wi.getDistance(fb.getPosition(), this.ai.getPosition());
                    if (sortDist < minSortDistance) {
                        minSortDistance = sortDist;
                        nearestID = fb.getID();
                    }
                }
            }
        }
    }
    return nearestID;
  }

  /**
   * 找嗓门能喊到的最近的警察
   */
  public EntityID getNearyPFinVoiceRange() {
    int voiceRange = this.si.getRawConfig().getIntValue("comms.channels.0.range");
    double minSortDistance = Double.MAX_VALUE;
    EntityID nearestID = null;

    for (EntityID id : this.changed) {
        StandardEntity entity = this.wi.getEntity(id);
        if (entity instanceof PoliceForce) {
            PoliceForce pf = (PoliceForce) entity;
            if (this.wi.getPosition(pf.getID()) instanceof Road) {
                double actualDist = this.wi.getDistance(pf.getPosition(), this.ai.getID());
                if (actualDist < voiceRange) {
                    double sortDist = this.wi.getDistance(pf.getPosition(), this.ai.getPosition());
                    if (sortDist < minSortDistance) {
                        minSortDistance = sortDist;
                        nearestID = pf.getID();
                    }
                }
            }
        }
    }
    return nearestID;
  }

  // ==================== 静态非生物实体提取 (解决致命无限递归 Bug 区) ====================

  public Set<Blockade> getBlockades() {
    Set<Blockade> results = new HashSet<>();
    for (EntityID id : this.changed) {
        StandardEntity entity = this.wi.getEntity(id);
        if (entity instanceof Blockade) {
            results.add((Blockade) entity);
        }
    }
    return results;
  }

  public Set<EntityID> getBlockadeIDs() {
    Set<EntityID> results = new HashSet<>();
    for (Blockade b : this.getBlockades()) {
        results.add(b.getID());
    }
    return results;
  }

  /**
   * 寻找对车辆构成威胁的最近路障
   */
  public Blockade getNearyBlockade() {
    return this.nearyBlockade(this.getBlockades(), this.x, this.y);
  }

  public Set<Road> getRoads() {
    Set<Road> results = new HashSet<>();
    for (EntityID id : this.changed) {
        StandardEntity entity = this.wi.getEntity(id);
        if (entity instanceof Road) {
            results.add((Road) entity);
        }
    }
    return results;
  }

  /**
   * 致命 Bug 修复区 1：原版使用 this.getRoadIDs().stream()... 导致自己调自己引发死循环。
   * 现已修改为调用 this.getRoads()。
   */
  public Set<EntityID> getRoadIDs() {
    Set<EntityID> results = new HashSet<>();
    for (Road r : this.getRoads()) {
        results.add(r.getID());
    }
    return results;
  }

  public Set<Building> getBuildings() {
    Set<Building> results = new HashSet<>();
    for (EntityID id : this.changed) {
        StandardEntity entity = this.wi.getEntity(id);
        if (entity instanceof Building) {
            results.add((Building) entity);
        }
    }
    return results;
  }

  /**
   * 致命 Bug 修复区 2：修复无限递归死循环。
   */
  public Set<EntityID> getBuildingIDs() {
    Set<EntityID> results = new HashSet<>();
    for (Building b : this.getBuildings()) {
        results.add(b.getID());
    }
    return results;
  }

  public Set<Refuge> getRefuges() {
    Set<Refuge> results = new HashSet<>();
    for (EntityID id : this.changed) {
        StandardEntity entity = this.wi.getEntity(id);
        if (entity instanceof Refuge) {
            results.add((Refuge) entity);
        }
    }
    return results;
  }

  /**
   * 致命 Bug 修复区 3：修复无限递归死循环。
   */
  public Set<EntityID> getRefugeIDs() {
    Set<EntityID> results = new HashSet<>();
    for (Refuge r : this.getRefuges()) {
        results.add(r.getID());
    }
    return results;
  }

  // ==================== 底层几何与数学运算支持 ====================

  /**
   * 计算地图上两个实体门牌号之间的曼哈顿距离。
   */
  private double distance(EntityID a1, EntityID a2) {
    Pair<Integer, Integer> p1 = this.wi.getLocation(a1);
    Pair<Integer, Integer> p2 = this.wi.getLocation(a2);
    return Math.hypot(
        Math.abs(p1.first() - p2.first()),
        Math.abs(p1.second() - p2.second())
    );
  }

  /**
   * 这个算法用于在一堆路障中，挑出“不仅离我近，而且有可能已经把我卡住”的最危险路障。
   */
  private Blockade nearyBlockade(Set<Blockade> bs, double x, double y) {
    if (bs == null) {
      return null;
    }
    Blockade ret = null;
    double dis = Double.MAX_VALUE;
    // 使用团队自定义的地质雷达工具，判断我所在的坐标 (x,y) 是否被路障围死了
    final EntrappedState stucked = new EntrappedState(bs, x, y);

    for (Blockade b : bs) {
      // 如果我已经身陷囹圄，那就把困住我的那个路障直接定为最急需处理的目标
      if (stucked.isEntrappedState()) {
        return b;
      }
      // 否则，正常测算我到这个路障边缘线的最近物理距离
      final double d = this.getDistance(b, x, y);
      if (dis > d) {
        ret = b;
        dis = d;
      }
    }
    return ret;
  }

  /**
   * 修复了数组越界崩溃隐患的路障距离计算器。
   * 物理意义：路障（Blockade）不是一个点，而是一堆不规则的碎石多边形。
   * 我们要把多边形的每一条边（线段）都扒出来，计算车体中心点到这条线段的最短投影距离。
   */
  private double getDistance(Blockade b, double x, double y) {
    // 拿到组成这个碎石堆边缘的所有顶点坐标 [X1, Y1, X2, Y2, X3, Y3...]
    int[] apexs = b.getApexes();
    double dis = Double.MAX_VALUE;

    // 多边形实际有多少个顶点
    int totalPoints = apexs.length / 2;

    // 遍历多边形的每一条封闭边缘线段
    for (int i = 0; i < totalPoints; i++) {
        // 提取线段起点的 X 和 Y
        double x1 = apexs[2 * i];
        double y1 = apexs[2 * i + 1];

        // 提取线段终点的 X 和 Y（使用取模运算巧妙实现多边形首尾闭合）
        int nextIndex = (i + 1) % totalPoints;
        double x2 = apexs[2 * nextIndex];
        double y2 = apexs[2 * nextIndex + 1];

        // 利用线性代数公式：计算点到直线的法向投影距离
        double dx = x2 - x1;
        double dy = y2 - y1;

        if (dx != 0 || dy != 0) {
            // 点到直线方程距离公式：d = |Ax + By + C| / sqrt(A^2 + B^2) 的变体
            double dist = Math.abs((dx * (y - y1) - dy * (x - x1)) / Math.sqrt(dx * dx + dy * dy));
            if (dis > dist) {
                dis = dist;
            }
        }
    }
    return dis;
  }

  /**
   * 通用的点到线段几何距离求值器（备用工具，当前通过上面的合并算法实现）
   */
  private double calcDistancePointToLine(Point2D point, Line2D line) {
    final Point2D l1 = line.getOrigin();
    final Point2D l2 = line.getEndPoint();
    return Math.abs((l2.getX() - l1.getX()) * (l1.getY() - point.getY())
             - (l1.getX() - point.getX()) * (l2.getY() - l1.getY()))
        / Math.sqrt(Math.pow(l2.getX() - l1.getX(), 2) + Math.pow(l2.getY() - l1.getY(), 2));
  }
}
