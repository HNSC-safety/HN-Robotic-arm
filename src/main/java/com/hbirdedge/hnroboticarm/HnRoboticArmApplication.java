package com.hbirdedge.hnroboticarm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用启动类 —— 机械臂 ICE 控制器。
 *
 * <p>启动后提供两类能力：
 * <ul>
 *   <li>REST 业务接口（HTTP 9988）—— 见 {@code /api/arm/**}</li>
 *   <li>ICE 自测接口（HTTP 9988）—— 见 {@code /api/ice/**}</li>
 * </ul>
 *
 * <p>同时会起一个本地 ICE 回调 ObjectAdapter（默认端口 10001），
 * 等现场算法端反向回调我们。
 *
 * @author hbird
 * @since 2026-11-03
 */
@SpringBootApplication
public class HnRoboticArmApplication {

    public static void main(String[] args) {
        SpringApplication.run(HnRoboticArmApplication.class, args);
    }

}
