package UESTC.module.algorithm;

import org.locationtech.jts.geom.*;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.*;

import java.util.*;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

/**
 * 几何处理模块
 * 提供区域可通行性计算、路径验证及几何对象转换等功能
 */
public class SpatialMorphologyCore {

    // agent的安全半径，用于几何膨胀操作
    private static final double AGENT_RADIUS = 500.0;

    // ======================== 工具函数 ========================

    /**
     * 创建GeometryFactory实例
     */
    private static GeometryFactory factory() {
        return new GeometryFactory();
    }

    /**
     * 创建一个空的多边形对象
     */
    private static Geometry emptyPolygon() {
        return factory().createPolygon();
    }

    // ======================== 可通行区域计算 ========================

    /**
     * 计算区域内的可通行空间
     * 通过剔除不可通行边及障碍物区域得到最终结果
     */
    public static Geometry computePassable(Area area, Collection<Blockade> blockades) {

        Geometry base = areaToGeom(area); // 将区域转换为几何对象

        Geometry noWalls = subtractWallBuffer(base, collectBlockedEdges(area)); // 移除不可通行边的影响

        Geometry obstacles = mergeBlockades(blockades); // 构建障碍物区域

        return noWalls.difference(obstacles).norm().buffer(1.0); // 得到最终可通行区域
    }

    // ======================== buffer处理 ========================

    /**
     * 对几何对象进行膨胀操作
     */
    private static Geometry expand(Geometry g) {
        return g.buffer(AGENT_RADIUS);
    }

    /**
     * 从区域中剔除不可通行边对应的缓冲区域
     */
    private static Geometry subtractWallBuffer(Geometry geom, Collection<Line2D> lines) {

        Geometry merged = lines.stream()
                .map(SpatialMorphologyCore::lineToGeometry)
                .map(SpatialMorphologyCore::expand)
                .reduce(Geometry::union)
                .orElse(emptyPolygon());

        return geom.difference(merged);
    }

    // ======================== 路径验证 ========================

    /**
     * 判断两条路径是否同时位于同一可通行区域内
     */
    public static boolean validate(Geometry passable, Line2D from, Line2D next) {

        Geometry g1 = lineToGeometry(from); // 第一条路径
        Geometry g2 = lineToGeometry(next); // 第二条路径

        for (Geometry part : splitGeometry(passable)) {
            if (part.intersects(g1) && part.intersects(g2)) {
                return true;
            }
        }

        return false;
    }

    // ======================== 障碍物处理 ========================

    /**
     * 构建所有障碍物的几何联合体
     */
    private static Geometry mergeBlockades(Collection<Blockade> blockades) {

        return blockades.stream()
                .map(SpatialMorphologyCore::blockadeToGeom)
                .map(SpatialMorphologyCore::expand)
                .reduce(Geometry::union)
                .orElse(emptyPolygon());
    }

    /**
     * 将Blockade转换为几何对象
     */
    private static Geometry blockadeToGeom(Blockade b) {
        return apexToPolygon(b.getApexes());
    }

    // ======================== 线段转换 ========================

    /**
     * 将Line2D转换为Geometry对象
     */
    private static Geometry lineToGeometry(Line2D line) {

        Coordinate[] coords = new Coordinate[2];

        for (int i = 0; i < 2; i++) {
            Point2D p = line.getPoint((double) i);
            coords[i] = new Coordinate(p.getX(), p.getY());
        }

        LineString ls = factory().createLineString(coords);

        return ls.getLength() > 0 ? ls : ls.getPointN(0);
    }

    // ======================== 区域转换 ========================

    /**
     * 将Area转换为几何对象
     */
    private static Geometry areaToGeom(Area a) {
        return apexToPolygon(a.getApexList());
    }

    // ======================== 多边形构建 ========================

    /**
     * 根据顶点数组构建多边形
     */
    private static Geometry apexToPolygon(int[] apex) {

        if (apex == null || apex.length % 2 != 0) {
            throw new IllegalArgumentException("Invalid apex");
        }

        return factory().createPolygon(buildClosedCoords(apex));
    }

    /**
     * 构建闭合的坐标数组
     */
    private static Coordinate[] buildClosedCoords(int[] apex) {

        int n = apex.length / 2;
        Coordinate[] arr = new Coordinate[n + 1];

        for (int i = 0; i < n; i++) {
            arr[i] = new Coordinate(apex[2 * i], apex[2 * i + 1]);
        }

        arr[n] = arr[0];

        return arr;
    }

    // ======================== 边处理 ========================

    /**
     * 收集区域中所有不可通行的边
     */
    private static Collection<Line2D> collectBlockedEdges(Area area) {

        Stream<Line2D> stream = area.getEdges()
                .stream()
                .filter(e -> !e.isPassable())
                .map(Edge::getLine);

        return stream.collect(toList());
    }

    // ======================== 几何拆分 ========================

    /**
     * 将Geometry拆分为多个子几何对象
     */
    private static Collection<Geometry> splitGeometry(Geometry g) {

        if (!(g instanceof GeometryCollection)) {
            return Collections.singletonList(g);
        }

        GeometryCollection col = (GeometryCollection) g;

        List<Geometry> list = new ArrayList<>(col.getNumGeometries());

        for (int i = 0; i < col.getNumGeometries(); i++) {
            list.add(col.getGeometryN(i));
        }

        return list;
    }
}
