package com.hbirdedge.hnroboticarm.web.dto;

/**
 * 拧钥匙请求。
 *
 * @author hbird
 * @since 2026-11-03
  *
 */
public class TurnKeyRequest {

    /** 拧钥匙模式：auto / manual */
    private String mode;

    /** 机械臂名（不传 = 默认臂） */
    private String arm;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getArm() {
        return arm;
    }

    public void setArm(String arm) {
        this.arm = arm;
    }

    @Override
    public String toString() {
        return "TurnKeyRequest{"
                + "mode='" + mode + '\''
                + ", arm='" + arm + '\''
                + '}';
    }
}
