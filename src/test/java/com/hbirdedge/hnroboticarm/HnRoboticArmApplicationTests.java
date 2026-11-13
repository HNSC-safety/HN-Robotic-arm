package com.hbirdedge.hnroboticarm;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文加载冒烟测试。
 *
 * <p>使用独立端口，避免与 {@link IceEndToEndTests}（占用 9987 / 10007 / 10006）冲突。
 */
@SpringBootTest(properties = {
        "server.port=0",
        // 用独立端口，避免与 IceEndToEndTests（占用 9987 / 10007 / 10006）冲突。
        // 注意配置键：统一后是 ice.local.port（不再是 ice.callback.port）。
        // 10012/10013 选在 10000 段之后，避开本机常见的 10000-10010 服务占用。
        "ice.local.port=10012",
        // 内置假算法端也用独立端口（默认 10000 是 IceEndToEndTests 的）
        "ice.test.mock-port=10013",
        // 假算法端反向回调的目标端口 = 本测试的回调端口
        "ice.test.mock-callback-port=10012"
    })
class HnRoboticArmApplicationTests {

    @Test
    void contextLoads() {
    }

}
