package com.hbirdedge.hnroboticarm.web;

import QNEdge.EErrorCode;
import com.hbirdedge.hnroboticarm.ice.service.ConfigService;
import com.hbirdedge.hnroboticarm.web.dto.ConfigRequest;
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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置接口（对应 {@code QNEdge.ice} 的 {@code IQNEConfig}）。
 *
 * <pre>
 *   POST /api/config        —— 把事件接收 URL 推给算法端（ICE 出方向）
 *   GET  /api/config        —— 查看当前配置（含来源和更新时间）
 * </pre>
 *
 * <p>注意：算法端也可能通过 ICE 反向调我们（同一个 servant），
 * 那种情况不会经过这个 Controller，但配置同样能在 {@code GET /api/config} 里看到。
 *
 * @author hbird
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/api/config")
@Tag(name = "config-controller", description = "配置接口 —— 把事件接收 URL 推给算法端")
public class ConfigController {

    /** 业务接口固定走真实算法端 */
    private static final boolean USE_REAL_ALG_SERVER = true;

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * 把事件接收 URL 推给算法端。
     *
     * <p>两种传参方式都支持：
     * <ul>
     *     <li>JSON body：{@code POST /api/config} + {@code {"urls":["http://a/x"]}}</li>
     *     <li>query 参数：{@code POST /api/config?url=http://a/x&url=http://b/y}</li>
     * </ul>
     *
     * @param request JSON body（可为 null）
     * @param url     query 参数里的 URL 列表
     * @return 算法端返回的错误码
     */
    @PostMapping
    @Operation(summary = "把事件接收 URL 推给算法端", description = """
            ICE 出方向 —— 我们主动把配置推给算法端。

            两种传参方式都支持：
            - JSON body：`{"urls":["http://a/x"]}`
            - query 参数：`?url=http://a/x&url=http://b/y`
            """)
    public ResponseEntity<Map<String, Object>> push(
            @Parameter(description = "JSON body，可为 null") @RequestBody(required = false) ConfigRequest request,
            @Parameter(description = "事件接收 URL 列表") @RequestParam(required = false) List<String> url) {

        List<String> urls = resolveUrls(request, url);
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("urls 不能为空，至少给一个事件接收 URL");
        }

        EErrorCode code = configService.pushToAlgServer(urls, USE_REAL_ALG_SERVER);
        return ResponseEntity.ok(Map.of(
                "code", code.name(),
                "message", code == EErrorCode.EECSuccess ? "success" : code.name(),
                "urls", urls));
    }

    /**
     * 查看当前配置。
     *
     * @return 配置内容、来源和更新时间
     */
    @GetMapping
    @Operation(summary = "查看当前配置", description = "含来源和更新时间 —— 无论配置是推过去的还是算法端回调带回来的，都能看到")
    public Map<String, Object> current() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("urls", Arrays.asList(configService.eventUrls()));
        result.put("source", configService.lastSource());
        result.put("lastUpdateTime", configService.lastUpdateTime());
        return result;
    }

    /**
     * body 优先，其次 query 参数。
     *
     * @param request   JSON body
     * @param queryUrls query 参数里的 URL 列表
     * @return 事件接收 URL 列表
     */
    private List<String> resolveUrls(ConfigRequest request, List<String> queryUrls) {
        if (request != null && request.getUrls() != null && !request.getUrls().isEmpty()) {
            return request.getUrls();
        }
        if (queryUrls != null && !queryUrls.isEmpty()) {
            return queryUrls;
        }
        return List.of();
    }
}
