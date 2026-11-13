# ICE 消息收发自测指南

> 目标：**一个 `real` 参数决定「连假算法端」还是「连真实 C# 算法端」**，
> 两种模式下 REST 接口完全一样，区别只是"对面是谁"。

---

## 0. 核心：一个参数决定连谁

### 0.1 语义

`real` 参数**只有两个值**，没有第三种：

| `real` | 连谁 | 什么时候用 |
|---|---|---|
| `false` | **内置假算法端** `127.0.0.1:10000` | 开发、自测、CI。克隆下来就能跑，不用装 C# 程序 |
| `true` | **真实 C# 算法端** `ice.alg.host:ice.alg.port` | 现场验收（C# 程序已启动） |

**这个参数是唯一真相。** 不存在"自动降级""超时自动切"这种隐式行为：
你传 `real=true`，就直连真机，连不上就是**失败**，明确告诉你真机没通。

### 0.2 两个 profile（只改"默认值"）

| profile | 配置文件 | 不传参数时默认连谁 |
|---|---|---|
| **默认**（不指定） | `application.yaml` | **内置假算法端** |
| **`real`** | `application-real.yaml` | **真实 C# 算法端** `192.168.0.103:10000` |

```bash
# 默认（假端）
mvn spring-boot:run

# 真机
mvn spring-boot:run -Dspring-boot.run.profiles=real
```

> 💡 **最快的方式**（优先级高于任何 yaml，IntelliJ 里加个 VM option 就行）：
> ```
> -Dice.test.default-real=false     # 默认连假端
> -Dice.test.default-real=true      # 默认连真端
> ```

### 0.3 参数在哪里用

**测试方法上**（`IceEndToEndTests`）—— 4 个方向方法，每个带 `real` 参数：

```java
@ParameterizedTest(name = "从上往下移动（{0}）")
@ValueSource(booleans = {false, true})
@DisplayName("从上往下移动")
void shouldMoveArmFromTopToBottom(boolean real) throws Exception {
    assertMove(EDirection.Down, real);
}
```

4 个方向 × 2 种模式 = **8 个用例**，方法只需要 4 个。

**REST 接口上**（`/api/ice/test/*`）—— 加 `real` query 参数：

```bash
# 模拟
curl -X POST "http://127.0.0.1:9988/api/ice/test/arm/move?direction=Down&speed=5&real=false"

# 真机
curl -X POST "http://127.0.0.1:9988/api/ice/test/arm/move?direction=Down&speed=5&real=true"

# 四个方向扫一遍
curl -X POST "http://127.0.0.1:9988/api/ice/test/arm/sweep?speed=5&real=true"

# 看目标状态
curl "http://127.0.0.1:9988/api/ice/test/status?real=true"
```

**业务接口不传 `real`**（`/api/arm/*`、`/api/config`）——
它们**固定走真实算法端**，是给现场用的，不能让人误连到假端。

### 0.4 在 IntelliJ 里怎么跑

### 跑测试（推荐）

| 想打什么 | IntelliJ 里怎么设 |
|---|---|
| **只跑模拟那 4 个** | 什么都不用设 —— 用例自带 `real=false` 走假端 |
| **只跑真实那 4 个** | 用例自带 `real=true`，算法端没开就**失败**（这是真实情况，不该掩盖） |

命令行等价物：

```bash
mvn test                                                          # 8 个全跑
mvn test -Dtest=IceEndToEndTests#shouldMoveArmFromTopToBottom     # 只跑"从上往下"
```

> ⚠️ **PowerShell 里 `-D` 参数会被吞**。要用 `--%` 开头：
> ```powershell
> mvn test --% -Dtest=IceEndToEndTests#shouldMoveArmFromTopToBottom
> ```

> 📌 **真机连不上时报 Failed 而不是 Skipped，是故意的**：
> 连不上真实算法端，说明这台机器上 C# 程序没启动 —— 这是**需要你知道的事实**。
> 报 Skipped 会让人以为"测过了"。
>
> 想区分"代码有问题"还是"程序没起"，看 `RealModeLinkTests`：
> 它把 `ice.alg.host` 指到本机，让内置假端冒充真机，
> 验证 `real=true` 这条链路本身是通的。它过了 = 代码没问题，现场失败就只剩"程序没起"。

---

## 1. 一句话原理

`QNEdge.ice` 里**没有**自定义的 ping 方法，但 ICE 协议本身自带三个"通用动作"，
任何 servant 都自动支持：

| ICE 内置动作 | 作用 | 失败时抛什么 |
|---|---|---|
| `ice_ping()` | 纯活体检测，只回一个空包 | `ConnectionRefusedException`（端口不通）<br>`ObjectNotExistException`（暗号不对） |
| `ice_ids()` | 列出对端真正实现的 Slice 类型 | 同上 |
| `ice_isA(id)` | 精确判断对端是不是某个接口 | 同上 |

所以排查"连不上"永远是**三层**，对应三种完全不同的原因：

```
第 1 层  端口通不通？      → ConnectionRefusedException   → 对端没启动 / 端口写错
第 2 层  暗号对不对？      → ObjectNotExistException     → 暗号大小写/空格不一致
第 3 层  接口类型对不对？  → ice_ids() 里没有目标类型     → 对端实现了别的接口
```

