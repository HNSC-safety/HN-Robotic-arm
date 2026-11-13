package com.hbirdedge.hnroboticarm.ice.service;

import QNEdge.EErrorCode;
import QNEdge.EDirection;
import QNEdge.EFireControlMode;
import QNEdge.IQNEAlgHandlePrx;
import QNEdge.IQNEArmControlPrx;
import QNEdge.SDevice;
import QNEdge.SPoint;
import com.hbirdedge.hnroboticarm.ice.client.IceTarget;
import com.hbirdedge.hnroboticarm.ice.client.QNEdgeIceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 业务层：把 ICE 远程调用包装成业务方法。
 *
 * <p><b>每个方法都带 {@code real} 参数</b> —— 真机还是模拟由调用方明确指定，
 * 不靠全局开关。这样"这次调用在跟谁说话"一眼可见。
 *
 * <h2>机械臂怎么区分</h2>
 * <p>Slice 定义固定（{@code MoveTask(EDirection, int)}，没有臂标识字段），
 * 所以"动的是哪条臂"通过<b>两个渠道</b>表达：
 * <ol>
 *   <li><b>选端点</b> —— {@code arm} 参数决定连哪台算法端（{@code ice.arms} 配置）</li>
 *   <li><b>Ice Context</b> —— 臂名以 {@code strArmID} 放进请求上下文，
 *       对端从 {@code Current.ctx} 读，<b>不用改 Slice 定义</b></li>
 * </ol>
 *
 * @author hbird
 * @since 2026-11-03
 */
@Service
public class ArmControlService {

    private static final Logger log = LoggerFactory.getLogger(ArmControlService.class);

    /**
     * Ice Context 里携带机械臂标识的键名。
     *
     * <p><b>C# 端取法</b>：{@code current.ctx["strArmID"]}。
     * 键名必须完全一致（大小写敏感），否则对端读不到。
     */
    public static final String CTX_ARM_ID = "strArmID";

    private final QNEdgeIceClient iceClient;

    public ArmControlService(QNEdgeIceClient iceClient) {
        this.iceClient = iceClient;
    }

    /**
     * 控制机械手臂移动。
     *
     * @param direction 移动方向
     * @param speed     移动速度
     * @param arm       机械臂名，可为 null（单臂场景）
     * @param real      true = 真实算法端；false = 内置假算法端
     * @return 算法端返回的错误码
     */
    public EErrorCode moveTask(EDirection direction, int speed, String arm, boolean real) {
        IceTarget target = iceClient.resolve(real, arm);
        IQNEArmControlPrx prx = target.getArmControl();
        if (prx == null) {
            throw new AlgServerUnreachableException(unreachableMessage(target));
        }
        log.info("[ICE] 调用 MoveTask direction={} speed={} 机械臂={} 目标={}",
                direction, speed, target.getArmName(), target.getDescription());
        EErrorCode result = prx.MoveTask(direction, speed, ctxOf(target));
        log.info("[ICE] MoveTask 返回 {}", result);
        return result;
    }

    /**
     * 控制机械手臂点击某按钮或某批按钮。
     *
     * @param buttonTexts 按钮文本列表
     * @param coordinates 对应坐标列表
     * @param arm         机械臂名，可为 null
     * @param real        true = 真实算法端；false = 内置假算法端
     * @return 算法端返回的错误码
     */
    public EErrorCode clickTask(String[] buttonTexts, SPoint[] coordinates, String arm, boolean real) {
        IceTarget target = iceClient.resolve(real, arm);
        IQNEArmControlPrx prx = target.getArmControl();
        if (prx == null) {
            throw new AlgServerUnreachableException(unreachableMessage(target));
        }
        log.info("[ICE] 调用 ClickTask buttons={} coordinates={} 机械臂={} 目标={}",
                (Object) buttonTexts, (Object) coordinates, target.getArmName(),
                target.getDescription());
        EErrorCode result = prx.ClickTask(buttonTexts, coordinates, ctxOf(target));
        log.info("[ICE] ClickTask 返回 {}", result);
        return result;
    }

    /**
     * 控制机械手臂拧钥匙（自动/手动）。
     *
     * @param mode 拧钥匙模式
     * @param arm  机械臂名，可为 null
     * @param real true = 真实算法端；false = 内置假算法端
     * @return 算法端返回的错误码
     */
    public EErrorCode turnKey(EFireControlMode mode, String arm, boolean real) {
        IceTarget target = iceClient.resolve(real, arm);
        IQNEArmControlPrx prx = target.getArmControl();
        if (prx == null) {
            throw new AlgServerUnreachableException(unreachableMessage(target));
        }
        log.info("[ICE] 调用 TurnKey mode={} 机械臂={} 目标={}",
                mode, target.getArmName(), target.getDescription());
        EErrorCode result = prx.TurnKey(mode, ctxOf(target));
        log.info("[ICE] TurnKey 返回 {}", result);
        return result;
    }

