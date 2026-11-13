package com.hbirdedge.hnroboticarm.ice.client;

import Ice.Communicator;
import Ice.ObjectPrx;
import QNEdge.IQNEAlgHandlePrx;
import QNEdge.IQNEAlgHandlePrxHelper;
import QNEdge.IQNEArmControlPrx;
import QNEdge.IQNEArmControlPrxHelper;
import QNEdge.IQNEConfigPrx;
import QNEdge.IQNEConfigPrxHelper;
import com.hbirdedge.hnroboticarm.ice.config.IceProperties;
import com.hbirdedge.hnroboticarm.ice.service.IcePortConflictException;
import com.hbirdedge.hnroboticarm.mock.MockAlgorithmServerRunner;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ICE 客户端 —— 把"连谁"这件事做成<b>显式参数</b>，而不是一个全局开关。
 *
 * <h2>为什么不用全局开关</h2>
 * <p>之前用 {@code ice.test.mock-mode} 决定连假端还是真端。问题在于：
 * <ul>
 *   <li>它是<b>可变全局状态</b> —— 谁最后改了这个开关，行为就变，
 *       读代码的人无法一眼看出"这次调用到底在跟谁说话"</li>
 *   <li>测试方法里就得先切模式、再还原，多一层状态管理</li>
 * </ul>
 * <p>现在改成：<b>要连谁，由调用的参数说了算</b>。
 * {@link #resolve(boolean)} 传入 {@code true} 就是真机，{@code false} 就是模拟。
 * 每次调用都拿到一个独立的 {@link IceTarget}，互不影响。
 *
 * <h2>一个方向只用一个暗号</h2>
 * <p>核心就 2 行：
 * <pre>
 *   ObjectPrx base = communicator.stringToProxy("QNEArmControl:default -h 127.0.0.1 -p 10000");
 *   IQNEArmControlPrx prx = IQNEArmControlPrxHelper.uncheckedCast(base);
 * </pre>
 *
 * <p>算法端的 servant 可能同时实现多个 Slice 接口。对端注册时只用了 1 个暗号，
 * 我们拿到的代理先用 {@code ice_ids()} 探出它实现了哪些类型，
 * 再 {@code uncheckedCast} 到具体接口 —— 不需要为每个接口单独配一个暗号。
 *
 * <h2>暗号未知怎么办</h2>
 * <p>{@code ice.alg.fallback-identities} 里配了几个候选名，
 * 挨个 {@code ice_ping()} 探测，第一个能通的胜出。
 *
 * @author hbird
 * @since 2026-11-03
 */
@Component
public class QNEdgeIceClient {

    private static final Logger log = LoggerFactory.getLogger(QNEdgeIceClient.class);

    /**
     * 等待算法端上线时，<b>两次重试之间的间隔</b>（毫秒）。
     *
     * <p>现场联调时算法端可能几秒后才起来 —— 每秒探一次，
     * 上线后最多 1 秒内就能接上，不会白等。
     */
    private static final long WAIT_RETRY_INTERVAL_MS = 1000L;

    /**
     * TCP 预检的连接超时（毫秒）。
     *
     * <p><b>刻意设得很短（500ms）</b>：它只是问"端口有人接吗"，
     * 不是真的要把 ICE 会话建起来。连不上时快速失败，
     * 这样等待窗口里的每一秒都能用上。
     */
    private static final int TCP_PROBE_TIMEOUT_MS = 500;

    private final Communicator communicator;
    private final IceProperties properties;

    /**
     * 内置假算法端的持有者 —— 模拟模式下要先确保它已经监听，才能探到暗号。
     *
     * <p><b>用构造器直接注入（而不是 {@code ObjectProvider}）是有意为之</b>：
     * 直接注入会让 Spring 保证 {@link MockAlgorithmServerRunner} <b>先</b>完成初始化
     * （即先 {@code afterPropertiesSet()} 起好 10000 端口），本类才被创建。
     *
     * <p>用 {@code ObjectProvider} 时没有这个顺序保证 ——
     * {@code QNEdgeIceClient} 可能先于 {@code MockAlgorithmServerRunner} 初始化，
     * 于是探测暗号时 10000 还没人监听，日志报"全部候选暗号都没通"。
     *
     * <p>顺序一旦确定，这条链路就是<b>确定性</b>的，不再靠运气。
     */
    private final MockAlgorithmServerRunner mockRunner;

    /**
     * 默认目标 —— 真机还是模拟由 {@code ice.test.default-real} 配置决定。
     *
     * <p>只给"不传参数时用哪个"用；所有业务接口都显式传参。
     */
    private volatile IceTarget defaultTarget;

    public QNEdgeIceClient(Communicator communicator, IceProperties properties,
                           MockAlgorithmServerRunner mockRunner) {
        this.communicator = communicator;
        this.properties = properties;
        this.mockRunner = mockRunner;
    }

    /**
     * 启动时把默认目标准备好。
     *
     * <p>连不上也不抛异常 —— 让应用照常起来，业务调用时再报错。
     *
     * <p><b>端口冲突也只记日志，不让应用起不来</b>：
     * 假算法端只是"模拟模式才用得上"的附属品，真实模式下用不到它。
     * 所以启动阶段炸掉整个应用没有必要 —— 等真有人调 {@code resolve(false)}
     * 时再报错，那时候的消息才是给用户看的。
     */
    @PostConstruct
    public void init() {
        properties.validateArmConfig();
        try {
            this.defaultTarget = resolve(properties.getTest().isDefaultReal());
        } catch (IcePortConflictException e) {
            log.error("[ICE] 默认目标初始化失败：{}", e.getMessage());
        }
    }

    /**
     * 解析一个目标 —— <b>连谁由参数决定</b>。
     *
     * <p><b>这个方法不改任何全局状态</b>。它只是"问一下那个地址通不通"，
     * 不会把应用配置切来切去 —— 所以谁调用、什么时候调用都不影响别人。
     *
     * @param real true = 真实 C# 算法端；false = 内置假算法端
     * @return 目标（连不上时代理为 null，但对象本身永远不为 null）
     */
    public IceTarget resolve(boolean real) {
        return resolve(real, null);
    }

    /**
     * 解析一个目标 —— <b>连谁由参数决定，并且指定机械臂</b>。
     *
     * <p><b>机械臂怎么影响"连哪台"</b>：
     * <ul>
     *   <li>{@code ice.arms} 里配了这条臂 → 连它自己的 {@code host:port:identity}</li>
     *   <li>没配、但 {@code ice.arms} 清单本身是空的 → 连 {@code ice.alg} 的端点，
     *       臂名只作为<b>标签</b>塞进 Ice Context 告诉对端"动哪条"</li>
     *   <li>清单不为空、但没这条臂 → <b>直接报错</b>（避免打错字却悄悄打到默认臂）</li>
     * </ul>
     *
     * <p><b>模拟模式（{@code real=false}）永远连内置假端</b> ——
     * 假端只有一份，臂名同样只作为标签进 Context。
     *
     * @param real    true = 真实 C# 算法端；false = 内置假算法端
     * @param armName 机械臂名，可为 null
     * @return 目标（连不上时代理为 null，但对象本身永远不为 null）
     */
    public IceTarget resolve(boolean real, String armName) {
        IceProperties.Alg alg = properties.getAlg();
        String arm = normalizeArmName(armName);

        // 臂名校验在两种模式下都要做 —— 模拟模式也不能"打错字却悄悄打到默认臂"。
        IceProperties.Arm armCfg = resolveArmConfig(arm);

        if (!real) {
            // 模拟模式：确保内置假算法端在监听，再探暗号。
            // startIfNeeded() 在端口被占时抛 IcePortConflictException ——
            // 这里必须让它继续往上抛，不能吞掉后继续 probe()，
            // 否则会连上别的 JVM 的假算法端，产生"假阳性"。
            mockRunner.startIfNeeded();
            return probe(false, arm, "127.0.0.1",
                    properties.getTest().mockPortOrDefault(alg.getPort()),
                    alg.allIdentities());
        }

        // 真实模式：直连现场那台 C# 程序。
        // 内置假算法端不用停 —— 它在 127.0.0.1:10000，现场那台在别的机器上，不冲突。
        if (armCfg == null) {
            return probe(true, arm, alg.getHost(), alg.getPort(), alg.allIdentities());
        }

        // 这条臂有自己的端点 —— 端点留空的字段继承 ice.alg
        String host = isBlank(armCfg.getHost()) ? alg.getHost() : armCfg.getHost();
        Integer port = armCfg.getPort();
        int realPort = (port == null || port <= 0) ? alg.getPort() : port;

        List<String> identities = new ArrayList<>();
        if (!isBlank(armCfg.getIdentity())) {
            identities.add(armCfg.getIdentity());
        }
        identities.addAll(alg.allIdentities());

        return probe(true, arm, host, realPort, identities);
    }

    /**
     * 默认目标 —— 不传参数时用这个。
     *
     * @return 默认目标
     */
    public IceTarget defaultTarget() {
        IceTarget target = defaultTarget;
        if (target == null) {
            target = resolve(properties.getTest().isDefaultReal());
            defaultTarget = target;
        }
        return target;
    }

    /**
     * 挨个试所有候选暗号，把能通的那个探出来。
     *
     * <p>用途：<b>不知道 C# 端的准确暗号时，用这个把它探出来。</b>
     * ICE 没有「列出某 endpoint 上所有 identity」的 API（{@code ice_ids()} 查的是类型 ID），
     * 所以只能靠候选名单挨个 {@code ice_ping()} 试。
     *
     * @param real true = 真实算法端；false = 内置假算法端
     * @return 每个候选暗号的探测结果
     */
    public Map<String, String> probeCandidates(boolean real) {
        IceProperties.Alg alg = properties.getAlg();
        String host = real ? alg.getHost() : "127.0.0.1";
        int port = real ? alg.getPort() : properties.getTest().mockPortOrDefault(alg.getPort());

        Map<String, String> result = new LinkedHashMap<>();
        for (String identity : alg.allIdentities()) {
            String proxyStr = identity + ":default -h " + host + " -p " + port;
            try {
                ObjectPrx prx = communicator.stringToProxy(proxyStr);
                prx.ice_ping();
                result.put(identity, "OK  ids=" + String.join(",", prx.ice_ids()));
            } catch (Ice.ObjectNotExistException e) {
                result.put(identity, "IDENTITY_NOT_FOUND");
            } catch (Ice.ConnectionRefusedException e) {
                result.put(identity, "CONNECTION_REFUSED");
                // 端口不通 = 整台机器不可达，剩下几个暗号也是同样结果 —— 别再白等
                break;
            } catch (Ice.ConnectFailedException e) {
                result.put(identity, "CONNECT_FAILED: " + e.getMessage());
                break;
            } catch (Exception e) {
                result.put(identity, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        return result;
    }

    /**
     * 解析机械臂配置 —— 顺带校验臂名合法性。
     *
     * @param armName 臂名（已归一化）
     * @return 配置；没指定 / 单臂场景返回 null
     * @throws IllegalArgumentException 清单不为空但没这条臂
     */
    private IceProperties.Arm resolveArmConfig(String armName) {
        if (armName == null) {
            return null;
        }
        Map<String, IceProperties.Arm> arms = properties.getArms();
        if (arms == null || arms.isEmpty()) {
            // 没配清单 —— 臂名只是标签，端点用 ice.alg
            return null;
        }
        IceProperties.Arm cfg = arms.get(armName);
        if (cfg == null) {
            throw new IllegalArgumentException(
                    "未配置的机械臂 '" + armName + "'。已配置的机械臂：" + String.join("、", arms.keySet()));
        }
        return cfg;
    }

    /**
     * 归一化臂名 —— 空字符串按"没指定"处理。
     *
     * @param armName 原始臂名
     * @return 归一化后的臂名；null 表示没指定
     */
    private String normalizeArmName(String armName) {
        if (armName == null || armName.isBlank()) {
            // 没传 → 用默认臂
            String def = properties.getDefaultArm();
            return (def == null || def.isBlank()) ? null : def.trim();
        }
        return armName.trim();
    }

    /**
     * 挨个试候选暗号，探出一个能用的代理。
     *
     * <p><b>⚠️ 连接层失败要提前退出</b>：
     * 所有候选暗号都指向<b>同一个</b> {@code host:port}。
     * 如果连不上（{@code ConnectionRefusedException} / {@code ConnectFailedException}），
     * 说明这台机器/这个端口整体不可达 —— 再试剩下几个暗号也是白等，
     * 每个都要付一次 {@code ConnectTimeout}（默认 3 秒 × 5 个暗号 = 15 秒）。
     *
     * <p>区分两种失败，很重要：
     * <ul>
     *   <li><b>连接层失败</b>（端口不通）→ 换暗号也没用 → <b>立刻停</b></li>
     *   <li><b>身份层失败</b>（{@code ObjectNotExistException}）→ 服务器活着，
     *       只是这个暗号不存在 → <b>继续试下一个</b></li>
     * </ul>
     *
     * <p><b>⚠️ 但"立刻停"的前提是"以后也连不上"</b> —— 现场联调时经常是我们先起、
     * 算法端后起，或者算法端正在重启。配置了
     * {@code ice.alg.connect-wait-seconds}（&gt; 0）时，连接层失败会
     * <b>在窗口内反复重试</b>，直到连上或窗口耗尽才判死。
     *
     * <p><b>⚠️ 为什么每轮要先做 TCP 预检</b>：
     * 走 Ice 代理探测时，连不上要走满 Ice 内部的连接建立流程
     * （实测一轮约 5 秒），30 秒窗口只能试 6 次。
     * 而"端口通不通"用一次裸 TCP connect 就能在<b>毫秒级</b>判掉 ——
     * 端口不通就直接进下一轮，把等待窗口的每一秒都用上。
     *
     * <p><b>⚠️ 等待期间本地回调端口（10001）始终开着</b>：
     * 回调 adapter 由 Ice 自己的线程池驱动，跟阻塞在探测里的 HTTP 线程无关。
     * 所以算法端在窗口内任何时刻上线并反向回调，我们都能接得住。
     *
     * @param real       是否真实算法端
     * @param armName    机械臂名（可为 null）
     * @param host       目标主机
     * @param port       目标端口
     * @param candidates 候选暗号
     * @return 目标
     */
    private IceTarget probe(boolean real, String armName, String host, int port, List<String> candidates) {
        int waitSeconds = properties.getAlg().getConnectWaitSeconds();
        long deadline = waitSeconds > 0
                ? System.currentTimeMillis() + waitSeconds * 1000L
                : 0L;

        List<String> tried = new ArrayList<>();
        int attempt = 0;

        while (true) {
            attempt++;

            // TCP 预检 —— 端口不通就用毫秒级的方式判掉，不浪费一轮 Ice 探测
            if (!tcpReachable(host, port)) {
                tried.clear();
                tried.add("端口不通");

                long remain = deadline - System.currentTimeMillis();
                if (deadline == 0L || remain <= 0) {
                    break;
                }
                log.info("[ICE] 算法端 {}:{} 还没上线（第 {} 次探测），等待重试（剩余约 {} 秒）……",
                        host, port, attempt, remain / 1000);
                if (!sleepFor(Math.min(WAIT_RETRY_INTERVAL_MS, remain))) {
                    break;
                }
                continue;
            }

            // 端口通了 —— 挨个试暗号（TCP 通了但 ICE 未必可用）
            List<String> roundTried = new ArrayList<>();
            IceTarget result = probeOnce(real, armName, host, port, candidates, roundTried);
            if (result != null) {
                return result;
            }

            // 只保留最后一轮的探测结果 —— 攒几十轮重复记录没人看得懂
            tried.clear();
            tried.addAll(roundTried);

            // 端口通、但暗号全不对 —— 这是配置问题，换暗号也没用，重试没意义
            long remain = deadline - System.currentTimeMillis();
            if (deadline == 0L || remain <= 0) {
                break;
            }
            log.info("[ICE] 算法端 {}:{} 端口已通但暗号没对上（第 {} 次探测），等待重试（剩余约 {} 秒）……",
                    host, port, attempt, remain / 1000);
            if (!sleepFor(Math.min(WAIT_RETRY_INTERVAL_MS, remain))) {
                break;
            }
        }

        log.warn("[ICE] 全部候选暗号都没通：{}。算法端 {}:{} 可能未启动，或暗号不对。", tried, host, port);
        return IceTarget.unreachable(real, armName, host, port, tried);
    }

    /**
     * 裸 TCP 探活 —— 只问"这个端口有人接吗"，不问 ICE 暗号。
     *
     * <p><b>为什么要有它</b>：走 Ice 代理探测时，连不上要走满 Ice 内部的
     * 连接建立流程（实测一轮约 5 秒）。等待窗口是按秒计的，
     * 必须有毫秒级的探活才能把窗口用满。
     *
     * @param host 目标主机
     * @param port 目标端口
     * @return true = 端口有人监听
     */
    private boolean tcpReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TCP_PROBE_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 探一轮 —— 挨个试候选暗号。
     *
     * @param real       是否真实算法端
     * @param armName    机械臂名（可为 null）
     * @param host       目标主机
     * @param port       目标端口
     * @param candidates 候选暗号
     * @param tried      已试过的暗号（累积，跨轮次保留）
     * @return 探通了返回目标；连接层失败（值得重试）返回 null；暗号全不对返回 unreachable
     */
    private IceTarget probeOnce(boolean real, String armName, String host, int port,
                                List<String> candidates, List<String> tried) {
        for (String identity : candidates) {
            String proxyStr = identity + ":default -h " + host + " -p " + port;
            try {
                ObjectPrx prx = communicator.stringToProxy(proxyStr);
                prx.ice_ping();

                // ping 通了，再探一下它实现了哪些接口
                List<String> typeIds = List.of(prx.ice_ids());
                String resolved = identityOf(prx);

                log.info("[ICE] 暗号探测成功：{} -> {}:{}{}  对端实现的接口={}",
                        resolved, host, port,
                        armName == null ? "" : "  机械臂=" + armName,
                        typeIds);

                return new IceTarget(real, armName, host, port, resolved, typeIds, tried,
                        IQNEArmControlPrxHelper.uncheckedCast(prx),
                        IQNEAlgHandlePrxHelper.uncheckedCast(prx),
                        IQNEConfigPrxHelper.uncheckedCast(prx));
            } catch (Ice.ObjectNotExistException e) {
                // 服务器活着，只是这个暗号不存在 —— 继续试下一个
                tried.add(identity + "(不存在)");
            } catch (Ice.ConnectionRefusedException e) {
                // 端口不通 —— 整台机器都不可达，别再白等了
                tried.add(identity + "(端口不通)");
                log.warn("[ICE] 算法端 {}:{} 端口不通（连接被拒绝），停止探测剩余暗号：{}", host, port, tried);
                return null;
            } catch (Ice.ConnectFailedException e) {
                // 路由不可达 / 主机不存在 —— 同上，整个端点都没戏
                tried.add(identity + "(连不上)");
                log.warn("[ICE] 算法端 {}:{} 连不上（{}），停止探测剩余暗号：{}", host, port, e.getMessage(), tried);
                return null;
            } catch (Exception e) {
                tried.add(identity + "(" + e.getClass().getSimpleName() + ")");
            }
        }

        log.warn("[ICE] 全部候选暗号都没通：{}。算法端 {}:{} 可能未启动，或暗号不对。", tried, host, port);
        return IceTarget.unreachable(real, armName, host, port, tried);
    }

    /**
     * 从代理里抠出暗号。
     *
     * @param prx 代理
     * @return 暗号
     */
    private String identityOf(ObjectPrx prx) {
        try {
            return prx.ice_getIdentity().name;
        } catch (Exception e) {
            return properties.getAlg().getIdentity();
        }
    }

    /**
     * 字符串是否为空。
     *
     * @param s 字符串
     * @return true 表示 null 或全空白
     */
    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 等待一段时间 —— 被中断时立刻返回 false，让调用方退出重试循环。
     *
     * @param millis 等待毫秒数
     * @return true = 正常等完；false = 被中断
     */
    private boolean sleepFor(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
