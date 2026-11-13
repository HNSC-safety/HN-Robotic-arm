package com.hbirdedge.hnroboticarm.ice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ICE 相关配置。
 *
 * <p><b>结构说明（参照 hBird-edge-ice 项目）：按「方向」分，不按「接口」分。</b>
 *
 * <p>核心认知：<b>一个方向只需要一个暗号（identity）</b>。
 * ICE 的一个 servant 可以同时实现多个 Slice 接口（multi-interface），
 * 对端拿到代理后用 {@code checkedCast} 可以窄化成任意一个接口。
 * 所以没必要给每个接口配一个暗号 —— 那只是把同一件事拆成多份配置。
 *
 * <p>端口约定：
 * <ul>
 *     <li>算法端（ICE 服务端）监听 10000 —— 我们作为调用方去连它</li>
 *     <li>本地回调端口 10001 —— 算法端反向回调我们时用的端口（我们起 ObjectAdapter）</li>
 *     <li>Spring Boot HTTP 端口 9988</li>
 * </ul>
 *
 * @author hbird
 * @since 2026-11-03
 */
@ConfigurationProperties(prefix = "ice")
public class IceProperties {

    /** 算法端（ICE 服务端）配置 */
    private Alg alg = new Alg();

    /** 本地回调（ObjectAdapter）配置 */
    private Local local = new Local();

    /** 总开关 */
    private Service service = new Service();

    /**
     * 测试模式配置 —— <b>决定连假算法端还是真算法端</b>。
     */
    private Test test = new Test();

    /**
     * 机械臂清单 —— <b>臂名 → 算法端点</b> 的映射。
     *
     * <p><b>为什么需要它</b>：{@code MoveTask(EDirection, int)} 里没有任何"哪条臂"的字段
     * （Slice 不改，协议固定）。所以"控制的是哪条臂"只能靠<b>调用时选哪个目标</b>表达：
     * <ul>
     *   <li>多台算法端、一台管一条臂 → 每个臂配一条 {@code host:port:identity}，
     *       调用时用 {@code arm=臂名} 选</li>
     *   <li>一台算法端管多条臂 → 臂标识走 <b>Ice Context</b>（{@code strArmID}），
     *       对端从 {@code Current.ctx} 读，<b>不用改 Slice</b></li>
     * </ul>
     *
     * <p>两种方式<b>可以同时用</b>：{@code arm} 先决定"连哪台"，
     * 同时把臂名塞进 Context，让对端知道"具体动哪条"。
     *
     * <p>配置示例：
     * <pre>
     * ice:
     *   arms:
     *     arm-a:
     *       host: 192.168.0.103
     *       port: 10000
     *       identity: AlgHandle
     *     arm-b:
     *       host: 192.168.0.104
     *       port: 10000
     *       identity: AlgHandle
     * </pre>
     *
     * <p>没在清单里配置、也没传 {@code arm} 时，<b>退回 {@link #alg}</b>（单臂场景）。
     */
    private Map<String, Arm> arms = new LinkedHashMap<>();

    /**
     * 默认机械臂名 —— 调用时<b>不传</b> {@code arm} 就用它。
     *
     * <p>留空 = 用 {@link #alg} 的端点（单臂场景）。
     */
    private String defaultArm;

    /**
     * 校验机械臂配置 —— <b>配错了要在启动时就炸，而不是等到有人按消音键才炸</b>。
     *
     * <p>校验一条：{@code ice.default-arm} 配了、但不在 {@code ice.arms} 清单里 → 拒绝启动。
     *
     * <p><b>为什么默认臂不合法必须挡住启动</b>：默认臂不合法意味着
     * <b>每一次</b>机械臂调用都会失败 —— 而调用往往发生在消防现场。
     * 与其让应用带着一颗定时炸弹上线，不如启动时就说清楚。
     *
     * <p>清单里每条臂的端点字段不校验 —— 留空表示"继承 {@code ice.alg}"，是合法的。
     *
     * @throws IllegalStateException 默认臂不在清单里
     */
    public void validateArmConfig() {
        if (arms == null || arms.isEmpty()) {
            // 单臂场景 —— 臂名只是标签，没有清单可校验
            return;
        }
        if (defaultArm == null || defaultArm.isBlank()) {
            return;
        }
        String trimmed = defaultArm.trim();
        if (!arms.containsKey(trimmed)) {
            throw new IllegalStateException(
                    "ice.default-arm='" + defaultArm + "' 不在 ice.arms 清单里。已配置的机械臂："
                            + String.join("、", arms.keySet())
                            + "。要么把它改成清单里的臂名，要么把它清空（清空 = 不指定默认臂）。");
        }
    }

    /**
     * 机械臂清单。
     *
     * @return 臂名 → 配置
     */
    public Map<String, Arm> getArms() {
        return arms;
    }

    public void setArms(Map<String, Arm> arms) {
        this.arms = arms;
    }

    /**
     * 默认机械臂名。
     *
     * @return 默认臂名；没配时为 null
     */
    public String getDefaultArm() {
        return defaultArm;
    }

    public void setDefaultArm(String defaultArm) {
        this.defaultArm = defaultArm;
    }

