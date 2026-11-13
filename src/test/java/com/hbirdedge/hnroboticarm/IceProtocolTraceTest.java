package com.hbirdedge.hnroboticarm;

import Ice.Communicator;
import Ice.InitializationData;
import Ice.Util;
import QNEdge.EErrorCode;
import QNEdge.EDirection;
import QNEdge.IQNEArmControlPrx;
import QNEdge.IQNEArmControlPrxHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.Map;

/**
 * 用 Ice <b>引擎自带</b>的协议 trace 把线上真实字节打出来。
 *
 * <p>{@code Ice.Trace.Protocol=1} 会让 Ice 引擎把它<b>真正写出去的字节</b>
 * 逐条打出来 —— 比自己手搓解析器靠谱得多，因为它打的就是真实线上数据。
 *
 * <p>这个测试起一个<b>独立的</b> Communicator（带 trace），连上应用内置的假算法端，
 * 调两次 MoveTask：一次不带 Context，一次带 Context。
 * 两次的<b>协议字节差异</b>就是臂标识的落点。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=9995",
        "ice.test.mock-port=10036",
        "ice.local.port=10037",
        "ice.test.mock-callback-port=10037",
        "ice.alg.host=127.0.0.1",
        "ice.alg.port=10036"
})
@DisplayName("Ice 协议 trace —— 看真实上线字节")
class IceProtocolTraceTest {

    /**
     * 连假算法端，调两次 MoveTask，让 Ice 把协议字节打出来。
     */
    @Test
    @DisplayName("打印 MoveTask 的真实协议字节")
    void traceMoveTask() {
        InitializationData initData = new InitializationData();
        initData.properties = Util.createProperties();
        // ★ 关键：打开协议 trace
        initData.properties.setProperty("Ice.Trace.Protocol", "1");
        initData.properties.setProperty("Ice.Trace.Protocol.1", "MoveTask");
        initData.properties.setProperty("Ice.Trace.Network", "1");
        initData.properties.setProperty("Ice.Default.Protocol", "tcp");

        Communicator communicator = Util.initialize(initData);
        try {
            // ★ 连本地 TCP 代理（10099），代理再转发到假算法端（10036）——
            //   这样能看到真正跑在网络上的原始字节
            Object prxBase = communicator.stringToProxy(
                    "AlgHandle:default -h 127.0.0.1 -p 10099");
            IQNEArmControlPrx prx = IQNEArmControlPrxHelper.uncheckedCast((Ice.ObjectPrx) prxBase);

            System.out.println("\n############ 第一次：不带 Context ############");
            EErrorCode r1 = prx.MoveTask(EDirection.Down, 5);
            System.out.println(">>> 返回 = " + r1);

            System.out.println("\n############ 第二次：带 Context strArmID=arm-a ############");
            Map<String, String> ctx = new HashMap<>();
            ctx.put("strArmID", "arm-a");
            EErrorCode r2 = prx.MoveTask(EDirection.Up, 7, ctx);
            System.out.println(">>> 返回 = " + r2);
        } finally {
            communicator.destroy();
        }
    }
}