    /**
     * 模拟点击某按钮获取真实坐标。
     *
     * @param buttonText 按钮文本
     * @param arm        机械臂名，可为 null
     * @param real       true = 真实算法端；false = 内置假算法端
     * @return 按钮坐标
     */
    public SPoint simulationClickGetCoordinates(String buttonText, String arm, boolean real) {
        IceTarget target = iceClient.resolve(real, arm);
        IQNEArmControlPrx prx = target.getArmControl();
        if (prx == null) {
            throw new AlgServerUnreachableException(unreachableMessage(target));
        }
        log.info("[ICE] 调用 SimulationClickGetCoordinates button={} 机械臂={} 目标={}",
                buttonText, target.getArmName(), target.getDescription());
        SPoint point = prx.SimulationClickGetCoordinates(buttonText, ctxOf(target));
        log.info("[ICE] SimulationClickGetCoordinates 返回 ({}, {})",
                point == null ? null : point.nX, point == null ? null : point.nY);
        return point;
    }

    /**
     * 启动所有设备算法。
     *
     * @param devices 设备列表
     * @param arm     机械臂名，可为 null
     * @param real    true = 真实算法端；false = 内置假算法端
     * @return 算法端返回的错误码
     */
    public EErrorCode startAllDeviceAlg(SDevice[] devices, String arm, boolean real) {
        IceTarget target = iceClient.resolve(real, arm);
        IQNEAlgHandlePrx prx = target.getAlgHandle();
        if (prx == null) {
            throw new AlgServerUnreachableException(unreachableMessage(target));
        }
        log.info("[ICE] 调用 StartAllDeviceALG count={} 机械臂={} 目标={}",
                devices == null ? 0 : devices.length, target.getArmName(),
                target.getDescription());
        return prx.StartAllDeviceALG(devices, ctxOf(target));
    }

    /**
     * 停止所有设备算法。
     *
     * @param arm  机械臂名，可为 null
     * @param real true = 真实算法端；false = 内置假算法端
     * @return 算法端返回的错误码
     */
    public EErrorCode stopAllDeviceAlg(String arm, boolean real) {
        IceTarget target = iceClient.resolve(real, arm);
        IQNEAlgHandlePrx prx = target.getAlgHandle();
        if (prx == null) {
            throw new AlgServerUnreachableException(unreachableMessage(target));
        }
        log.info("[ICE] 调用 StopALLDeviceALG 机械臂={} 目标={}",
                target.getArmName(), target.getDescription());
        return prx.StopALLDeviceALG(ctxOf(target));
    }

    /**
     * 启动单台设备算法。
     *
     * @param device 设备
     * @param arm    机械臂名，可为 null
     * @param real   true = 真实算法端；false = 内置假算法端
     * @return 算法端返回的错误码
     */
    public EErrorCode startSpecialDeviceAlg(SDevice device, String arm, boolean real) {
        IceTarget target = iceClient.resolve(real, arm);
        IQNEAlgHandlePrx prx = target.getAlgHandle();
        if (prx == null) {
            throw new AlgServerUnreachableException(unreachableMessage(target));
        }
        log.info("[ICE] 调用 StartSpecialDeviceALG device={} 机械臂={} 目标={}",
                device == null ? null : device.strID, target.getArmName(),
                target.getDescription());
        return prx.StartSpecialDeviceALG(device, ctxOf(target));
    }

    /**
     * 停止单台设备算法。
     *
     * @param deviceId 设备 ID
     * @param arm      机械臂名，可为 null
     * @param real     true = 真实算法端；false = 内置假算法端
     * @return 算法端返回的错误码
     */
    public EErrorCode stopSpecialDeviceAlg(String deviceId, String arm, boolean real) {
        IceTarget target = iceClient.resolve(real, arm);
        IQNEAlgHandlePrx prx = target.getAlgHandle();
        if (prx == null) {
            throw new AlgServerUnreachableException(unreachableMessage(target));
        }
        log.info("[ICE] 调用 StopSpecialDeviceALG device={} 机械臂={} 目标={}",
                deviceId, target.getArmName(), target.getDescription());
        return prx.StopSpecialDeviceALG(deviceId, ctxOf(target));
    }

    /**
     * 构造 Ice Context —— 带上机械臂标识。
     *
     * <p><b>没指定机械臂时返回 null</b>：Ice 的 {@code null} context 表示"不带上下文"，
     * 和传空 Map 效果一样，但更干净。
     *
     * @param target 目标
     * @return 上下文；无臂名时为 null
     */
    private Map<String, String> ctxOf(IceTarget target) {
        if (!target.hasArm()) {
            return null;
        }
        Map<String, String> ctx = new HashMap<>();
        ctx.put(CTX_ARM_ID, target.getArmName());
        return ctx;
    }

    /**
     * 连不上算法端时给用户看的说明。
     *
     * @param target 目标
     * @return 拼好的中文说明
     */
    private String unreachableMessage(IceTarget target) {
        return "连不上算法端（" + target.getDescription() + "）。"
                + "已探测的暗号：" + target.getTriedIdentities() + "。"
                + "请确认算法端已启动、暗号拼写正确（大小写必须完全一致），"
                + "或调用 GET /api/ice/probe 查看逐个探测结果。";
    }
}
