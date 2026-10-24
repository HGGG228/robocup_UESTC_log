package UESTC.module.complex.common; 


import rescuecore2.standard.entities.Human;

/**
 * SurvivalChronicle 伤员剩余寿命预测模拟器。
 * 核心战术价值：医疗分诊系统（Triage System）。
 * 它不执行任何真实的救援动作，而是在大脑中进行一次“如果我不去救他，他什么时候会死”的沙盘推演。
 * 推算出的剩余存活回合数（Life Expectancy）将直接作为后续优先队列调度的核心排序指标。
 */
public class SurvivalChronicle {

  // 观察对象：当前需要进行生命体征评估的目标人类实体
  private Human targetVictim;
  
  // ==================== 生理衰减模型常数矩阵 ====================
  // 物理意义：伤员的流血速率并不是一成不变的，随着时间的推移，由于未得到救治，
  // 伤情会呈现非线性的加速恶化（如感染、休克、挤压综合征引发的急性肾衰竭）。

  // 基础环境白噪声常数：即使没有任何外力，躺在废墟里每回合也会自然增加的轻微损伤
  private final double NOISE_FACTOR = 0.1;
  
  // 掩埋伤害（Bury）的二次项（抛物线）加速系数：模拟长时间被重物压迫导致的内出血加速恶化
  private final double BURY_QUADRATIC_COEF = 0.00025;
  
  // 砸伤伤害（Collapse）的二次项加速系数：模拟骨折或钝器创伤带来的并发症加速恶化
  private final double COLLAPSE_QUADRATIC_COEF = 0.00035;
  
  // 掩埋伤害的线性基础增量：每回合固定恶化的基础数值
  private final double BURY_LINEAR_BASE = 0.01;
  
  // 砸伤伤害的线性基础增量：每回合固定恶化的基础数值
  private final double COLLAPSE_LINEAR_BASE = 0.01;

  /**
   * 构造医生 1：带目标患者入场。
   * @param targetVictim 需要被诊断的患者实体
   */
  public SurvivalChronicle(Human targetVictim) {
    this.targetVictim = targetVictim;
  }

  /**
   * 构造医生 2：空手入场，通常用于只调用静态计算公式的场景。
   */
  public SurvivalChronicle() {
    this.targetVictim = null;
  }

  /**
   * 外部提问接口 A：根据当前绑定的患者实体，推算其还能活多久。
   * @return 预估的剩余存活仿真步长（回合数）
   */
  public int getLifeXpectancy() {
    // 将绑定的患者移交给内部私有诊疗室进行推演
    return this.calcLifeXpectancy(this.targetVictim);
  }

  /**
   * 外部提问接口 B：脱离实体对象，纯粹抛入血量和伤害数字进行裸算。
   * @param hp 当前剩余生命值
   * @param damage 当前每回合的流血伤害速率
   * @return 预估的剩余存活仿真步长（回合数）
   */
  public int getLifeXpectancy(int hp, int damage) {
    // 将硬性数据移交给数字推演中心
    return this.calcLifeXpectancy(hp, damage);
  }

  /**
   * 内部私有诊疗室：提取患者的真实体征，并委托给底层数学模型。
   * 优化点：通过直接调用另一个重载方法，消除了原代码中大段的重复 while 循环，极大地降低了代码冗余度。
   */
  private int calcLifeXpectancy(Human victim) {
    // 安全防爆网：如果查无此人，直接判定剩余寿命为 0
    if (victim == null) {
        return 0;
    }
    // 提取出患者体内的两项核心关键指标：当前血槽存量，以及此刻的伤口流血流速
    int currentHp = victim.getHP();
    int currentDamage = victim.getDamage();
    
    // 委托给纯数字推演中心进行沙盘推演
    return this.calcLifeXpectancy(currentHp, currentDamage);
  }

  /**
   * 纯数字推演中心：医学死亡倒计时模拟器。
   * 在沙盘里一秒一秒地快进时间，直到患者血槽彻底归零，记录下熬过了多少个回合。
   */
  private int calcLifeXpectancy(int initialHp, int initialDamage) {
    // 初始化沙盘体征：将整型的外部数据强制转换为双精度浮点数，以应对微小的衰减计算
    double simulatedHealth = initialHp;
    double simulatedBleedingRate = initialDamage;
    
    // 计时器归零：准备记录他在推演中存活的循环次数
    int survivedCycles = 0;
    
    // 开启死神倒计时：只要沙盘里这个人的血量还没见底（还能喘气），就让时间继续往前走
    while (simulatedHealth > 0) {
      // 冻结当前回合初的流血速率，作为计算并发症恶化的基准参数
      final double baselineDamage = simulatedBleedingRate;
      
      // 恶化叠加 1：挤压综合征发作，伤口情况进一步恶化，流血速率增加
      simulatedBleedingRate += this.calcDamageBury(baselineDamage);
      
      // 恶化叠加 2：创伤并发症发作，多器官开始衰竭，流血速率再次增加
      simulatedBleedingRate += this.calcDamageCollapse(baselineDamage);
      
      // 恶化叠加 3：烧伤感染发作（虽然当前系统此值为0，但保留结构以应对未来赛事规则更新）
      simulatedBleedingRate += this.calcDamageFire(baselineDamage);
      
      // 致命一击：在当前回合结束时，将叠加恶化后的总流血量，从患者的生命血槽中扣除
      simulatedHealth -= simulatedBleedingRate;
      
      // 他又艰难地熬过了一个回合，计时器加一
      survivedCycles++;
    }
    
    // 推演结束（此时 simulatedHealth <= 0），返回他总共撑住的回合数
    return survivedCycles;
  }

  /**
   * 掩埋伤害恶化公式计算器。
   * @param currentDamage 当前的流血速率
   * @return 本回合新增的流血增量
   */
  private double calcDamageBury(double currentDamage) {
    // 这是一个标准的开口向上的抛物线函数：Y = k*X^2 + L + C
    // 伤势越重（currentDamage 越大），恶化的速度不是成比例增加，而是呈爆炸式指数级加速！
    return (BURY_QUADRATIC_COEF * currentDamage * currentDamage) + BURY_LINEAR_BASE + NOISE_FACTOR;
  }

  /**
   * 坍塌砸伤恶化公式计算器。
   * @param currentDamage 当前的流血速率
   * @return 本回合新增的流血增量
   */
  private double calcDamageCollapse(double currentDamage) {
    // 机制同上，只是系数矩阵略微不同（被砸伤的急性恶化率略高于被掩埋的慢性恶化率）
    return (COLLAPSE_QUADRATIC_COEF * currentDamage * currentDamage) + COLLAPSE_LINEAR_BASE + NOISE_FACTOR;
  }

  /**
   * 火灾烧伤恶化公式计算器。
   * @param currentDamage 当前的流血速率
   * @return 本回合新增的流血增量（暂时弃用，返回 0）
   */
  private double calcDamageFire(double currentDamage) {
    // 根据当前版本的官方内核规则，烧伤伤害由底层 FireSimulator 独立结算，不计入此类二次衰减模型中
    return 0.0;
  }

}
