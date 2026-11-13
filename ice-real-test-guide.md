# 真实 C# 算法端测试指南

> 目标：用**真实算法端**跑通机械臂上下左右。
>
> 现在的做法：**一个 `real` 参数**，不写脚本、不改代码。
> ```bash
> curl -X POST "http://127.0.0.1:9988/api/ice/test/arm/sweep?speed=5&real=true"
> ```

---

## 0. 先说结论：现在能测到什么，不能测到什么

我把本机能找到的 C# 算法端翻了一遍（`D:\workSpace\hnserver-patch\HNServer.dll.orig` 的字符串常量），
得出一个**必须先解决**的问题：

| | 前项目 C# 算法端（`HNServer.dll`） | 本项目 `QNEdge.ice` |
|---|---|---|
| Slice 模块名 | `SMT` | `QNEdge` |
| 事件接口 | `::SMT::ISMMSEvent` | `::QNEdge::IQNEEvent` |
| 算法接口 | `::SMT::ISMMSAlgHandle` | `::QNEdge::IQNEAlgHandle` |
| 配置接口 | `::SMT::ISMMSConfig` | `::QNEdge::IQNEConfig` |
| **机械臂接口** | ❌ **没有** | `::QNEdge::IQNEArmControl` |

`HNServer.dll` 里**根本不存在** `MoveTask` / `IQNEArmControl` / `EDirection` 这几个字符串（已逐个二进制搜索确认）。

### 这意味着两件事

**① Slice 模块名不同 → 类型 ID 不同 → 接口对不上**

Ice 的类型 ID 是 `模块名::接口名`。`::SMT::ISMMSEvent` 和 `::QNEdge::IQNEEvent`
是**两个完全不同的类型**，哪怕方法签名一模一样。

后果：对端 `ice_isA("::QNEdge::IQNEEvent")` 会返回 `false`，
我们 `checkedCast` 拿不到代理，调用直接抛 `Ice.MarshalException` / `OperationNotExistException`。

**② 机械臂控制接口，真实算法端根本还没有**

`MoveTask` 是 `QNEdge.ice` 新增的接口，C# 那边要重新用 `QNEdge.ice` 生成代码并实现才有。

### 所以真实测试分两步

| 步骤 | 目标 | 前置条件 | 能不能现在做 |
|---|---|---|---|
| **A. 打通链路** | 验证 ICE 双向通信、暗号、事件回调 | 有一台跑 `QNEdge.ice` 的算法端 | ❌ 需要算法端 |
| **B. 测机械臂** | 验证上下左右 | 算法端实现了 `IQNEArmControl` | ❌ 同上 |

> **如果算法端还没用 `QNEdge.ice` 重编，本地能做的只有假端测试**
> （`mvn test`，57 个用例，已全绿）。
> 真实测试的阻塞点在 **C# 端**，不在 Java 端。

---

## 1. 已经确认的真实约定（不用猜）

这些是从二进制和旧项目配置里**反查出来的**，不是推测：

### 1.1 算法端身份名 = `AlgHandle`

`HNServer.dll` 的字符串常量里有 `AlgHandle`，旧项目配置也是这么配的：

```yaml
# d:\workSpace\hBird-edge-ice\src\main\resources\application.yaml
ice:
  alg:
    host: 192.168.0.103
    port: 10000
    identity: AlgHandle        # ← 算法端 servant 的身份名
```

### 1.2 回调身份名 = `SMTEvent`（硬编码，不可改）

`HNServer.dll` 里有个字符串常量：

```
SMTEvent:default -h ? -p ?
```

这是算法端**反向回调我们**时拼出来的 proxy 字符串，`SMTEvent` 是写死在代码里的。
所以**我们本地 servant 的身份名必须叫 `SMTEvent`**，改别的名字算法端就找不到我们。

### 1.3 算法端不提供 `ISMMSConfig` 服务

旧项目配置里有这么一行注释：

```yaml
  # 算法端 ISMMSConfig 的 identity（默认 SMMSConfig，暂未使用）
  alg-config-identity: SMMSConfig
```

对应到本项目就是：推配置给算法端时可能拿到 `Ice.ObjectNotExistException`。
`ConfigService.pushToAlgServer` 已经**优雅捕获**这个异常并打 WARN，不会炸 —— 这是故意的。

---

## 2. 怎么切到真实算法端

### 方式 A：配置文件（推荐）

**现在 `application.yaml` 默认就是真实算法端** —— 现场部署时它就是生效的那份，
所以默认就该连现场那台机器。

