package com.hbirdedge.hnroboticarm.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 让 {@code application/x-www-form-urlencoded} 的请求体也能像 JSON 一样被解析。
 *
 * <h2>为什么需要它</h2>
 * <p>接口文档承诺 {@code POST /api/arm/move?direction=Up&speed=5}（query 传参）不带 body 也能用。
 * 但很多 HTTP 客户端即使 body 为空也会带上
 * {@code Content-Type: application/x-www-form-urlencoded} —— 典型就是 PowerShell 的
 * {@code Invoke-RestMethod -Method Post}。
 *
 * <p>这时 Spring 找不到能读该 Content-Type 的消息转换器，会抛
 * {@link org.springframework.web.HttpMediaTypeNotSupportedException} → <b>500</b>，
 * 把"参数其实是对的"这种请求误判成服务端错误。
 *
 * <h2>怎么解决</h2>
 * <p>本转换器把 form 格式的 body（{@code direction=Up&speed=5}）解析成
 * {@code Map<String,String>}，再交给 Jackson 转成目标 DTO ——
 * 于是两种 Content-Type 走同一条代码路径，DTO 和校验逻辑完全不用改。
 *
 * <p><b>为什么不用 {@code RequestBodyAdvice}</b>：Spring 的
 * {@code AbstractMessageConverterMethodArgumentResolver#readWithMessageConverters}
 * 是<b>先选消息转换器、选不到才轮到 advice</b>，advice 根本没机会插手。
 * 所以必须提供一个真正的 {@code HttpMessageConverter}。
 *
 * @author hbird
 * @since 2026-11-03
 * @see WebConfig
 * @see ArmControlController
 */
public class FormUrlEncodedHttpMessageConverter extends AbstractHttpMessageConverter<Object> {

    private static final Logger log = LoggerFactory.getLogger(FormUrlEncodedHttpMessageConverter.class);

    private final ObjectMapper objectMapper;

    public FormUrlEncodedHttpMessageConverter(ObjectMapper objectMapper) {
        super(MediaType.APPLICATION_FORM_URLENCODED);
        this.objectMapper = objectMapper;
    }

    /**
     * 本转换器只读不写。
     *
     * @param clazz 目标类型
     * @return 恒为 true
     */
    @Override
    protected boolean supports(Class<?> clazz) {
        return true;
    }

    /**
     * 只认 form-urlencoded。
     *
     * <p>注意 {@code mediaType} 为 null 时返回 false —— 那表示"请求没带 Content-Type"，
     * 应该走原有的"无 body"分支，而不是本转换器。
     *
     * @param clazz     目标类型
     * @param mediaType 请求的 Content-Type
     * @return true 表示本转换器能处理
     */
    @Override
    public boolean canRead(Class<?> clazz, MediaType mediaType) {
        return mediaType != null && MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(mediaType);
    }

    /**
     * 本转换器只用于读。
     *
     * @param clazz       目标类型
     * @param mediaType   媒体类型
     * @return 恒为 false
     */
    @Override
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
        return false;
    }

    /**
     * 把 form body 读成目标 DTO。
     *
     * <p>走 Jackson 是为了复用现成的类型转换（{@code speed=5} → {@code int 5}），
     * 免得为每个 DTO 手写解析。
     *
     * @param clazz        目标类型
     * @param inputMessage 请求消息
     * @return 转换后的对象；body 为空时返回 null
     * @throws IOException 读取或转换失败
     */
    @Override
    protected Object readInternal(Class<?> clazz, HttpInputMessage inputMessage) throws IOException {
        String raw = new String(inputMessage.getBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> params = parseForm(raw);

        if (params.isEmpty()) {
            // 空 body：返回 null，让上层按 @RequestBody(required = false) 处理（走 query 参数）
            return null;
        }

        log.debug("[请求体] form-urlencoded -> {}: {}", clazz.getSimpleName(), params);
        try {
            return objectMapper.convertValue(params, clazz);
        } catch (Exception e) {
            throw new org.springframework.http.converter.HttpMessageNotReadableException(
                    "form-urlencoded 请求体无法转换为 " + clazz.getSimpleName() + ": " + e.getMessage(),
                    e, inputMessage);
        }
    }

    /**
     * 不支持写 —— 本转换器只用于读。
     *
     * @param o             对象
     * @param outputMessage 输出消息
     */
    @Override
    protected void writeInternal(Object o, HttpOutputMessage outputMessage) {
        throw new UnsupportedOperationException("本转换器只用于读");
    }

    /**
     * 构造一个内存里的请求体 —— 供单元测试直接喂数据。
     *
     * @param body        请求体文本
     * @param contentType Content-Type
     * @return 请求消息
     */
    public static HttpInputMessage textMessage(String body, MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }

    /**
     * 解析 {@code a=1&b=2} 形式的 body。
     *
     * <p>同名参数取最后一个值；没有 {@code =} 的按空串处理。
     *
     * @param raw 原始文本
     * @return 参数表
     */
    private Map<String, String> parseForm(String raw) {
        Map<String, String> params = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.put(decode(key), decode(value));
        }
        return params;
    }

    /**
     * URL 解码 —— 非法转义序列时原样返回。
     *
     * @param s 原始文本
     * @return 解码后的文本
     */
    private String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 非法转义序列时原样返回，别让整个请求挂掉
            return s;
        }
    }
}
