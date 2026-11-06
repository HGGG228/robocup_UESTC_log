package UESTC.module.algorithm;

import java.util.Objects;

/**
 * 泛型二元组核心数据结构 (Generic Immutable Tuple / Pair Structure)
 * 本类提供了一个线程安全且不可变的双元素载体，广泛用于图论算法中的边对(Edge Pairs)、
 * 坐标映射(Coordinate Mappings)以及复合键值对(Composite Keys)的封装。
 * 设计上严格遵循了不可变对象(Immutable Object)的最佳实践。
 */
public class DataPair<T> {

    // ========================================================================
    // [SECTION 1] 核心状态字与只读成员 (Core State Variables) - 优先置顶
    // ========================================================================


    public final T elem1;
    public final T elem2;


    // ========================================================================
    // [SECTION 2] 等价性与同一性验证 (Equivalence & Identity Verification)
    // ========================================================================

    /**
     * 深度等价性判定 (Deep Equivalence Evaluation)
     * 严格遵守自反性、对称性、传递性以及一致性契约。
     *
     * @param candidateReference 待比较的候选对象引用
     * @return 仅当类型一致且内部两组件的内存状态或 equals 均一致时返回 true
     */
    @Override
    public boolean equals(Object candidateReference) {
        // [Phase 1: 内存地址短路验证] 若指向同一块堆内存，直接判定为等价，节省 CPU 算力
        if (this == candidateReference) {
            return true;
        }

        // [Phase 2: 类型边界与空指针防护] 拦截 null 值以及不同类的对象实例
        if (candidateReference == null || this.getClass() != candidateReference.getClass()) {
            return false;
        }

        // [Phase 3: 安全类型转换] 使用无界通配符进行强转，完美规避泛型擦除带来的 unchecked 警告
        DataPair<?> comparedTuple = (DataPair<?>) candidateReference;

        // [Phase 4: 状态矩阵比对] 委托给底层 Objects API 处理可能存在的 null 组件比对
        return Objects.equals(this.elem1, comparedTuple.elem1) &&
               Objects.equals(this.elem2, comparedTuple.elem2);
    }


    // ========================================================================
    // [SECTION 3] 散列指纹计算 (Hash Fingerprint Computation)
    // ========================================================================

    /**
     * 散列值计算引擎 (Hash Code Generator)
     * 采用经典质数 31 作为乘数因子，在保证散列分布均匀性（降低哈希碰撞率）的同时，
     * 完美兼容现代 CPU 的移位与减法优化指令 (31 * i == (i << 5) - i)。
     * * 备注：此处的计算拓扑严格保持了 31 * hash(elem1) + hash(elem2) 的原始公式，
     * 绝对保证生成的整型指纹与重构前 100% 一致。
     */
    @Override
    public int hashCode() {
        // 提取第一组件的哈希基底
        int hashMultiplierBase = Objects.hashCode(this.elem1);
        // 提取第二组件的哈希累加值
        int hashAccumulator = Objects.hashCode(this.elem2);

        // 执行质数散列混淆算法
        return 31 * hashMultiplierBase + hashAccumulator;
    }


    // ========================================================================
    // [SECTION 4] 初始化构造器 (Initialization Constructor) - 沉底排版
    // ========================================================================

    /**
     * 实例化一个新的不可变二元组 (Instantiate a new Immutable Tuple)
     *
     * @param firstComponent  元组的第一节点数据
     * @param secondComponent 元组的第二节点数据
     */
    public DataPair(T firstComponent, T secondComponent) {
        // 将局部入参绑定至不可变的全局状态字
        this.elem1 = firstComponent;
        this.elem2 = secondComponent;
    }

}