    /**
     * 找一条臂的配置。
     *
     * @param name 臂名
     * @return 配置；没配返回 null
     */
    public Arm findArm(String name) {
        if (name == null || name.isBlank() || arms == null) {
            return null;
        }
        return arms.get(name.trim());
    }

    /**
     * 算法端配置。
     *
     * @return 算法端配置
     */
    public Alg getAlg() {
        return alg;
    }

    public void setAlg(Alg alg) {
        this.alg = alg;
    }

    /**
     * 本地回调配置。
     *
     * @return 本地回调配置
     */
    public Local getLocal() {
        return local;
    }

    public void setLocal(Local local) {
        this.local = local;
    }

    /**
     * 总开关。
     *
     * @return 总开关配置
     */
    public Service getService() {
        return service;
    }

    public void setService(Service service) {
        this.service = service;
    }

    /**
     * 测试模式配置。
     *
     * @return 测试模式配置
     */
    public Test getTest() {
        return test;
    }

    public void setTest(Test test) {
        this.test = test;
    }

    /**
     * 机械臂配置项。
     *
     * <p><b>所有字段都可留空</b> —— 留空表示"继承 {@code ice.alg} 的值"。
     * 这样只改一台的地址时，其他臂不用重复写。
     */
    public static class Arm {

        /** 算法端 IP；留空 = 继承 {@code ice.alg.host} */
        private String host;

        /** 算法端 ICE 端口；留空或 &lt;=0 = 继承 {@code ice.alg.port} */
        private Integer port;

        /** 算法端 servant 暗号；留空 = 继承 {@code ice.alg.identity} */
        private String identity;

        /** 人类可读的说明，会出现在日志和接口返回里 */
        private String description;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        public String getIdentity() {
            return identity;
        }

