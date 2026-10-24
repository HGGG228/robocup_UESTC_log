package UESTC.module.comm;

import java.util.*;
import adf.core.agent.info.*;
import static rescuecore2.standard.entities.StandardEntityURN.*;
import rescuecore2.standard.entities.StandardEntityURN;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.communication.standard.bundle.*;

public class MedicRescueLink extends BaseLinkPort {
    // 声明救护车队专属通信频道类，继承底层信道基准实现。

    // ========================================================================
    // [SECTION 1] 通信过滤与准入评估算子 (Communication Filtering & Admission)
    // ========================================================================

    @Override
    protected boolean applyFilter(StandardMessage incomingPayload) {
        // 重写受保护的频道过滤接口，拦截不合规的非法数据报文。

        if (!super.applyFilter(incomingPayload)) {
            // 优先调用超类判定逻辑，核验物理介质与基础信号规约是否匹配。
            return false;
            // 若底层物理层校验不通过，则直接丢弃包体，拒绝准入。
        }
        // 物理层及基础介质的握手协议与过滤防线校验通关。

        final Class<?> payloadSignature = incomingPayload.getClass();
        // 提取传入消息报文的具体运行期类签名，用作内容特征核对基准。

        final StandardEntityURN observerIdentity = this.egoContext.me().getStandardURN();
        // 抓取当前正在执行通信侦听的智能体本体物理形态分类识别码。

        final boolean isGloballyPermitted = Arrays.asList(PERMITTED_SIGNATURES).contains(payloadSignature);
        // 核查该报文的类签名是否已被载入全局通信白名单，评估其默认放行特权。

        final boolean isConditionallyPermitted = Arrays.asList(RESTRICTED_SIGNATURES).contains(payloadSignature);
        // 核查该报文的类签名是否落入条件性许可清单，需进行二次身份验证。

        return isGloballyPermitted || (isConditionallyPermitted && observerIdentity == AMBULANCE_TEAM);
        // 综合裁决：若享有全局特权，或符合条件清单且当前特工确系救护车阵营，则全线放行。
    }
    // 报文准入安防与通信权限鉴权网闸执行闭环。

    // ========================================================================
    // [SECTION 2] 生命周期装配与初始化 (Lifecycle Assembly & Initialization)
    // ========================================================================

    public MedicRescueLink(AgentInfo metadataContext, int[] allocatedBands) {
        // 建立救护车通信频道的实例化构造车间，承接顶层架构分发的网络配置表。

        super(allocatedBands, true);
        // 向上溯源初始化基类底座，注册专属信道矩阵频段，并硬性声明为无线波段。

        this.egoContext = metadataContext;
        // 将传递而来的智能体基础上下文常量注入内部槽位，留作权限审计的核对锚点。
    }
    // 救护专用子频段网关的底板装配及注册分配告竣。

    // ========================================================================
    // [SECTION 3] 静态常量库与沉底状态字典 (Static Constants & Sunk State)
    // ========================================================================

    private AgentInfo egoContext;
    // 声明不可变私有字段，固化当前智能体的信息聚合体探针引用，极限下沉。

    private static final Class<?>[] RESTRICTED_SIGNATURES = {
        // 声明静态受限报文库常量数组，存放仅允许特定单位阵营方可解析接收的消息类型簇，沉底至末。
    };

    private static final Class<?>[] PERMITTED_SIGNATURES = {
        // 声明静态白名单报文库常量数组，存放全网通用且享有免验证通行特权的系统信令类簇，极限下沉。
        MessageCivilian.class,
        // 注册受困平民状态通报标准消息类的白名单免检资格。
        MessageAmbulanceTeam.class,
        // 注册友军救护车队协同调度标准消息类的绝对准入特权。
    };

}
