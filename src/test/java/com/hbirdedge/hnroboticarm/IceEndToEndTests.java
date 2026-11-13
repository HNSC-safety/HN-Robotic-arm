package com.hbirdedge.hnroboticarm;

import QNEdge.EDirection;
import com.hbirdedge.hnroboticarm.mock.MockAlgorithmRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 机械臂上下左右移动测试。
 *
 * <h2>4 个方法，8 个用例</h2>
 * <p>机械臂只有 4 个方向：上、下、左、右。每个方向一个测试方法，
 * 方法名直接用现场口语（从上往下、从下往上……），报告里一眼看懂是哪个方向。
 *
 * <p>每个方法带一个 {@code real} 参数：
 * <ul>
 *   <li>{@code real = false} → <b>模拟</b>：连应用内置的假算法端，
 *       还能断言"对端到底收到了什么"</li>
 *   <li>{@code real = true} → <b>真实</b>：连现场那台 C# 算法端</li>
 * </ul>
 * 4 个方向 × 2 种模式 = <b>8 个用例</b>，方法只需要 4 个。
 *
 * <h2>模式由参数决定，不由超时决定</h2>
 * <p>{@code real=true} 时就<b>直连真机</b>。连不上就是连不上 —— 测试会<b>失败</b>，
 * 明确告诉你"真机没通"，而不是自动降级成模拟让你以为测过了。
 * 你自己控制：算法端没起就测 {@code false}，起了就测 {@code true}。
 *
 * <h2>怎么跑</h2>
 * <pre>
 *   mvn test                                        # 跑全部 8 个
 *   mvn test -Dtest=IceEndToEndTests#shouldMoveArmFromTopToBottom
 * </pre>
 *
 * <h2>端口必须独立</h2>
 * <p>本测试类用<b>固定端口</b>（HTTP 9987 / 假算法端 10006 / 本地回调 10001），
 * 和其它测试类、以及你可能正在 IntelliJ 里跑的应用实例错开。
 *
 * <p><b>为什么必须错开</b>：内置假算法端和本地回调 adapter 都是
 * <b>进程级端口占用</b>。撞端口时的表现极具迷惑性 ——
 * 假算法端绑端口失败会被吞掉，然后去连 {@code 127.0.0.1:10006}，
 * 结果<b>连上了另一个 JVM 的假算法端</b>：日志显示"暗号探测成功"，
 * 但 {@code MockAlgorithmRecorder} 是 static 字段，记录写在<b>别的进程</b>里，
 * 断言 {@code lastDirection()} 永远是 {@code null}。
 *
 * <p><b>⚠️ 唯一例外是回调端口 10001</b>：它被现场 C# 端硬编码在
 * {@code SMTEvent:default -h <ip> -p 10001} 里，不能改 ——
 * 改了就收不到算法端的反向回调。
 *
 * @author hbird
 * @since 2026-11-03
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
    // HTTP 端口
        "server.port=9987",
        // 内置假算法端监听端口（模拟模式的对端）
        "ice.test.mock-port=10006",
        // 本地回调端口（算法端反向回调我们的入口）
        // ⚠️ 必须是 10001 —— 现场 C# 端把回调地址硬编码成 "SMTEvent:default -h <ip> -p 10001"，
        //    端口改成别的，算法端的反向回调就打不进来（表现为"我们能下发，但永远收不到推送"）。
        "ice.local.port=10001",
        // 假算法端反向回调的目标端口 = 本测试的回调端口
        "ice.test.mock-callback-port=10001",
        // 机械臂清单：配了清单，传未配置的臂名就会被拒绝（400）
        "ice.arms.arm-a.description=测试机械臂 A",
        "ice.arms.arm-b.description=测试机械臂 B",
        // 显式清空默认臂 —— 挡住外部环境里的 ice.default-arm 干扰（测试必须自洽）
        "ice.default-arm="
})
@DisplayName("机械臂上下左右移动")
class IceEndToEndTests {

    /**
     * <b>等待真实算法端上线的秒数</b> —— 现场联调时改这里。
     *
     * <p><b>为什么需要它</b>：现场是我们先起、算法端后起。
     * 没有这个窗口，第一次"连接被拒绝"就直接判死 ——
     * 人还没来得及启动算法端，测试就挂了。
     *
     * <p><b>这 30 秒拿来干什么</b>：
     * <ol>
     *   <li>每秒做一次 TCP 预检（毫秒级判定），探测算法端 10000 端口</li>
     *   <li>端口一通，立刻用 Ice 协议挨个试暗号</li>
     *   <li>探通 → 立即下发 MoveTask 并断言</li>
     *   <li>30 秒内一直没通 → 如实报"算法端不可达"</li>
     * </ol>
     *
     * <p><b>⚠️ 等待期间本地回调端口 10001 始终开着</b>：
     * 回调 adapter 跑在 Ice 自己的线程池上，跟阻塞在探测里的 HTTP 线程无关。
     * 所以算法端在这 30 秒内任何时刻上线并反向回调，我们都能接得住。
     *
     * <p><b>怎么调</b>（两种，都不用改代码）：
     * <ul>
     *   <li>IntelliJ Run/Debug Configuration → VM options 加
     *       {@code -Dice.test.connect-wait-seconds=60}</li>
     *   <li>命令行 {@code mvn test -Dice.test.connect-wait-seconds=60}</li>
     * </ul>
     */
    private static final int CONNECT_WAIT_SECONDS =
            Integer.getInteger("ice.test.connect-wait-seconds", 30);

