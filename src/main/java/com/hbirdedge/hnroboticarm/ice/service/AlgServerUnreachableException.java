package com.hbirdedge.hnroboticarm.ice.service;

/**
 * 【连不上算法端】—— 算法端没启动 / 暗号不对 / 网络不通。
 *
 * <p><b>为什么要自己定义一个异常</b>：
 * ICE 自带的异常（{@code Ice.LocalException} 等）<b>构造函数不接受字符串消息</b>，
 * 没法把"连的是哪台机器、试了哪些暗号"这些关键信息带出去。
 * 而之前直接返回 {@code null} 代理，调用方一用就 NPE：
 * <pre>
 *   Cannot invoke "IQNEArmControlPrx.MoveTask(...)" because "prx" is null
 * </pre>
 * 看到这句话根本不知道该干嘛。
 *
 * <p>本异常会被 {@code IceExceptionHandler} 转成 <b>502 Bad Gateway</b> ——
 * 因为请求本身没问题，是下游服务的问题。
 *
 * @author hbird
 * @since 2026-11-03
 */
public class AlgServerUnreachableException extends RuntimeException {

    /**
     * @param message 给人看的话：连的是哪、试了哪些暗号、接下来怎么办
     */
    public AlgServerUnreachableException(String message) {
        super(message);
    }
}
