# ICE 新接口对接 5 步走

> 适用项目：任何 ZeroC Ice 3.7+ 的 Java 端项目（以 [`hBird-edge-ice`](../) 为示例）
> 读者：第一次用 ICE 调别人（或被别人调）的小明
> 目的：把"我下一步该干什么"讲透——5 步，每步明确"打开哪个文件、加什么代码、为什么"

---

## 0. 一句话先镇场

ICE 跨语言调用，本质就是**"按暗号找人 + 调他身上的方法"**。暗号一致 → 找到 → 直接当本地方法调。**没有别的**。

| 比喻               | 真实 ICE 概念                                  |
|------------------|--------------------------------------------|
| 小明               | 您的 Java 进程（客户端/服务端都可能）                     |
| 小王               | 算法端（C# 进程，监听某个端口）                          |
| 小明拿的纸条           | 代理 Proxy（`stringToProxy`）                  |
| 纸条上写的暗号          | Identity（`stringToIdentity("XXX")`）        |
| 暗号对得上            | 调得到；暗号对不上                                  | 抛 `ObjectNotExistException` |
| "找小王手里那个叫 X 的对象" | `stringToProxy("X:default -h IP -p PORT")` |

---

## 1. 整体流程图（按这个顺序写代码）

```
① slice2java 编译 .ice
        ↓ 生成 com.<module>.* Java 类
② 复制到 src/main/java + 加 ice 依赖
        ↓ 代码能编译通过
③ 写 IceConfig 初始化 Communicator（@Bean）
        ↓ ICE 引擎在进程里启动
④ 决定方向：要不要被算法端回调？
        ├─ 是 → 起 ObjectAdapter + 实现接口基类
        └─ 否 → 跳过
⑤ 写业务 Client：stringToProxy → uncheckedCast → 调方法
```

**严格按顺序**。第 1 步不做，第 2 步没法写；第 2 步不做，第 3 步跑不起来；第 3 步不做，第 5 步没东西可用。

---

## 2. 第 1 步：编译 `.ice` 文件生成 Java 桩代码

### 2.1 为什么必须做

`.ice`（Slice 文件）是**两端共享的"接口合同"**——里面写的方法签名、参数类型、返回值类型，会被 `slice2java` 翻译成 Java 类。

**没有这一步，后面所有步骤都没法做**——Java 类不存在，`import` 都报错。

### 2.2 怎么知道要编译什么

打开目标项目的 `.ice` 文件（本项目的 [`SMT.ice`](../SMT.ice) 是 `module SMT`，您对接的新项目可能是 `module QNEdge`
等），找到您要调用的 `interface` 块。

比如本项目 `SMT.ice` 的 `interface ISMMSAlgHandle`：

```slice
interface ISMMSAlgHandle
{
    EErrorCode AlgImageHandle(SDevice sd, SEQImage seqImg, SAlgConfig sac);
    EErrorCode StartAllDeviceALG(SEQDevice sds);
    EErrorCode StopALLDeviceALG();
    EErrorCode StartSpecialDeviceALG(SDevice sd);
    EErrorCode StopSpecialDeviceALG(string strDeviceID);
};
```

**这 5 个方法就是您之后唯一能调的方法**。要加新方法？**必须先改** `.ice` **→ 重新编译**。您不能凭空在 Java 端加方法。

### 2.3 编译命令

```bash
# 安装 ZeroC Ice slice 编译器
# 下载地址：https://zeroc.com/downloads/ice

# 编译（本项目示例）
slice2java --output-dir src/main/java SMT.ice
```

### 2.4 编译后生成了什么

按 `.ice` 里写的名字，**每个名字生成 3 类产物**：

| `.ice` 里写的                 | 生成的 Java 类                    | 用途                                       |
|----------------------------|-------------------------------|------------------------------------------|
| `interface ISMMSAlgHandle` | `ISMMSAlgHandlePrx`           | 代理接口（您拿来调用的）                             |
| 同上                         | `ISMMSAlgHandlePrxHelper`     | 工厂类（`checkedCast` / `uncheckedCast` 都在这） |
| 同上                         | `_ISMMSAlgHandleDisp`         | **基类**（您要**被**算法端调时 `extends` 它）         |
| `struct SDevice`           | `SDevice`                     | 普通 POJO                                  |
| `enum EErrorCode`          | `EErrorCode`                  | Java 枚举                                  |
| `sequence<SDevice>`        | `SDevice[]` 或 `List<SDevice>` | 数组 / 列表                                  |

> 本项目已经把 `SMT.ice` 编译过了，产物在 `com.hbirdedge.ice` 包的 `SMT` 子包下。**您新项目要重新编译**。

---

