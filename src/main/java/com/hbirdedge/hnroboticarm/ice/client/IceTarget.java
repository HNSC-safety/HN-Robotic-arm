package com.hbirdedge.hnroboticarm.ice.client;

import QNEdge.IQNEAlgHandlePrx;
import QNEdge.IQNEArmControlPrx;
import QNEdge.IQNEConfigPrx;

import java.util.List;

/**
 * 一个 ICE 目标 —— 要么是内置的假算法端，要么是现场的真实算法端。
 *
 * <p>这个类只回答一个问题：<b>我现在到底在跟谁说话？</b>
 * <ul>
 *   <li>{@link #isReal()} —— 真机还是模拟</li>
 *   <li>{@link #getDescription()} —— 具体地址</li>
 *   <li>三个代理 —— 能调什么接口</li>
 * </ul>
 *
 * <p>连不上时（算法端没启动）三个代理都是 {@code null}，
 * {@link #isReachable()} 返回 {@code false}。
 * 调用方据此给出明确提示，而不是拿到 {@code null} 然后 NPE。
 *
 * @author hbird
 * @since 2026-11-03
 */
public class IceTarget {

    /** 是否真实算法端 */
    private final boolean real;

    /**
     * 机械臂名 —— <b>这次调的是哪条臂</b>。
     *
     * <p>没指定时为 {@code null}（单臂场景）。
     */
    private final String armName;

    /** 目标主机 */
    private final String host;

    /** 目标端口 */
    private final int port;

    /** 探测出来的暗号（连不上时为 null） */
    private final String resolvedIdentity;

    /** 对端实现的 Slice 类型 ID（连不上时为空列表） */
    private final List<String> remoteTypeIds;

    /** 挨个试过的暗号及结果（连不上时告诉用户"我猜了哪些名字"） */
    private final List<String> triedIdentities;

    /** 机械臂控制接口代理 */
    private final IQNEArmControlPrx armControl;

    /** 算法管理接口代理 */
    private final IQNEAlgHandlePrx algHandle;

    /** 配置接口代理 */
    private final IQNEConfigPrx config;

    IceTarget(boolean real, String host, int port,
              String resolvedIdentity, List<String> remoteTypeIds, List<String> triedIdentities,
              IQNEArmControlPrx armControl, IQNEAlgHandlePrx algHandle, IQNEConfigPrx config) {
        this(real, null, host, port, resolvedIdentity, remoteTypeIds, triedIdentities,
                armControl, algHandle, config);
    }

    IceTarget(boolean real, String armName, String host, int port,
              String resolvedIdentity, List<String> remoteTypeIds, List<String> triedIdentities,
              IQNEArmControlPrx armControl, IQNEAlgHandlePrx algHandle, IQNEConfigPrx config) {
        this.real = real;
        this.armName = armName;
        this.host = host;
        this.port = port;
        this.resolvedIdentity = resolvedIdentity;
        this.remoteTypeIds = remoteTypeIds == null ? List.of() : List.copyOf(remoteTypeIds);
        this.triedIdentities = triedIdentities == null ? List.of() : List.copyOf(triedIdentities);
        this.armControl = armControl;
        this.algHandle = algHandle;
        this.config = config;
    }

    /**
     * 构造一个"连不上"的目标。
     *
     * @param real           是否真实算法端
     * @param host           目标主机
     * @param port           目标端口
     * @param triedIdentities 试过的暗号及结果
     * @return 连不上的目标
     */
    static IceTarget unreachable(boolean real, String host, int port, List<String> triedIdentities) {
        return new IceTarget(real, null, host, port, null, List.of(), triedIdentities, null, null, null);
    }

    /**
     * 构造一个"连不上"的目标（带臂名）。
     *
     * @param real            是否真实算法端
     * @param armName         机械臂名
     * @param host            目标主机
     * @param port            目标端口
     * @param triedIdentities 试过的暗号及结果
     * @return 连不上的目标
     */
    static IceTarget unreachable(boolean real, String armName, String host, int port,
                                 List<String> triedIdentities) {
        return new IceTarget(real, armName, host, port, null, List.of(), triedIdentities, null, null, null);
    }

    /**
     * 是否真实算法端。
     *
     * @return true 表示真实算法端；false 表示内置假算法端
     */
    public boolean isReal() {
        return real;
    }

    /**
     * 连上了没有。
     *
     * @return true 表示暗号已探通、代理可用
     */
    public boolean isReachable() {
        return armControl != null;
    }

    /**
     * 目标描述 —— 给人看的。
     *
     * @return 例：{@code 192.168.0.103:10000（真实算法端）}
     */
    public String getDescription() {
        String base = host + ":" + port + (real ? "（真实算法端）" : "（内置假算法端）");
        return armName == null ? base : base + " · 机械臂=" + armName;
    }

    /**
     * 机械臂名 —— <b>这次调的是哪条臂</b>。
     *
     * @return 臂名；没指定时为 null
     */
    public String getArmName() {
        return armName;
    }

    /**
     * 有没有指定机械臂。
     *
     * @return true 表示调用方明确指定了臂名
     */
    public boolean hasArm() {
        return armName != null && !armName.isBlank();
    }

    /**
     * 目标主机。
     *
     * @return 主机地址
     */
    public String getHost() {
        return host;
    }

    /**
     * 目标端口。
     *
     * @return 端口号
     */
    public int getPort() {
        return port;
    }

    /**
     * 探测出来的暗号。
     *
     * @return 暗号；连不上时为 null
     */
    public String getResolvedIdentity() {
        return resolvedIdentity;
    }

    /**
     * 对端实现了哪些 Slice 接口。
     *
     * @return 类型 ID 列表；连不上时为空列表
     */
    public List<String> getRemoteTypeIds() {
        return remoteTypeIds;
    }

    /**
     * 挨个试过的暗号及结果。
     *
     * @return 例：{@code [AlgHandle(不存在), QNEArmControl(端口不通)]}
     */
    public List<String> getTriedIdentities() {
        return triedIdentities;
    }

    /**
     * 机械臂控制接口代理。
     *
     * @return 代理；连不上时为 null
     */
    public IQNEArmControlPrx getArmControl() {
        return armControl;
    }

    /**
     * 算法管理接口代理。
     *
     * @return 代理；连不上时为 null
     */
    public IQNEAlgHandlePrx getAlgHandle() {
        return algHandle;
    }

    /**
     * 配置接口代理。
     *
     * @return 代理；连不上时为 null
     */
    public IQNEConfigPrx getConfig() {
        return config;
    }
}
