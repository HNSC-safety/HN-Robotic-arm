package com.hbirdedge.hnroboticarm.web.dto;

/**
 * 平面坐标点。
 *
 * @author hbird
 * @since 2026-11-03
 */
public class PointRequest {

    /** X 坐标（像素） */
    private Integer x;

    /** Y 坐标（像素） */
    private Integer y;

    public Integer getX() {
        return x;
    }

    public void setX(Integer x) {
        this.x = x;
    }

    public Integer getY() {
        return y;
    }

    public void setY(Integer y) {
        this.y = y;
    }

    @Override
    public String toString() {
        return "PointRequest{"
                + "x=" + x
                + ", y=" + y
                + '}';
    }
}