## 3. 第 2 步：把生成代码塞进项目 + 加 Maven 依赖

### 3.1 拷贝生成产物

把 `slice2java` 生成的整个包目录，拷贝到您项目的 `src/main/java/` 下，保持包结构一致。

例如 `.ice` 里写 `module SMT`，则产物路径：

```
src/main/java/com/hbirdedge/ice/SMT/
    ISMMSAlgHandlePrx.java
    ISMMSAlgHandlePrxHelper.java
    _ISMMSAlgHandleDisp.java
    SDevice.java
    EErrorCode.java
    ...
```

### 3.2 加 ICE runtime 依赖

`pom.xml` 里：

```xml
<dependency>
    <groupId>com.zeroc</groupId>
    <artifactId>ice</artifactId>
    <version>3.7.10</version>
</dependency>
```

### 3.3 验证标准

到这一步，**项目能编译通过**——`import com.<module>.<InterfaceName>Prx;` 不报错。

**但还跑不起来**——ICE 还没真正"动起来"。

---

## 4. 第 3 步：初始化 ICE 引擎（Communicator）

### 4.1 做什么

在 Java 进程里**启动 ICE runtime**。**整个进程只需要 1 个** `Communicator`。

**对应小王（C# 算法端）的代码**：

```csharp
CGlobalData.g_com = Ice.Util.initialize(initData);
```

**对应 Java 端您要写的代码**——基于本项目 [`IceConfig.java`](../src/main/java/com/hbirdedge/ice/config/IceConfig.java)
简化：

```java
// 文件：src/main/java/com/qnedge/config/QNIceConfig.java
package com.qnedge.config;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.InitializationData;
import com.zeroc.Ice.Util;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QNIceConfig {

    @Bean(destroyMethod = "destroy")
    public Communicator iceCommunicator() {
        InitializationData initData = new InitializationData();
        initData.properties = Util.createProperties();

        // 强制 TCP
        initData.properties.setProperty("Ice.Default.Protocol", "tcp");
        // 消息上限（默认 1MB，图片事件容易超）
        initData.properties.setProperty("Ice.MessageSizeMax", "10485760");
        // 超时
        initData.properties.setProperty("Ice.Override.Timeout", "30000");

        return Util.initialize(initData);
    }
}
```

### 4.2 关键点

- `@Bean(destroyMethod = "destroy")` = Spring 关闭时自动释放引擎，**不写会内存泄漏**
- `initData.properties` 是**全局 ICE 配置**（超时、消息上限、协议族等）
- 这一步**只是把引擎开起来**，还没有"接电话的客服"、还没有"号码本"

### 4.3 验证标准

Spring 能正常启动（`HBirdEdgeIceApplication` 起来不报 NoSuchBean / 初始化异常）。

---

## 5. 第 4 步：决定方向——要不要被算法端回调

**这一步有方向性，必须分清楚**。

### 5.1 两种角色

| 角色                 | 含义          | 例子                          |
|--------------------|-------------|-----------------------------|
| **调用方**（Client 角色） | 您**主动**调算法端 | 您让机械手臂 `MoveTask(Left, 5)`  |
| **被调方**（Server 角色） | 算法端**主动**调您 | 算法端把检测事件 `AddEvent(se)` 推给您 |

**一个 Java 进程可以同时是两种**（本项目就是——又调算法端 `StartAllDeviceALG`，又被算法端调 `AddEvent`）。

### 5.2 只需要当调用方？跳过本步

如果您只调算法端（机械手臂场景就是这种），**直接跳到第 5 步**。

### 5.3 需要被算法端调用？起 ObjectAdapter + 实现接口基类

#### 5.3.1 起"接电话的客服"（ObjectAdapter）

**对应小王的代码**：

```csharp
CGlobalData.g_adapter = CGlobalData.g_com.createObjectAdapterWithEndpoints(
    "AlgHandle", "default -p 10000");
```

**对应 Java 端**，参考本项目 
[](../src/main/java/com/hbirdedge/ice/bootstrap/IceClientBootstrap.java)`IceClientBootstrap.java:108-117`：

```java
@Bean
public ObjectAdapter smtEventCallbackAdapter(Communicator communicator) {
    // 起一个"接电话的客服"，监听 10001
    ObjectAdapter adapter = communicator.createObjectAdapterWithEndpoints(
        "SMTEventCallbackAdapter",       // adapter 名字（仅人类可读）
        "default -p 10001"               // 监听端口
    );
    adapter.activate();                  // 开始接电话
    return adapter;
}
```

#### 5.3.2 实现 servant（真正干活的工人）

继承 `_InterfaceNameDisp` 基类（本项目示例 `ISMMSEventImpl`）：