---

## 2. 自测接口（9988）

| 接口 | 作用 |
|---|---|
| `GET  /api/ice/check?real=true\|false` | 对算法端做上面三层探测 |
| `GET  /api/ice/check/local` | 检查我们本地回调端口(10001)是否正常应答 |
| `GET  /api/ice/probe?real=true\|false` | **逐个试候选暗号**，看哪个能通（不知道 C# 端暗号时用） |
| `GET  /api/ice/test/status?real=true\|false` | **看目标地址、暗号、连通状态** |
| `GET  /api/ice/test/received` | **查假算法端收到的调用流水**（证明"真的送到了"） |
| `POST /api/ice/test/received/clear` | 清空流水 |
| `POST /api/ice/test/arm/move?direction=&speed=&real=` | **机械臂移动**（方向 + 速度 + 真机还是模拟） |
| `POST /api/ice/test/arm/sweep?speed=&real=` | **机械臂四方向扫一遍**（从上往下→从下往上→从左往右→从右往左） |
| `POST /api/ice/test/message?direction=&speed=&real=` | **发一条真实业务消息** `MoveTask`，看对端 servant 收不收得到 |
| `POST /api/ice/test/complex?real=` | 发带数组+嵌套 struct 的消息，验证复杂参数序列化 |

> 💡 带 `real` 的接口，`real=true` 是**真实 C# 算法端**，`real=false` 是**内置假算法端**。
> 默认值都是 `false`（模拟），所以不传参数就是在本地假端上跑。

### 用法

```bash
# 1) 看目标状态
curl "http://127.0.0.1:9988/api/ice/test/status?real=true"

# 2) 连通性自检（三层探测）
curl "http://127.0.0.1:9988/api/ice/check?real=true"

# 3) 探测暗号（不知道算法端叫什么时）
curl "http://127.0.0.1:9988/api/ice/probe?real=true"

# 4) 发消息，看对端收不收得到
curl -X POST "http://127.0.0.1:9988/api/ice/test/message?direction=Left&speed=5&real=true"

# 5) 四方向扫一遍（一条命令测完上下左右）
curl -X POST "http://127.0.0.1:9988/api/ice/test/arm/sweep?speed=5&real=true"

# 6) 查对端到底收到了什么（假端模式下）
curl http://127.0.0.1:9988/api/ice/test/received

# 7) 发复杂结构消息
curl -X POST "http://127.0.0.1:9988/api/ice/test/complex?deviceId=DEV-001&deviceName=Camera01&real=true"

# 8) 检查本地回调端口
curl http://127.0.0.1:9988/api/ice/check/local

# 9) 看目标状态
curl "http://127.0.0.1:9988/api/ice/test/status?real=true"
```

### 输出示例（对端在线时）

```json
{
  "endpoint": "127.0.0.1:10000（内置假算法端）",
  "real": false,
  "resolvedIdentity": "QNEArmControl",
  "targets": [
    {
      "identity": "QNEArmControl",
      "reachable": true,
      "ids": ["::Ice::Object", "::QNEdge::IQNEArmControl", "::QNEdge::IQNEAlgHandle"],
      "hasArmControl": true,
      "hasAlgHandle": true,
      "status": "OK"
    },
    {
      "identity": "QNEdgeAlgHandle",
      "reachable": false,
      "status": "IDENTITY_NOT_FOUND",
      "error": "暗号 'QNEdgeAlgHandle' 在对端不存在（大小写/空格必须完全一致）"
    }
  ],
  "allUp": true,
  "remoteTypeIds": ["::Ice::Object", "::QNEdge::IQNEArmControl", "::QNEdge::IQNEAlgHandle"]
}
```

### `/api/ice/test/status?real=...` 输出示例

```json
{
  "real": true,
  "mode": "REAL",
  "modeDesc": "真实 C# 算法端",
  "target": "192.168.0.103:10000（真实算法端）",
  "identity": "AlgHandle",
  "resolvedIdentity": "AlgHandle",
  "remoteTypeIds": ["::Ice::Object", "::QNEdge::IQNEArmControl", "..."],
  "triedIdentities": [],
  "connected": true,
  "mockServerRunning": false
}
```

传 `real=false` 时 `mode` 变 `MOCK`、`target` 变成 `127.0.0.1:10000（内置假算法端）`。
真实算法端没起时 `connected` 会如实报 `false`，`triedIdentities` 会告诉你试过哪些暗号。

### `/api/ice/test/received` 输出示例

```json
{
  "total": 4,
  "calls": [
    "MoveTask direction=Down speed=5",
    "MoveTask direction=Up speed=5",
    "MoveTask direction=Right speed=5",
    "MoveTask direction=Left speed=5"
  ]
}
```

> 真实算法端模式下看不到这个（我们看不到 C# 端内部收到了什么），
> 接口会返回提示，让你看算法端自己的日志。

### 失败时的状态码

| status | 含义 | 怎么办 |
|---|---|---|
| `OK` | 端口通、暗号对、接口对 | 一切正常 |
| `CONNECTION_REFUSED` | 端口不通 | 算法端没启动，或端口不是 10000 |
| `IDENTITY_NOT_FOUND` | 暗号对端没有 | 暗号大小写/空格必须和算法端**一字不差** |
| `TYPE_MISMATCH` | 暗号在，但不是这个接口 | 对端实现的是别的 Slice 类型，看 `ids` 字段 |
| `LOCAL_ERROR` | 网络层错误 | 看 `error` 字段原文 |

