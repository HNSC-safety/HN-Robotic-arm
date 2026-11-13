package com.hbirdedge.hnroboticarm.web.dto;

import java.util.List;

/**
 * 配置请求体 —— 对应 {@code QNEdge.ice} 里的 {@code SConfig}。
 *
 * <p>{@code SConfig.seqURL} 的语义是「事件接收 URL 列表」：
 * 告诉算法端检测到的事件往哪些地址推送。
 */
public class ConfigRequest {

    /** 事件接收 URL 列表 */
    private List<String> urls;

    public List<String> getUrls() {
        return urls;
    }

    public void setUrls(List<String> urls) {
        this.urls = urls;
    }

    @Override
    public String toString() {
        return "ConfigRequest{"
                + "urls=" + urls
                + '}';
    }
}
