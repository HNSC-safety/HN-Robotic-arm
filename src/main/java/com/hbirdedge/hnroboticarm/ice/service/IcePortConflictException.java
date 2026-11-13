package com.hbirdedge.hnroboticarm.ice.service;

/**
 * 【端口被占用】—— 本地 ICE adapter 或内置假算法端绑端口失败。
 *
 * <p><b>为什么必须单独定义一个异常</b>：
 * 端口冲突如果被静默吞掉，表现极具迷惑性 ——
 * 内置假算法端绑端口失败后去连 {@code 127.0.0.1:<port>}，
 * 结果<b>连上了另一个 JVM 的假算法端</b>：日志显示"暗号探测成功"，
 * 但 {@code MockAlgorithmRecorder} 是 static 字段，记录写在<b>别的进程</b>里，
 * 断言 {@code lastDirection()} 永远是 {@code null}。
 *
 * <p>所以这里把冲突<b>显式炸出来</b>，并在消息里说清楚：
 * <ul>
 *   <li>是哪个端口</li>
 *   <li>被谁占着（怎么查）</li>
 *   <li>怎么解决</li>
 * </ul>
 *
 * @author hbird
 * @since 2026-11-03
 */
public class IcePortConflictException extends RuntimeException {

    /**
     * @param message 给人看的话：哪个端口、怎么查、怎么解决
     */
    public IcePortConflictException(String message) {
        super(message);
    }

    /**
     * 端口冲突的通用说明。
     *
     * @param port     被占用的端口
     * @param purpose  这个端口是干什么用的
     * @return 拼好的中文说明
     */
    public static String message(int port, String purpose) {
        return "ICE 端口 " + port + " 被占用（用于" + purpose + "）。"
                + "查是谁占着：netstat -ano | findstr :" + port + "，"
                + "再用 tasklist /FI \"PID eq <进程号>\" 看进程。"
                + "解决办法：① 关掉占端口的程序（多半是另一个正在跑的本应用实例）；"
                + "② 或者改配置换端口（ice.test.mock-port / ice.local.port）。";
    }

    /**
     * 判断一个异常是不是"端口被占用"。
     *
     * <p>ICE 把 {@code bind} 失败包成 {@code Ice.SocketException}，
     * 真正的原因（{@code java.net.BindException: Address already in use}）
     * 藏在 cause 链里，所以要顺着链子找。
     *
     * @param e 异常
     * @return true = 端口冲突；false = 别的错误
     */
    public static boolean isPortConflict(Throwable e) {
        for (Throwable t = e; t != null; t = (t.getCause() == t ? null : t.getCause())) {
            if (t instanceof java.net.BindException) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && msg.contains("Address already in use")) {
                return true;
            }
        }
        return false;
    }
}
