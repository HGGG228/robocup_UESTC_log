package UESTC.module.algorithm;

import rescuecore2.standard.entities.Area;
import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Point2D;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

public class ExteriorEnvelope {
    // 声明一个计算空间凸包边界的核心工具类。

    private static Geometry extractPolygonFromArea(Area spatialRegion) {
        // 定义一个私有静态方法，用于从区域对象中提取几何形状。

        List<Point2D> extractedVertices = GeometryTools2D.vertexArrayToPoints(spatialRegion.getApexList());
        // 利用二维几何工具类将实体的顶点数组转换为点集列表。

        return constructJtsGeometry(extractedVertices.toArray(new Point2D[0]));
        // 将点集列表转换为数组，并传递给底层构建器生成JTS几何对象。
    }
    // 区域顶点提取方法定义结束。

    private static Geometry constructJtsGeometry(Point2D[] rawPoints) {
        // 定义一个根据原始二维点阵列构建标准JTS多边形的方法。

        if (rawPoints == null || rawPoints.length < 3) {
            // 检查输入的点阵列是否为空或数量不足以构成多边形。

            throw new IllegalArgumentException("Input array of points cannot be null or have less than 3 points");
            // 若不满足最小拓扑条件，则抛出非法参数异常。
        }
        // 基础合法性校验通过。

        int totalNodes = rawPoints.length;
        // 获取输入点阵列的总节点数量。

        Coordinate[] topologicalCoords = new Coordinate[totalNodes + 1];
        // 申请一个长度加一的坐标数组，以为闭合多边形首尾相连预留空间。

        for (int i = 0; i < totalNodes; i++) {
            // 遍历每一个输入的原始坐标点。

            topologicalCoords[i] = new Coordinate(rawPoints[i].getX(), rawPoints[i].getY());
            // 将自定义的点对象属性逐一映射到标准的JTS坐标系对象中。
        }
        // 坐标系转换循环结束。

        topologicalCoords[totalNodes] = new Coordinate(topologicalCoords[0]);
        // 把第一个坐标点深度拷贝到数组末尾，形成拓扑学上的闭合环。

        try {
            // 准备调用底层库创建多边形，开启异常捕获块。

            GeometryFactory jtsFactory = new GeometryFactory();
            // 实例化一个标准的JTS几何体制造工厂。

            return jtsFactory.createPolygon(topologicalCoords);
            // 使用工厂方法根据闭合的坐标序列生成并返回多边形实例。

        } catch (Exception ex) {
            // 捕获在多边形构建过程中可能引发的任何异常。

            throw new RuntimeException("Failed to create polygon from points", ex);
            // 将底层异常重新包装为运行时异常并向上层抛出。
        }
        // 异常捕获块结束。
    }
    // JTS多边形构建方法结束。

    private void triggerEvaluationIfNecessary() {
        // 定义一个用于按需触发惰性求值的私有检查方法。

        if (this.resolvedConvexPolygon == null) {
            // 检查当前缓存的凸包几何体是否为空（未计算或已失效）。

            this.compute();
            // 若缓存确实为空，则主动调用计算方法去生成凸包。
        }
        // 状态检查与触发逻辑结束。
    }
    // 惰性求值触发器方法完成。

    public void compute() {
        // 定义执行核心空间并集与凸包闭包运算的公共方法。

        GeometryFactory baseFactory = new GeometryFactory();
        // 同样先实例化一个几何基础工厂对象。

        Geometry baseEmptyPolygon = baseFactory.createPolygon();
        // 利用工厂造出一个空的几何多边形，作为后续累加的初始底座。

        Geometry amalgamatedShape = this.spatialRegionPool.stream()
                // 将当前缓存池中所有的空间区域转化为数据流准备处理。

                .map(ExteriorEnvelope::extractPolygonFromArea)
                // 通过映射方法，把每一个区域对象都提取并转换为几何体。

                .reduce(baseEmptyPolygon, Geometry::union);
                // 利用归约操作，把所有的几何体通过求并集的方式融合到一起。

        this.resolvedConvexPolygon = amalgamatedShape.convexHull();
        // 求出这个巨大融合体的最小外接凸包，并将其刷新到缓存变量中。
    }
    // 核心运算中枢方法结束。

    public java.awt.Polygon get() {
        // 定义对外输出AWT格式二维多边形的方法。

        this.triggerEvaluationIfNecessary();
        // 首先呼叫惰性触发器，确保内部缓存数据是最新的。

        Coordinate[] spatialCoordinates = this.resolvedConvexPolygon.convexHull().getCoordinates();
        // 对缓存体再次进行凸包运算以确保绝对凸性，并拉取其边界坐标序列。

        int boundarySize = spatialCoordinates.length;
        // 获取整个边界外轮廓的坐标节点总数。

        int[] abscissae = IntStream.range(0, boundarySize)
                // 开启整数流以准备提取所有的横坐标。

                .map(idx -> (int) Math.round(spatialCoordinates[idx].getX()))
                // 读取对应的X坐标数值并将其四舍五入为整型。

                .toArray();
                // 将流中的所有整数收集为一个标准的数组。

        int[] ordinates = IntStream.range(0, boundarySize)
                // 再次开启整数流以提取所有的纵坐标。

                .map(idx -> (int) Math.round(spatialCoordinates[idx].getY()))
                // 读取对应的Y坐标数值并进行四舍五入处理。

                .toArray();
                // 将收集到的纵坐标也转为一个整型数组。

        return new java.awt.Polygon(abscissae, ordinates, boundarySize);
        // 最终用这两个数组加上节点总数，拼装成AWT多边形并抛出。
    }
    // 渲染AWT多边形的方法定义完成。

    public Point2D[] getApexes() {
        // 定义获取凸包外围所有物理顶点坐标的方法。

        this.triggerEvaluationIfNecessary();
        // 同样需要先检查并触发惰性计算，防止拿到空数据。

        Coordinate[] spatialCoordinates = this.resolvedConvexPolygon.convexHull().getCoordinates();
        // 严谨地再套一层凸包运算，随后抽取底层完整的坐标矩阵。

        return Arrays.stream(spatialCoordinates)
                // 把获取到的底层坐标数组转换为连续的对象流。

                .map(coord -> new Point2D(coord.getX(), coord.getY()))
                // 将JTS的坐标对象逐个转换为我们自定义的Point2D平面点对象。

                .toArray(Point2D[]::new);
                // 收集流内元素，实例化为一个Point2D数组并将其返回。
    }
    // 顶点坐标抽取方法彻底结束。

    public void add(Area newRegion) {
        // 暴露一个公共接口，允许外部向引擎中不断塞入新的区域块。

        this.resolvedConvexPolygon = null;
        // 一旦有新数据混入，立即销毁现有的凸包缓存，标记为已脏。

        this.spatialRegionPool.add(newRegion);
        // 将这个新来的物理区域实体登记到内部的待处理缓冲池中。
    }
    // 数据注入方法编写完毕。

    private Geometry resolvedConvexPolygon = null;
    // 声明一个私有变量，用来妥善保管已经被算出来的凸包多边形。

    private final Set<Area> spatialRegionPool = new HashSet<>();
    // 初始化一个哈希集合，专门充当尚未进行融合计算的区域暂存池。

}
