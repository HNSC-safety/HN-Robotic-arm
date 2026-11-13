package com.hbirdedge.hnroboticarm.mock;

import Ice.Communicator;
import Ice.ObjectAdapter;
import com.hbirdedge.hnroboticarm.ice.config.IceProperties;
import com.hbirdedge.hnroboticarm.ice.service.IcePortConflictException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * 【应用内置的假算法端】—— 由 {@code real=false} 开关控制。
 *
 * <p>就是「一个布尔参数切换真实/假」：
 * <ul>
 *   <li>模拟（{@code real=false} → 应用自己起一个假算法端，
 *       监听 {@code ice.test.mock-port}（默认 10000），机械臂指令发给它，
 *       它再反向回调我们。<b>不需要装任何 C# 程序</b>。</li>
 *   <li>真实（{@code real=true} → 不起假端，连 *       * {@code ice.alg.host:port} 上跑着的真实 C# 程序。</li>
 * </ul>
 *
 * <p>两种模式下 REST 接口完全一样，区别只是"对面是谁"。
 *
 * <p><b>注意</b>：本类是无条件注册</b>的 Bean（没有用 {@code @ConditionalOnProperty}），
 * 因为模拟模式要能随时把它拉起来。
 * {@code QNEdgeIceClient.resolve(true)} 里会主动时自动不自动拉"。
 */
@Component
public class MockAlgorithmServerRunner implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(MockAlgorithmServerRunner.class);

    private final Communicator communicator;
    private final IceProperties properties;

    private ObjectAdapter adapter;

    public MockAlgorithmServerRunner(Communicator communicator, IceProperties properties) {
        this.communicator = communicator;
        this.properties = properties;
    }

    /**
     * 启动假算法端。
     *
     * <p>用 {@link InitializingBean#afterPropertiesSet()} 而不是 {@code @PostConstruct}，
     * 是为了保证 <b>它一定比依赖它的 {@code QNEdgeIceClient} 先就绪</b>：
     * Spring 保证被依赖的 bean 先完成初始化。
     * {@code @PostConstruct} 是在 bean 自身初始化后才跑的，两者顺序不保证。
     *
     * <p>另外 {@link QNEdgeIceClient#init()} 里还会再调一次 {@link #startIfNeeded()}，
     * 兜住"谁先谁后"的竞态 —— {@link #startIfNeeded()} 是幂等的，重复调不会有副作用。
     *
     * <p><b>⚠️ 启动阶段绑端口失败不抛异常</b>：应用起不来就没法做任何事，
     * 而假算法端只是"模拟模式才用得上"的附属品 —— 真实模式下它用不到，
     * 所以这里只记一条 ERROR，等真要连假端时（{@link #startIfNeeded()}）再炸。
     */
    @Override
    public void afterPropertiesSet() {
        // 默认就起假算法端（连真实模式，resolve(true) 时会把它停掉）
        try {
            startIfNeeded();
        } catch (IcePortConflictException e) {
            // 端口被占 —— 应用照常起来，但模拟模式会不可用。
            // 到时候 resolve(false) 会再抛一次，那时报错信息才是给用户看的。
            log.error("[ICE] 内置假算法端未能启动：{}", e.getMessage());
        }
    }

    /**
     * 启动假算法端（模拟模式下按需启动时用，或运行时从  切回 MockAlgorithmRecorder} 时用）。
     *
     * @return 启动成功返回 true；已在跑返回 false
     * @throws IcePortConflictException 端口被别的进程占用
     */
    public synchronized boolean startIfNeeded() {
        if (adapter != null) {
            return true;
        }

        IceProperties.Alg alg = properties.getAlg();
        IceProperties.Local local = properties.getLocal();
        IceProperties.Test test = properties.getTest();

        int port = test.mockPortOrDefault(alg.getPort());
        // 反向回调的目标：默认 127.0.0.1（假端和我们在同一台机器）
        String callbackHost = test.mockCallbackHostOrDefault("127.0.0.1");
        int callbackPort = test.mockCallbackPortOrDefault(local.getPort());
        // 暗号必须和调用方（我们自己）用的一致，否则代理找不到 servant
        String identity = (test.getMockIdentity() == null || test.getMockIdentity().isBlank())
                ? alg.getIdentity()
                : test.getMockIdentity();

        try {
            adapter = MockAlgorithmServer.start(communicator, port,
                    callbackHost, callbackPort, local.getIdentity(), identity);
        } catch (RuntimeException e) {
            if (IcePortConflictException.isPortConflict(e)) {
                log.error("[ICE] 内置假算法端启动失败：端口 {} 被占用", port);
                throw new IcePortConflictException(IcePortConflictException.message(port, "内置假算法端监听"));
            }
            log.error("[ICE] 内置假算法端启动失败（端口 {}）：{}", port, e.getMessage(), e);
            throw e;
        }

        log.info("[ICE] 内置假算法端已启动：端口 {}，暗号 {}（反向回调 {}@{}:{}）",
                port, identity, local.getIdentity(), callbackHost, callbackPort);
        return true;
    }

    @PreDestroy
    public void stop() {
        if (adapter != null) {
            try {
                adapter.destroy();
                log.info("[ICE] 内置假算法端已停止");
            } catch (Exception e) {
                log.warn("[ICE] 关闭内置假算法端失败", e);
            } finally {
                adapter = null;
            }
        }
    }

    /** 假算法端是否在跑 */
    public boolean isRunning() {
        return adapter != null;
    }
}
