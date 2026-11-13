package com.hbirdedge.hnroboticarm;

import com.hbirdedge.hnroboticarm.ice.client.QNEdgeIceClient;
import com.hbirdedge.hnroboticarm.mock.MockAlgorithmRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code real=true} 这条链路本身是通的。
 *
 * <p><b>为什么要单独测</b>：真机上跑 {@code real=true} 失败，可能有两种原因 ——
 * <ul>
 *   <li>现场那台 C# 程序没启动</li>
 *   <li><b>代码本身有问题</b>（比如真机分支根本没走到）</li>
 * </ul>
 * 两种原因混在一起，就会误判。所以这里把 {@code ice.alg.host} 指到本机，
 * 让内置假算法端<b>冒充</b>真实算法端，用 {@code real=true} 去调 ——
 * 如果这条能通，就证明真机分支的代码是对的，现场失败就只剩"程序没起"这一个原因。
 *
 * @author hbird
 * @since 2026-11-03
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                // 让内置假算法端冒充"真实算法端"：
                // 假端监听 ice.test.mock-port，real=true 时连的是 ice.alg.port，
                // 两者都设成 10004，假端就顶替了"真机"的位置
                "ice.alg.host=127.0.0.1",
                "ice.alg.port=10004",
                "ice.test.mock-port=10004",
                // 独立端口，避免与其它测试类冲突
                // （IceEndToEndTests 用 9987/10007/10006，HnRoboticArmApplicationTests 用 -/10002/10003）
                "server.port=9989",
                "ice.local.port=10005",
                "ice.test.mock-callback-port=10005"
        })
@DisplayName("真实模式链路自检")
class RealModeLinkTests {

    @Autowired
    private QNEdgeIceClient iceClient;

    @BeforeEach
    void clearRecorder() {
        MockAlgorithmRecorder.reset();
    }

    @Test
    @DisplayName("real=true 时能连上并收到 MoveTask")
    void shouldReachAlgServerInRealMode() {
        // ice.alg.host / port 都指向本机的假算法端，它冒充"真实算法端"
        assertTrue(iceClient.resolve(true).isReachable(),
                "real=true 应能连上 127.0.0.1:10004");

        // 确认指令真的送到了
        iceClient.resolve(true).getArmControl().MoveTask(
                QNEdge.EDirection.Down, 5);
        assertEquals(QNEdge.EDirection.Down, MockAlgorithmRecorder.lastDirection(),
                "对端应收到 Down");
    }
}
