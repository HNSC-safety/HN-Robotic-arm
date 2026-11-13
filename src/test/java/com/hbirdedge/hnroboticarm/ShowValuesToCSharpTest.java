package com.hbirdedge.hnroboticarm;

import QNEdge.EErrorCode;
import QNEdge.EDirection;
import com.hbirdedge.hnroboticarm.mock.MockAlgorithmRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * <b>这个测试不测功能，只把"构造的值"原原本本打印出来。</b>
 *
 * <p>目的：让"给到 C# 端的到底是什么"成为<b>可执行的事实</b>，
 * 而不是靠读代码推出来的结论。
 *
 * <p>打印四样东西：
 * <ol>
 *   <li>Java 侧构造了什么（枚举名 / 序号 / 速度 / 臂名）</li>
 *   <li>Ice 参数区上线的<b>字节</b>（枚举序号 + int 小端）</li>
 *   <li>对端（假算法端）<b>实际收到</b>什么</li>
 *   <li>返回值（C# 端回给 Java 的东西）</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=9993",
        "ice.test.mock-port=10026",
        "ice.local.port=10027",
        "ice.test.mock-callback-port=10027"
})
@DisplayName("打印给到 C# 端的构造值")
class ShowValuesToCSharpTest {

    private static final int PORT = 9993;

    /**
     * 打印 4 个方向 + 2 个臂名组合的完整值。
     */
    @Test
    @DisplayName("打印 4 个方向的构造值与对端收到的值")
    void printConstructedValues() throws Exception {
        System.out.println("\n============ 给到 C# 端的值 ============");
        System.out.printf("%-8s %-22s %-24s %-16s %s%n",
                "方向", "Java 构造", "Ice 参数区字节(ed+speed)", "对端收到 ed", "对端收到 speed");
        System.out.println("----------------------------------------------------------");

        for (EDirection dir : new EDirection[]{
                EDirection.Down, EDirection.Up, EDirection.Right, EDirection.Left}) {
            MockAlgorithmRecorder.reset();
            post("/api/ice/test/arm/move?direction=" + dir.name() + "&speed=5&real=false");

            System.out.printf("%-8s %-22s %-24s %-16s %d%n",
                    dir.name(),
                    "EDirection." + dir.name(),
                    bytesOf(dir),
                    String.valueOf(MockAlgorithmRecorder.lastDirection()),
                    MockAlgorithmRecorder.lastSpeed());
        }
        System.out.println("======================================\n");
    }

    /**
     * 打印带臂名和不带臂名的对比 —— 证明臂标识走的是 Context 而不是参数区。
     */
    @Test
    @DisplayName("打印臂标识的两种情况")
    void printArmIdValues() throws Exception {
        System.out.println("\n======== 臂标识（Ice Context strArmID）========");

        MockAlgorithmRecorder.reset();
        post("/api/ice/test/arm/move?direction=Down&speed=5&real=false");
        System.out.println("不传 arm   → 对端收到 strArmID = " + MockAlgorithmRecorder.lastArmId());

        MockAlgorithmRecorder.reset();
        post("/api/ice/test/arm/move?direction=Down&speed=5&real=false&arm=arm-a");
        System.out.println("传 arm-a   → 对端收到 strArmID = " + MockAlgorithmRecorder.lastArmId());

        MockAlgorithmRecorder.reset();
        post("/api/ice/test/arm/move?direction=Down&speed=5&real=false&arm=arm-b");
        System.out.println("传 arm-b   → 对端收到 strArmID = " + MockAlgorithmRecorder.lastArmId());
        System.out.println("=============================================\n");
    }

    /**
     * 打印返回值 —— C# 端回给 Java 的东西。
     */
    @Test
    @DisplayName("打印返回值")
    void printReturnValue() throws Exception {
        MockAlgorithmRecorder.reset();
        String body = post("/api/ice/test/arm/move?direction=Down&speed=5&real=false");

        System.out.println("\n========== 返回值 ==========");
        System.out.println("  HTTP body = " + body);
        System.out.println("  EErrorCode.EECSuccess.ordinal = " + EErrorCode.EECSuccess.value()
                + "  (Ice enum 上线就是 1 字节的序号)");
        System.out.println("  对端收到的调用流水 = " + MockAlgorithmRecorder.moveTaskCalls());
        System.out.println("============================\n");
    }

    /**
     * 枚举上线的字节 —— Ice 对 enum 的编码就是它的 ordinal（1 字节）。
     *
     * @param dir 方向
     * @return 字节描述
     */
    private String bytesOf(EDirection dir) {
        return "0x" + hex((byte) dir.value())
                + " + " + hex((byte) 5) + hex((byte) 0) + hex((byte) 0) + hex((byte) 0);
    }

    /**
     * 字节转十六进制。
     *
     * @param b 字节
     * @return 两位十六进制
     */
    private String hex(byte b) {
        return String.format("%02X", b);
    }

    /**
     * 发 POST 请求。
     *
     * @param path 路径
     * @return 响应体
     * @throws Exception 网络异常
     */
    private String post(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + PORT + path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "请求应成功，实际 " + resp.statusCode() + " " + resp.body());
        return resp.body();
    }
}