    /**
     * 把等待时长注入 Spring 上下文。
     *
     * <p><b>为什么用 {@code @DynamicPropertySource} 而不是写在
     * {@code @SpringBootTest(properties=...)} 里</b>：
     * {@code properties} 是<b>编译期</b>固定值，改了要重新编译；
     * 而 {@code @DynamicPropertySource} 在<b>运行时</b>求值，
     * 现场联调时用 VM option 就能随时改等待时长。
     *
     * <p><b>⚠️ 优先级</b>：{@code @DynamicPropertySource} 的值比
     * {@code @SpringBootTest(properties)} 高，所以它能覆盖 yaml 里的默认值。
     */
    @DynamicPropertySource
    static void iceProperties(DynamicPropertyRegistry registry) {
        registry.add("ice.alg.connect-wait-seconds", () -> CONNECT_WAIT_SECONDS);
    }

    /**
     * 应用 HTTP 端口。
     *
     * <p>与 {@code server.port=9987} 对应 —— 两处必须一致，
     * 所以这里定义成常量，{@code properties} 里那个值改的时候一起改。
     */
    private static final int APP_HTTP_PORT = 9987;

    /** 测试用速度 */
    private static final int TEST_SPEED = 5;

    @BeforeEach
    void clearRecorder() {
        MockAlgorithmRecorder.reset();
    }

    // ══════════════════════════════════════════════════════════════════
    // 四个方向
    // ══════════════════════════════════════════════════════════════════

    /**
     * 机械臂从上往下移动。
     *
     * @param real true = 真实算法端；false = 模拟
     * @throws Exception 网络请求失败
     */
    @ParameterizedTest(name = "从上往下移动（{0}）")
//    @ValueSource(booleans = {false})
     @ValueSource(booleans = {true})
    @DisplayName("从上往下移动")
    void shouldMoveArmFromTopToBottom(boolean real) throws Exception {
        assertMove(EDirection.Down, real);
    }

    /**
     * 机械臂从下往上移动。
     *
     * @param real true = 真实算法端；false = 模拟
     * @throws Exception 网络请求失败
     */
    @ParameterizedTest(name = "从下往上移动（{0}）")
    @ValueSource(booleans = {false, true})
    @DisplayName("从下往上移动")
    void shouldMoveArmFromBottomToTop(boolean real) throws Exception {
        assertMove(EDirection.Up, real);
    }

    /**
     * 机械臂从左往右移动。
     *
     * @param real true = 真实算法端；false = 模拟
     * @throws Exception 网络请求失败
     */
    @ParameterizedTest(name = "从左往右移动（{0}）")
    @ValueSource(booleans = {false, true})
    @DisplayName("从左往右移动")
    void shouldMoveArmFromLeftToRight(boolean real) throws Exception {
        assertMove(EDirection.Right, real);
    }

    /**
     * 机械臂从右往左移动。
     *
     * @param real true = 真实算法端；false = 模拟
     * @throws Exception 网络请求失败
     */
    @ParameterizedTest(name = "从右往左移动（{0}）")
    @ValueSource(booleans = {false, true})
    @DisplayName("从右往左移动")
    void shouldMoveArmFromRightToLeft(boolean real) throws Exception {
        assertMove(EDirection.Left, real);
    }

    // ══════════════════════════════════════════════════════════════════
    // 机械臂标识（"我控制的是哪条臂"）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 机械臂标识的断言组。
     *
     * <p><b>为什么单独一组</b>：Slice 定义固定（{@code MoveTask(EDirection, int)}），
     * 协议里<b>没有</b>"哪条臂"的字段。所以"控制的是哪条臂"必须走
     * <b>Ice Context</b>（{@code strArmID}）—— 这组测试钉死这条通路真的能通，
     * 并且 C# 端照同样的方式读就能拿到。
     */
    @Nested
    @DisplayName("机械臂标识（Ice Context strArmID）")
    class ArmIdentification {

