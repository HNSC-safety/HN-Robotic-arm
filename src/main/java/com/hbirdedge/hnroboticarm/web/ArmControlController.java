package com.hbirdedge.hnroboticarm.web;

import QNEdge.EErrorCode;
import QNEdge.EDirection;
import QNEdge.EFireControlMode;
import QNEdge.SPoint;
import com.hbirdedge.hnroboticarm.ice.service.ArmControlService;
import com.hbirdedge.hnroboticarm.web.dto.ClickTaskRequest;
import com.hbirdedge.hnroboticarm.web.dto.MoveTaskRequest;
import com.hbirdedge.hnroboticarm.web.dto.PointRequest;
import com.hbirdedge.hnroboticarm.web.dto.TurnKeyRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 机械手臂控制 REST 接口（Spring Boot HTTP 端口 9988）。
 *
 * <p><b>本接口固定走真实算法端</b>（{@code ice.alg.host:port}）——
 * 它是给现场用的业务接口，不能让人误连到假端。
 * 需要跑模拟时，用 {@code POST /api/ice/test/arm/move?real=false}。
 *
 * <h2>机械臂怎么选</h2>
 * <p>所有接口都支持 {@code arm} 参数（query）或 {@code arm} 字段（JSON body）：
 * <ul>
 *   <li>不传 → 默认臂（{@code ice.default-arm}，没配则用 {@code ice.alg} 端点）</li>
 *   <li>传了 → 连 {@code ice.arms} 里这条臂自己的端点，
 *       同时把臂名放进 Ice Context（{@code strArmID}）告诉对端动哪条</li>
 * </ul>
 *
 * @author hbird
 * @since 2026-11-03
  *
 */
@RestController
@RequestMapping("/api/arm")
@Tag(name = "arm-control-controller", description = "机械臂业务接口 —— 固定走真实算法端")
public class ArmControlController {

    /** 业务接口固定走真实算法端 */
    private static final boolean USE_REAL_ALG_SERVER = true;

    /** 机械臂合法方向 —— 拼错误提示用 */
    private static final String DIRECTION_OPTIONS = "Left / Right / Up / Down";

    /** 方向解析失败时兜底的速度*/
    private static final int DEFAULT_SPEED = 0;

    private final ArmControlService armControlService;

    public ArmControlController(ArmControlService armControlService) {
        this.armControlService = armControlService;
    }

