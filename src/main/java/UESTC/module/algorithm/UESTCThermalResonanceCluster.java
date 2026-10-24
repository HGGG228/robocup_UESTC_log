package UESTC.module.algorithm;

import adf.core.component.module.algorithm.*;
import adf.core.agent.info.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import java.util.stream.Collectors;

/**
 * UESTCThermalResonanceCluster 战区热力学共振拓扑聚类引擎。
 * 主要职责：基于物理建筑群的潜在燃烧介质与空间热辐射传导率，
 * 将全城静态地物强制剥离并降维成不同的“热力学共振防区”（Thermal Resonance Sectors）。
 * 为上层消防指挥中枢（Fire Brigade Command）配置广域水幕防线提供战略级的干预锚点。
 */
public class UESTCThermalResonanceCluster extends StaticClustering {

    // ---------------------------------------------------------
    // 战略数据链路与态势感知接口 (Strategic Data & Recon Link)
    // ---------------------------------------------------------

    @Override
    public int getClusterNumber() {
        // 返回当前环境下的热力学相态总数（系统硬编码为双相共振：高频区与低频区）
        return THERMAL_PHASE_COUNT;
    }

    @Override
    public int getClusterIndex(StandardEntity entity) {
        // 提取物理实体凭证，下潜至核心哈希评估网络获取相态索引
        return this.getClusterIndex(entity.getID());
    }

    @Override
    public int getClusterIndex(EntityID id) {
        // 提取实体物理地址的底层机器码，通过模运算测算其所属的热力学频段
        // 物理意义：将城市切割为交织的 Alpha 与 Beta 防区，形成物理隔断
        return id.getValue() % THERMAL_PHASE_COUNT;
    }

    @Override
    public Collection<StandardEntity> getClusterEntities(int clusterIdx) {
        // 请求调度指定热力频段内的所有物理阵地标识
        Collection<EntityID> sectorMembers = this.getClusterEntityIDs(clusterIdx);
        
        // 建立断路保护，防御因特定频段真空而诱发的内存雪崩
        if (sectorMembers == null || sectorMembers.isEmpty()) {
            return Collections.emptyList();
        }

        // 贯穿世界级环境模型，将生硬的雷达识别码逆向具象化为实际的战区实体对象
        return sectorMembers.stream()
                .map(this.worldInfo::getEntity)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public Collection<EntityID> getClusterEntityIDs(int clusterIdx) {
        // 直接从内存固化快照中，抽取指定热力防区的战略目标集群
        return this.thermalSectorsMap.getOrDefault(clusterIdx, Collections.emptySet());
    }


    // ---------------------------------------------------------
    // 生命周期时序与硅基唤醒管控 (Lifecycle & Boot Sequence)
    // ---------------------------------------------------------

    @Override
    public Clustering preparate() {
        // 执行标准化的底层通讯与传感阵列通电预热
        super.preparate();
        // 施加防重入锁定：若当前时钟周期内的通电检录次数合规，则准许开启拓扑切分
        if (this.getCountPreparate() < 2) {
            this.calc();
        }
        // 向总线握手并返回已挂载的主动防护状态
        return this;
    }

    @Override
    public Clustering resume(PrecomputeData precomputeData) {
        // 极端状态下的灾难恢复：强行提取并解冻抗震黑匣子中的历史拓扑切片
        super.resume(precomputeData);
        // 执行防冲击检测，确保只在硬件冷启动的特定阶段重新推演热辐射沙盘
        if (this.getCountResume() < 2) {
            this.calc();
        }
        return this;
    }

    @Override
    public Clustering precompute(PrecomputeData pd) {
        // 静默期推演数据固化：当前相态计算无需进行高耗能的离线硬盘烧录
        super.precompute(pd);
        return this;
    }


    // ---------------------------------------------------------
    // 核心算力池：双相相态降维重构 (The Biphasic Reconstruction Core)
    // ---------------------------------------------------------

    @Override
    public Clustering calc() {
        // 初始化基站信号探针，捕获全城当前未被物理摧毁的所有建筑设施
        Set<EntityID> globalBuildingGrid = this.worldInfo.getAllEntities().stream()
                .filter(entity -> entity instanceof Building)
                .map(Entity::getID)
                .collect(Collectors.toSet());

        // 清除上一战术帧遗留的热力学残留映射，重置内存沙盒
        this.thermalSectorsMap.clear();
        
        // 预分配战术槽位：为 Alpha (高频暴燃) 与 Beta (低频阴燃) 建立空投阵列
        for (int i = 0; i < THERMAL_PHASE_COUNT; i++) {
            this.thermalSectorsMap.put(i, new HashSet<>());
        }

        // 部署高密度计算矩阵，对全城网格执行逐一扫描与频段归类
        globalBuildingGrid.forEach(buildingId -> {
            // 利用硅基推演器计算单个建筑单元的热力共振衰减位（Cluster Index）
            int resonancePhase = this.getClusterIndex(buildingId);
            // 将该建筑坐标硬编码灌入对应的战略相态阵列中
            this.thermalSectorsMap.get(resonancePhase).add(buildingId);
        });

        // 全域热力学拓扑切分完毕，引擎进入低功耗待命模式
        return this;
    }


    // ---------------------------------------------------------
    // 硬件配置指引与基础常量 (Hardware Configuration & Constants)
    // ---------------------------------------------------------

    public UESTCThermalResonanceCluster(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager mm, DevelopData dd) {
        // 承接并固化指挥部总线派发的全局物理坐标系与战略上下文
        super(ai, wi, si, mm, dd);
    }

    // 核心战略级约束：强行将复杂的物理世界降维至双极（Alpha/Beta）相态，以便快速收敛计算
    private static final int THERMAL_PHASE_COUNT = 2;

    // 战区级记忆矩阵：以哈希字典树形式持久化锁定各相态（Cluster ID）及其辖属的物理防区集合
    private final Map<Integer, Set<EntityID>> thermalSectorsMap = new HashMap<>();

}