```yaml
# src/main/resources/application.yaml（默认值，不用改）
ice:
  alg:
    host: 192.168.0.103      # 真实算法端机器 IP
    port: 10000
    identity: AlgHandle      # ← 真实算法端身份名
  local:
    host: 192.168.0.209     # ← 本机 IP，算法端要反向连到这里
    port: 10001
    identity: SMTEvent      # ← 算法端硬编码认这个名字
  test:
    default-real: false    # ← 默认值；真机测试时用 real=true 参数指定
```

想显式表达"我要打真机"，用 profile：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=real
```

### 方式 B：直接用参数（推荐）

**不需要切任何开关** —— 每个接口都带 `real` 参数：

```bash
# 打真机
curl -X POST "http://127.0.0.1:9988/api/ice/test/arm/move?direction=Down&speed=5&real=true"

# 四个方向扫一遍
curl -X POST "http://127.0.0.1:9988/api/ice/test/arm/sweep?speed=5&real=true"

# 看真机通不通
curl "http://127.0.0.1:9988/api/ice/test/status?real=true"
```

> ⚠️ **`ice.local.identity` 必须是 `SMTEvent`**，不能是默认的 `QNEdgeCallback`。
> 用默认值的唯一后果就是：算法端回调我们时抛 `ObjectNotExistException`，
> 表现为「调我们正常、但我们永远收不到算法端的推送」。

> 💡 **反过来的也行**（现场 C# 端没起来时，先用假端把逻辑调通）：
> 同一个接口把 `real=true` 改成 `real=false` 就是假端，
> 不用重启应用、不用改配置。

---

## 3. 真实测试步骤

### 步骤 1：确认网络连通

```powershell
# 本机 IP 必须是 192.168.0.209（算法端要反向连这个 IP）
ipconfig | Select-String "192.168"

