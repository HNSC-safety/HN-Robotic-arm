package com.hbirdedge.hnroboticarm.web;

import QNEdge.EDirection;
import QNEdge.EErrorCode;
import com.hbirdedge.hnroboticarm.ice.client.IceTarget;
import com.hbirdedge.hnroboticarm.ice.service.ArmControlService;
import com.hbirdedge.hnroboticarm.ice.service.IceHealthService;
import com.hbirdedge.hnroboticarm.ice.service.IceTestModeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ICE 自测接口（9988）。
 *
 * <p><b>核心：一个 {@code real} 参数决定连谁</b>
 * <ul>
 *   <li>{@code real=false} → <b>模拟</b>：连应用内置的假算法端，不用装 C# 程序</li>
 *   <li>{@code real=true} → <b>真实</b>：连现场那台 C# 算法端</li>
 * </ul>
 *
 * <pre>
 *   GET  /api/ice/check?real=false              —— 对算法端做三层探测：端口/暗号/接口类型
 *   GET  /api/ice/check/local                   —— 检查我们本地回调端口(10001)是否正常应答
 *   GET  /api/ice/probe?real=true               —— 挨个试候选暗号，把能通的那个探出来
 *
 *   GET  /api/ice/test/status?real=false        —— 查看目标地址、暗号、连通状态
 *   GET  /api/ice/test/received                 —— 查假算法端收到的调用流水
 *   POST /api/ice/test/received/clear           —— 清空流水
 *
 *   POST /api/ice/test/arm/move?direction=Down&amp;speed=5&amp;real=false
 *                                                  —— 机械臂移动（方向 + 速度 + 真机还是模拟）
 *   POST /api/ice/test/arm/sweep?speed=5&amp;real=false
 *                                                  —— 机械臂四个方向扫一遍
 *
 *   POST /api/ice/test/message?direction=Left&amp;speed=5&amp;real=true
 *                                                  —— 发一条真实业务消息过去
 *   POST /api/ice/test/complex?real=true         —— 发一条带嵌套结构的消息
 * </pre>
 *
 * @author hbird
 * @since 2026-11-03
  *
 */
@RestController
@RequestMapping("/api/ice")
@Tag(name = "ice-test-controller", description = "ICE 自测接口 —— 真机/模拟切换、暗号探测、连通性检查")
public class IceTestController {

    private final IceHealthService iceHealthService;
    private final IceTestModeService testModeService;
    private final ArmControlService armControlService;
    private final com.hbirdedge.hnroboticarm.ice.config.IceProperties properties;

    public IceTestController(IceHealthService iceHealthService,
                             IceTestModeService testModeService,
                             ArmControlService armControlService,
                             com.hbirdedge.hnroboticarm.ice.config.IceProperties properties) {
        this.iceHealthService = iceHealthService;
        this.testModeService = testModeService;
        this.armControlService = armControlService;
        this.properties = properties;
    }

    /**
     * 对算法端做完整自检。
     *
     * @param real true = 真实算法端；false = 内置假算法端
     * @return 每个暗号的连通结果
     */
    @GetMapping("/check")
    @Operation(summary = "对算法端做三层自检", description = "依次检查端口通不通、暗号对不对、对端实现了哪些 Slice 接口")
    public Map<String, Object> check(
            @Parameter(description = "true=真实机 C# 算法端，false=内置假算法端")
            @RequestParam(defaultValue = "false") boolean real) {
        return iceHealthService.check(real);
    }

    /**
     * 检查本地回调端口。
     *
     * @return 检查结果
     */
    @GetMapping("/check/local")
    @Operation(summary = "检查本地回调端口", description = "确认 10001 端口在监听，SMTEvent 暗号能应答，并列出实现的多接口")
    public Map<String, Object> checkLocal() {
        return iceHealthService.checkLocalCallback();
    }