### ⚠️ 端口被占用（HTTP 409）

本地要占三个端口：HTTP `9988`、内置假算法端 `10000`、本地回调 `10001`。
**任何一个被别的进程占着，内置假端就会绑不上去。**

最常见的场景：**IntelliJ 里已经跑着一个应用实例，同时又在跑测试。**
两个进程抢同一批端口 → 假算法端启动失败。

以前这里是**静默失败**的，后果极具迷惑性：
绑端口失败被吞掉 → 继续去连 `127.0.0.1:10000` → **连上了另一个 JVM 的假算法端**
→ 日志显示"暗号探测成功" → 但 `MockAlgRecorder` 是 static 字段，
记录写在**别的进程**里 → 断言 `lastDirection()` 永远是 `null`。

现在端口冲突会**直接报错**，明确告诉你哪个端口被占、怎么查、怎么解决：

```json
{
  "success": false,
  "error": "ICE_PORT_CONFLICT",
  "message": "ICE 端口 10000 被占用（用于内置假算法端监听）。查是谁占着：netstat -ano | findstr :10000，再用 tasklist /FI \"PID eq <进程号>\" 看进程。解决办法：① 关掉占端口的程序（多半是另一个正在跑的本应用实例）；② 或者改配置换端口（ice.test.mock-port / ice.local.port）。"
}
```

> HTTP 状态码是 **409 Conflict**（不是 500）—— 请求本身没问题，
> 是**本机环境**的状态问题，和 502（下游服务不可达）不是一回事。

**排查步骤**：

```bash
# 1. 看谁占着端口
netstat -ano | findstr ":10000"

# 2. 看那个进程是谁
tasklist /FI "PID eq <上面查到的进程号>"
```

| 占用者 | 处理 |
|---|---|
| 另一个正在跑的本应用实例 | 关掉它（IntelliJ 里点停止），或改 `ice.test.mock-port` |
| 现场那台 C# 算法端（跑在**同一台机器**上） | 用 `real=true` 连它，把 `ice.test.mock-port` 改成别的端口腾出 10000 |
| 别的无关程序 | 改 `ice.test.mock-port` / `ice.local.port` 换端口 |

> **测试端口是独立的**：`IceEndToEndTests` 用 `9987 / 10006 / 10007`，
> `RealModeLinkTests` 用 `9989 / 10004 / 10005`，
> `HnRoboticArmApplicationTests` 用 `10002 / 10003` —— 都和应用的 `9988 / 10000 / 10001` 错开，
> 所以**测试和运行中的应用可以共存**。


> `/api/ice/probe` 的输出是"暗号 → 探测结果"的映射，能通的那个标 `OK`：
> ```json
> {
>   "candidates": {
>     "QNEArmControl": "OK  ids=::Ice::Object,::QNEdge::IQNEArmControl,...",
>     "QNEdgeAlgHandle": "IDENTITY_NOT_FOUND"
>   },
>   "resolvedIdentity": "QNEArmControl",
>   "remoteTypeIds": ["::Ice::Object", "::QNEdge::IQNEArmControl", "..."],
>   "hint": "把能 OK 的那个填回 application.yaml 的 ice.alg.identity，或加进 ice.alg.fallback-identities"
> }
> ```

---

## 2.4.5 「我控制的是哪条机械臂」—— 臂标识怎么传

> 这个问题在本项目里出现频率最高，因为 **`QNEdge.ice` 里没有臂字段**：
> ```slice
> interface IQNEArmControl {
>     EErrorCode MoveTask(EDirection ed, int nSpeed);   // 只有方向和速度
>     ...
> }
> ```
> **协议固定，不改 Slice**。所以"哪条臂"只有两个表达渠道，**本项目两个都支持**。

### 渠道一：选端点（一条臂一台算法端）

在 `application.yaml` 里配机械臂清单，每条臂一个 `host:port:identity`：

```yaml
ice:
  alg:                     # 默认端点（没指定臂时用它）
    host: 192.168.0.103
    port: 10000
    identity: AlgHandle
  arms:                    # 机械臂清单
    arm-a:
      host: 192.168.0.103
      port: 10000
      identity: AlgHandle
      description: 1 号琴台机械臂
    arm-b:
      host: 192.168.0.104
      port: 10000
      identity: AlgHandle
      description: 2 号琴台机械臂
  default-arm: arm-a       # 不传 arm 时用它
```

调用时带 `arm=臂名`：

```bash
curl -X POST "http://127.0.0.1:9988/api/arm/move?direction=Up&speed=5&arm=arm-b"
```

> 清单里没配的字段**继承 `ice.alg`** —— 只写 `description` 也行（只当标签用）。

### 渠道二：Ice Context（一台算法端管多条臂）

臂名以 `strArmID` 放进 **Ice 请求上下文**，对端从 `Current.ctx` 读：

```java
// Java（本项目发的地方）—— 见 ArmControlService
Map<String, String> ctx = Map.of("strArmID", armName);
prx.MoveTask(direction, speed, ctx);
```