# 算法端端口通不通
Test-NetConnection -ComputerName 192.168.0.103 -Port 10000
```

`TcpTestSucceeded` 必须是 `True`。**目前实测是 `False`** —— 说明算法端没启动或 IP 变了。

### 步骤 2：启动算法端（C# 机器上）

```powershell
.\HNServer.exe
```

看到 `start server success!!!` 说明起来了。

### 步骤 3：启动本项目

```bash
mvn spring-boot:run
```

### 步骤 4：确认目标状态 + 探测暗号

```bash
curl "http://127.0.0.1:9988/api/ice/test/status?real=true"
curl "http://127.0.0.1:9988/api/ice/probe?real=true"
```

能通的那个会标 `OK`，并列出对端实现了哪些 Slice 类型：

```json
{
  "real": true,
  "candidates": {
    "AlgHandle": "OK  ids=::Ice::Object,::QNEdge::IQNEArmControl,...",
    "QNEArmControl": "IDENTITY_NOT_FOUND"
  }
}
```

**如果所有候选都是 `IDENTITY_NOT_FOUND`**，说明：
- 端口不通（`CONNECTION_REFUSED`），或
- 身份名不对（对照第 1.1 节）

### 步骤 5：三层自检 —— 关键判据

```bash
curl http://127.0.0.1:9988/api/ice/check
```

关键看 `ids` 数组里有没有 `::QNEdge::IQNEArmControl`：

| 现象 | 含义 |
|---|---|
| `ids` 里**没有** `::QNEdge::IQNEArmControl` | 算法端还是旧的 `SMT` 版本 → **模块名不匹配，测不了机械臂** |
| `ids` 里**有** `::QNEdge::IQNEArmControl` | 算法端已用 `QNEdge.ice` 重编 → 可以测机械臂 |

### 步骤 6：测机械臂四个方向

```bash
# 一条命令测完四个方向
curl -X POST "http://127.0.0.1:9988/api/ice/test/arm/sweep?speed=5&real=true"
```

返回：

```json
{
  "real": true,
  "speed": 5,
  "steps": [
    {"label": "从上往下", "direction": "Down",  "code": "EECSuccess"},
    {"label": "从下往上", "direction": "Up",    "code": "EECSuccess"},
    {"label": "从左往右", "direction": "Right", "code": "EECSuccess"},
    {"label": "从右往左", "direction": "Left",  "code": "EECSuccess"}
  ],
  "allSuccess": true
}
```

**同时看算法端控制台**，它应该打印出对应的 MoveTask 指令。

### 步骤 7：验证反向回调（算法端 → 我们）

```bash
curl http://127.0.0.1:9988/api/events
```

如果算法端推了事件过来，这里能看到 —— **这证明 `SMTEvent` 这个身份名配对了**。

---

## 4. 排查对照表

| 现象 | 根因 | 修法 |
|---|---|---|
| `CONNECTION_REFUSED` | 算法端没起 / IP 变了 | `ice.alg.host` / `ice.alg.port` |
| `IDENTITY_NOT_FOUND` | 身份名不对 | 应为 `AlgHandle`（见 1.1），用 `/api/ice/probe` 确认 |
| `ids` 里没有 `::QNEdge::IQNEArmControl` | **Slice 模块名不匹配**（算法端还是 `SMT`） | 算法端用 `QNEdge.ice` 重新生成代码并实现 |
| `ids` 里有但 `MoveTask` 报 `OperationNotExistException` | 算法端注册了接口但没实现这个方法 | 算法端补实现 |
| 调我们正常，但我们收不到算法端推送 | `ice.local.identity` 不是 `SMTEvent` | 改成 `SMTEvent`（见 1.2） |
| 推配置给算法端返回 `ObjectNotExistException` | 算法端没暴露 Config 服务 | **正常现象**，代码已优雅降级为 WARN |
| 机械臂动了但方向和预期相反 | 坐标系定义问题 | `.ice` 没定义语义，看算法端实现 |

---

## 5. 需要向算法端同事确认的 3 个问题

1. **你们用 `QNEdge.ice` 重新生成过 C# 代码吗？**
   还是仍用 `SMT.ice`？（决定能不能测机械臂）

2. **`IQNEArmControl` 的 `MoveTask` 实现了吗？**
   `EDirection.Up` 是"机械臂升高"还是"关节上抬"？坐标系怎么定义的？

3. **`MoveTask` 的 `nSpeed` 取值范围是几档？**
   `.ice` 里只写了 `int`，没约束。1~10？0~100？

---

## 6. 现在的状态小结

| 项 | 状态 |
|---|---|
| Java 端代码 | ✅ 完成 |
| 真机/模拟切换 | ✅ **一个 `real` 参数**（测试方法上、REST 接口上都一样），不用切配置、不用重启 |
| 机械臂方向测试 | ✅ 4 个方法 × 2 种模式 = 8 个用例 |
| 真机链路自检 | ✅ `RealModeLinkTests` —— 假端冒充真机，证明 `real=true` 代码路径本身是通的 |
| 默认配置 | ✅ `application.yaml` 默认连内置假端（克隆下来就能跑）；`real` profile = 默认连真机 |
| 真实暗号（`AlgHandle` / `SMTEvent`） | ✅ 已从二进制反查确认，并已写进默认配置 |
| 真机连不上时 | ✅ **如实失败**并说清楚连的是哪台机器、试过哪些暗号（不自动降级、不掩盖） |
| 真实算法端连通性 | ❌ `192.168.0.103:10000` 当前**连接被拒绝**（算法端没启动 / IP 变了） |
| Slice 模块名兼容 | ❌ 本地 C# 是 `SMT`，本项目是 `QNEdge` → **阻塞点** |
| 机械臂接口实现 | ❌ 旧 C# 里没有 `MoveTask` → **阻塞点** |

### ⚠️ 真机测试目前卡在哪

**当前实测**：`192.168.0.103:10000` 返回"连接被拒绝"
（说明这台机器在线，但 10000 端口上没有 ICE 服务在监听 —— 大概率 `HNServer.exe` 没启动）。

即使 C# 端起来了，还有**两个 Slice 层面的阻塞点**必须先解决：

1. **模块名不一致** —— C# 端是 `::SMT::*`，本项目是 `::QNEdge::*`。
   类型 ID 不同 = 方法签名不同 = **调不通**。
2. **机械臂接口不存在** —— `IQNEArmControl` / `MoveTask` 是 `QNEdge.ice` 新增的，
   旧 C# 代码里根本没有这几个字符串。

所以 C# 端需要**用 `QNEdge.ice` 重新生成代码并实现 `IQNEArmControl`**。

**怎么确认对方做到哪一步了**：应用起来后打这个接口，

```bash
curl http://127.0.0.1:9988/api/ice/check
```

看 `remoteTypeIds` 里有没有 `::QNEdge::IQNEArmControl`：

| 现象 | 含义 |
|---|---|
| `ids` 里**没有** `::QNEdge::IQNEArmControl` | C# 端还是旧 `SMT` 版本 → **还测不了机械臂** |
| `ids` 里**有** `::QNEdge::IQNEArmControl` | C# 端已用 `QNEdge.ice` 重编 → **可以测机械臂** |

接口还会自动检测模块名并在 `hint` 字段里说明原因，不用自己猜。