        public void setIdentity(String identity) {
            this.identity = identity;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    /**
     * 算法端配置 —— 我们主动连它。
     */
    public static class Alg {

        /** 算法端 IP */
        private String host = "127.0.0.1";

        /** 算法端 ICE 端口 */
        private int port = 10000;

        /**
         * 算法端 servant 的暗号（主用）。
         * 必须与算法端 {@code stringToIdentity(...)} 一字不差。
         */
        private String identity = "QNEArmControl";

        /**
         * 备用暗号列表。
         *
         * <p>算法端可能把不同接口注册成不同身份（参考项目里就有
         * {@code AlgHandle} / {@code SMTEvent} 分开的先例）。
         * 这里给几个候选名，启动时挨个 {@code ice_ping()} 探测，
         * 第一个能通的胜出 —— 省得必须先知道暗号才能启动。
         */
        private List<String> fallbackIdentities = new ArrayList<>();

        /**
         * 连不上算法端时，<b>等待它上线</b>的最长秒数。
         *
         * <p><b>为什么需要它</b>：现场联调时经常是"我们先起，算法端后起"，
         * 或者算法端正在重启。第一次探测拿到 {@code ConnectionRefusedException}
         * 不代表"以后都连不上" —— 只代表"这一刻还没起"。
         *
         * <p>这个值 &gt; 0 时，连接层失败会<b>在窗口内反复重试</b>，
         * 直到连上或者窗口耗尽才判死。
         *
         * <p>取 0 = 不等待，一次失败立刻返回（CI / 单元测试用）。
         * 默认 0 —— 保持"连不上就快速失败"的原有行为，
         * 现场联调时用 {@code -Dice.alg.connect-wait-seconds=30} 打开。
         */
        private int connectWaitSeconds = 0;

        /**
         * 主暗号 + 备用暗号，合成完整候选列表（主暗号排第一）。
         *
         * @return 候选暗号列表
         */
        public List<String> allIdentities() {
            List<String> all = new ArrayList<>();
            if (identity != null && !identity.isBlank()) {
                all.add(identity);
            }
            for (String s : fallbackIdentities) {
                if (s != null && !s.isBlank() && !all.contains(s)) {
                    all.add(s);
                }
            }
            return all;
        }

        public int getConnectWaitSeconds() {
            return connectWaitSeconds;
        }

        public void setConnectWaitSeconds(int connectWaitSeconds) {
            this.connectWaitSeconds = connectWaitSeconds;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getIdentity() {
            return identity;
        }

        public void setIdentity(String identity) {
            this.identity = identity;
        }

        public List<String> getFallbackIdentities() {
            return fallbackIdentities;
        }

        public void setFallbackIdentities(List<String> fallbackIdentities) {
            this.fallbackIdentities = fallbackIdentities;
        }
    }

    /**
     * 本地回调配置 —— 我们起 adapter，等算法端反向回调。
     */
    public static class Local {

        /** 本地 IP（仅日志用；算法端连哪个 IP 由算法端自己的配置决定） */
        private String host = "127.0.0.1";

        /** 本地 ObjectAdapter 监听端口 */
        private int port = 10001;

        /**
         * 本地 servant 的暗号。
         * 算法端 C# 里必须写 {@code stringToProxy("QNEdgeCallback:default -h <本机IP> -p 10001")}。
         */
        private String identity = "QNEdgeCallback";

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getIdentity() {
            return identity;
        }

        public void setIdentity(String identity) {
            this.identity = identity;
        }
    }

    /** 总开关 */
    public static class Service {

        /** 是否启用 ICE 功能 */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * 测试模式配置 —— 决定<b>不传参数时</b>默认连谁。
     *
     * <p>核心开关：{@link #defaultReal}
     * <ul>
     *   <li>{@code true} —— <b>真实 C# 算法端</b>：连 {@code ice.alg.host:port}
     *       上跑着的真实 C# 程序（HNServer）。适合现场验收。</li>
     *   <li>{@code false} —— <b>内置假算法端</b>：应用启动时自己内置一个 ICE 服务端（监听
     *       {@code ice.test.mock-port}，默认 10000），机械臂指令发给它，它再反向回调我们。
     *       不需要装任何 C# 程序，适合开发、联调前自测、CI。</li>
     * </ul>
     *
     * <p><b>注意</b>：这只是<b>默认值</b>，不参与运行时切换。
     * 业务接口和测试方法都<b>显式传参</b>（{@code true}=真机，{@code false}=模拟），
     * 不依赖这个开关 —— 避免"全局可变状态"让调用方猜不出在跟谁说话。
     */
    public static class Test {

        /**
         * 不传参数时默认连真机还是模拟。
         *
         * <p>{@code true} = 真实算法端；{@code false} = 内置假算法端。
         *
         * <p>默认值 {@code false} —— 这样克隆下来就能跑，不用先装 C# 环境。
         */
        private boolean defaultReal = false;

        /**
         * 假算法端监听的端口。
         *
         * <p>默认跟 {@code ice.alg.port} 一致（10000），因为调用方就是往那儿发。
         */
        private Integer mockPort = 10000;

        /**
         * 假算法端注册 servant 时用的暗号。
         *
         * <p>必须和 {@code ice.alg.identity} 一致 —— 否则我们发过去的代理找不到它。
         * 留空表示"跟随 {@code ice.alg.identity}"。
         */
        private String mockIdentity;

        /**
         * 假算法端反向回调的目标端口。
         *
         * <p>默认跟 {@code ice.local.port} 一致（10001）。
         *
         * <p>存在的原因：同一个 JVM 里可能有多个 Spring 容器（比如两个测试类各加载一次），
         * 它们的回调端口不同，假算法端得知道该回调哪个。
         */
        private Integer mockCallbackPort;

        /**
         * 假算法端反向回调的目标 host。
         *
         * <p>默认 {@code 127.0.0.1} —— 假算法端和我们在同一台机器。
         *
         * <p>注意：<b>不能</b>默认用 {@code ice.local.host}（那是 {@code 192.168.0.209}），
         * 因为 {@code ice.local.host} 的语义是"真实算法端要回调到哪"，
         * 和假端回连自己是两回事。
         */
        private String mockCallbackHost;

        /**
         * 不传参数时默认连真机还是模拟。
         *
         * @return true 表示默认连真实算法端
         */
        public boolean isDefaultReal() {
            return defaultReal;
        }

        public void setDefaultReal(boolean defaultReal) {
            this.defaultReal = defaultReal;
        }

        public Integer getMockPort() {
            return mockPort;
        }

        public void setMockPort(Integer mockPort) {
            this.mockPort = mockPort;
        }

        public String getMockIdentity() {
            return mockIdentity;
        }

        public void setMockIdentity(String mockIdentity) {
            this.mockIdentity = mockIdentity;
        }

        public Integer getMockCallbackPort() {
            return mockCallbackPort;
        }

        public void setMockCallbackPort(Integer mockCallbackPort) {
            this.mockCallbackPort = mockCallbackPort;
        }

        public String getMockCallbackHost() {
            return mockCallbackHost;
        }

        public void setMockCallbackHost(String mockCallbackHost) {
            this.mockCallbackHost = mockCallbackHost;
        }

        /**
         * 假算法端监听端口（YAML 里没配就用 {@code ice.alg.port}）。
         *
         * @param fallback 回退端口
         * @return 实际使用的端口
         */
        public int mockPortOrDefault(int fallback) {
            return mockPort != null && mockPort > 0 ? mockPort : fallback;
        }

        /**
         * 假算法端反向回调的目标端口（YAML 里没配就用 {@code ice.local.port}）。
         *
         * @param fallback 回退端口
         * @return 实际使用的端口
         */
        public int mockCallbackPortOrDefault(int fallback) {
            return mockCallbackPort != null && mockCallbackPort > 0 ? mockCallbackPort : fallback;
        }

        /**
         * 假算法端反向回调的目标 host（YAML 里没配就用 {@code fallback}）。
         *
         * <p>默认应该是 {@code 127.0.0.1}，<b>不是</b> {@code ice.local.host}
         * （那个是"真实算法端要回调我们到哪"）。
         *
         * @param fallback 回退 host
         * @return 实际使用的 host
         */
        public String mockCallbackHostOrDefault(String fallback) {
            return (mockCallbackHost == null || mockCallbackHost.isBlank()) ? fallback : mockCallbackHost;
        }
    }
}
