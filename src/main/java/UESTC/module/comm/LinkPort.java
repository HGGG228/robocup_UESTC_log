package UESTC.module.comm;

import java.util.*;
import adf.core.agent.communication.standard.bundle.*;

public interface LinkPort {
    // 声明系统级核心通信信道标准接口，界定所有衍生物理频段必须遵循的数据交换契约。

    // ========================================================================
    // [SECTION 1] 资源获取与状态暴露抽象层 (Resource Retrieval & State Exposure)
    // ========================================================================

    public int[] getNumbers();
    // 定义硬件频带标号提取规约。
    // 要求所有实现类必须能够向上游主控总线透明呈报其所绑定的绝对物理数字矩阵地址。

    public List<StandardMessage> getMessages();
    // 定义信道滞留载荷序列抓取规约。
    // 强制规定各频段必须开放受保护的内部堆栈，允许外界全量提取当前已获准放行并排队待发的标准化通信帧。

    // ========================================================================
    // [SECTION 2] 内部运作与数据装填抽象层 (Internal Operation & Data Injection)
    // ========================================================================

    public void sort();
    // 定义通信帧发射前置整理规约。
    // 责成实现类必须具备自我队列重排机制，以确保在高并发场景下严格遵照战术优先级先后顺序执行射频播发。

    public void addWithFilter(StandardMessage incomingPayload);
    // 定义带防御性过滤网闸的数据包注入规约。
    // 确立入栈安全准则，要求任何尝试压入频段缓冲区的消息实例（重命名参量为 incomingPayload），均需经受该信道专属介质特性的拦截与质检。

}
