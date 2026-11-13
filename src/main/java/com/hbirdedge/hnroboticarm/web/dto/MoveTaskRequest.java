package com.hbirdedge.hnroboticarm.web.dto;

/**
 * 机械手臂移动请求。
 *
 * <p>{@code arm} —— 机械臂名。<b>不传就是默认那条臂</b>
 * （{@code ice.default-arm}，没配则用 {@code ice.alg} 的端点）。
 *
 * @author hbird
 * @since 2026-11-03
  *
 */
public class MoveTaskRequest {

    /** 移动方向：Up / Down / Left / Right */
    private String direction;

    /**
     * 移动速度。
     *
     * <p><b>用包装类型 {@code Integer} 而不是 {@code int}</b> —— 不传时为 {@code null}，
     * 调用方能区分"没传速度"和"速度为 0"。
     */
    private Integer speed;

    /** 机械臂名（不传 = 默认臂） */
    private String arm;

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public Integer getSpeed() {
        return speed;
    }

    public void setSpeed(Integer speed) {
        this.speed = speed;
    }

    public String getArm() {
        return arm;
    }

    public void setArm(String arm) {
        this.arm = arm;
    }

    @Override
    public String toString() {
        return "MoveTaskRequest{"
                + "direction='" + direction + '\''
                + ", speed=" + speed
                + ", arm='" + arm + '\''
                + '}';
    }
}
