package com.hbirdedge.hnroboticarm.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 文档元信息 —— Knife4j / Swagger UI 页面顶部那段说明。
 *
 * <p><b>为什么单独一个类</b>：这些是"关于文档本身"的配置，
 * 跟业务无关，放 {@code application.yaml} 里反而零散。
 *
 * <p>⚠️ <b>springdoc 3.x 的包名是 {@code io.swagger.v3.oas.models}</b>，
 * 不是旧版的 {@code io.swagger.models} —— Boot 4 下写错包名直接编译不过。
 */
@Configuration
public class OpenApiConfig {

    /**
     * 文档元信息。
     *
     * @return OpenAPI 描述
     */
    @Bean
    public OpenAPI hnRoboticArmOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("HN-Robotic-arm 机械臂 ICE 控制器")
                        .version("1.0.0")
                        .description("""
                                机械臂控制服务 —— 通过 ICE（ZeroC）协议对接算法端。

                                **两个「移动」接口，别搞混：**

                                | 接口 | 真机 / 模拟 | 用途 |
                                |---|---|---|
                                | `POST /api/arm/move` | **固定真机** | 现场业务接口 |
                                | `POST /api/ice/test/arm/move` | 由 `real` 参数决定 | 自测 / 联调 |

                                **两个端口，别搞混：**

                                | 端口 | 谁监听 | 用途 |
                                |---|---|---|
                                | `10000` | 算法端 | 我们**主动连**它，下发机械臂指令 |
                                | `10001` | 我们 | 算法端**反向回调**我们（暗号硬编码为 `SMTEvent`） |

                                **机械臂标识**：Slice 协议里没有臂字段，臂名走 Ice Context 的
                                `strArmID`（C# 端读 `current.ctx["strArmID"]`）。
                                """)
                        .contact(new Contact().name("hBird"))
                        .license(new License().name("Proprietary")))
                .servers(List.of(
                        new Server().url("http://localhost:9988").description("本机（默认 HTTP 端口）"),
                        new Server().url("http://127.0.0.1:9987").description("测试实例（IceEndToEndTests）")));
    }
}
