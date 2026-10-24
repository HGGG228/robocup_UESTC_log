package UESTC.module.complex.common; 

import rescuecore2.standard.entities.Building;

/**
 * StructuralFatigueOracle 建筑结构热力学疲劳与坍塌预测模拟器。
 * 核心战术价值：广域消防调度与废墟清障预判（Structural Collapse Triage）。
 * 它不执行任何真实的物理阻断动作，而是在硅基大脑中进行一次“如果消防队不介入，这栋楼几个回合后会彻底化为废墟”的沙盘推演。
 * 推算出的残存结构寿命（Structural Expectancy）将作为特警破拆或高压水枪阵地部署的避险指标。
 */
public class StructuralFatigueOracle {

  // 观察对象：当前需要进行物理承重力与热辐射评估的建筑实体
  private Building targetBuilding;
  
  // ==================== 物理建材衰减模型常数矩阵 ====================
  // 物理意义：燃烧或受损建筑的崩塌并不是线性的。随着承重墙的持续受热，
  // 钢筋混凝土结构会发生非线性的屈服形变（如热胀冷缩撕裂、材料熔点剥离）。

  // 基础环境白噪声常数：即使火势被控制，受损建筑在风力与自重下每回合也会产生的微观断裂
  private final double NOISE_FACTOR = 0.15;
  
  // 核心热力学侵蚀（Thermal Erosion）二次项加速系数：模拟高温对钢筋骨架的指数级软化效应
  private final double THERMAL_QUADRATIC_COEF = 0.00045;
  
  // 连带沉降剪切力（Shear Stress）二次项加速系数：模拟部分楼板塌陷后对剩余承重墙的拉扯破坏
  private final double SHEAR_QUADRATIC_COEF = 0.00030;
  
  // 热力学侵蚀的线性基础增量：每回合火焰烘烤带来的固定材料剥离数值
  private final double THERMAL_LINEAR_BASE = 0.02;
  
  // 沉降剪切力的线性基础增量：每回合物理重心偏移带来的固定应力增加
  private final double SHEAR_LINEAR_BASE = 0.015;

  /**
   * 构造勘探员 1：携带目标建筑蓝图入场。
   * @param targetBuilding 需要被评估结构疲劳度的建筑实体
   */
  public StructuralFatigueOracle(Building targetBuilding) {
    this.targetBuilding = targetBuilding;
  }

  /**
   * 构造勘探员 2：空手入场，通常用于只调用底层材料力学静态公式的场景。
   */
  public StructuralFatigueOracle() {
    this.targetBuilding = null;
  }

  /**
   * 外部提问接口 A：根据当前绑定的建筑实体，推算其还能在重力下支撑多久。
   * @return 预估的剩余屹立仿真步长（回合数）
   */
  public int getCollapseExpectancy() {
    // 将绑定的建筑对象移交给内部私有物理实验室进行推演
    return this.calcCollapseExpectancy(this.targetBuilding);
  }

  /**
   * 外部提问接口 B：脱离实体对象，纯粹抛入建筑耐久度与当前热辐射损伤进行裸算。
   * @param structuralHp 当前剩余的结构完整度（耐久池）
   * @param stressDamage 当前每回合承受的应力破坏速率
   * @return 预估的剩余屹立仿真步长（回合数）
   */
  public int getCollapseExpectancy(int structuralHp, int stressDamage) {
    // 将硬性数据移交给数字风洞推演中心
    return this.calcCollapseExpectancy(structuralHp, stressDamage);
  }

  /**
   * 内部私有物理实验室：提取建筑的真实破损参数，并委托给底层力学模型。
   * 优化点：通过归一化物理引擎的数据字典，将离散的破碎度转化为连续的寿命标量。
   */
  private int calcCollapseExpectancy(Building building) {
    // 安全防爆网：如果查无此楼，直接判定残余屹立寿命为 0
    if (building == null) {
        return 0;
    }
    
    // 提取出建筑的物理关键指标：将系统原生的 Brokenness (0-100) 逆向转换为结构血槽
    // 并将当前实时的温度 (Temperature) 视作正在施加的破坏流速
    int currentIntegrity = (100 - building.getBrokenness()) * 10; 
    int currentThermalStress = building.getTemperature();
    
    // 如果建筑已经彻底坍塌（完整度<=0），拦截冗余计算
    if (currentIntegrity <= 0) {
        return 0;
    }
    
    // 委托给纯数字风洞推演中心进行沙盘推演
    return this.calcCollapseExpectancy(currentIntegrity, currentThermalStress);
  }

