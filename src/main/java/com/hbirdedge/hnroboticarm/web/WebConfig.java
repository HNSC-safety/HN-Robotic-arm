package com.hbirdedge.hnroboticarm.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Web 层配置。
 *
 * <p>注册 {@link FormUrlEncodedHttpMessageConverter}：
 * 让 {@code application/x-www-form-urlencoded} 的请求体也能像 JSON 一样被解析。
 *
 * <p>具体为什么要它，见该类的类注释。
 *
 * @author hbird
 * @since 2026-11-03
 */
@Configuration
public class WebConfig {

    /**
     * 把 form 转换器插到 Jackson 前面。
     *
     * <p>用 {@code extendMessageConverters} 而不是 {@code configureMessageConverters}：
     * 前者只做"加一个"，保留 Spring Boot 自动配置好的 Jackson 等所有转换器；
     * 后者会<b>清空</b>自动配置的全部转换器。
     *
     * <p>顺序其实无所谓 —— form-urlencoded 只有本转换器认领，Jackson 只认 JSON，
     * 互不干扰。放前面只是为了读起来更符合直觉。
     *
     * @param objectMapper Jackson 映射器
     * @return MVC 配置器
     */
    @Bean
    public WebMvcConfigurer formUrlEncodedConverterConfigurer(ObjectMapper objectMapper) {
        return new WebMvcConfigurer() {
            @Override
            public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
                converters.add(0, new FormUrlEncodedHttpMessageConverter(objectMapper));
            }
        };
    }
}
