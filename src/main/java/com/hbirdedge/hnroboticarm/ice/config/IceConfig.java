package com.hbirdedge.hnroboticarm.ice.config;

import Ice.Communicator;
import Ice.InitializationData;
import Ice.Util;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 第 3 步：初始化 ICE 引擎（Communicator）。
 *
 * <p>整个进程只需要 1 个 {@link Communicator}。
 * {@code destroyMethod = "destroy"} 保证 Spring 关闭时释放引擎，避免内存泄漏。
 *
 * <p><b>每个 Spring 容器一个 Communicator</b>：Ice 的 Communicator 是线程安全的，
 * 一个进程多个也行，不会抢端口（端口是 ObjectAdapter 占的）。
 *
 * @author hbird
 * @since 2026-11-03
 */
@Configuration
@EnableConfigurationProperties(IceProperties.class)
public class IceConfig {

    /**
     * ICE 引擎的线程池大小。
     *
     * <p><b>⚠️ 这个值必须 &gt; 1，否则会死锁。</b>
     *
     * <p>原因：Ice 3.6 默认线程池只有 <b>1 个线程</b>。而我们的链路是
     * <b>自己回调自己</b>：
     * <pre>
     *   HTTP 线程  → 调假算法端 MoveTask
     *                  ↓（假算法端在同一个 JVM、同一个 Communicator）
     *              假算法端 servant 在【服务端线程池】里处理
     *                  ↓
     *              反向回调我们本地的 AddEvent
     *                  ↓
     *              本地 servant 要在【客户端线程池】里处理
     * </pre>
     * 客户端和服务端<b>共用</b>这 1 个线程 → 客户端线程在等服务端线程，
     * 服务端线程在等客户端线程 → <b>互相等待，直接死锁</b>。
     *
     * <p>表现：{@code MoveTask} 卡住不动，最后抛 {@code ConnectionTimeoutException}
     * （而不是立刻报错），整个请求挂 30 秒。
     *
     * <p>给足线程数（客户端 + 服务端各 4 个）就不会有这个问题。
     */
    private static final int THREAD_POOL_SIZE = 4;

    /** 一次业务调用的最长等待时间（毫秒） */
    private static final int INVOKE_TIMEOUT_MS = 30000;

    /**
     * 建立连接的最长等待时间（毫秒）。
     *
     * <p>⚠️ <b>必须短</b>：连不上时快速失败，不要干等。
     * 没有它，连一台没开机的机器（比如 192.168.0.103）要等系统默认的
     * TCP 连接超时（可能几十秒甚至 2 分钟），自检接口会卡死。
     */
    private static final int CONNECT_TIMEOUT_MS = 3000;

    /** 单条 ICE 消息的大小上限（字节）—— 默认 1MB，图片事件容易超 */
    private static final int MESSAGE_SIZE_MAX_BYTES = 10 * 1024 * 1024;

    @Bean(destroyMethod = "destroy")
    public Communicator iceCommunicator() {
        InitializationData initData = new InitializationData();
        initData.properties = Util.createProperties();

        // 强制 TCP
        initData.properties.setProperty("Ice.Default.Protocol", "tcp");
        // 消息上限
        initData.properties.setProperty("Ice.MessageSizeMax", String.valueOf(MESSAGE_SIZE_MAX_BYTES));

        // ---- 超时 ----
        // 调用超时：一次业务调用最多等 30 秒
        initData.properties.setProperty("Ice.Override.Timeout", String.valueOf(INVOKE_TIMEOUT_MS));
        // 连接超时：连不上时快速失败
        initData.properties.setProperty("Ice.Override.ConnectTimeout", String.valueOf(CONNECT_TIMEOUT_MS));

        // ---- 线程池 ----
        // 客户端线程池：处理我们发出去的请求的响应 + 我们主动发起的回调
        initData.properties.setProperty("Ice.ThreadPool.Client.Size", String.valueOf(THREAD_POOL_SIZE));
        initData.properties.setProperty("Ice.ThreadPool.Client.SizeMax", String.valueOf(THREAD_POOL_SIZE));
        // 服务端线程池：处理对端发给我们的请求
        initData.properties.setProperty("Ice.ThreadPool.Server.Size", String.valueOf(THREAD_POOL_SIZE));
        initData.properties.setProperty("Ice.ThreadPool.Server.SizeMax", String.valueOf(THREAD_POOL_SIZE));

        return Util.initialize(initData);
    }
}
