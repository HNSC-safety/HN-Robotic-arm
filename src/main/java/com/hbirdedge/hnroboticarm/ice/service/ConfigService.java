package com.hbirdedge.hnroboticarm.ice.service;

import QNEdge.EErrorCode;
import QNEdge.SConfig;
import com.hbirdedge.hnroboticarm.ice.client.IceTarget;
import com.hbirdedge.hnroboticarm.ice.client.QNEdgeIceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 配置服务 —— 对应 {@code QNEdge.ice} 里的 {@code IQNEConfig} 接口。
 *
 * <p>{@code SConfig} 里目前只有一个字段 {@code seqURL}（事件接收 URL 数组），
 * 语义是：告诉算法端「检测到的事件往哪些 URL 推送」。
 *
 * <p><b>方向说明：</b>参考 hBird-edge-ice 项目的实测结论 ——
 * 算法端（C# HNServer）<b>不暴露</b> {@code ISMMSConfig} 接口，
 * 调它会抛 {@code Ice.ObjectNotExistException}。
 * 所以本类的出方向调用是<b>可选能力</b>：算法端支持就用，不支持就只记日志，
 * 不影响本地 REST 接口。
 *
 * <p>入方向（算法端反向调我们）是<b>确定可用</b>的：
 * 见 {@link com.hbirdedge.hnroboticarm.ice.servant.QNEdgeCallbackServant}。
 *
 * @author hbird
 * @since 2026-11-03
 */
@Service
public class ConfigService {

    /** 配置来源：由 REST 接口推过去 */
    private static final String SOURCE_REST = "REST";

    /** 配置来源：由算法端通过 ICE 回调带回来 */
    private static final String SOURCE_ICE_CALLBACK = "ICE回调";

    /** 配置来源：由 ICE 调用下发成功 */
    private static final String SOURCE_ICE_CALL = "ICE调用";

    /** 尚未有配置时的来源占位符 */
    private static final String SOURCE_NONE = "无";

    /** 尚未有配置时的时间戳占位符 */
    private static final String TIME_NONE = "无";

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    private final QNEdgeIceClient iceClient;

    /**
     * 本地保存的最新配置。
     *
     * <p>用 {@link AtomicReference} 而不是普通字段：ICE 回调线程和 HTTP 线程
     * 会并发读写，得保证可见性。
     */
    private final AtomicReference<String[]> eventUrls = new AtomicReference<>(new String[0]);

    /** 最近一次配置的来源：{@code REST} / {@code ICE回调} / {@code ICE调用} */
    private final AtomicReference<String> lastSource = new AtomicReference<>(SOURCE_NONE);

    /** 最近一次配置的时间戳 */
    private final AtomicReference<String> lastUpdateTime = new AtomicReference<>(TIME_NONE);

    public ConfigService(QNEdgeIceClient iceClient) {
        this.iceClient = iceClient;
    }

    /**
     * 把事件接收 URL 列表推给算法端（出方向）。
     *
     * <p>算法端不支持 {@code IQNEConfig} 时返回 {@code EECFail} 并记日志，
     * 而不是抛异常把整个 REST 请求打成 500。
     *
     * @param urls 事件接收 URL 列表
     * @param real true = 真实算法端；false = 内置假算法端
     * @return 算法端返回的错误码
     */
    public EErrorCode pushToAlgServer(List<String> urls, boolean real) {
        String[] arr = urls == null ? new String[0] : urls.toArray(new String[0]);
        SConfig config = new SConfig(arr);

        EErrorCode code;
        try {
            IceTarget target = iceClient.resolve(real);
            if (target.getConfig() == null) {
                throw new AlgServerUnreachableException("连不上算法端（" + target.getDescription() + "）。"
                        + "已探测的暗号：" + target.getTriedIdentities() + "。");
            }
            code = target.getConfig().Config(config);
        } catch (Ice.ObjectNotExistException e) {
            log.warn("[ICE] 算法端不暴露 IQNEConfig 接口（实测已知问题），配置未下发到算法端。"
                    + "本地仍会记住这份配置。");
            apply(config, SOURCE_REST + "（算法端不支持，仅本地）");
            return EErrorCode.EECFail;
        }

        // 推送成功才更新本地状态，避免本地记的和算法端不一致
        if (code == EErrorCode.EECSuccess) {
            apply(config, SOURCE_ICE_CALL);
        }
        log.info("[ICE] Config → 算法端 urls={} 返回 {}", Arrays.toString(arr), code);
        return code;
    }

    /**
     * 接收算法端下发的配置（入方向，由 {@code QNEdgeCallbackServant} 调用）。
     *
     * @param config 算法端下发的配置
     * @return 固定返回 {@code EECSuccess}
     */
    public EErrorCode acceptFromAlgServer(SConfig config) {
        apply(config, SOURCE_ICE_CALLBACK);
        log.info("[ICE回调] Config urls={}", Arrays.toString(eventUrls.get()));
        return EErrorCode.EECSuccess;
    }

    /**
     * 当前配置的事件接收 URL。
     *
     * @return URL 数组副本
     */
    public String[] eventUrls() {
        return eventUrls.get().clone();
    }

    /**
     * 最近一次配置的来源。
     *
     * @return 来源标识
     */
    public String lastSource() {
        return lastSource.get();
    }

    /**
     * 最近一次配置的时间戳。
     *
     * @return 时间戳文本
     */
    public String lastUpdateTime() {
        return lastUpdateTime.get();
    }

    /**
     * 保存本地配置状态。
     *
     * @param config 配置；为 null 时忽略
     * @param source 来源标识
     */
    private void apply(SConfig config, String source) {
        if (config == null) {
            return;
        }
        eventUrls.set(config.seqURL == null ? new String[0] : config.seqURL.clone());
        lastSource.set(source);
        lastUpdateTime.set(LocalDateTime.now().toString());
    }
}
