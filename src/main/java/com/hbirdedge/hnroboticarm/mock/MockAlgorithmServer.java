package com.hbirdedge.hnroboticarm.mock;

import Ice.Communicator;
import Ice.Current;
import Ice.DispatchStatus;
import Ice.ObjectAdapter;
import Ice.Util;
import IceInternal.BasicStream;
import IceInternal.Incoming;
import QNEdge.EErrorCode;
import QNEdge.EDirection;
import QNEdge.EFireControlMode;
import QNEdge.IQNEEventPrx;
import QNEdge.IQNEEventPrxHelper;
import QNEdge.SAlgConfig;
import QNEdge.SConfig;
import QNEdge.SDevice;
import QNEdge.SEvent;
import QNEdge.SPoint;
import QNEdge._IQNEAlgHandleDisp;
import QNEdge._IQNEArmControlDisp;
import QNEdge._IQNEConfigDisp;
import QNEdge._IQNEEventDisp;
import QNEdge._IQNEFCHInfoDisp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 【测试用】模拟算法端（ICE 服务端）。
 *
 * <p>在真实的 C# 算法端还没就绪时，用它验证双向链路是否打通：
 *
 * <pre>
 *   <li><b>应用内置</b>（推荐，模拟模式 {@code run       } —— {@code MockAlgorithmServerRunner} —— Spring 容器里托管，跟着应用一起起、一起停。
 *                     （我们：9988 HTTP + 10001 回调）
 *   <li><b>独立进程</b>：{@code mvn exec:java}，老用法端：10000）
 * </ol>
 *
 * <p>它会做三件事：
 * <ol>
 *     <li>在 {@code ice.alg.port}（默认 10000）起一个 ObjectAdapter，挂上 1 个「多接口 servant」</li>
 *     <li>每收到一次 MoveTask，就反向回调我们 10001 上的 {@code AddEvent}</li>
 *     <li>把收到的一切打印到控制台，肉眼即可确认"消息真的到了对端"</li>
 * </ol>
 *
 * <p><b>刻意与真实算法端保持一致的结构</b>：一个 servant 同时实现多个 Slice 接口，
 * 只用<b>一个暗号</b>注册（默认 {@code QNEArmControl}）。
 * 这样才能验证"我们这边一个暗号也能正确 narrow 成多个接口"。
 */
public final class MockAlgorithmServer {

