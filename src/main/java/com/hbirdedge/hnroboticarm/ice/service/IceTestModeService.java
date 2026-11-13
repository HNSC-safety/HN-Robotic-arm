package com.hbirdedge.hnroboticarm.ice.service;

import com.hbirdedge.hnroboticarm.ice.client.IceTarget;
import com.hbirdedge.hnroboticarm.ice.client.QNEdgeIceClient;
import com.hbirdedge.hnroboticarm.ice.config.IceProperties;
import com.hbirdedge.hnroboticarm.mock.MockAlgorithmServer;
import com.hbirdedge.hnroboticarm.mock.MockAlgorithmServerRunner;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试辅助服务 —— 把"连谁"变成<b>一次调用一个明确的目标</b>。
 *
 * <p><b>没有全局开关了</b>。之前用 {@code ice.test.mock-mode} 一个可变全局状态
 * 决定连谁，谁最后改了它行为就变。现在：
 * <ul>
 *   <li>{@link #resolve(boolean)} —— {@code true} = 真机，{@code false} = 模拟</li>
 *   <li>每次调用返回独立的 {@link IceTarget}，互不影响</li>
 * </ul>
 *
 * @author hbird
 * @since 2026-11-03
 */
@Service
public class IceTestModeService {

    private final IceProperties properties;
    private final QNEdgeIceClient iceClient;
    private final MockAlgorithmServerRunner mockRunner;

    public IceTestModeService(IceProperties properties,
                               QNEdgeIceClient iceClient,
                               MockAlgorithmServerRunner mockRunner) {
        this.properties = properties;
        this.iceClient = iceClient;
        this.mockRunner = mockRunner;
    }

    /**
     * 解析一个目标。
     *
     * @param real true = 真实 C# 算法端；false = 内置假算法端
     * @return 目标
     */
    public IceTarget resolve(boolean real) {
        return iceClient.resolve(real);
    }

    /**
     * 目标状态 —— 目标地址、暗号、连通情况。
     *
     * @param real true = 真实 C# 算法端；false = 内置假算法端
     * @return 状态
     */
    public Map<String, Object> status(boolean real) {
        IceTarget target = iceClient.resolve(real);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("real", real);
        result.put("mode", real ? "REAL" : "MOCK");
        result.put("modeDesc", real ? "真实 C# 算法端" : "内置假算法端（应用自己起的，不需要 C# 环境）");
        result.put("target", target.getDescription());
        result.put("identity", properties.getAlg().getIdentity());
        result.put("resolvedIdentity", target.getResolvedIdentity());
        result.put("remoteTypeIds", target.getRemoteTypeIds());
        result.put("triedIdentities", target.getTriedIdentities());
        result.put("connected", target.isReachable());
        result.put("mockServerRunning", mockRunner.isRunning());
        result.put("arms", armList());
        return result;
    }

    /**
     * 机械臂清单 —— 让"我控制的是哪条臂"能被一眼看到。
     *
     * <p>每项包含：臂名、它连的端点（host:port）、暗号、说明。
     *
     * @return 机械臂清单
     */
    public List<Map<String, Object>> armList() {
        Map<String, IceProperties.Arm> arms = properties.getArms();
        List<Map<String, Object>> list = new ArrayList<>();
        if (arms == null || arms.isEmpty()) {
            // 没配清单 = 单臂场景，端点就是 ice.alg
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("arm", properties.getDefaultArm());
            item.put("host", properties.getAlg().getHost());
            item.put("port", properties.getAlg().getPort());
            item.put("identity", properties.getAlg().getIdentity());
            item.put("description", "单臂场景（未配置 ice.arms）");
            list.add(item);
            return list;
        }
        for (Map.Entry<String, IceProperties.Arm> e : arms.entrySet()) {
            IceProperties.Arm cfg = e.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("arm", e.getKey());
            item.put("host", isBlank(cfg.getHost()) ? properties.getAlg().getHost() : cfg.getHost());
            item.put("port", (cfg.getPort() == null || cfg.getPort() <= 0)
                    ? properties.getAlg().getPort() : cfg.getPort());
            item.put("identity", isBlank(cfg.getIdentity())
                    ? properties.getAlg().getIdentity() : cfg.getIdentity());
            item.put("description", cfg.getDescription());
            item.put("default", e.getKey().equals(properties.getDefaultArm()));
            list.add(item);
        }
        return list;
    }

    /**
     * 假算法端收到的调用流水。
     *
     * <p><b>只在模拟模式下有意义</b> —— 真实 C# 端我们看不到它内部收到了什么，
     * 只能看 ICE 返回码和算法端自己的日志。
     *
     * @return 流水
     */
    public Map<String, Object> receivedCalls() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> calls = MockAlgorithmServer.receivedCalls();
        result.put("total", calls.size());
        result.put("calls", calls);
        result.put("hint", "这个流水只有内置假算法端才有。真实 C# 端内部收到了什么，"
                + "要看算法端自己的日志。");
        return result;
    }

    /**
     * 清空假算法端的调用流水。
     *
     * @return 操作结果
     */
    public Map<String, Object> clearReceivedCalls() {
        MockAlgorithmServer.clearReceivedCalls();
        return Map.of("success", true, "message", "已清空假算法端调用流水");
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
}