```csharp
// C#（算法端读的地方）
public EErrorCode MoveTask(EDirection ed, int nSpeed, Ice.Current current)
{
    var armId = current.ctx["strArmID"];   // ← 机械臂标识
    ...
}
```

> ✅ **不用重新生成 C# 代码**：Slice 签名一个字节没动，
> Context 是 Ice 协议自带能力，C# 端只要在实现里读 `current.ctx` 就行。
> ⚠️ C# 端的 `Ice.Current` 需要在方法签名里声明（否则拿不到 ctx）。

### 两个渠道可以同时用

`arm=臂名` 时：**先按清单选端点（决定连哪台），同时把臂名塞进 Context（告诉对端是哪条）**。

### 查询当前有哪些臂

```bash
curl http://127.0.0.1:9988/api/ice/arms
```

```json
{
  "arms": [
    { "arm": "arm-a", "host": "192.168.0.103", "port": 10000,
      "identity": "AlgHandle", "description": "1 号琴台机械臂", "default": true },
    { "arm": "arm-b", "host": "192.168.0.104", "port": 10000,
      "identity": "AlgHandle", "description": "2 号琴台机械臂", "default": false }
  ],
  "defaultArm": "arm-a",
  "hint": "Slice 协议里没有臂字段，臂标识通过 Ice Context 的 strArmID 传给对端..."
}
```

> `GET /api/ice/test/status` 的返回里也带 `arms` 字段。

### 未配置的臂名会被拒绝（400）

**配了 `ice.arms` 之后**，传入清单外的臂名 → **400 拒绝**，不会悄悄打到默认臂：

```
{"success":false,"error":"IllegalArgumentException",
 "message":"未配置的机械臂 'not-exist'。已配置的机械臂：arm-a、arm-b"}
```

> 为什么不"宽容地"打默认臂：消音按错机械臂是消防事故。
> 打错一个字就打到另一条臂上，比直接报错危险得多。

> **不配 `ice.arms` 时**（单臂场景），`arm` 参数仍然可用 ——
> 此时臂名只作为标签进 Context，不做清单校验。

### 默认臂配错 → 启动就拒绝

`ice.default-arm` 填了清单里没有的臂名 → **应用启动失败**，不会带着定时炸弹上线：

```
ice.default-arm='arm-z' 不在 ice.arms 清单里。已配置的机械臂：arm-a、arm-b。
要么把它改成清单里的臂名，要么把它清空（清空 = 不指定默认臂）。
```

> 为什么挡在启动期：默认臂不合法意味着**每一次**机械臂调用都会失败，
> 而调用往往发生在消防现场。启动时说清楚，比现场按了消音键才发现强。
> （对应测试：`IcePropertiesArmValidationTest`）

### 怎么确认对端真收到了

模拟模式下，假算法端会把读到的臂标识打出来：

```
[MockAlg] MoveTask direction=Up speed=5 armId=arm-b
```

真机模式下看 C# 端自己的日志（它读 `current.ctx["strArmID"]`）。

---

## 2.5 机械臂移动接口（两种传参方式）

`POST /api/arm/move` 支持两种写法，**随便挑一种**：

```bash
# 方式 A：query 参数（现场手敲 URL 更方便）
curl -X POST "http://127.0.0.1:9988/api/arm/move?direction=Up&speed=5"

# 方式 B：JSON body（前端调用更规范）
curl -X POST http://127.0.0.1:9988/api/arm/move \
     -H "Content-Type: application/json" \
     -d '{"direction":"Up","speed":5}'
```

| 字段 | 取值 | 说明 |
|---|---|---|
| `direction` | `Left` / `Right` / `Up` / `Down` | 对应 `QNEdge.ice` 里的 `EDirection` 枚举 |
| `speed` | 整数 | 速度档位，按算法端约定（通常是 1~10） |

> 💡 **`/api/ice/test/message` 支持中文口语方向**（现场不用记枚举名）：
> `从上往下` / `从下往上` / `从左往右` / `从右往左`，
> 以及单字 `上` / `下` / `左` / `右`、`向上` / `向下` / `向左` / `向右`。
>
> ```bash
> curl -X POST "http://127.0.0.1:9988/api/ice/test/message?direction=从上往下&speed=5"
> ```

### 三种 Content-Type 都支持

| Content-Type | 请求体 | 行为 |
|---|---|---|
| 不带 | 不带 | 走 query 参数 |
| `application/json` | JSON | 正常解析成 DTO |
| `application/x-www-form-urlencoded` | 空 或 `direction=Up&speed=5` | 空 → 走 query 参数；有 → 解析成 DTO |

> ⚠️ **`application/x-www-form-urlencoded` 为什么要专门支持？**
> PowerShell 的 `Invoke-RestMethod -Method Post` 即使不带 body，也会带上
> `Content-Type: application/x-www-form-urlencoded`。Spring 找不到能读它的转换器 →
> 抛 `HttpMediaTypeNotSupportedException` → **500**，
> 把"参数完全正确"的请求误判成服务端故障。
>
> 修法见 `FormUrlEncodedHttpMessageConverter`：把 form body 转成 Map 再交给 Jackson，
> 两种 Content-Type 走同一条代码路径。
>
> 注意：Spring 是**先选消息转换器、选不到才轮到 `RequestBodyAdvice`**，
> 所以这个坑用 `RequestBodyAdvice` **修不了**，必须提供真正的 `HttpMessageConverter`。