    private static final Logger LOG = LoggerFactory.getLogger(MockAlgorithmServer.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 收到的调用流水（每条形如 {@code MoveTask direction=Up speed=5}）。
     *
     * <p>暴露出来是为了让 {@code GET /api/ice/test/received} 能查询 —— 
     * 这样 /b>不用盯着控制台/b>就能确认"消息真的到了对端"。
     */
    private static final List<String> RECEIVED_CALLS = new CopyOnWriteArrayList<>();

    /** 最近一次收到的配置 URL */
    private static final AtomicReference<String[]> LAST_CONFIG_URLS =
            new AtomicReference<>(new String[0]);

    private MockAlgorithmServer() {
    }

    /**
     * 收到的调用流水（副本）。
     */
    public static List<String> receivedCalls() {
        return List.copyOf(RECEIVED_CALLS);
    }

    /**
     * 清空流水 —— 每次测之前调一下，断言就只看这一次收到的东西。
     */
    public static void clearReceivedCalls() {
        RECEIVED_CALLS.clear();
        MockAlgorithmRecorder.reset();
    }

    /**
     * 最近一次收到的配置 URL。
     */
    public static String[] lastConfigUrls() {
        return MockAlgorithmRecorder.lastConfigUrls();
    }

    /**
     * 启动假算法端（应用内置模式）。
     *
     * @param communicator      复用应用的 ICE 引擎（整个进程只需要一个 Communicator）
     * @param selfPort          自己监听的端口
     * @param callbackHost      反向回调的目标 host
     * @param callbackPort      反向回调的目标端口
     * @param callbackIdentity  反向回调时用的暗号（必须是我们本地 servant 的暗号）
     * @param selfIdentity      自己注册 servant 用的暗号
     * @return 启动好的 ObjectAdapter（调用方负责 destroy）
     */
    public static ObjectAdapter start(Communicator communicator, int selfPort,
                                      String callbackHost, int callbackPort,
                                      String callbackIdentity, String selfIdentity) {
        ObjectAdapter adapter = communicator.createObjectAdapterWithEndpoints(
                "MockAlgorithmAdapter", "default -p " + selfPort);

        adapter.add(new UnifiedMockServant(communicator, callbackHost, callbackPort, callbackIdentity),
                communicator.stringToIdentity(selfIdentity));

        adapter.activate();

        log("模拟算法端已启动 端口:" + selfPort + " 暗号:" + selfIdentity
                + " 反向回调=" + callbackIdentity + "@" + callbackHost + ":" + callbackPort);
        return adapter;
    }

    public static void main(String[] args) {
        String selfHost = args.length > 0 ? args[0] : "127.0.0.1";
        int selfPort = args.length > 1 ? Integer.parseInt(args[1]) : 10000;
        String callbackHost = args.length > 2 ? args[2] : "127.0.0.1";
        int callbackPort = args.length > 3 ? Integer.parseInt(args[3]) : 10001;
        String callbackIdentity = args.length > 4 ? args[4] : "QNEdgeCallback";
        String selfIdentity = args.length > 5 ? args[5] : "QNEArmControl";

        Communicator communicator = Util.initialize();
        ObjectAdapter adapter = start(communicator, selfPort, callbackHost, callbackPort,
                callbackIdentity, selfIdentity);

        LOG.info("    ICE 监听端口 : {}", selfPort);
        LOG.info("    暗号         : {}（同时实现 IQNEArmControl / IQNEAlgHandle / IQNEEvent / IQNEFCHInfo / IQNEConfig）",
                selfIdentity);
        LOG.info("    反向回调目标 : {} @ {}:{}", callbackIdentity, callbackHost, callbackPort);
        LOG.info("    按 Ctrl+C 退出");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                adapter.destroy();
                communicator.destroy();
            } catch (Exception ignored) {
                // 关闭过程中的异常无需处理
            }
        }));

        // 阻塞主线程
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 多接口 servant —— 同时实现 5 个 Slice 接口，只用 1 个暗号注册。
     *
     * <p>结构上必须和我们 app 里的 {@code QNEdgeCallbackServant} 一致，
     * 否则测出来的行为不可信。
     */
    static class UnifiedMockServant extends Ice.ObjectImpl
            implements QNEdge.IQNEArmControl, QNEdge.IQNEAlgHandle,
            QNEdge.IQNEEvent, QNEdge.IQNEFCHInfo, QNEdge.IQNEConfig {

        /**
         * 所有 mock 实现的全集 Slice 类 ID 列表
         *
         * <p><b>必须按字典序排好</b>：Ice 用 {@code ice_isA} 做
         * {@code Arrays.binarySearch}，数组没排序就查不到 —— 
         * 表现是 {@code checkedCast} 静默返回 {@code null}。
         */
        private static final String[] IDS = sortedIds(
                "::QNEdge::IQNEArmControl",
                "::QNEdge::IQNEAlgHandle",
                "::QNEdge::IQNEEvent",
                "::QNEdge::IQNEFCHInfo",
                "::QNEdge::IQNEConfig");

        /** 拼 {@code ::Ice::Object} 并排序 —— Ice 要求 type id 数组有序 */
        private static String[] sortedIds(String... ids) {
            String[] all = new String[ids.length + 1];
            all[0] = "::Ice::Object";
            System.arraycopy(ids, 0, all, 1, ids.length);
            java.util.Arrays.sort(all);
            return all;
        }

        private final Communicator communicator;
        private final String callbackHost;
        private final int callbackPort;
        private final String callbackIdentity;

        UnifiedMockServant(Communicator communicator, String callbackHost, int callbackPort, String callbackIdentity) {
            this.communicator = communicator;
            this.callbackHost = callbackHost;
            this.callbackPort = callbackPort;
            this.callbackIdentity = callbackIdentity;
        }

        // ---- Ice.Object：告诉对端我实现了哪些接口 ----

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
            return IDS[1];
        }

        @Override
        public String ice_id(Current current) {
            return IDS[1];
        }

        @Override
        protected void __writeImpl(BasicStream os) {
            for (int i = 1; i < IDS.length; i++) {
                os.startWriteSlice(IDS[i], -1, i == IDS.length - 1);
                os.endWriteSlice();
            }
        }

        @Override
        protected void __readImpl(BasicStream is) {
            while (is.startReadSlice() != null) {
                is.endReadSlice();
            }
        }

        // ---- 统一分发 ----

        @Override
        public DispatchStatus __dispatch(Incoming in, Current current) {
            switch (current.operation) {
                case "MoveTask":
                    return _IQNEArmControlDisp.___MoveTask(this, in, current);
                case "ClickTask":
                    return _IQNEArmControlDisp.___ClickTask(this, in, current);
                case "TurnKey":
                    return _IQNEArmControlDisp.___TurnKey(this, in, current);
                case "SimulationClickGetCoordinates":
                    return _IQNEArmControlDisp.___SimulationClickGetCoordinates(this, in, current);
                case "AlgImageHandle":
                    return _IQNEAlgHandleDisp.___AlgImageHandle(this, in, current);
                case "StartAllDeviceALG":
                    return _IQNEAlgHandleDisp.___StartAllDeviceALG(this, in, current);
                case "StopALLDeviceALG":
                    return _IQNEAlgHandleDisp.___StopALLDeviceALG(this, in, current);
                case "StartSpecialDeviceALG":
                    return _IQNEAlgHandleDisp.___StartSpecialDeviceALG(this, in, current);
                case "StopSpecialDeviceALG":
                    return _IQNEAlgHandleDisp.___StopSpecialDeviceALG(this, in, current);
                case "GetEventList":
                    return _IQNEEventDisp.___GetEventList(this, in, current);
                case "AddEvent":
                    return _IQNEEventDisp.___AddEvent(this, in, current);
                case "DeleteEvent":
                    return _IQNEEventDisp.___DeleteEvent(this, in, current);
                case "UpdateEvent":
                    return _IQNEEventDisp.___UpdateEvent(this, in, current);
                case "CorrectButtonCoordinates":
                    return _IQNEFCHInfoDisp.___CorrectButtonCoordinates(this, in, current);
                case "Config":
                    return _IQNEConfigDisp.___Config(this, in, current);
                default:
                    return super.__dispatch(in, current);
            }
        }

        // ---- IQNEArmControl ----

        @Override
        public EErrorCode MoveTask(EDirection ed, int nSpeed, Current current) {
            String armId = armIdOf(current);
            log("MoveTask direction=" + ed + " speed=" + nSpeed
                    + (armId == null ? "" : " armId=" + armId));
            record("MoveTask direction=" + ed + " speed=" + nSpeed
                    + (armId == null ? "" : " armId=" + armId));
            MockAlgorithmRecorder.recordMoveTask(ed, nSpeed);
            MockAlgorithmRecorder.recordArmId(armId);
            pushEvent("MoveTask-" + ed + "-" + nSpeed);
            return EErrorCode.EECSuccess;
        }

        /**
         * 从 Ice Context 里取机械臂标识。
         *
         * <p><b>这就是 C# 端要做的同一件事</b>：Slice 里没有臂标识字段，
         * 臂标识走 {@code Ice.Context}，对端从 {@code Current.ctx} 读取。
         *
         * @param current 当前调用上下文
         * @return 臂标识；没带时为 null
         */
        private String armIdOf(Current current) {
            if (current == null || current.ctx == null) {
                return null;
            }
            return current.ctx.get("strArmID");
        }

        @Override
        public EErrorCode ClickTask(String[] seqButtonText, SPoint[] seqCoordinates, Current current) {
            log("ClickTask buttons=" + java.util.Arrays.toString(seqButtonText)
                    + " coords=" + java.util.Arrays.toString(seqCoordinates));
            // 用逗号拼接（不用 Arrays.toString 的方括号），方便直接看。
            MockAlgorithmRecorder.recordClickTask(
                    String.join(",", seqButtonText == null ? new String[0] : seqButtonText));
            return EErrorCode.EECSuccess;
        }

        @Override
        public EErrorCode TurnKey(EFireControlMode efcm, Current current) {
            log("TurnKey mode=" + efcm);
            MockAlgorithmRecorder.recordTurnKey(efcm);
            return EErrorCode.EECSuccess;
        }

        @Override
        public SPoint SimulationClickGetCoordinates(String strButtonText, Current current) {
            log("SimulationClickGetCoordinates button=" + strButtonText);
            MockAlgorithmRecorder.recordSimulatedClick(strButtonText);
            // 固定的假坐标，方便断点调试。
            return new SPoint(11, 22);
        }

        @Override
        public EErrorCode MoveTask(EDirection ed, int nSpeed) {
            return MoveTask(ed, nSpeed, null);
        }

        @Override
        public EErrorCode ClickTask(String[] seqButtonText, SPoint[] seqCoordinates) {
            return ClickTask(seqButtonText, seqCoordinates, null);
        }

        @Override
        public EErrorCode TurnKey(EFireControlMode efcm) {
            return TurnKey(efcm, null);
        }

        @Override
        public SPoint SimulationClickGetCoordinates(String strButtonText) {
            return SimulationClickGetCoordinates(strButtonText, null);
        }

        // ---- IQNEAlgHandle ----

        @Override
        public EErrorCode AlgImageHandle(SDevice sd, byte[] seqImg, SAlgConfig sac, Current current) {
            log("AlgImageHandle device=" + (sd == null ? "?" : sd.strID)
                    + " imgBytes=" + (seqImg == null ? 0 : seqImg.length));
            return EErrorCode.EECSuccess;
        }

        @Override
        public EErrorCode StartAllDeviceALG(SDevice[] sds, Current current) {
            log("StartAllDeviceALG count=" + (sds == null ? 0 : sds.length));
            MockAlgorithmRecorder.recordDeviceCount(sds == null ? 0 : sds.length);
            return EErrorCode.EECSuccess;
        }

        @Override
        public EErrorCode StopALLDeviceALG(Current current) {
            log("StopALLDeviceALG");
            return EErrorCode.EECSuccess;
        }

        @Override
        public EErrorCode StartSpecialDeviceALG(SDevice sd, Current current) {
            log("StartSpecialDeviceALG device=" + (sd == null ? "?" : sd.strID));
            return EErrorCode.EECSuccess;
        }

        @Override
        public EErrorCode StopSpecialDeviceALG(String strDeviceID, Current current) {
            log("StopSpecialDeviceALG device=" + strDeviceID);
            return EErrorCode.EECSuccess;
        }

        @Override
        public EErrorCode AlgImageHandle(SDevice sd, byte[] seqImg, SAlgConfig sac) {
            return AlgImageHandle(sd, seqImg, sac, null);
        }

        @Override
        public EErrorCode StartAllDeviceALG(SDevice[] sds) {
            return StartAllDeviceALG(sds, null);
        }

        @Override
        public EErrorCode StopALLDeviceALG() {
            return StopALLDeviceALG(null);
        }

        @Override
        public EErrorCode StartSpecialDeviceALG(SDevice sd) {
            return StartSpecialDeviceALG(sd, null);
        }

        @Override
        public EErrorCode StopSpecialDeviceALG(String strDeviceID) {
            return StopSpecialDeviceALG(strDeviceID, null);
        }

        // ---- IQNEEvent ----

        @Override
        public SEvent[] GetEventList(String strDeviceID, int nPage, Current current) {
            log("GetEventList device=" + strDeviceID + " page=" + nPage);
            return new SEvent[0];
        }

        @Override
        public EErrorCode AddEvent(SEvent se, Current current) {
            log("AddEvent id=" + se.strID);
            return EErrorCode.EECSuccess;
        }

        @Override
        public EErrorCode DeleteEvent(SEvent se, Current current) {
            log("DeleteEvent id=" + se.strID);
            return EErrorCode.EECSuccess;
        }

        @Override
        public EErrorCode UpdateEvent(SEvent se, Current current) {
            log("UpdateEvent id=" + se.strID);
            return EErrorCode.EECSuccess;
        }

        @Override
        public SEvent[] GetEventList(String strDeviceID, int nPage) {
            return GetEventList(strDeviceID, nPage, null);
        }

        @Override
        public EErrorCode AddEvent(SEvent se) {
            return AddEvent(se, null);
        }

        @Override
        public EErrorCode DeleteEvent(SEvent se) {
            return DeleteEvent(se, null);
        }

        @Override
        public EErrorCode UpdateEvent(SEvent se) {
            return UpdateEvent(se, null);
        }

        // ---- IQNEFCHInfo ----

        @Override
        public EErrorCode CorrectButtonCoordinates(String strButtonText, SPoint spNew, Current current) {
            log("CorrectButtonCoordinates button=" + strButtonText
                    + " x=" + (spNew == null ? "?" : spNew.nX)
                    + " y=" + (spNew == null ? "?" : spNew.nY));
            return EErrorCode.EECSuccess;
        }

        @Override
        public EErrorCode CorrectButtonCoordinates(String strButtonText, SPoint spNew) {
            return CorrectButtonCoordinates(strButtonText, spNew, null);
        }

        // ---- IQNEConfig ----

        @Override
        public EErrorCode Config(SConfig sc, Current current) {
            log("Config urls=" + java.util.Arrays.toString(sc == null ? null : sc.seqURL));
            LAST_CONFIG_URLS.set(sc == null || sc.seqURL == null ? new String[0] : sc.seqURL.clone());
            MockAlgorithmRecorder.recordConfigUrls(sc == null ? null : sc.seqURL);
            record("Config urls=" + java.util.Arrays.toString(sc == null ? null : sc.seqURL));
            return EErrorCode.EECSuccess;
        }

        @Override
        public EErrorCode Config(SConfig sc) {
            return Config(sc, null);
        }

        /** 反向回调我们本地 10001 的 servant，推一条事件过去 */
        private void pushEvent(String tag) {
            try {
                Ice.ObjectPrx base = communicator.stringToProxy(
                        callbackIdentity + ":default -h " + callbackHost + " -p " + callbackPort);
                IQNEEventPrx eventPrx = IQNEEventPrxHelper.uncheckedCast(base);

                SEvent event = new SEvent();
                event.strID = "MOCK-" + System.currentTimeMillis();
                event.strDeviceID = "MOCK-DEVICE";
                event.strName = "来自模拟算法端的事件 " + tag;
                event.strType = tag;
                event.strCreateDTime = TS.format(LocalDateTime.now());
                event.strDesc = "由 MockAlgorithmServer 反向回调推送";

                EErrorCode code = eventPrx.AddEvent(event);
                log("  → 反向回调 AddEvent(" + event.strID + ") 返回 " + code);
            } catch (Exception e) {
                log("  → 反向回调失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    /** 记录一条收到的调用（供接口查询用） */
    static void record(String call) {
        RECEIVED_CALLS.add(call);
    }

    static void log(String message) {
        LOG.info("[{}] [MockAlgorithm] {}", TS.format(LocalDateTime.now()), message);
    }
}
