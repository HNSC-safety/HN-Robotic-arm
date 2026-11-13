package com.hbirdedge.hnroboticarm.web;

import com.hbirdedge.hnroboticarm.ice.service.AlgServerUnreachableException;
import com.hbirdedge.hnroboticarm.ice.service.IcePortConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * 统一异常出口。
 *
 * <ul>
 *     <li>ICE 调用失败（算法端没起 / 暗号不对）→ <b>502 Bad Gateway</b>，
 *         因为请求本身没问题，是下游服务的问题</li>
 *     <li>参数不合法 → <b>400 Bad Request</b></li>
 *     <li>其它 → 500</li>
 * </ul>
 *
 * <p>目的是让前端拿到可读的 JSON，而不是一坨堆栈。
 *
 * @author hbird
 * @since 2026-11-03
 */
@RestControllerAdvice
public class IceExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(IceExceptionHandler.class);

    /** 参数不合法（方向名写错、body 格式错等） */
    @ExceptionHandler({
            IllegalArgumentException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMediaTypeNotSupportedException.class
    })
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception e) {
        log.warn("[请求参数错误] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "success", false,
                        "error", e.getClass().getSimpleName(),
                        "message", friendlyMessage(e)));
    }

    /** ICE 远程调用失败 */
    @ExceptionHandler(Ice.Exception.class)
    public ResponseEntity<Map<String, Object>> handleIceException(Ice.Exception e) {
        log.error("[ICE] 远程调用失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of(
                        "success", false,
                        "error", e.getClass().getSimpleName(),
                        "message", String.valueOf(e.getMessage())));
    }

    /**
     * 连不上算法端 —— 请求本身没问题，是下游服务的问题，所以也是 <b>502</b>。
     *
     * <p>和 {@link Ice.Exception} 分开处理，因为它带的是<b>我们拼好的中文说明</b>
     * （连的是哪台机器、试了哪些暗号、接下来怎么办），不能被覆盖掉。
     *
     * @param e 连不上算法端的异常
     * @return 502 响应
     */
    @ExceptionHandler(AlgServerUnreachableException.class)
    public ResponseEntity<Map<String, Object>> handleAlgServerUnreachable(AlgServerUnreachableException e) {
        log.warn("[ICE] 算法端不可达: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of(
                        "success", false,
                        "error", "ALG_SERVER_UNREACHABLE",
                        "message", e.getMessage()));
    }

    /**
     * ICE 端口被占用 —— 返回 <b>409 Conflict</b>。
     *
     * <p>单独处理，因为它带的是<b>我们拼好的中文说明</b>
     * （哪个端口、怎么查、怎么解决），比笼统的 500 有用得多。
     *
     * <p>用 409 而不是 500：请求本身没问题，是<b>本机环境</b>的状态问题
     * （端口被别的进程占了），和 502（下游服务不可达）也不是一回事。
     *
     * @param e 端口冲突异常
     * @return 409 响应
     */
    @ExceptionHandler(IcePortConflictException.class)
    public ResponseEntity<Map<String, Object>> handlePortConflict(IcePortConflictException e) {
        log.warn("[ICE] 端口冲突: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "success", false,
                        "error", "ICE_PORT_CONFLICT",
                        "message", e.getMessage()));
    }

    /**
     * 兜底。
     *
     * @param e 异常
     * @return 500 响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.error("请求处理失败", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "success", false,
                        "error", e.getClass().getSimpleName(),
                        "message", String.valueOf(e.getMessage())));
    }

    /**
     * 把框架异常翻译成人话。
     *
     * @param e 异常
     * @return 可读提示
     */
    private String friendlyMessage(Exception e) {
        if (e instanceof HttpMessageNotReadableException) {
            return "JSON 格式错误，请检查请求体";
        }
        if (e instanceof MethodArgumentTypeMismatchException m) {
            return "参数 '" + m.getName() + "' 取值非法";
        }
        if (e instanceof HttpMediaTypeNotSupportedException m) {
            return "不支持的 Content-Type：" + m.getContentType()
                    + "。本接口支持 application/json 或 application/x-www-form-urlencoded";
        }
        return String.valueOf(e.getMessage());
    }
}