```java
// 文件：src/main/java/com/hbirdedge/ice/impl/ISMMSEventImpl.java
package com.hbirdedge.ice.impl;

import com.hbirdedge.ice.SMT.EErrorCode;
import com.hbirdedge.ice.SMT.SEvent;
import com.hbirdedge.ice.SMT._ISMMSEventDisp;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
public class ISMMSEventImpl extends _ISMMSEventDisp {

    // 算法端会调的 3 个方法
    @Override
    public EErrorCode AddEvent(SEvent se, com.zeroc.Ice.Current current) {
        System.out.println("收到事件: " + se.strID);
        return EErrorCode.EECSuccess;
    }

    @Override
    public EErrorCode UpdateEvent(SEvent se, com.zeroc.Ice.Current current) {
        return EErrorCode.EECSuccess;
    }

    @Override
    public EErrorCode DeleteEvent(SEvent se, com.zeroc.Ice.Current current) {
        return EErrorCode.EECSuccess;
    }
}
```

#### 5.3.3 把"工人"挂到"客服"上，**起一个暗号**

**关键一步**——本项目 
[](../src/main/java/com/hbirdedge/ice/bootstrap/IceClientBootstrap.java)`IceClientBootstrap.java:111-117`：

```java
@PostConstruct  // Spring 启动后自动调
public void registerServant() {
    // 暗号必须和算法端代码里 stringToProxy("SMTEvent:...") 的名字完全一致
    smtEventCallbackAdapter.add(
        iSMMSEventImpl,
        communicator.stringToIdentity("SMTEvent")   // ← 暗号
    );
}
```

> ⚠️ **暗号对不上 = 找不到**。`SMTEvent`（本项目）必须和算法端 C# 代码里 `stringToProxy("SMTEvent:...")` 的第一个字段**一字不差
> **。

---

## 6. 第 5 步：写业务 Client——创建代理、调用算法端

**这是您真正"调算法端"的代码——所有 ICE 业务逻辑的核心，就这 2 行**。

### 6.1 核心 2 行

```java
// ① 拿"号码本"
ObjectPrx base = communicator.stringToProxy(
    "AlgHandle:default -h 192.168.0.103 -p 10000"
);

// ② 窄化成 ISMMSAlgHandle 接口的代理
ISMMSAlgHandlePrx algPrx = ISMMSAlgHandlePrxHelper.uncheckedCast(base);
```

**逐字拆解** `stringToProxy` **的参数**：

```
"AlgHandle:default -h 192.168.0.103 -p 10000"
  │          │       │                  │
  │          │       │                  └─ 算法端端口
  │          │       └─ 算法端 IP
  │          └─ 用默认协议族（TCP）
  └─ 暗号（identity），必须和算法端 stringToIdentity("AlgHandle") 一字不差
```

### 6.2 完整业务 Client 示例

参考本项目 [`IceClientBootstrap.java:155-165`](../src/main/java/com/hbirdedge/ice/bootstrap/IceClientBootstrap.java)
的写法：

```java
// 文件：src/main/java/com/hbirdedge/ice/client/AlgDevicePusher.java
package com.hbirdedge.ice.client;

import com.hbirdedge.ice.SMT.EErrorCode;
import com.hbirdedge.ice.SMT.ISMMSAlgHandlePrx;
import com.hbirdedge.ice.SMT.ISMMSAlgHandlePrxHelper;
import com.hbirdedge.ice.SMT.SDevice;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectPrx;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AlgDevicePusher {

    @Value("${ice.alg.host}")     private String algHost;
    @Value("${ice.alg.port}")     private int    algPort;
    @Value("${ice.alg.identity}") private String algIdentity;

    private final Communicator communicator;
    private ISMMSAlgHandlePrx algPrx;

    public AlgDevicePusher(Communicator communicator) {
        this.communicator = communicator;
    }

    @PostConstruct
    public void init() {
        // 核心 2 行
        String proxyStr = algIdentity + ":default -h " + algHost + " -p " + algPort;
        ObjectPrx base = communicator.stringToProxy(proxyStr);
        algPrx = ISMMSAlgHandlePrxHelper.uncheckedCast(base);
    }

    // 业务方法：推设备列表给算法端启动分析
    public EErrorCode pushDevices(SDevice[] devices) {
        // 看起来像调本地方法，实际 ICE 帮您序列化后发到 10000
        return algPrx.StartAllDeviceALG(devices);
    }
}
```

### 6.3 `application.yaml` 配套配置

```yaml
ice:
  alg:
    host: 192.168.0.103        # 算法端 IP
    port: 10000                # 算法端端口
    identity: AlgHandle         # 暗号，必须和算法端 stringToIdentity("AlgHandle") 一字不差
```