成功响应：
```json
{"code":"EECSuccess","message":"success"}
```

失败响应（**400**，会明确告诉你可选值）：
```json
{"success":false,"error":"IllegalArgumentException",
 "message":"direction 'Sideways' 非法，可选值：Left / Right / Up / Down"}
```

> ⚠️ **状态码语义**：
> - `400` —— 你传错了（方向名拼错、缺参数、body 格式错、Content-Type 不支持）
> - `502` —— 你传对了，但**下游算法端**有问题（没启动 / 暗号不对 / 超时）
> - `500` —— 其它未预期错误

一键跑完四个方向（推荐）：

```bash
# Windows
powershell -ExecutionPolicy Bypass -File scripts\test-arm-move.ps1

# Linux / Mac / Git Bash
bash scripts/test-arm-move.sh
```

脚本会依次做：连通性自检 → 上下左右各发一次 → 本地回调端口自检 → 打印收到的事件。

---

---

## 3. 假算法端（不用等 C# 程序）

项目自带 `MockAlgServer` —— 一个完整的模拟算法端，实现了 `.ice` 里的全部 5 个接口，
并且**每收到一条消息就反向回调我们 10001**。

### 启动方式：一个终端就够了

```bash
mvn spring-boot:run
```

**假算法端是应用内置的**（`real=false` 时自动启动），
不用再开第二个终端跑 `mvn exec:java`。

启动日志会看到：

```
[ICE] 内置假算法端已启动：端口 10000，暗号 AlgHandle（反向回调 SMTEvent@127.0.0.1:10001）
[ICE] 代理已就绪 暗号=AlgHandle -> 127.0.0.1:10000  对端实现的接口=[...]
[ICE] 回调 ObjectAdapter 已启动：端口 10001，暗号 SMTEvent（实现 IQNEEvent / IQNEFCHInfo / IQNEConfig）
```

> 📌 **为什么反向回调地址是 `127.0.0.1` 而不是 `192.168.0.209`**：
> `ice.local.host`（`192.168.0.209`）的语义是"**真实算法端**要反向连我们到哪"，
> 和"假端回连自己"是两回事。
> 所以假端的回调地址单独配：`ice.test.mock-callback-host`（默认 `127.0.0.1`）。
>
> 📌 **暗号为什么是 `AlgHandle` / `SMTEvent`**：
> 这两个是从现场 C# 程序（`HNServer.dll`）里反查出来的真实值，
> **和真机模式完全一致** —— 这样切环境时不用改任何代码。
> 假端注册 `AlgHandle`、回调我们时用 `SMTEvent`，都跟着配置走。

### 端到端验证步骤

```
1. mvn spring-boot:run
   → 日志出现 "内置假算法端已启动"
2. curl "http://127.0.0.1:9988/api/ice/test/status?real=false"
   → 应看到 "mode":"MOCK", "connected":true, "mockServerRunning":true
3. curl -X POST "http://127.0.0.1:9988/api/ice/test/arm/sweep?speed=5&real=false"
   → 应看到 4 个方向全部 "EECSuccess"
4. curl http://127.0.0.1:9988/api/ice/test/received
   → 应看到 4 条流水，顺序是 Down → Up → Right → Left
5. 看应用控制台 → 应打印：
   [MockAlg] MoveTask direction=Down speed=5
   [MockAlg]   → 反向回调 AddEvent(MOCK-1793588195336) 返回 EECSuccess
6. 看应用日志 → 应打印：
   [ICE回调] AddEvent id=MOCK-1793588195336 device=MOCK-DEVICE type=...
```

**第 3、4、5、6 步全部对上 = 双向链路完全打通。**

> 💡 想打真机，把上面所有 `real=false` 改成 `real=true` 即可 —— 其它完全一样。

### 独立进程模式（可选，一般用不到）

如果想让假算法端跑在**另一个进程**（比如想让它冒充"远程机器"）：

```bash
mvn exec:java -Dexec.args="127.0.0.1 10000 127.0.0.1 10001 SMTEvent AlgHandle"
```

> 依次是：自己监听 host/port、反向回调目标 host/port/暗号、自己注册 servant 的暗号。
>
> ⚠️ 但这样**必须把应用自己的内置假端关掉**，否则抢 10000 端口。
> 内置假端现在是无条件启动的，要用它就得先把 `ice.test.mock-port` 改成别的端口。

### ⚠️ 两个必须知道的坑（都已修）

**坑 1：Ice 默认线程池只有 1 个线程 → 自调用反向回调直接死锁**

假端和应用在**同一个 JVM、同一个 Communicator** 里：

```
HTTP 线程  → 调假端 MoveTask
                ↓ 假端 servant 在【服务端线程池】里处理
              反向回调我们本地的 AddEvent
                ↓ 本地 servant 要在【客户端线程池】里处理
```

客户端和服务端**共用**这 1 个线程 → 客户端线程在等服务端线程，
服务端线程在等客户端线程 → **互相等待，死锁**。
表现是 `MoveTask` 卡住 30 秒，最后抛 `ConnectionTimeoutException`（而不是立刻报错）。

