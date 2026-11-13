package com.hbirdedge.hnroboticarm.ice.service;

import Ice.Communicator;
import Ice.ObjectPrx;
import QNEdge.IQNEAlgHandlePrx;
import QNEdge.IQNEArmControlPrx;
import com.hbirdedge.hnroboticarm.ice.client.IceTarget;
import com.hbirdedge.hnroboticarm.ice.client.QNEdgeIceClient;
import com.hbirdedge.hnroboticarm.ice.config.IceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 连通性自检。
 *
 * <p>{@code QNEdge.ice} 里没有自定义的 ping 方法，但 ICE 协议本身自带
 * {@code ice_ping()} / {@code ice_ids()} / {@code ice_isA()}——任何 servant 都自动支持。
 * 用它们可以分三层确认：
 *
 * <ol>
 *     <li><b>端口通不通</b>：连不上会抛 {@code ConnectionRefusedException}</li>
 *     <li><b>暗号对不对</b>：暗号不存在会抛 {@code ObjectNotExistException}</li>
 *     <li><b>接口类型对不对</b>：{@code ice_ids()} 会列出对端真正实现的 Slice 类型</li>
 * </ol>
 *
 * <p><b>一个方向只有一个暗号</b>，所以自检也只探一个目标：
 * 用探测出来的暗号连上去，看它实现了哪些接口 —— 那才是"我们能调什么"的真相。
 *
 * @author hbird
 * @since 2026-11-03
 */
@Service
public class IceHealthService {

    private static final Logger log = LoggerFactory.getLogger(IceHealthService.class);

    /**
     * 机械臂接口的 Slice 类型 ID（两种模块都认）。
     *
     * <p>{@code QNEdge} 是本项目用的；
     * {@code SMT} 是现场 C# 算法端 {@code HNServer.dll} 里的（见 ice-real-test-guide.md）。
     *
     * <p>⚠️ 注意：<b>Slice 模块名不同 = 方法签名不同 = 调不通</b>。
     * 对端实现 {@code ::SMT::ISMMSEvent} 时，我们发 {@code ::QNEdge::IQNEEvent}
     * 的请求它根本不认识。这里认两种只是为了让自检能<b>如实报告</b>
     * "对端实现的是 SMT 模块"，而不是笼统地说"类型不匹配"。
     */
    private static final String[] ARM_CONTROL_TYPE_IDS = {
            "::QNEdge::IQNEArmControl",
            "::SMT::ISMMSArmControl"
    };

    /** 算法接口的 Slice 类型 ID（两种模块都认） */
    private static final String[] ALG_HANDLE_TYPE_IDS = {
            "::QNEdge::IQNEAlgHandle",
            "::SMT::ISMMSAlgHandle"
    };

    /** 本项目使用的 Slice 模块名 */
    private static final String OWN_SLICE_MODULE = "QNEdge";

    /** 现场 C# 算法端使用的 Slice 模块名 */
    private static final String FIELD_SLICE_MODULE = "SMT";

    private final Communicator communicator;
    private final QNEdgeIceClient iceClient;
    private final IceProperties properties;

    public IceHealthService(Communicator communicator, QNEdgeIceClient iceClient, IceProperties properties) {
        this.communicator = communicator;
        this.iceClient = iceClient;
        this.properties = properties;
    }

    /**
     * 对算法端做一次完整自检。
     *
     * @param real true = 真实 C# 算法端；false = 内置假算法端
     * @return 每个暗号的连通结果
     */
    public Map<String, Object> check(boolean real) {
        IceTarget target = iceClient.resolve(real);
        IceProperties.Alg alg = properties.getAlg();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("endpoint", target.getDescription());
        result.put("real", real);

        List<Map<String, Object>> targets = new ArrayList<>();
        for (String identity : alg.allIdentities()) {
            targets.add(probe(identity, real));
        }

        result.put("resolvedIdentity", target.getResolvedIdentity());
        result.put("targets", targets);
        result.put("allUp", target.isReachable());
        result.put("remoteTypeIds", target.getRemoteTypeIds());

        // Slice 模块对不上 —— 这是"连得上但调不通"最常见的原因
        String remoteModule = detectModule(target.getRemoteTypeIds().toArray(new String[0]));
        if (remoteModule != null) {
            result.put("remoteSliceModule", remoteModule);
            if (!OWN_SLICE_MODULE.equals(remoteModule)) {
                result.put("sliceModuleMismatch", true);
                result.put("hint", "对端实现的 Slice 模块是 '" + remoteModule
                        + "'，本项目用 '" + OWN_SLICE_MODULE + "'。"
                        + "Slice 模块不同 = 方法签名不同 = 调不通。"
                        + "机械臂指令（MoveTask）需要用 QNEdge.ice 重新编译 C# 端。");
            }
        }
        return result;
    }

