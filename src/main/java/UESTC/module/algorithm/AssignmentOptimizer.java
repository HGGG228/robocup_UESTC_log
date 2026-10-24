package UESTC.module.algorithm;

/**
 * 匈牙利算法实现
 * 用于求解二分图最优匹配问题
 */
public class AssignmentOptimizer {

  /**
   * 计算匹配结果
   */
  public static int[] execute(int[][] cost) {

    // 获取行数
    int rowCount = cost.length;

    // 获取列数
    int colCount = cost[0].length;

    // 初始化输出结果
    int[] result = new int[rowCount];

    // 初始化左右匹配关系
    int[] matchRight = new int[rowCount];
    int[] matchLeft = new int[colCount];

    // 初始化顶点势
    int[] labelLeft = new int[rowCount];
    int[] labelRight = new int[colCount];

    // 初始化左侧匹配
    initLeftSide(matchRight, labelLeft);

    // 初始化右侧匹配
    initRightSide(matchLeft, labelRight);

    // 遍历每个左侧节点
    for (int start = 0; start < rowCount; start++) {

      // 执行增广路径搜索
      expandMatching(cost, start, matchRight, matchLeft, labelLeft, labelRight);
    }

    // 构建输出结果
    fillResult(result, matchRight);

    // 返回匹配数组
    return result;
  }

  /**
   * 填充结果数组
   */
  private static void fillResult(int[] result, int[] matchRight) {

    // 遍历左侧节点
    for (int i = 0; i < result.length; i++) {

      // 记录匹配结果
      result[i] = matchRight[i];
    }
  }

  /**
   * 初始化左侧数据
   */
  private static void initLeftSide(int[] matchRight, int[] labelLeft) {

    // 遍历所有左侧节点
    for (int i = 0; i < matchRight.length; i++) {

      // 初始化未匹配状态
      matchRight[i] = -1;

      // 初始化顶点势
      labelLeft[i] = 0;
    }
  }

  /**
   * 初始化右侧数据
   */
  private static void initRightSide(int[] matchLeft, int[] labelRight) {

    // 遍历所有右侧节点
    for (int j = 0; j < matchLeft.length; j++) {

      // 初始化未匹配状态
      matchLeft[j] = -1;

      // 初始化顶点势
      labelRight[j] = 0;
    }
  }

  /**
   * 执行单次增广路径搜索
   */
  private static void expandMatching(
      int[][] cost,
      int root,
      int[] matchRight,
      int[] matchLeft,
      int[] labelLeft,
      int[] labelRight) {

    int n = cost.length;
    int m = cost[0].length;

    // 标记左右集合
    boolean[] visLeft = new boolean[n];
    boolean[] visRight = new boolean[m];

    // 路径记录
    int[] prev = new int[m];

    // 当前候选节点
    int[] candidate = new int[m];

    // 初始化访问状态
    resetLeft(visLeft);

    // 初始化右侧状态
    resetRight(visRight, prev, candidate, root);

    // 标记起点
    visLeft[root] = true;

    // 主循环
    while (true) {

      // 计算最小松弛值
      int delta = computeMinSlack(cost, labelLeft, labelRight, visRight, candidate);

      // 更新顶点势
      updateLabels(visLeft, visRight, labelLeft, labelRight, delta);

      // 找到新的可行边
      int next = findZeroEdge(cost, labelLeft, labelRight, visRight, candidate);

      // 记录路径
      prev[next] = candidate[next];

      // 获取当前匹配
      int matched = matchLeft[next];

      // 如果未匹配则增广
      if (matched < 0) {
        augmentPath(next, prev, matchRight, matchLeft);
        break;
      }

      // 扩展搜索树
      visRight[next] = true;
      visLeft[matched] = true;

      // 更新候选节点
      relaxEdges(cost, labelLeft, labelRight, matched, candidate);
    }
  }

  /**
   * 初始化左侧访问标记
   */
  private static void resetLeft(boolean[] visLeft) {

    // 清空标记
    for (int i = 0; i < visLeft.length; i++) {
      visLeft[i] = false;
    }
  }

  /**
   * 初始化右侧结构
   */
  private static void resetRight(boolean[] visRight, int[] prev, int[] candidate, int root) {

    // 初始化所有列
    for (int j = 0; j < visRight.length; j++) {

      // 未访问状态
      visRight[j] = false;

      // 路径初始化
      prev[j] = -1;

      // 候选初始化
      candidate[j] = root;
    }
  }

  /**
   * 计算最小松弛量
   */
  private static int computeMinSlack(
      int[][] cost,
      int[] labelLeft,
      int[] labelRight,
      boolean[] visRight,
      int[] candidate) {

    int delta = Integer.MAX_VALUE;

    // 遍历所有右侧节点
    for (int j = 0; j < visRight.length; j++) {

      // 仅考虑未访问节点
      if (!visRight[j]) {

        // 计算松弛值
        delta = Math.min(delta,
            calcSlack(cost, labelLeft, labelRight, candidate[j], j));
      }
    }

    return delta;
  }

  /**
   * 更新顶点势
   */
  private static void updateLabels(
      boolean[] visLeft,
      boolean[] visRight,
      int[] labelLeft,
      int[] labelRight,
      int delta) {

    // 更新左侧
    for (int i = 0; i < visLeft.length; i++) {
      if (visLeft[i]) {
        labelLeft[i] -= delta;
      }
    }

    // 更新右侧
    for (int j = 0; j < visRight.length; j++) {
      if (visRight[j]) {
        labelRight[j] += delta;
      }
    }
  }

  /**
   * 查找可行边
   */
  private static int findZeroEdge(
      int[][] cost,
      int[] labelLeft,
      int[] labelRight,
      boolean[] visRight,
      int[] candidate) {

    // 遍历右侧节点
    for (int j = 0; j < visRight.length; j++) {

      // 找到满足条件的边
      if (!visRight[j] &&
          calcSlack(cost, labelLeft, labelRight, candidate[j], j) == 0) {
        return j;
      }
    }

    return -1;
  }

  /**
   * 更新候选边
   */
  private static void relaxEdges(
      int[][] cost,
      int[] labelLeft,
      int[] labelRight,
      int node,
      int[] candidate) {

    // 遍历所有列
    for (int j = 0; j < candidate.length; j++) {

      // 更新更优候选
      if (calcSlack(cost, labelLeft, labelRight, node, j)
          < calcSlack(cost, labelLeft, labelRight, candidate[j], j)) {
        candidate[j] = node;
      }
    }
  }

  /**
   * 执行增广路径
   */
  private static void augmentPath(
      int col,
      int[] prev,
      int[] matchRight,
      int[] matchLeft) {

    // 反向更新路径
    while (col >= 0) {

      // 获取对应行
      int row = prev[col];

      // 获取旧匹配
      int next = matchRight[row];

      // 更新匹配
      matchLeft[col] = row;
      matchRight[row] = col;

      // 跳转
      col = next;
    }
  }

  /**
   * 计算松弛值
   */
  private static int calcSlack(
      int[][] cost,
      int[] labelLeft,
      int[] labelRight,
      int i,
      int j) {

    // 返回调整后的权值
    return cost[i][j] + labelLeft[i] + labelRight[j];
  }
}