        /**
         * 模拟模式下，指定臂名 → 假算法端能从 Context 读到它。
         *
         * @throws Exception 网络请求失败
         */
        @Test
        @DisplayName("指定 arm 时对端能读到 strArmID")
        void shouldDeliverArmIdViaIceContext() throws Exception {
            String body = post("/api/ice/test/arm/move?direction=Down&speed=" + TEST_SPEED
                    + "&real=false&arm=arm-a");

            assertTrue(body.contains("\"success\":true"),
                    "应下发成功，实际: " + body);
            assertEquals("arm-a", MockAlgorithmRecorder.lastArmId(),
                    "假算法端应从 Ice Context 读到 strArmID=arm-a，实际: "
                            + MockAlgorithmRecorder.lastArmId());
        }

        /**
         * 不指定臂名 → 不带 Context（对端拿到的 ctx 为空）。
         *
         * @throws Exception 网络请求失败
         */
        @Test
        @DisplayName("不指定 arm 时不带 Context")
        void shouldOmitContextWhenNoArmSpecified() throws Exception {
            String body = post("/api/ice/test/arm/move?direction=Down&speed=" + TEST_SPEED
                    + "&real=false");

            assertTrue(body.contains("\"success\":true"),
                    "应下发成功，实际: " + body);
            assertEquals(null, MockAlgorithmRecorder.lastArmId(),
                    "没指定臂名时不该带 strArmID，实际: " + MockAlgorithmRecorder.lastArmId());
        }

        /**
         * 两条臂要能区分开 —— 这是"多臂"能力的核心断言。
         *
         * @throws Exception 网络请求失败
         */
        @Test
        @DisplayName("两条臂发出的指令能被区分")
        void shouldDistinguishBetweenTwoArms() throws Exception {
            post("/api/ice/test/arm/move?direction=Down&speed=" + TEST_SPEED
                    + "&real=false&arm=arm-a");
            assertEquals("arm-a", MockAlgorithmRecorder.lastArmId());

            post("/api/ice/test/arm/move?direction=Up&speed=" + TEST_SPEED
                    + "&real=false&arm=arm-b");
            assertEquals("arm-b", MockAlgorithmRecorder.lastArmId());
        }

        /**
         * 未配置的臂名要<b>明确报错</b>，不能悄悄打到默认臂。
         *
         * <p>打错字却打到另一条臂 —— 在消防场景下是事故。
         *
         * @throws Exception 网络请求失败
         */
        @Test
        @DisplayName("未配置的臂名被拒绝")
        void shouldRejectUnknownArmName() throws Exception {
            HttpResponse<String> response = rawPost(
                    "/api/ice/test/arm/move?direction=Down&speed=" + TEST_SPEED
                            + "&real=false&arm=not-exist");

            assertTrue(response.statusCode() >= 400,
                    "未配置的臂名应报错，实际 HTTP " + response.statusCode()
                            + " body=" + response.body());
            assertTrue(response.body().contains("not-exist"),
                    "错误信息应点名是哪个臂名，实际: " + response.body());
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 断言
    // ══════════════════════════════════════════════════════════════════

    /**
     * 下发一次移动指令并断言。
     *
     * <p>断言分两层：
     * <ol>
     *   <li>HTTP 层：返回 {@code EECSuccess}，说明 ICE 调用往返成功</li>
     *   <li>ICE 层：<b>仅模拟模式</b>能断言"对端收到的方向和速度与我们发的一致"</li>
     * </ol>
     *
     * @param direction 期望方向
     * @param real      true = 真实算法端；false = 模拟
     * @throws Exception 网络请求失败
     */
    private void assertMove(EDirection direction, boolean real) throws Exception {
        String body = post("/api/ice/test/arm/move?direction=" + direction.name()
                + "&speed=" + TEST_SPEED + "&real=" + real);

        assertTrue(body.contains("\"success\":true"),
                "向 " + direction + " 移动（real=" + real + "）应成功，实际: " + body);

        // 真实 C# 端内部收到什么我们看不到，只能看返回码
        if (real) {
            System.out.println("真实测试");
            return;
        }
        assertEquals(direction, MockAlgorithmRecorder.lastDirection(),
                "假算法端收到的方向应为 " + direction + "，实际: " + MockAlgorithmRecorder.lastDirection());
        assertEquals(TEST_SPEED, MockAlgorithmRecorder.lastSpeed(),
                "假算法端收到的速度应为 " + TEST_SPEED + "，实际: " + MockAlgorithmRecorder.lastSpeed());
    }

    /**
     * POST 请求，返回响应体。
     *
     * @param path 请求路径
     * @return 响应体
     * @throws Exception 网络请求失败
     */
    private static String post(String path) throws Exception {
        return rawPost(path).body();
    }

    /**
     * POST 请求，返回完整响应（含状态码）。
     *
     * @param path 请求路径
     * @return 响应
     * @throws Exception 网络请求失败
     */
    private static HttpResponse<String> rawPost(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + APP_HTTP_PORT + path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }
}