**修法**（`IceConfig.java`）：客户端和服务端线程池各给 4 个。
改完 56 个用例从"卡死"变成 **11 秒跑完**。

**坑 2：连不上时干等 → 每个暗号都付一次连接超时**

所有候选暗号都指向**同一个** `host:port`。
端口不通时（`ConnectionRefusedException`），再试剩下 4 个暗号也是白等，
每个都要付一次 `ConnectTimeout`（3 秒 × 5 = **15 秒**）。

**修法**（`QNEdgeIceClient.resolveProxy()`）：
**连接层失败立刻停**，只报第一个暗号的结果。
区分两种失败很关键：

| 失败类型 | 含义 | 怎么办 |
|---|---|---|
| `ConnectionRefusedException` / `ConnectFailedException` | **整个端点不可达** | 停止探测（换暗号也没用） |
| `ObjectNotExistException` | 服务器活着，只是这个暗号不存在 | **继续**试下一个暗号 |

配 `Ice.Override.ConnectTimeout=3000`（3 秒快速失败），而不是等系统默认的几十秒。

---

## 4. 自动化测试

```bash
mvn test
```

### `IceEndToEndTests` —— 机械臂上下左右（8 个用例，4 个方法）

| 方法 | `real=false`（模拟） | `real=true`（真实） |
|---|---|---|
| `shouldMoveArmFromTopToBottom` | 从上往下 | 从上往下（打真机） |
| `shouldMoveArmFromBottomToTop` | 从下往上 | 从下往上（打真机） |
| `shouldMoveArmFromLeftToRight` | 从左往右 | 从左往右（打真机） |
| `shouldMoveArmFromRightToLeft` | 从右往左 | 从右往左（打真机） |

**断言分两层**：

| 模式 | 断言什么 |
|---|---|
| `real=false` | ① HTTP 返回 `success:true`　② **假端收到的方向和速度与我们发的一致** |
| `real=true` | ① HTTP 返回 `success:true`（真实端内部收到什么看不到，只能看返回码） |

### `RealModeLinkTests` —— 验证 `real=true` 这条链路本身是通的

把 `ice.alg.host/port` 指到本机，让内置假端**冒充**真实算法端，
再用 `real=true` 去调。过了 = 真机分支代码没问题，
现场失败就只剩"C# 程序没启动"这一个原因。

> 💡 测试用的假算法端就是**应用内置那个**，
> 所以测试环境和真实环境跑的是同一套代码路径。
### 统一暗号（核心）

| 测试 | 验证什么 |
|---|---|
| `singleIdentityShouldExposeMultipleInterfaces` | 一个暗号的 `ice_ids()` 能报出多个 Slice 接口 |
| `singleIdentityShouldCastToMultipleInterfaces` | 一个暗号 `checkedCast` 到 4 个接口都成功 |
| `localCallbackShouldExposeThreeInterfaces` | 本地一个 servant 同时实现 3 个接口 |
| `shouldReceiveConfigCallbackFromAlgServer` | 算法端反向回调我们本地的 `IQNEConfig` |
| `shouldPushConfigToAlgServer` | 我们把配置推给算法端（出方向） |
| `probeEndpointShouldFindWorkingIdentity` | 暗号探测接口能报出能通的那个 |

### 测试模式切换 + 中文口语方向（第 5 组）

| 测试 | 验证什么 |
|---|---|
| `shouldMatchConfiguredMode` | 当前模式**与配置一致**（假端就断言 MOCK+已连通，真端就断言 REAL+假端没在跑） |
| `shouldSwitchToRealModeAtRuntime` | 运行时切到 REAL：模式变了、内置假端停了 |
| `shouldSwitchBackToMockModeAndWorkImmediately` | 切回 MOCK 后**立刻能发指令**，不用重启 |
| `shouldAcceptChineseDirectionNames` | 中文口语方向能正确解析（展开 8 次） |
| `shouldSweepAllFourDirectionsViaApi` | 四方向扫一遍接口，顺序是 从上往下→从下往上→从左往右→从右往左 |
| `shouldExposeReceivedCalls` | 能查到假算法端收到的调用流水（"真的送到了"的独立证据） |

### Content-Type 兼容性（回归）

| 测试 | 验证什么 |
|---|---|
| `shouldAcceptFormUrlEncodedWithEmptyBody` | form-urlencoded + 空 body → 走 query 参数（PowerShell 场景） |
| `shouldAcceptFormUrlEncodedWithBody` | form-urlencoded + 带 body → 参数按 DTO 解析（`"8"` → `int 8`） |
| `shouldAcceptNoContentTypeHeader` | 完全没有 Content-Type 头 → 走 query 参数（curl 场景） |
| `shouldStillParseJsonBodyNormally` | JSON 请求体不被 form 转换器截胡 |
| `shouldReturnBadRequestForUnsupportedContentType` | 不支持的 Content-Type → **400** 而非 500 |

### 机械臂上下左右（核心）

| 测试 | 验证什么 |
|---|---|
| `shouldMoveArmInAllFourDirections` | **上下左右四个方向**都能下发（`@CsvSource` 展开 4 次） |
| `shouldPassThroughSpeedCorrectly` | 方向 + 速度组合正确透传（展开 6 次） |
| `shouldSendSequentialMovesInOrder` | 连续下发 上→右→下→左，顺序不乱、不串号 |
| `shouldMoveArmUsingQueryParams` | query 参数方式（不带 body）也能下发 |
| `shouldCallMoveTaskDirectlyViaIceProxy` | 绕过 REST 直接用 Ice Proxy 调（用于分层定位问题） |

