package UESTC.module.algorithm;

/**
 * 三元组数据结构
 * 用于保存三个同类型对象
 */
public class ThreeElementHolder<T> {

  /**
   * 判断对象是否相等
   */
  @Override
  public boolean equals(Object object) {
    // 若引用相同则直接相等
    if (this == object) {
      return true;
    }

    // 若对象为空或类型不同则不相等
    if (object == null || getClass() != object.getClass()) {
      return false;
    }

    // 转换为同类型对象
    final ThreeElementHolder<?> other = (ThreeElementHolder<?>) object;

    // 逐个比较三个元素
    return sameValue(this.elem1, other.elem1)
        && sameValue(this.elem2, other.elem2)
        && sameValue(this.elem3, other.elem3);
  }

  // 第一个元素
  public final T elem1;

  // 第二个元素
  public final T elem2;

  // 第三个元素
  public final T elem3;

  /**
   * 构造函数
   * 用于初始化三个元素
   */
  public ThreeElementHolder(T first, T second, T third) {
    // 初始化第一个元素
    this.elem1 = first;

    // 初始化第二个元素
    this.elem2 = second;

    // 初始化第三个元素
    this.elem3 = third;
  }

  /**
   * 计算哈希值
   */
  @Override
  public int hashCode() {
    // 计算第一个元素哈希
    final int h1 = valueHash(this.elem1);

    // 计算第二个元素哈希
    final int h2 = valueHash(this.elem2);

    // 计算第三个元素哈希
    final int h3 = valueHash(this.elem3);

    // 返回组合哈希值
    return h1 ^ h2 ^ h3;
  }

  /**
   * 计算单个对象的哈希值
   */
  private int valueHash(Object value) {
    // 若对象为空则返回0
    if (value == null) {
      return 0;
    }

    // 返回对象自身哈希值
    return value.hashCode();
  }

  /**
   * 比较两个对象是否相等
   */
  private boolean sameValue(Object left, Object right) {
    // 若左值为空则仅当右值也为空时相等
    if (left == null) {
      return right == null;
    }

    // 左值非空时比较内容
    return left.equals(right);
  }
}
