package com.hbirdedge.hnroboticarm.web;

import QNEdge.SEvent;
import com.hbirdedge.hnroboticarm.ice.service.EventStoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 本地事件列表（来自算法端通过 ICE 回调推过来的事件）。
 *
 * @author hbird
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/api/events")
@Tag(name = "event-controller", description = "本地事件列表 —— 来自算法端通过 ICE 回调推过来的事件")
public class EventController {

    private final EventStoreService eventStore;

    public EventController(EventStoreService eventStore) {
        this.eventStore = eventStore;
    }

    /**
     * 查询事件列表。
     *
     * @param deviceId 按设备 ID 过滤，不传=全部
     * @param page     页码，从 1 开始
     * @return 事件总数、当前页码和事件列表
     */
    @GetMapping
    @Operation(summary = "查询事件列表", description = "算法端通过 ICE 反向回调推来的事件（AddEvent）都存在这里")
    public Map<String, Object> list(
            @Parameter(description = "按设备 ID 过滤，不传=全部") @RequestParam(required = false) String deviceId,
            @Parameter(description = "页码，从 1 开始", example = "1") @RequestParam(defaultValue = "1") int page) {
        List<SEvent> events = eventStore.list(deviceId, page);
        return Map.of("total", eventStore.size(), "page", page, "events", events);
    }
}