    /**
     * 控制机械手臂移动。
     *
     * <p>方向和速度两种传参方式都支持：
     * <ul>
     *     <li>JSON body：{@code POST /api/arm/move} + {@code {"direction":"Up","speed":5,"arm":"arm-a"}}</li>
     *     <li>query 参数：{@code POST /api/arm/move?direction=Up&speed=5&arm=arm-a}（body 可选）</li>
     * </ul>
     *
     * @param request   JSON body（可为 null）
     * @param direction query 参数里的方向，为空则取 body 里的
     * @param speed     query 参数里的速度，为空则取 body 里的
     * @param arm       机械臂名（query 优先于 body）
     * @return 算法端返回的错误码
     */
    @PostMapping("/move")
    @Operation(summary = "机械臂移动", description = """
            下发一次 MoveTask(方向、速度)。—— **固定走真实算法端**。

            ⚠️ **机械臂会真的动！**

            方向和 Slice 枚举的对应关系：
            | 中文 | 枚举 | wire 值 |
            |---|---|---|
            | 从上往下 | Down | 3 |
            | 从下往上 | Up | 2 |
            | 从左往右 | Right | 1 |
            | 从右往左 | Left | 0 |
            """)
    public ResponseEntity<Map<String, Object>> move(
            @Parameter(description = "JSON body，可以为 null") @RequestBody(required = false) MoveTaskRequest request,
            @Parameter(description = "方向：Up/Down/Left/Right", example = "Up")
            @RequestParam(required = false) String direction,
            @Parameter(description = "速度", example = "5")
            @RequestParam(required = false) Integer speed,
            @Parameter(description = "机械臂名，不传就是默认臂")
            @RequestParam(required = false) String arm) {

        String dirName = (direction != null && !direction.isBlank())
                ? direction
                : (request == null ? null : request.getDirection());
        Integer spd = (speed != null)
                ? speed
                : (request == null ? null : request.getSpeed());
        String armName = (arm != null && !arm.isBlank())
                ? arm
                : (request == null ? null : request.getArm());

        if (dirName == null || dirName.isBlank()) {
            throw new IllegalArgumentException("direction 不能为空，可选值：" + DIRECTION_OPTIONS);
        }

        EDirection dir;
        try {
            dir = EDirection.valueOf(dirName.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "direction '" + dirName + "' 非法，可选值：" + DIRECTION_OPTIONS);
        }

        EErrorCode code = armControlService.moveTask(dir, spd == null ? DEFAULT_SPEED : spd, armName, USE_REAL_ALG_SERVER);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.name());
        body.put("message", codeMessage(code));
        body.put("arm", armName);
        return ResponseEntity.ok(body);
    }

    /**
     * 控制机械手臂点击某按钮或某批按钮。
     *
     * <p>按钮文本和坐标都从 JSON body 来（两者是平行数组，一一对应）。
     *
     * @param request 点击请求
     * @param arm     机械臂名（query 优先于 body）
     * @return 算法端返回的错误码
     */
    @PostMapping("/click")
    @Operation(summary = "机械臂点击按钮", description = "下发一次 ClickTask —— **固定走真实算法端**。按钮文本与坐标是平行数组，一一对应")
    public ResponseEntity<Map<String, Object>> click(
            @Parameter(description = "JSON body，需包含 buttonTexts 与 coordinates")
            @RequestBody(required = false) ClickTaskRequest request,
            @Parameter(description = "机械臂名，不传就是默认臂")
            @RequestParam(required = false) String arm) {

        if (request == null) {
            throw new IllegalArgumentException("body 不能为空，需包含 buttonTexts 与 coordinates");
        }

        String[] buttonTexts = request.getButtonTexts() == null
                ? new String[0]
                : request.getButtonTexts().toArray(new String[0]);

        SPoint[] points;
        if (request.getCoordinates() == null) {
            points = new SPoint[0];
        } else {
            points = request.getCoordinates().stream()
                    .map(p -> new SPoint(p.getX() == null ? 0 : p.getX(), p.getY() == null ? 0 : p.getY()))
                    .toArray(SPoint[]::new);
        }

        String armName = (arm != null && !arm.isBlank()) ? arm : request.getArm();

        EErrorCode code = armControlService.clickTask(buttonTexts, points, armName, USE_REAL_ALG_SERVER);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.name());
        body.put("message", codeMessage(code));
        body.put("arm", armName);
        return ResponseEntity.ok(body);
    }

    /**
     * 控制机械手臂拧钥匙。
     *
     * @param request 拧钥匙请求
     * @param arm     机械臂名（query 优先于 body）
     * @return 算法端返回的错误码
     */
    @PostMapping("/turn-key")
    @Operation(summary = "机械臂拧钥匙", description = "下发一次 TurnKey —— **固定走真实算法端**。mode 取值：auto / manual")
    public ResponseEntity<Map<String, Object>> turnKey(
            @Parameter(description = "JSON body，需包含 mode：auto / manual")
            @RequestBody TurnKeyRequest request,
            @Parameter(description = "机械臂名，不传就是默认臂")
            @RequestParam(required = false) String arm) {
        EFireControlMode mode = EFireControlMode.valueOf(request.getMode());
        String armName = (arm != null && !arm.isBlank()) ? arm : request.getArm();
        EErrorCode code = armControlService.turnKey(mode, armName, USE_REAL_ALG_SERVER);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.name());
        body.put("message", codeMessage(code));
        body.put("arm", armName);
        return ResponseEntity.ok(body);
    }

    /**
     * 模拟点击某按钮获取真实坐标。
     *
     * @param buttonText 按钮文本
     * @param arm        机械臂名
     * @return 按钮坐标
     */
    @GetMapping("/coordinates")
    @Operation(summary = "模拟点击获取真实坐标", description = "下发一次 SimulationClickGetCoordinates —— **固定走真实算法端**")
    public ResponseEntity<Map<String, Object>> coordinates(
            @Parameter(description = "按钮文本", example = "启动")
            @RequestParam String buttonText,
            @Parameter(description = "机械臂名，不传就是默认臂")
            @RequestParam(required = false) String arm) {
        SPoint point = armControlService.simulationClickGetCoordinates(buttonText, arm, USE_REAL_ALG_SERVER);
        return ResponseEntity.ok(Map.of(
                "buttonText", buttonText,
                "arm", arm == null ? "" : arm,
                "x", point == null ? -1 : point.nX,
                "y", point == null ? -1 : point.nY));
    }

    /**
     * 把错误码翻译成人话。
     *
     * @param code 错误码
     * @return 说明
     */
    private String codeMessage(EErrorCode code) {
        return code == EErrorCode.EECSuccess ? "success" : code.name();
    }
}