### 四个物理方向（现场调试用，每个方向一个独立用例）

> `EDirection` 只有 4 个值，"从上往下"这类口语说法需要自己映射。
> 单独拆成 4 个用例，方便现场只跑一个方向、报告里一眼看懂是哪个。

| 测试 | 口语说法 | `EDirection` |
|---|---|---|
| `shouldMoveArmFromTopToBottom` | 从上往下 | `Down` |
| `shouldMoveArmFromBottomToTop` | 从下往上 | `Up` |
| `shouldMoveArmFromLeftToRight` | 从左往右 | `Right` |
| `shouldMoveArmFromRightToLeft` | 从右往左 | `Left` |
| `shouldMoveArmDownThenUp` | 竖直来回（下→上） | `Down` → `Up` |
| `shouldMoveArmRightThenLeft` | 水平来回（右→左） | `Right` → `Left` |
| `shouldSweepAllFourPhysicalDirections` | 四方向扫一遍（下→上→右→左） | 全部 4 个 |

单独跑某一个：

```bash
mvn test -Dtest=IceEndToEndTests#shouldMoveArmFromTopToBottom    # 从上往下
mvn test -Dtest=IceEndToEndTests#shouldMoveArmFromBottomToTop    # 从下往上
mvn test -Dtest=IceEndToEndTests#shouldMoveArmFromLeftToRight     # 从左往右
mvn test -Dtest=IceEndToEndTests#shouldMoveArmFromRightToLeft     # 从右往左
```

> ⚠️ **方向语义要以实物为准**。`.ice` 文件只定义了枚举名，没定义坐标系，
> `Up` 到底是"机械臂升高"还是"关节上抬"，要看真实机械臂。
> 建议先跑一次 `Up`、一次 `Down`，肉眼确认哪个才是真正的「往下」。

### 机械臂其余指令

| 测试 | 验证什么 |
|---|---|
| `shouldClickButton` | `ClickTask` 按钮文本正确送达 |
| `shouldTurnKey` | `TurnKey` 的 auto / manual 两种模式（展开 2 次） |
| `shouldGetButtonCoordinates` | `SimulationClickGetCoordinates` 返回 struct 正确反序列化 |

### 事件回调与自检

| 测试 | 验证什么 |
|---|---|
| `shouldReceiveCallbackFromAlgServer` | 算法端 → 10001 → 我们，反向回调通 |
| `healthCheckShouldReportAllUpWhenAlgServerIsRunning` | 自检报 `allUp=true` 且类型匹配 |
| `healthCheckShouldReportLocalCallbackOk` | 本地 10001 可达 |
| `testMessageEndpointShouldReachAlgServer` | 测试消息真的打到了对端（断言对端收到的方向/速度） |
| `complexMessageEndpointShouldSerializeStructs` | 数组+嵌套 struct 序列化正确（对端收到 1 台设备） |
| `healthCheckShouldDiagnoseWrongIdentity` | 暗号不存在时抛 `ObjectNotExistException` |

### 错误处理

| 测试 | 验证什么 |
|---|---|
| `shouldRejectInvalidDirection` | 方向名非法 → 400 + 提示可选值 |
| `shouldRejectMissingDirection` | 缺 direction → 400 + 指明缺哪个参数 |


---

## 5. 对接真实算法端时的排查顺序

```
curl http://127.0.0.1:9988/api/ice/check        # 三层探测（端口/暗号/接口）
curl http://127.0.0.1:9988/api/ice/probe        # 逐个试候选暗号
```

按 `status` 对号入座：

| 现象 | 根因 | 修法 |
|---|---|---|
| `CONNECTION_REFUSED` | 算法端没起 / IP 端口错 | 改 `application.yaml` 的 `ice.alg.host` / `ice.alg.port` |
| `IDENTITY_NOT_FOUND` | 暗号不一致 | 找算法端代码里的 `stringToIdentity("XXX")`，把 XXX 填进 `ice.alg.identity` |
| `TYPE_MISMATCH` | 对端没实现这个接口 | 看返回的 `ids` 数组，确认实际实现了什么 |
| HTTP `409` + `ICE_PORT_CONFLICT` | **本地端口被占**（常见：另一个应用实例在跑） | 见 2.4「端口被占用」 |
| 全部 `OK` 但业务调用报错 | 方法签名/调用顺序不对 | 查 `.ice` 里的签名，注意 `StartAllDeviceALG` 必须先于 `StopALLDeviceALG` |
| `checkedCast` 返回 `null` | 类型 ID 数组**没排序** | 见 6.4 —— `ice_isA` 用 `binarySearch` |

---

## 6. 暗号配置速查（`application.yaml`）

> **设计原则：按「方向」配暗号，不按「接口」配。**
> 一个方向只需要 **1 个**暗号 —— 因为一个 servant 可以同时实现多个 Slice 接口
> （multi-interface），对端拿到代理后 `checkedCast` 到任意接口都行。

