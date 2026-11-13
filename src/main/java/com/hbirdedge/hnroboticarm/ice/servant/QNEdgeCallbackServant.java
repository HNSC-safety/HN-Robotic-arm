package com.hbirdedge.hnroboticarm.ice.servant;

import Ice.Current;
import Ice.DispatchStatus;
import IceInternal.BasicStream;
import IceInternal.Incoming;
import QNEdge.EErrorCode;
import QNEdge.SConfig;
import QNEdge.SEvent;
import QNEdge.SPoint;
import QNEdge._IQNEConfigDisp;
import QNEdge._IQNEFCHInfoDisp;
import QNEdge._IQNEEventDisp;
import com.hbirdedge.hnroboticarm.ice.service.ConfigService;
import com.hbirdedge.hnroboticarm.ice.service.EventStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * <b>统一的回调 servant</b> —— 一个对象同时实现 3 个 Slice 接口。
 *
 * <h2>为什么要合并成一个</h2>
 * <p>之前是 3 个 servant、3 个暗号（{@code QNEdgeEvent} / {@code QNEFCHInfo} / {@code QNEdgeConfig}）。
 * 但 ICE 里<b>一个 servant 可以实现多个接口</b>（multi-interface），
 * 对端 {@code stringToProxy("暗号:...")} 拿到代理后，
 * {@code checkedCast} 到任意一个接口都能成功。
 *
 * <p>所以正确做法是<b>一个方向只配一个暗号</b>，和 hBird-edge-ice 项目一致
 * （那边 {@code SMT.ice} 也有 3 个 interface，但本地只注册了 1 个 servant + 1 个暗号 {@code SMTEvent}）。
 *
 * <h2>这个 servant 实现的接口</h2>
 * <ul>
 *     <li>{@code IQNEEvent} —— 事件列表增删改查（算法端推事件给我们）</li>
 *     <li>{@code IQNEFCHInfo} —— 消防按钮坐标修正（算法端推坐标给我们）</li>
 *     <li>{@code IQNEConfig} —— 配置下发（算法端推事件接收 URL 给我们）</li>
 * </ul>
 *
 * <h2>实现手法</h2>
 * <p>Java 映射里每个接口生成一个 {@code _IXxxDisp} 抽象类，它们各自实现
 * {@code __dispatch}。要合并多个接口，只能<b>不继承</b>它们，
 * 改为直接实现 {@code Ice.Object} + 自己写 {@code __dispatch} 分发：
 *
 * <pre>
 *   operation 名 → 找到对应 Disp 的静态 ___方法 → 调用
 * </pre>
 *
 * <p>同时 {@code ice_ids()} 要返回全部 3 个类型 ID（否则对端 {@code checkedCast} 会失败），
 * {@code __writeImpl} 要写全部 3 个 slice（proxy 序列化时用）。
 *
 * @author hbird
 * @since 2026-11-03
 */
