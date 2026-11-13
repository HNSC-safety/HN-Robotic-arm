package com.hbirdedge.hnroboticarm.ice.bootstrap;

import Ice.Communicator;
import Ice.ObjectAdapter;
import com.hbirdedge.hnroboticarm.ice.config.IceProperties;
import com.hbirdedge.hnroboticarm.ice.service.IcePortConflictException;
import com.hbirdedge.hnroboticarm.ice.servant.QNEdgeCallbackServant;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 第 4 步：起"接电话的客服"（ObjectAdapter），并把 servant 挂上去起暗号。
 *
 * <p>本地监听 10001，算法端通过它反向回调我们。
 *
 * <p><b>只注册 1 个 servant、1 个暗号</b>：{@link QNEdgeCallbackServant} 同时实现了
 * {@code IQNEEvent} / {@code IQNEFCHInfo} / {@code IQNEConfig} 三个接口，
 * 算法端用同一个暗号 {@code stringToProxy("QNEdgeCallback:...")} 拿到代理后，
 * {@code checkedCast} 到任意接口都能成功。
 *
 * <p>这和 hBird-edge-ice 项目一致（那边 {@code SMT.ice} 也有多个 interface，
 * 本地同样只注册了 1 个 servant + 1 个暗号 {@code SMTEvent}）。
 */
@Component
public class IceCallbackBootstrap {

    private static final Logger log = LoggerFactory.getLogger(IceCallbackBootstrap.class);

    private final Communicator communicator;
    private final IceProperties properties;
    private final QNEdgeCallbackServant callbackServant;

    private ObjectAdapter adapter;

    public IceCallbackBootstrap(Communicator communicator,
                                IceProperties properties,
                                QNEdgeCallbackServant callbackServant) {
        this.communicator = communicator;
        this.properties = properties;
        this.callbackServant = callbackServant;
    }

    @PostConstruct
    public void start() {
        IceProperties.Local local = properties.getLocal();

        try {
            adapter = communicator.createObjectAdapterWithEndpoints(
                    "QNEdgeCallbackAdapter",
                    "default -p " + local.getPort()
            );
        } catch (RuntimeException e) {
            // 端口被占 → 应用起不来。这里必须说清楚是哪个端口，
            // 否则只看到一条 Ice.SocketException 栈，根本猜不到是端口冲突。
            if (IcePortConflictException.isPortConflict(e)) {
                log.error("[ICE] 回调 ObjectAdapter 启动失败：端口 {} 被占用", local.getPort());
                throw new IcePortConflictException(
                        IcePortConflictException.message(local.getPort(), "本地 ICE 回调 adapter 监听失败"));
            }
            log.error("[ICE] 回调 ObjectAdapter 启动失败（端口 {}）：{}", local.getPort(), e.getMessage(), e);
            throw e;
        }

        // 暗号必须与算法端 stringToProxy("QNEdgeCallback:...") 的名字完全一致
        adapter.add(callbackServant, communicator.stringToIdentity(local.getIdentity()));

        adapter.activate();

        log.info("[ICE] 回调 ObjectAdapter 已启动：端口 {}，暗号 {}（实现 IQNEEvent / IQNEFCHInfo / IQNEConfig）",
                local.getPort(), local.getIdentity());
    }

    @jakarta.annotation.PreDestroy
    public void stop() {
        if (adapter != null) {
            try {
                adapter.destroy();
                log.info("[ICE] 回调 ObjectAdapter 已关闭（端口 {}）", properties.getLocal().getPort());
            } catch (Exception e) {
                log.warn("[ICE] 关闭 ObjectAdapter 失败", e);
            } finally {
                adapter = null;
            }
        }
    }
}