```yaml
ice:
  alg:                         # 出方向：我们主动连算法端
    host: 127.0.0.1
    port: 10000
    identity: QNEArmControl    # 主暗号
    fallback-identities:       # 备用暗号，启动时逐个 ice_ping() 探测
      - QNEdgeAlgHandle
      - QNEAlgHandle
      - QNEdge
  local:                       # 入方向：算法端反向回调我们
    host: 127.0.0.1
    port: 10001
    identity: QNEdgeCallback   # 我们本地 servant 的暗号（1 个 servant / 3 个接口）
  service:
    enabled: true
```

### 6.1 两个方向的区别

| | `ice.alg` | `ice.local` |
|---|---|---|
| 方向 | 出（我们 → 算法端） | 入（算法端 → 我们） |
| 谁监听 | 算法端 | **我们**（ObjectAdapter） |
| 暗号含义 | 算法端 `stringToIdentity(...)` 的名字 | 算法端 `stringToProxy("XXX:...")` 的第一个字段 |
| 端口 | 10000 | 10001 |

### 6.2 一个暗号对应多个接口

| 暗号 | 位置 | 实现的 Slice 接口 |
|---|---|---|
| `QNEdgeCallback` | 本地 10001 | `IQNEEvent` / `IQNEFCHInfo` / `IQNEConfig` |
| `QNEArmControl` | 算法端 10000 | `IQNEArmControl` / `IQNEAlgHandle` / `IQNEEvent` / `IQNEFCHInfo` / `IQNEConfig` |

对端这样用就能拿到任意接口：

```java
ObjectPrx base = communicator.stringToProxy("QNEdgeCallback:default -h 127.0.0.1 -p 10001");
IQNEEventPrx  ev    = IQNEEventPrxHelper.checkedCast(base);   // ✅
IQNEConfigPrx cfg   = IQNEConfigPrxHelper.checkedCast(base);  // ✅ 同一个暗号
```

### 6.3 ⚠️ 暗号与 Slice 类型 ID 是两回事

| | Slice 类型 ID | 运行时暗号（identity） |
|---|---|---|
| 例子 | `::QNEdge::IQNEArmControl` | `QNEArmControl` |
| 来自哪 | `.ice` 文件，编译期固定 | 运行时 `stringToIdentity(...)` 随便起 |
| 谁能改 | 不能（改了要重新编译） | 能（改 `application.yaml` 即可） |
| `QNEdge.ice` 里有没有 | **有**（接口定义处） | **没有**（ICE 协议概念，不在 Slice 里） |

> 简单说：`QNEdge.ice` 定义了**接口**，但没规定**叫什么名字**。
> 名字是部署时双方约定出来的 —— 这就是为什么 `application.yaml` 里要配它。

### 6.4 ⚠️ 实现 multi-interface servant 的坑：类型 ID 数组必须排序

Ice 的 `ice_isA()` 内部用的是 `Arrays.binarySearch`：

```java
public boolean ice_isA(String s) {
    return java.util.Arrays.binarySearch(__ids, s) >= 0;   // ← 要求 __ids 有序！
}
```

**数组没排序就查不到**，表现为 `checkedCast` **静默返回 `null`**（不抛异常，最难查）。

所以 `ice_ids()` 返回的数组必须**按字典序排好**。本项目统一用 `sortedIds(...)` 构造，
不要手写数组字面量。

### 6.5 排查顺序（对接真实算法端时）

```
curl http://127.0.0.1:9988/api/ice/check        # 三层探测
curl http://127.0.0.1:9988/api/ice/probe        # 逐个试候选暗号
```

| 现象 | 根因 | 修法 |
|---|---|---|
| `CONNECTION_REFUSED` | 算法端没起 / IP 端口错 | 改 `ice.alg.host` / `ice.alg.port` |
| `IDENTITY_NOT_FOUND` | 暗号不一致 | 找算法端代码里的 `stringToIdentity("XXX")`，把 XXX 填进 `ice.alg.identity` |
| `TYPE_MISMATCH` | 对端没实现这个接口 | 看返回的 `ids` 数组，确认实际实现了什么 |
| HTTP `409` + `ICE_PORT_CONFLICT` | **本地端口被占**（常见：另一个应用实例在跑） | 见 2.4「端口被占用」 |
| 全部 `OK` 但业务调用报错 | 方法签名/调用顺序不对 | 查 `.ice` 里的签名，注意 `StartAllDeviceALG` 必须先于 `StopALLDeviceALG` |
| `checkedCast` 返回 `null` | 类型 ID 数组**没排序** | 见 6.4 —— `ice_isA` 用 `binarySearch` |

---

## 7. 一页速查：新增一个接口要改哪儿

| 场景 | 改哪里 |
|---|---|
| 加一个 Slice 接口方法 | `QNEdge.ice` → 重新编译 → 在 servant 里实现 + 在 `__dispatch` 里加 case |
| 本地要暴露新接口 | `QNEdgeCallbackServant`：`IDS` 加类型 ID → 实现方法 → `__dispatch` 加 case（**暗号不用改**） |
| 算法端暗号变了 | `application.yaml` 的 `ice.alg.identity`（**只改这一个地方**） |
| 本地暗号变了 | `application.yaml` 的 `ice.local.identity`（**只改这一个地方**） |
| 对端接口实现情况不明 | `GET /api/ice/probe` |

