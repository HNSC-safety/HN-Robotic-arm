package com.hbirdedge.hnroboticarm.ice.service;

import QNEdge.SEvent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地事件库：保存算法端通过 ICE 回调推过来的事件。
 *
 * @author hbird
 * @since 2026-11-03
 */
@Service
public class EventStoreService {

    /** 每页的事件条数 */
    private static final int PAGE_SIZE = 20;

    /** 页码的最小值 —— 从 1 开始 */
    private static final int MIN_PAGE_NUMBER = 1;

    private final Map<String, SEvent> events = new ConcurrentHashMap<>();

    /**
     * 新增事件。
     *
     * @param event 事件；为 null 时直接忽略
     */
    public void add(SEvent event) {
        if (event == null) {
            return;
        }
        events.put(event.strID, event);
    }

    /**
     * 更新事件。
     *
     * @param event 事件；为 null 时直接忽略
     */
    public void update(SEvent event) {
        if (event == null) {
            return;
        }
        events.put(event.strID, event);
    }

    /**
     * 删除事件。
     *
     * @param event 事件；为 null 时直接忽略
     */
    public void delete(SEvent event) {
        if (event == null) {
            return;
        }
        events.remove(event.strID);
    }

    /**
     * 分页查询事件列表。
     *
     * @param strDeviceID 设备 ID，为空表示全部设备
     * @param nPage       页码，从 1 开始
     * @return 当前页的事件；越界时返回空列表
     */
    public List<SEvent> list(String strDeviceID, int nPage) {
        List<SEvent> all = new ArrayList<>(events.values());
        all.sort(Comparator.comparing((SEvent e) -> e.strCreateDTime == null ? "" : e.strCreateDTime).reversed());
        if (strDeviceID != null && !strDeviceID.isEmpty()) {
            all.removeIf(e -> !strDeviceID.equals(e.strDeviceID));
        }
        int page = Math.max(nPage, MIN_PAGE_NUMBER);
        int from = (page - 1) * PAGE_SIZE;
        if (from >= all.size()) {
            return List.of();
        }
        return all.subList(from, Math.min(from + PAGE_SIZE, all.size()));
    }

    /**
     * 当前事件总数。
     *
     * @return 事件条数
     */
    public int size() {
        return events.size();
    }
}
