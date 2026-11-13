package com.hbirdedge.hnroboticarm.mock;

import QNEdge.EDirection;
import QNEdge.EFireControlMode;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 假算法端收到的指令记录。
 *
 * <p>作用：<b>让"消息真的送到了对端"这件事可以被程序断言</b>，
 * 而不是只能盯着控制台看。
 *
 * <p>用法：
 * <ul>
 *   <li>REST：{@code GET /api/ice/test/received}</li>
 *   <li>测试：直接断言 {@link #lastDirection()} 等</li>
 * </ul>
 *
 * @author hbird
 * @since 2026-11-03
 */
public final class MockAlgorithmRecorder {

    /** MoveTask 流水记录里"最近一次"没有值时的速度占位 */
    private static final int SPEED_NOT_SET = -1;

    /** MoveTask 流水记录里"最近一次"没有值时的设备数量占位 */
    private static final int DEVICE_COUNT_NOT_SET = -1;

    private MockAlgorithmRecorder() {
    }

    /** MoveTask 的调用流水（每条形如 "Up:5"） */
    private static final List<String> MOVE_TASK_CALLS = new CopyOnWriteArrayList<>();

    /** 最近一次 MoveTask 的方向 */
    private static final AtomicReference<EDirection> LAST_DIRECTION = new AtomicReference<>();

    /** 最近一次 MoveTask 的速度 */
    private static final AtomicInteger LAST_SPEED = new AtomicInteger(SPEED_NOT_SET);

    /**
     * 最近一次 MoveTask 收到的<b>机械臂标识</b>（来自 Ice Context 的 {@code strArmID}）。
     *
     * <p><b>这是"我们到底控制的是哪条臂"的可断言证据</b> ——
     * Slice 里没有臂字段，所以臂标识走 Context。
     */
    private static final AtomicReference<String> LAST_ARM_ID = new AtomicReference<>();

    /** 最近一次 ClickTask 的按钮文本 */
    private static final AtomicReference<String> LAST_CLICKED_BUTTONS = new AtomicReference<>();

    /** 最近一次 TurnKey 的模式 */
    private static final AtomicReference<EFireControlMode> LAST_TURN_KEY_MODE = new AtomicReference<>();

    /** 最近一次 SimulationClickGetCoordinates 的按钮文本 */
    private static final AtomicReference<String> LAST_SIMULATED_BUTTON = new AtomicReference<>();

    /** 最近一次 StartAllDeviceALG 收到的设备数量 */
    private static final AtomicInteger LAST_DEVICE_COUNT = new AtomicInteger(DEVICE_COUNT_NOT_SET);

    /** 最近一次 Config 收到的 URL 列表 */
    private static final AtomicReference<String[]> LAST_CONFIG_URLS = new AtomicReference<>(new String[0]);

    /**
     * MoveTask 调用流水。
     *
     * @return 流水副本
     */
    public static List<String> moveTaskCalls() {
        return List.copyOf(MOVE_TASK_CALLS);
    }

    /**
     * 最近一次 MoveTask 的方向。
     *
     * @return 方向；没有记录时为 null
     */
    public static EDirection lastDirection() {
        return LAST_DIRECTION.get();
    }

    /**
     * 最近一次 MoveTask 的速度。
     *
     * @return 速度；没有记录时为 -1
     */
    public static int lastSpeed() {
        return LAST_SPEED.get();
    }

    /**
     * 最近一次 MoveTask 收到的机械臂标识（来自 Ice Context）。
     *
     * @return 臂标识；没带时为 null
     */
    public static String lastArmId() {
        return LAST_ARM_ID.get();
    }

    /**
     * 最近一次 ClickTask 的按钮文本。
     *
     * @return 按钮文本；没记录时为 null
     */
    public static String lastClickedButtons() {
        return LAST_CLICKED_BUTTONS.get();
    }

    /**
     * 最近一次 TurnKey 的模式。
     *
     * @return 模式；没记录时为 null
     */
    public static EFireControlMode lastTurnKeyMode() {
        return LAST_TURN_KEY_MODE.get();
    }

    /**
     * 最近一次 SimulationClickGetCoordinates 的按钮文本。
     *
     * @return 按钮文本；没记录时为 null
     */
    public static String lastSimulatedButton() {
        return LAST_SIMULATED_BUTTON.get();
    }

    /**
     * 最近一次 StartAllDeviceALG 的设备数量。
     *
     * @return 设备数量；没记录时为 -1
     */
    public static int lastDeviceCount() {
        return LAST_DEVICE_COUNT.get();
    }

    /**
     * 最近一次 Config 收到的 URL 列表。
     *
     * @return URL 数组副本
     */
    public static String[] lastConfigUrls() {
        String[] urls = LAST_CONFIG_URLS.get();
        return urls == null ? new String[0] : urls.clone();
    }

    /**
     * 只清空 MoveTask 流水（其他记录不动）。
     *
     * <p>用来断言"这一轮连发的几条 MoveTask 顺序对不对"。
     */
    public static void resetMoveTask() {
        MOVE_TASK_CALLS.clear();
        LAST_DIRECTION.set(null);
        LAST_SPEED.set(SPEED_NOT_SET);
    }

    /**
     * 只重置"最近一次"的值，<b>不清流水</b>。
     *
     * <p>用途：连发多条 MoveTask 时，每条之前调这个 ——
     * 如果误用 {@link #resetMoveTask()} 会把前面的流水也清掉，
     * 导致"顺序"断言只能看到最后一条。
     */
    public static void resetLastMove() {
        LAST_DIRECTION.set(null);
        LAST_SPEED.set(SPEED_NOT_SET);
    }

    /**
     * 清空所有记录 —— 每次测之前调一下，断言就只看"这一轮"收到的东西。
     */
    public static void reset() {
        MOVE_TASK_CALLS.clear();
        LAST_DIRECTION.set(null);
        LAST_SPEED.set(SPEED_NOT_SET);
        LAST_ARM_ID.set(null);
        LAST_CLICKED_BUTTONS.set(null);
        LAST_TURN_KEY_MODE.set(null);
        LAST_SIMULATED_BUTTON.set(null);
        LAST_DEVICE_COUNT.set(DEVICE_COUNT_NOT_SET);
        LAST_CONFIG_URLS.set(new String[0]);
    }

    /**
     * 只重置设备数量。
     */
    public static void resetDeviceCount() {
        LAST_DEVICE_COUNT.set(DEVICE_COUNT_NOT_SET);
    }

    /**
     * 只重置点击记录。
     */
    public static void resetClick() {
        LAST_CLICKED_BUTTONS.set(null);
    }

    /**
     * 只重置拧钥匙记录。
     */
    public static void resetTurnKey() {
        LAST_TURN_KEY_MODE.set(null);
    }

    /**
     * 只重置模拟点击记录。
     */
    public static void resetSimulatedClick() {
        LAST_SIMULATED_BUTTON.set(null);
    }

    /**
     * 只重置配置记录。
     */
    public static void resetConfig() {
        LAST_CONFIG_URLS.set(new String[0]);
    }

    /**
     * 记录一次 MoveTask。
     *
     * @param direction 方向
     * @param speed     速度
     */
    static void recordMoveTask(EDirection direction, int speed) {
        LAST_DIRECTION.set(direction);
        LAST_SPEED.set(speed);
        MOVE_TASK_CALLS.add(direction.name() + ":" + speed);
    }

    /**
     * 记录收到的机械臂标识（来自 Ice Context）。
     *
     * @param armId 臂标识；null 表示这次请求没带臂标识
     */
    static void recordArmId(String armId) {
        LAST_ARM_ID.set(armId);
    }

    /**
     * 记录一次 ClickTask。
     *
     * @param buttons 按钮文本
     */
    static void recordClickTask(String buttons) {
        LAST_CLICKED_BUTTONS.set(buttons);
    }

    /**
     * 记录一次 TurnKey。
     *
     * @param mode 拧钥匙模式
     */
    static void recordTurnKey(EFireControlMode mode) {
        LAST_TURN_KEY_MODE.set(mode);
    }

    /**
     * 记录一次 SimulationClickGetCoordinates。
     *
     * @param buttonText 按钮文本
     */
    static void recordSimulatedClick(String buttonText) {
        LAST_SIMULATED_BUTTON.set(buttonText);
    }

    /**
     * 记录一次 StartAllDeviceALG 的设备数量。
     *
     * @param count 设备数量
     */
    static void recordDeviceCount(int count) {
        LAST_DEVICE_COUNT.set(count);
    }

    /**
     * 记录一次 Config 的 URL 列表。
     *
     * @param urls URL 列表；可为 null
     */
    static void recordConfigUrls(String[] urls) {
        LAST_CONFIG_URLS.set(urls == null ? new String[0] : urls.clone());
    }
}