  /**
   * 纯数字推演中心：建筑坍塌倒计时模拟器。
   * 在沙盘里一秒一秒地快进时间，直到大楼承重槽彻底归零，记录下支撑了多少个回合。
   */
  private int calcCollapseExpectancy(int initialIntegrity, int initialStress) {
    // 初始化沙盘体征：将整型的外部数据强制转换为双精度浮点数，以捕获微观层面的裂纹蔓延
    double simulatedIntegrity = initialIntegrity;
    double simulatedErosionRate = initialStress;
    
    // 计时器归零：准备记录该建筑在推演中抵抗地心引力的循环次数
    int survivedCycles = 0;
    
    // 开启坍塌倒计时：只要沙盘里这栋楼的主体结构还在，就让物理时间继续往前走
    while (simulatedIntegrity > 0) {
      // 冻结当前回合初的破坏流速，作为计算链式崩塌反应的基准参数
      final double baselineStress = simulatedErosionRate;
      
      // 恶化叠加 1：热力学侵蚀发作，钢筋软化情况进一步恶化，破坏速率增加
      simulatedErosionRate += this.calcDamageThermal(baselineStress);
      
      // 恶化叠加 2：剪切力撕裂发作，偏心荷载加剧，破坏速率再次增加
      simulatedErosionRate += this.calcDamageShear(baselineStress);
      
      // 恶化叠加 3：外部化学灭火剂冷却收缩导致的脆性断裂（结构预留，当前版本不计入）
      simulatedErosionRate += this.calcDamageChemicalShock(baselineStress);
      
      // 致命一击：在当前回合结束时，将叠加恶化后的总应力消耗，从建筑的承重血槽中扣除
      simulatedIntegrity -= simulatedErosionRate;
      
      // 建筑又艰难地抵抗了一个回合的重力，计时器加一
      survivedCycles++;
      
      // 绝对死锁保护：如果推演超出常规赛局总时间，强制熔断，说明该建筑永远不会塌
      if (survivedCycles > 500) {
          break;
      }
    }
    
    // 推演结束（此时 simulatedIntegrity <= 0），返回它总共撑住的回合数
    return survivedCycles;
  }

  /**
   * 热力学高温软化公式计算器。
   * @param currentStress 当前的结构破坏速率
   * @return 本回合新增的破坏增量
   */
  private double calcDamageThermal(double currentStress) {
    // 基于金属材料的高温屈服曲线函数：
    // 建筑内部温度越高，承载力的丧失呈现灾难性的指数级加速！
    return (THERMAL_QUADRATIC_COEF * currentStress * currentStress) + THERMAL_LINEAR_BASE + NOISE_FACTOR;
  }

  /**
   * 不均匀沉降与剪切力撕裂公式计算器。
   * @param currentStress 当前的结构破坏速率
   * @return 本回合新增的破坏增量
   */
  private double calcDamageShear(double currentStress) {
    // 机制同上，模拟失去部分支撑后的墙体在重力加速度下的撕裂形变
    return (SHEAR_QUADRATIC_COEF * currentStress * currentStress) + SHEAR_LINEAR_BASE + NOISE_FACTOR;
  }

  /**
   * 化学冷却收缩造成的次生破坏计算器。
   * @param currentStress 当前的结构破坏速率
   * @return 本回合新增的破坏增量（保留作为 API 扩展槽，目前返回 0）
   */
  private double calcDamageChemicalShock(double currentStress) {
    // 预留接口：应对后续官方升级引入的“高温骤冷导致混凝土炸裂”的新物理引擎规则
    return 0.0;
  }

}
