package com.hbirdedge.hnroboticarm.web.dto;

import java.util.List;

/**
 * 点击按钮请求。
 *
 * @author hbird
 * @since 2026-11-03
 */
public class ClickTaskRequest {

    /** 按钮文本列表 */
    private List<String> buttonTexts;

    /** 按钮坐标列表，与 {@code buttonTexts} 一一对应 */
    private List<PointRequest> coordinates;

    /** 机械臂名（不传 = 默认臂） */
    private String arm;

    public List<String> getButtonTexts() {
        return buttonTexts;
    }

    public void setButtonTexts(List<String> buttonTexts) {
        this.buttonTexts = buttonTexts;
    }

    public List<PointRequest> getCoordinates() {
        return coordinates;
    }

    public void setCoordinates(List<PointRequest> coordinates) {
        this.coordinates = coordinates;
    }

    public String getArm() {
        return arm;
    }

    public void setArm(String arm) {
        this.arm = arm;
    }

    @Override
    public String toString() {
        return "ClickTaskRequest{"
                + "buttonTexts=" + buttonTexts
                + ", coordinates=" + coordinates
                + ", arm='" + arm + '\''
                + '}';
    }
}