@Component
public class QNEdgeCallbackServant extends Ice.ObjectImpl
        implements QNEdge.IQNEEvent, QNEdge.IQNEFCHInfo, QNEdge.IQNEConfig {

    private static final Logger log = LoggerFactory.getLogger(QNEdgeCallbackServant.class);

    /**
     * 本 servant 实现的全部 Slice 类型 ID（ice_ids() 返回这个）。
     *
     * <p><b>必须按字典序排好</b>：Ice 的 {@code ice_isA} 用的是
     * {@code Arrays.binarySearch}，数组没排序就查不到 —— 表现为
     * {@code checkedCast} 静默返回 {@code null}。
     */
    private static final String[] IDS = sortedIds(
            "::QNEdge::IQNEEvent",
            "::QNEdge::IQNEFCHInfo",
            "::QNEdge::IQNEConfig");

    /**
     * 拼上 {@code ::Ice::Object} 并排序 —— Ice 要求 type id 数组有序。
     *
     * @param ids 业务类型 ID
     * @return 排好序的完整数组
     */
    private static String[] sortedIds(String... ids) {
        String[] all = new String[ids.length + 1];
        all[0] = "::Ice::Object";
        System.arraycopy(ids, 0, all, 1, ids.length);
        Arrays.sort(all);
        return all;
    }

    private final EventStoreService eventStore;
    private final ConfigService configService;

    public QNEdgeCallbackServant(EventStoreService eventStore, ConfigService configService) {
        this.eventStore = eventStore;
        this.configService = configService;
    }

    // ══════════════════════════════════════════════════════════════════
    // ICE.Object 实现：告诉对端"我实现了哪些接口"
    // ══════════════════════════════════════════════════════════════════

    @Override
    public String[] ice_ids() {
        return IDS.clone();
    }

    @Override
    public String[] ice_ids(Current current) {
        return IDS.clone();
    }

    @Override
    public boolean ice_isA(String s) {
        return Arrays.binarySearch(IDS, s) >= 0;
    }

    @Override
    public boolean ice_isA(String s, Current current) {
        return Arrays.binarySearch(IDS, s) >= 0;
    }

    @Override
    public String ice_id() {
        // 多个接口时 Ice 约定返回"最派生"的那个，这里返回第一个业务接口
        return IDS[1];
    }

    @Override
    public String ice_id(Current current) {
        return IDS[1];
    }

    /**
     * proxy 序列化：把所有实现的接口 slice 都写出去。
     *
     * <p>对端 {@code checkedCast} 靠的就是这里 —— 它读 proxy 的 slice 列表来确认
     * "这个对象支持哪些接口"。少写一个，对端就 cast 不到那个接口。
     *
     * @param os 输出流
     */
    @Override
    protected void __writeImpl(BasicStream os) {
        for (int i = 1; i < IDS.length; i++) {
            os.startWriteSlice(IDS[i], -1, i == IDS.length - 1);
            os.endWriteSlice();
        }
    }

    /**
     * proxy 反序列化：本端不需要读 proxy，切片全部跳过。
     *
     * @param is 输入流
     */
    @Override
    protected void __readImpl(BasicStream is) {
        String id;
        while ((id = is.startReadSlice()) != null) {
            is.endReadSlice();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 统一分发：把 operation 路由到对应接口的实现
    // ══════════════════════════════════════════════════════════════════

    @Override
    public DispatchStatus __dispatch(Incoming in, Current current) {
        String op = current.operation;

        // ---- IQNEEvent 的 4 个方法 ----
        switch (op) {
            case "AddEvent":
                return _IQNEEventDisp.___AddEvent(this, in, current);
            case "UpdateEvent":
                return _IQNEEventDisp.___UpdateEvent(this, in, current);
            case "DeleteEvent":
                return _IQNEEventDisp.___DeleteEvent(this, in, current);
            case "GetEventList":
                return _IQNEEventDisp.___GetEventList(this, in, current);
            // ---- IQNEFCHInfo 的 1 个方法 ----
            case "CorrectButtonCoordinates":
                return _IQNEFCHInfoDisp.___CorrectButtonCoordinates(this, in, current);
            // ---- IQNEConfig 的 1 个方法 ----
            case "Config":
                return _IQNEConfigDisp.___Config(this, in, current);
            default:
                // ice_* 内建操作交给基类
                return super.__dispatch(in, current);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // IQNEEvent 实现
    // ══════════════════════════════════════════════════════════════════

    @Override
    public EErrorCode AddEvent(SEvent se, Current current) {
        log.info("[ICE回调] AddEvent id={} device={} type={}", se.strID, se.strDeviceID, se.strName);
        eventStore.add(se);
        return EErrorCode.EECSuccess;
    }

    @Override
    public EErrorCode UpdateEvent(SEvent se, Current current) {
        log.info("[ICE回调] UpdateEvent id={} device={}", se.strID, se.strDeviceID);
        eventStore.update(se);
        return EErrorCode.EECSuccess;
    }

    @Override
    public EErrorCode DeleteEvent(SEvent se, Current current) {
        log.info("[ICE回调] DeleteEvent id={}", se.strID);
        eventStore.delete(se);
        return EErrorCode.EECSuccess;
    }

    @Override
    public SEvent[] GetEventList(String strDeviceID, int nPage, Current current) {
        log.info("[ICE回调] GetEventList device={} page={}", strDeviceID, nPage);
        return eventStore.list(strDeviceID, nPage).toArray(new SEvent[0]);
    }

    // ---- 无 Current 的便捷重载（Slice 的 NC 接口要求）----
    // 之前这些是由 _IQNEEventDisp 提供的；现在我们不继承它，得自己写。

    @Override
    public SEvent[] GetEventList(String strDeviceID, int nPage) {
        return GetEventList(strDeviceID, nPage, null);
    }

    @Override
    public EErrorCode AddEvent(SEvent se) {
        return AddEvent(se, null);
    }

    @Override
    public EErrorCode UpdateEvent(SEvent se) {
        return UpdateEvent(se, null);
    }

    @Override
    public EErrorCode DeleteEvent(SEvent se) {
        return DeleteEvent(se, null);
    }

    // ══════════════════════════════════════════════════════════════════
    // IQNEFCHInfo 实现
    // ══════════════════════════════════════════════════════════════════

    @Override
    public EErrorCode CorrectButtonCoordinates(String strButtonText, SPoint spNew, Current current) {
        log.info("[ICE回调] CorrectButtonCoordinates button={} x={} y={}",
                strButtonText, spNew == null ? null : spNew.nX, spNew == null ? null : spNew.nY);
        return EErrorCode.EECSuccess;
    }

    /**
     * 无 Current 的便捷重载。
     *
     * @param strButtonText 按钮文本
     * @param spNew         新坐标
     * @return 固定返回 {@code EECSuccess}
     */
    @Override
    public EErrorCode CorrectButtonCoordinates(String strButtonText, SPoint spNew) {
        return CorrectButtonCoordinates(strButtonText, spNew, null);
    }

    // ══════════════════════════════════════════════════════════════════
    // IQNEConfig 实现
    // ══════════════════════════════════════════════════════════════════

    @Override
    public EErrorCode Config(SConfig sc, Current current) {
        log.info("[ICE回调] Config seqURL={}",
                sc == null || sc.seqURL == null ? "null" : Arrays.toString(sc.seqURL));
        return configService.acceptFromAlgServer(sc);
    }

    /**
     * 无 Current 的便捷重载。
     *
     * @param sc 配置
     * @return 算法端返回的错误码
     */
    @Override
    public EErrorCode Config(SConfig sc) {
        return Config(sc, null);
    }
}
