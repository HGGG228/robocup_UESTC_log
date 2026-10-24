package UESTC.module.complex.common; 

import java.util.Set; 


import org.locationtech.jts.geom.*;


import rescuecore2.standard.entities.Blockade; 
import rescuecore2.worldmodel.EntityID;        
/**
 * EntrappedState 受困状态雷达侦测器。
 * 核心战术价值：当车辆发现自己无法移动时，调用此雷达扫描脚底。
 * 它通过提取乱石堆的多边形轮廓，并运用 JTS 的 Point-in-Polygon（点在多边形内）算法，
 * 精确判定车辆中心点是否已经陷入了碎石堆的包围圈。
 */
public class EntrappedState {

  // 侦测结果标定：只要发现自己踩中了哪怕一个路障，此警报就会被拉响（置为 true）
  private boolean result = false;
  
  // 罪魁祸首记录：如果确认被卡住，这里将记录下究竟是哪个具体的碎石堆困住了我
  private Blockade blockade = null;

  /**
   * 雷达开机扫描程序（构造方法）。
   * @param obstacleCollection 视野范围内所有可能对我造成威胁的碎石堆集合
   * @param agentX 我当前所处位置的绝对经度 X
   * @param agentY 我当前所处位置的绝对纬度 Y
   */
  public EntrappedState(Set<Blockade> obstacleCollection, double agentX, double agentY) {
    // 基础防呆测试：如果周围一片坦途，根本没有路障实体传进来，直接关闭雷达
    if (obstacleCollection == null) {
      return;
    }
    
    // 启动经典的 For-Each 巡检机制，挨个排查周围的每一个碎石堆
    for (Blockade currentObstacle : obstacleCollection) {
      // 呼叫底层的精密几何分析仪，判断我的坐标 (X,Y) 是否落入了当前碎石堆的内部
      if (this.isEntrappedState(currentObstacle, agentX, agentY)) {
        // 警报拉响！确诊已被卡死
        this.blockade = currentObstacle; // 锁定困住我的那个碎石堆，方便后续请求消防员来把它锯掉
        this.result = true;              // 状态改写为“受困”
        break;                           // 既然已经被卡住了，就没必要再去测其他的碎石堆了，立刻中断循环节省算力
      }
    }
  }

  /**
   * 核心几何分析仪：运用 JTS 构建物理拓扑模型进行重叠判定。
   * @param targetBlockade 需要被剖析的目标碎石堆
   * @param targetX 我的车辆横坐标
   * @param targetY 我的车辆纵坐标
   * @return 如果我在它肚子里，返回 true；否则返回 false
   */
  private boolean isEntrappedState(Blockade targetBlockade, double targetX, double targetY) {
    // 数据完备性校验：防止空指针幽灵
    if (targetBlockade == null) {
      return false;
    }
    
    // 从系统底层抽出这个碎石堆的原始轮廓顶点数据：[X1, Y1, X2, Y2, X3, Y3...]
    int[] rawVertices = targetBlockade.getApexes();
    
    // 计算该多边形真实的物理角点数量
    int actualVertexCount = rawVertices.length / 2;
    
    // 准备一块画板：按照 JTS 的严苛标准，闭合多边形的顶点数组必须首尾相连，所以容量为 N + 1
    Coordinate[] closedPolygonCoordinates = new Coordinate[actualVertexCount + 1];
    
    // 启动组装流水线：将干瘪的一维数组转化为丰满的 JTS 二维坐标对象
    for (int i = 0; i < actualVertexCount; i++) {
      // 提取横坐标 (X 永远在偶数位)
      int xValue = rawVertices[i * 2];
      // 提取纵坐标 (Y 永远在奇数位)
      int yValue = rawVertices[i * 2 + 1];
      // 烧制成 JTS 专属的坐标点砖块，并铺设到画板上
      closedPolygonCoordinates[i] = new Coordinate(xValue, yValue);
    }
    
    // 完美闭环焊点：将画板上画出的起笔第一个点，强行复制到最后一笔上，形成一个密不透风的图形
    closedPolygonCoordinates[actualVertexCount] = closedPolygonCoordinates[0];
    
    // 启动 JTS 重型几何制造工厂
    GeometryFactory jtsFactory = new GeometryFactory();
    
    try {
        // 工序 1：利用焊死的坐标系，浇筑出那个挡路的碎石多边形实体模型
        Geometry obstaclePolygon = jtsFactory.createPolygon(closedPolygonCoordinates);
        
        // 工序 2：把我的车辆中心坐标也做成一个 JTS 的孤立点模型
        Coordinate agentCoord = new Coordinate(targetX, targetY);
        Point agentPoint = jtsFactory.createPoint(agentCoord);
        
        // 终极审判：利用高级拓扑学定理，判断“面（碎石堆）”是否包含了“点（我的车）”
        return obstaclePolygon.contains(agentPoint);
        
    } catch (Exception e) {
        // 地图畸变防御网：官方给的某些路障顶点可能会形成“自相交”的麻花形状，导致 JTS 工厂崩溃抛出异常。
        // 为了防止一辆救护车因为看一眼路障就导致整个进程暴毙，我们将其捕获，并默认此畸变路障没有卡住我。
        e.printStackTrace();
        return false;
    }
  }

  /**
   * 状态对外公布接口：我到底有没有被困住？
   * @return true 代表动弹不得，false 代表畅通无阻
   */
  public boolean isEntrappedState() {
    return this.result;
  }

  /**
   * 实体溯源接口：如果我被困住了，能不能把那个该死的路障实体对象拿给我看看？
   * @return 罪魁祸首的路障对象（若未被困则返回 null）
   */
  public Blockade getBlockade() {
    return this.blockade;
  }

  /**
   * 门牌号追查接口：我不想看路障本身，我只需要知道困住我的路障的官方编号是多少？
   * @return 罪魁祸首的 EntityID 门牌号（若未被困则返回 null）
   */
  public EntityID getBlockadeID() {
    // 优雅的三元运算符判定：如果没有路障，安全返回 null；否则上报其 ID
    return this.blockade == null ? null : this.blockade.getID();
  }
}