    /**
     * 挨个试候选暗号 —— 不知道 C# 端准确暗号时用这个把它探出来。
     *
     * @param real true = 真实算法端；false = 内置假算法端
     * @return 探测结果
     */
    @GetMapping("/probe")
    @Operation(summary = "挨个试候选暗号", description = "不知道算法端准确暗号时用它探出来 —— OK 表示该暗号可用")
    public Map<String, Object> probe(
            @Parameter(description = "true=真实机 C# 算法端，false=内置假算法端")
            @RequestParam(defaultValue = "false") boolean real) {
        return iceHealthService.probeIdentities(real);
    }

    // ══════════════════════════════════════════════════════════════════
    // 目标状态
    // ══════════════════════════════════════════════════════════════════

    /**
     * 查看目标状态（地址、暗号、连通情况）。
     *
     * @param real true = 真实算法端；false = 内置假算法端
     * @return 状态
     */
    @GetMapping("/test/status")
    @Operation(summary = "查看目标状态", description = "目标地址、暗号、连通情况、机械臂清单")
    public Map<String, Object> status(
            @Parameter(description = "true=真实机 C# 算法端，false=内置假算法端")
            @RequestParam(defaultValue = "false") boolean real) {
        return testModeService.status(real);
    }

    /**
     * 机械臂清单 —— <b>"我控制的是哪条臂"一目了然</b>。
     *
     * <p>每项：臂名 / 它连的端点(host:port) / 暗号 / 说明 / 是否默认臂。
     *
     * <p>配合 {@code ?arm=臂名} 使用。
     * 先查清单拿到臂名，再把它传给移动、点击、拧钥匙接口。
     *
     * @return 机械臂清单
     */
    @GetMapping("/arms")
    @Operation(summary = "机械臂清单", description = "每条臂的名字 / 它连的端点 / 暗号 / 说明，配合 ?arm=臂名 使用")
    public Map<String, Object> arms() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("arms", testModeService.armList());
        result.put("defaultArm", properties.getDefaultArm());
        result.put("hint", "Slice 协议里没有臂字段，臂标识通过 Ice Context 的 strArmID 传给对端。"
                + "（C# 端：current.ctx[\"strArmID\"]）。不配 ice.arms = 单臂场景。");
        return result;
    }

    /**
     * 查假算法端收到的调用流水。
     *
     * <p>用途：<b>验证"消息真的送到了对端"</b>，不用盯着控制台。
     *
     * @return 流水
     */
    @GetMapping("/test/received")
    @Operation(summary = "查假算法端收到的调用流水", description = "验证「消息真的送到了对端」，不用盯着控制台")
    public Map<String, Object> received() {
        return testModeService.receivedCalls();
    }

    /**
     * 清空假算法端调用流水。
     *
     * @return 操作结果
     */
    @PostMapping("/test/received/clear")
    @Operation(summary = "清空假算法端调用流水")
    public Map<String, Object> clearReceived() {
        return testModeService.clearReceivedCalls();
    }

    // ══════════════════════════════════════════════════════════════════
    // 机械臂移动
    // ══════════════════════════════════════════════════════════════════

    /**
     * 机械臂移动 —— <b>方向由参数指定，真机还是模拟也由参数指定</b>。
     *
     * <pre>
     *   模拟：POST /api/ice/test/arm/move?direction=Down&amp;speed=5&amp;real=false
     *   真机：POST /api/ice/test/arm/move?direction=Down&amp;speed=5&amp;real=true
     *   指定臂：POST /api/ice/test/arm/move?direction=Down&amp;speed=5&amp;real=true&amp;arm=arm-a
     * </pre>
     *
     * <p><b>注意</b>：{@code real=true} 时机械臂会真的动。
     *
     * @param direction 方向，支持枚举名（Up/Down/Left/Right）和中文口语（从上往下 等）
     * @param speed     速度
     * @param real      true = 真实算法端；false = 内置假算法端
     * @return 算法端返回的错误码
     */
    @PostMapping("/test/arm/move")
    @Operation(summary = "机械臂移动", description = """
            下发一次 MoveTask(方向、速度)。

            ⚠️ **real=true 时机械臂会真的动！**

            `direction` 支持枚举名（Up/Down/Left/Right）和中文口语（从上往下等）。

            方向和 Slice 枚举的对应关系：
            | 中文 | 枚举 | wire 值 |
            |---|---|---|
            | 从上往下 | Down | 3 |
            | 从下往上 | Up | 2 |
            | 从左往右 | Right | 1 |
            | 从右往左 | Left | 0 |
            """)
    public Map<String, Object> moveArm(
            @Parameter(description = "移动方向：Up/Down/Left/Right，或中文 从上往下/从下往上/从左往右/从右往左", example = "Down")
            @RequestParam(defaultValue = "Left") String direction,
            @Parameter(description = "移动速度", example = "5")
            @RequestParam(defaultValue = "5") int speed,
            @Parameter(description = "true=真实算法端（机械臂真动），false=内置假算法端")
            @RequestParam(defaultValue = "false") boolean real,
            @Parameter(description = "机械臂名，不传就是默认臂。臂名会通过 Ice Context 的 strArmID 传给对端。")
            @RequestParam(required = false) String arm) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("real", real);
        result.put("direction", direction);
        result.put("speed", speed);
        result.put("arm", arm);

        EErrorCode code = armControlService.moveTask(parseDirection(direction), speed, arm, real);
        result.put("code", code.name());
        result.put("success", code == EErrorCode.EECSuccess);
        return result;
    }

    /**
     * 机械臂四个方向扫一遍 —— <b>从上往下 → 从下往上 → 从左往右 → 从右往左</b>。
     *
     * <pre>
     *   POST /api/ice/test/arm/sweep?speed=5&amp;real=false
     *   POST /api/ice/test/arm/sweep?speed=5&amp;real=true&amp;arm=arm-a
     * </pre>
     *
     * <p>一条命令测完四个方向，返回每一次的 ICE 返回码。
     *
     * @param speed 速度
     * @param real  true = 真实算法端；false = 内置假算法端
     * @return 每一方向的执行结果
     */
    @PostMapping("/test/arm/sweep")
    @Operation(summary = "机械臂四个方向扫一遍", description = """
            顺序固定为：从上往下/从下往上/从左往右/从右往左。

            一次命令测完四个方向，返回每一次的 ICE 返回码。

            ⚠️ **real=true 时机械臂会真的动！**
            """)
    public Map<String, Object> sweepArm(
            @Parameter(description = "移动速度", example = "5")
            @RequestParam(defaultValue = "5") int speed,
            @Parameter(description = "true=真实算法端（机械臂真动），false=内置假算法端")
            @RequestParam(defaultValue = "false") boolean real,
            @Parameter(description = "机械臂名，不传就是默认臂")
            @RequestParam(required = false) String arm) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("real", real);
        result.put("speed", speed);
        result.put("arm", arm);

        List<Map<String, Object>> steps = new ArrayList<>();
        // 顺序就是"从上往下 → 从下往上 → 从左往右 → 从右往左"
        EDirection[] order = {EDirection.Down, EDirection.Up, EDirection.Right, EDirection.Left};
        String[] labels = {"从上往下", "从下往上", "从左往右", "从右往左"};

        boolean allOk = true;
        for (int i = 0; i < order.length; i++) {
            EDirection dir = order[i];
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("label", labels[i]);
            step.put("direction", dir.name());
            step.put("speed", speed);
            try {
                EErrorCode code = armControlService.moveTask(dir, speed, arm, real);
                step.put("code", code.name());
                step.put("success", code == EErrorCode.EECSuccess);
                if (code != EErrorCode.EECSuccess) {
                    allOk = false;
                }
            } catch (Exception e) {
                step.put("success", false);
                step.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                allOk = false;
            }
            steps.add(step);
        }

        result.put("steps", steps);
        result.put("allSuccess", allOk);
        return result;
    }

    // ══════════════════════════════════════════════════════════════════
    // 消息收发测试
    // ══════════════════════════════════════════════════════════════════

    /**
     * 发一条真实业务消息，看对端能不能收到。
     *
     * @param direction 方向，支持枚举名（Up/Down/Left/Right）和中文口语（从上往下 等）
     * @param speed     速度
     * @param real      true = 真实算法端；false = 内置假算法端
     * @return 对端返回的错误码
     */
    @PostMapping("/test/message")
    @Operation(summary = "发一条真实业务消息", description = "发一次 MoveTask 过去，验证对端 servant 真的能收到并处理")
    public Map<String, Object> sendMessage(
            @Parameter(description = "移动方向", example = "Left")
            @RequestParam(defaultValue = "Left") String direction,
            @Parameter(description = "移动速度", example = "5")
            @RequestParam(defaultValue = "5") int speed,
            @Parameter(description = "true=真实机 C# 算法端，false=内置假算法端")
            @RequestParam(defaultValue = "false") boolean real) {
        return iceHealthService.sendTestMessage(parseDirection(direction), speed, real);
    }

    /**
     * 发一条带数组 + 嵌套 struct 的消息，验证复杂参数序列化。
     *
     * @param deviceId   设备 ID
     * @param deviceName 设备名称
     * @param real       true = 真实算法端；false = 内置假算法端
     * @return 对端返回的错误码
     */
    @PostMapping("/test/complex")
    @Operation(summary = "发一条带嵌套结构的消息", description = "验证复杂参数（数组 + 嵌套 struct）序列化正确")
    public Map<String, Object> sendComplex(
            @Parameter(description = "设备 ID", example = "DEV-001")
            @RequestParam(defaultValue = "DEV-001") String deviceId,
            @Parameter(description = "设备名称", example = "测试设备")
            @RequestParam(defaultValue = "测试设备") String deviceName,
            @Parameter(description = "true=真实机 C# 算法端，false=内置假算法端")
            @RequestParam(defaultValue = "false") boolean real) {
        return iceHealthService.sendComplexMessage(deviceId, deviceName, real);
    }

    /**
     * 方向解析 —— 同时支持枚举名和中文口语说法。
     *
     * <pre>
     *   Up / Down / Left / Right        枚举原名
     *   从上往下 / 向下 / 下              ↓
     *   从下往上 / 向上 / 上              ↑
     *   从左往右 / 向右 / 右              →
     *   从右往左 / 向左 / 左              ←
     * </pre>
     *
     * <p>现场调试直接敲中文更省事，不用记枚举名。
     *
     * @param raw 原始方向文本
     * @return 方向枚举
     * @throws IllegalArgumentException 认不出来时，消息里列出所有合法值
     */
    private EDirection parseDirection(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("direction 不能为空。可选值：" + directionOptions());
        }
        String v = raw.trim();

        // 先按枚举名试（大小写不敏感，方便手敲）
        for (EDirection d : EDirection.values()) {
            if (d.name().equalsIgnoreCase(v)) {
                return d;
            }
        }

        // 再按中文口语
        switch (v) {
            case "从上往下", "向下", "往下", "下":
                return EDirection.Down;
            case "从下往上", "向上", "往上", "上":
                return EDirection.Up;
            case "从左往右", "向右", "往右", "右":
                return EDirection.Right;
            case "从右往左", "向左", "往左", "左":
                return EDirection.Left;
            default:
                throw new IllegalArgumentException(
                        "认不出方向 '" + raw + "'。可选值：" + directionOptions());
        }
    }

    /**
     * 合法方向值提示（给异常信息用）。
     *
     * @return 提示文本
     */
    private String directionOptions() {
        return "Up/Down/Left/Right，或中文：从上往下、从下往上、从左往右、从右往左";
    }
}