    /**
     * 挨个试所有候选暗号，看哪个能通。
     *
     * <p>用途：<b>不知道 C# 端的准确暗号时，用这个接口把它探出来。</b>
     * ICE 没有「列出某 endpoint 上所有 identity」的 API（{@code ice_ids()} 查的是类型 ID），
     * 所以只能靠候选名单挨个 {@code ice_ping()} 试。
     *
     * @param real true = 真实 C# 算法端；false = 内置假算法端
     * @return 每个候选暗号的探测结果
     */
    public Map<String, Object> probeIdentities(boolean real) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("real", real);
        result.put("candidates", iceClient.probeCandidates(real));
        result.put("hint", "把能 OK 的那个填回 application.yaml 的 ice.alg.identity，"
                + "或加进 ice.alg.fallback-identities");
        return result;
    }

    /**
     * 发一条真实业务消息（MoveTask）过去，验证对端 servant 真的能收到并处理。
     *
     * @param direction 方向
     * @param speed     速度
     * @param real      true = 真实 C# 算法端；false = 内置假算法端
     * @return 对端返回的错误码
     */
    public Map<String, Object> sendTestMessage(QNEdge.EDirection direction, int speed, boolean real) {
        Map<String, Object> result = new LinkedHashMap<>();
        IceTarget target = iceClient.resolve(real);
        IQNEArmControlPrx prx = target.getArmControl();
        if (prx == null) {
            result.put("success", false);
            result.put("error", "ALG_SERVER_UNREACHABLE");
            result.put("message", "连不上算法端（" + target.getDescription() + "）。"
                    + "已探测的暗号：" + target.getTriedIdentities()
                    + "。请确认算法端已启动、暗号拼写正确，或调用 GET /api/ice/probe 查看逐个探测结果。");
            return result;
        }
        try {
            QNEdge.EErrorCode code = prx.MoveTask(direction, speed);
            result.put("success", code == QNEdge.EErrorCode.EECSuccess);
            result.put("code", code.name());
            result.put("message", "对端 servant 已收到 MoveTask(" + direction + ", " + speed + ") 并返回");
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    /**
     * 发一条带完整结构体的消息（StartAllDeviceALG），验证复杂参数（数组 + 嵌套 struct）能正确序列化。
     *
     * @param deviceId   设备 ID
     * @param deviceName 设备名称
     * @param real       true = 真实 C# 算法端；false = 内置假算法端
     * @return 对端返回的错误码
     */
    public Map<String, Object> sendComplexMessage(String deviceId, String deviceName, boolean real) {
        Map<String, Object> result = new LinkedHashMap<>();
        IceTarget target = iceClient.resolve(real);
        IQNEAlgHandlePrx prx = target.getAlgHandle();
        if (prx == null) {
            result.put("success", false);
            result.put("error", "ALG_SERVER_UNREACHABLE");
            result.put("message", "连不上算法端（" + target.getDescription() + "）。"
                    + "已探测的暗号：" + target.getTriedIdentities()
                    + "。请确认算法端已启动、暗号拼写正确，或调用 GET /api/ice/probe 查看逐个探测结果。");
            return result;
        }
        try {
            QNEdge.SDevice device = new QNEdge.SDevice();
            device.strID = deviceId;
            device.strName = deviceName;
            device.strIP = "192.168.1.100";
            device.strPort = "554";
            device.strRtspUrl = "rtsp://192.168.1.100:554/stream";
            device.strUserName = "admin";
            device.strPassword = "admin";

            QNEdge.EErrorCode code = prx.StartAllDeviceALG(new QNEdge.SDevice[]{device});
            result.put("success", code == QNEdge.EErrorCode.EECSuccess);
            result.put("code", code.name());
            result.put("message", "对端 servant 已收到 StartAllDeviceALG，设备 " + deviceId);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    /**
     * 检查我们本地的回调端口（10001）是否在监听，且暗号能应答 ice_ping。
     *
     * <p>本地只注册了 1 个 servant，所以只探 1 个暗号。
     * 但要额外确认它<b>确实实现了 3 个接口</b> —— multi-interface 的关键校验点。
     *
     * @return 检查结果
     */
    public Map<String, Object> checkLocalCallback() {
        Map<String, Object> result = new LinkedHashMap<>();
        IceProperties.Local local = properties.getLocal();

        try {
            ObjectPrx prx = communicator.stringToProxy(
                    local.getIdentity() + ":default -h 127.0.0.1 -p " + local.getPort());
            prx.ice_ping();

            String[] ids = prx.ice_ids();
            result.put("reachable", true);
            result.put("port", local.getPort());
            result.put("identity", local.getIdentity());
            result.put("ids", Arrays.asList(ids));
            result.put("implementsEvent", contains(ids, "::QNEdge::IQNEEvent"));
            result.put("implementsFchInfo", contains(ids, "::QNEdge::IQNEFCHInfo"));
            result.put("implementsConfig", contains(ids, "::QNEdge::IQNEConfig"));
            result.put("status", "OK");
            result.put("message", "本地回调端口 " + local.getPort() + " 正常应答");
        } catch (Exception e) {
            result.put("reachable", false);
            result.put("status", "ERROR");
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    /**
     * 单个暗号的三层探测：ping → ids → isA。
     *
     * @param identity 暗号
     * @param real     true = 真实 C# 算法端；false = 内置假算法端
     * @return 探测结果
     */
    private Map<String, Object> probe(String identity, boolean real) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("identity", identity);

        IceProperties.Alg alg = properties.getAlg();
        String host = real ? alg.getHost() : "127.0.0.1";
        int port = real ? alg.getPort() : properties.getTest().mockPortOrDefault(alg.getPort());

        ObjectPrx prx;
        try {
            prx = communicator.stringToProxy(identity + ":default -h " + host + " -p " + port);
        } catch (Exception e) {
            item.put("reachable", false);
            item.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return item;
        }

        try {
            // 第 1 层：连通性（TCP + 暗号存在）
            prx.ice_ping();
            item.put("reachable", true);

            // 第 2 层：对端实现了哪些 Slice 类型
            String[] ids = prx.ice_ids();
            item.put("ids", Arrays.asList(ids));

            // 第 3 层：我们要的接口在不在里面
            boolean armMatched = containsAny(ids, ARM_CONTROL_TYPE_IDS);
            boolean algMatched = containsAny(ids, ALG_HANDLE_TYPE_IDS);
            item.put("hasArmControl", armMatched);
            item.put("hasAlgHandle", algMatched);
            item.put("status", (armMatched || algMatched) ? "OK" : "TYPE_MISMATCH");

            // Slice 模块对不上时，明确告诉用户"为什么用不了"
            if (!armMatched && hasForeignModule(ids)) {
                item.put("hint", "对端实现的 Slice 模块是 " + detectModule(ids)
                        + "，本项目用的是 " + OWN_SLICE_MODULE + " 模块 —— "
                        + "机械臂指令（MoveTask）对端不认识。"
                        + "需要用 QNEdge.ice 重新编译 C# 端，或让对端提供 QNEdge 版本。");
            }
        } catch (Ice.ObjectNotExistException e) {
            item.put("reachable", false);
            item.put("status", "IDENTITY_NOT_FOUND");
            item.put("error", "暗号 '" + identity + "' 在对端不存在（大小写/空格必须完全一致）");
        } catch (Ice.ConnectionRefusedException e) {
            item.put("reachable", false);
            item.put("status", "CONNECTION_REFUSED");
            item.put("error", "对端没启动，或端口不是 " + port);
            // 端口不通 = 整个端点不可达，剩下几个暗号也是同样结果 —— 别再白等
            return item;
        } catch (Ice.ConnectFailedException e) {
            item.put("reachable", false);
            item.put("status", "CONNECT_FAILED");
            item.put("error", "连不上 " + host + ":" + port + "：" + e.getMessage());
            return item;
        } catch (Ice.LocalException e) {
            item.put("reachable", false);
            item.put("status", "LOCAL_ERROR");
            item.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (Exception e) {
            item.put("reachable", false);
            item.put("status", "ERROR");
            item.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        log.info("[ICE自检] {} -> {}", identity, item);
        return item;
    }

    /**
     * 类型 ID 数组里有没有包含其中之一。
     *
     * @param arr       类型 ID 数组
     * @param candidates 候选
     * @return true 表示命中
     */
    private boolean containsAny(String[] arr, String[] candidates) {
        for (String c : candidates) {
            if (contains(arr, c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对端实现的 Slice 模块是不是"别的"模块（不是 QNEdge 也不是 SMT）。
     *
     * @param ids 类型 ID 数组
     * @return true 表示是外部模块
     */
    private boolean hasForeignModule(String[] ids) {
        String module = detectModule(ids);
        return module != null && !OWN_SLICE_MODULE.equals(module) && !FIELD_SLICE_MODULE.equals(module);
    }

    /**
     * 从类型 ID 里猜出 Slice 模块名 —— 形如 {@code ::QNEdge::IQNEArmControl} → {@code QNEdge}。
     *
     * @param ids 类型 ID 数组
     * @return 模块名；识别不出来返回 null
     */
    private String detectModule(String[] ids) {
        for (String id : ids) {
            if (id.startsWith("::") && id.indexOf("::", 2) > 0) {
                return id.substring(2, id.indexOf("::", 2));
            }
        }
        return null;
    }

    /**
     * 数组里是否包含某个值。
     *
     * @param arr   数组
     * @param value 值
     * @return true 表示包含
     */
    private boolean contains(String[] arr, String value) {
        return Arrays.asList(arr).contains(value);
    }
}