### 6.4 `checkedCast` vs `uncheckedCast` 怎么选

| 写法                    | 行为                                | 何时用                                    |
|-----------------------|-----------------------------------|----------------------------------------|
| `uncheckedCast(base)` | **不验证**，按 Slice 编译时锁死的方法名直接发      | 对方肯定有（自己控制的服务端）                        |
| `checkedCast(base)`   | **真的去网络问**"你那头有 X 吗"，没有就返回 `null` | 对服务端存疑（参考小王 `checkedCast` 验证 SMTEvent） |

**本项目一律用** `uncheckedCast`——因为服务端是合作方，Slice 编译时已经对齐，运行时不再验。

### 6.5 调方法的本质

```java
algPrx.StartAllDeviceALG(devices);
// 看起来一行，实际 ICE 内部自动做了：
//   1. 把 devices[] 数组按 Slice 编码规则序列化成二进制
//   2. 通过 TCP 发到 192.168.0.103:10000
//   3. 算法端 ICE 引擎反序列化
//   4. 找到暗号是 AlgHandle 的工人（new CRPCAlgHandle()）
//   5. 调它的 StartAllDeviceALG 方法
//   6. 把返回值按同样规则编码回传
```

---

## 7. 最容易踩的 4 个坑

### 坑 1：暗号对不上 → 找不到人

| 您的配置                  | 算法端代码                            | 结果                                 |
|-----------------------|----------------------------------|------------------------------------|
| `identity: AlgHandle` | `stringToIdentity("AlgHandle")`  | 找到                                 |
| `identity: AlgHandle` | `stringToIdentity("ALGHandle")`  | 抛 `ObjectNotExistException`（大小写敏感） |
| `identity: AlgHandle` | `stringToIdentity(" algHandle")` | 找不到（多空格）                           |

### 坑 2：只配 `host:port` 不配 `identity` 找不到人

```yaml
ice.alg.host: 192.168.0.103
ice.alg.port: 10000
# identity 漏了
```

最终拼成 `null:default -h ... -p 10000`——端口能连上，但 ICE 不知道找哪个身份的对象。

### 坑 3：双向通信时两端的暗号要分开

- **您调算法端**：暗号是 `AlgHandle`（对应 `ice.alg.identity`）
- **算法端调您**：暗号是 `SMTEvent`（对应 `ice.event.identity`，且**算法端硬编码**）

**两个暗号是不同的字符串**，各管各的方向：

- `SMTEvent` 是您**自己起 adapter 时注册**的 servant 名字
- `AlgHandle` 是您**主动连对方时要找的**那个 servant 名字

### 坑 4：接口调用顺序错了

如果某方法**依赖另一次调用**，例如先 `StartAllDeviceALG` 才能 `StopALLDeviceALG`，ICE 不会帮您检查——`StopALLDeviceALG`
会照常被发到算法端，由对方决定返回什么错。

---

## 8. 您能改的 vs 您不能改的

| 您能改的                                            | 您不能改的（算法端定的）               |
|-------------------------------------------------|----------------------------|
| `application.yaml` 里的 `host:port`               | `.ice` 文件里的 `interface` 名字 |
| `application.yaml` 里的 `identity`（**但必须和算法端一致**） | `.ice` 文件里的方法签名            |
| 调方法的**业务逻辑**（什么时候调、参数从哪来）                       | 方法的**实现**（算法端写的）           |
| 超时时间、消息大小等 ICE runtime 参数                       | 端口号（**算法端定的，您要配合**）        |
| 您**自己**的 servant 实现（第 5.3.2 步）                  | —                          |

---

## 9. 写在最后

ICE 不复杂——它就是把远程调用"伪装"成本地方法调用。**所有抽象（Proxy/Adapter/Identity/Servant/Communicator）拆开看都很土**：

- Communicator = 引擎
- ObjectAdapter = 接电话的客服
- Servant = 干活的工人
- Identity = 工人被叫时的暗号
- Proxy = 号码本

**只要暗号对上，其他都是流水线**。您下次写代码时，问自己一个问题：

> "我这一步是在起引擎 / 找号码本 / 挂工人 / 还是让工人接电话？"

答对了，方向就不会偏。

---

## 10. 配套阅读

- [`ice-end-to-end-protocol.md`](./ice-end-to-end-protocol.md) — 本项目 4 端 × 3 协议的全链路梳理
- [`SMT.ice`](../SMT.ice) — 本项目 Slice 接口定义（参考对照）
- [`IceClientBootstrap.java`](../src/main/java/com/hbirdedge/ice/bootstrap/IceClientBootstrap.java) — 完整示例
