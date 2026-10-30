package UESTC.module.complex.common;
import org.locationtech.jts.geom.*;


import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Road;

/**
 * ZoneDescriptor 面积测绘工具类。
 * 在灾难救援仿真中，底层系统只会告诉我们一个物体的“边缘轮廓坐标点”。
 * 本类的核心功能是：利用 JTS 几何工厂，将这些零散的坐标点连成一个封闭的多边形（Polygon），
 * 并计算出它在二维地图上的绝对占地面积。这对于计算建筑容量、预估路障清除时间等战术决策至关重要。
 */
public class ZoneDescriptor {

  // 面积寄存器：用于存储经过 JTS 底层运算后得出的几何面积数值，初始值设为绝对零度
  private double result = 0.0;

  /**
   * 测绘员构造器 1：专门负责丈量“通用区域（Area）”的面积
   * @param baseArea 传入的通用区域实体对象（可能是房屋，也可能是广场）
   */
  public ZoneDescriptor(Area baseArea) {
    // 严谨的安全校验：如果传进来的是个空气（null），直接拒收，防止后续空指针爆炸
    if (baseArea == null) {
      return;
    }
    // 从该区域实体中提取出所有的顶点坐标序列，交接给底层测绘车间进行计算
    this.calcMenseki(baseArea.getApexList());
  }

  /**
   * 测绘员构造器 2：专门负责丈量“建筑物（Building）”的面积
   * @param targetBuilding 传入的建筑物实体对象
   */
  public ZoneDescriptor(Building targetBuilding) {
    if (targetBuilding == null) {
      return;
    }
    this.calcMenseki(targetBuilding.getApexList());
  }

  /**
   * 测绘员构造器 3：专门负责丈量“道路（Road）”的面积
   * @param targetRoad 传入的道路实体对象
   */
  public ZoneDescriptor(Road targetRoad) {
    if (targetRoad == null) {
      return;
    }
    this.calcMenseki(targetRoad.getApexList());
  }

  /**
   * 测绘员构造器 4：专门负责丈量“路障/碎石堆（Blockade）”的面积
   * 物理意义：路障的面积越大，警察（PoliceForce）挥舞电锯清除它所耗费的回合数就越多。
   * @param targetBlockade 传入的路障实体对象
   */
  public ZoneDescriptor(Blockade targetBlockade) {
    if (targetBlockade == null) {
      return;
    }
    // 注意：在底层源码中，路障获取顶点的方法叫 getApexes()，与其他实体略有不同
    this.calcMenseki(targetBlockade.getApexes());
  }

  /**
   * 结果提货口：外部高级战术模块通过调用此方法，拿走算好的面积报告。
   * @return 计算出的精确面积双精度浮点数（如果发生异常则为 0.0）
   */
  public double getResult() {
    return this.result;
  }

  /**
   * 核心测绘车间：使用经典的 JTS (Java Topology Suite) 几何工厂来计算面积。
   * 这里严格保留了原版算法的核心逻辑，仅修复了可能导致多边形无法完美闭合的底层 Bug。
   * @param rawCoordinates 原始的一维整数数组，包含了多边形所有顶点的 X 和 Y 坐标，交替排列。
   */
  private void calcMenseki(int[] rawCoordinates) {
      // 第一道质检关卡：坐标数组的长度必须是偶数，因为 X 和 Y 必须成双成对出现。
      // 如果不是偶数，说明地图数据损坏，拒绝处理并抛出异常。
      if (rawCoordinates.length % 2 != 0) {
          throw new IllegalArgumentException("严重错误：传入的顶点坐标数组长度必须是偶数，当前存在落单的坐标值！");
      }

      // 启动 JTS 重型几何制造工厂，准备开始构建物理模型
      GeometryFactory jtsGeometryFactory = new GeometryFactory();

      // 计算这个多边形实际上拥有几个物理顶点（数组长度除以2）
      int actualVertexCount = rawCoordinates.length / 2;

      // ！！！重点修复与重构区！！！
      // 在 JTS 的严格标准中，一个合法的闭合多边形（LinearRing），它的第一个点和最后一个点必须完全重合。
      // 所以，用来装载坐标的数组长度，必须是“物理顶点数 + 1”！
      Coordinate[] closedPolygonCoordinates = new Coordinate[actualVertexCount + 1];

      // 初始化一个游标，用于在对象数组中按顺序落座
      int objectIndex = 0;

      // 启动经典循环，每次跨越 2 个步长，把一维数组里干瘪的整数，组装成丰满的 Coordinate 对象
      for (int i = 0; i < rawCoordinates.length; i += 2) {
          // 提取 X 轴坐标
          int xValue = rawCoordinates[i];
          // 提取紧随其后的 Y 轴坐标
          int yValue = rawCoordinates[i + 1];

          // 实例化一个 JTS 专属的坐标点对象，并存入我们准备好的大数组中
          closedPolygonCoordinates[objectIndex] = new Coordinate(xValue, yValue);
          // 游标向前移动一格，准备接待下一个坐标
          objectIndex++;
      }

      // 完美闭环工序：将这根链条的首尾彻底焊死！
      // 把数组里的第一个坐标点，强行复制一份，填补到数组的最后那个空位上。
      // 这样 JTS 就能顺滑地沿着坐标画出一个天衣无缝的封闭图形。
      closedPolygonCoordinates[actualVertexCount] = closedPolygonCoordinates[0];

      try {
          // 将我们精心组装好的闭合坐标链条，送入 JTS 工厂的流水线
          // 工厂会利用底层算法，生成一个极其严谨的二维多边形对象（Polygon）
          Polygon finalShape = jtsGeometryFactory.createPolygon(closedPolygonCoordinates);

          // 找工厂要一份该多边形的占地面积，并刻录进我们自己的结果寄存器中
          this.result = finalShape.getArea();

      } catch (IllegalArgumentException e) {
          // 异常兜底网：如果传进来的坐标实在太奇葩（比如只有两个点无法形成面，或者形成了自相交的死结多边形）
          // JTS 工厂会罢工并抛出异常。我们在这里温和地接住它。
          e.printStackTrace();

          // 为了不让上层的救护车、消防车大脑直接宕机，我们将这块诡异区域的面积强制归零
          this.result = 0.0;
      }
  }
}
