# Redisson 分布式锁学习工程

基于 **JDK 21 + Spring Boot 3.3 + Spring Cloud 2023.0 + Redisson 3.45** 的分布式锁实战 Demo，
覆盖企业中最常见的 9 类分布式锁应用场景，每个场景都提供可直接调用的 HTTP 接口、
并发压测入口与原理讲解。

> 作者：wuzhudexiaopacai

---

## 一、工程结构

```
redisson-learn                            # 父工程（POM 聚合）
├── redisson-common                       # 公共模块：Redisson 配置、通用注解与 AOP
│   ├── config/RedissonConfig             #   RedissonClient 单节点配置（看门狗 30s）
│   ├── annotation/DistributedLock        #   通用分布式锁注解（支持 SpEL、公平锁）
│   ├── annotation/Idempotent             #   接口幂等注解
│   └── aspect/                           #   两个注解的 AOP 实现 + SpEL 解析器
├── redisson-eureka-server                # 注册中心 :8761（仅作架构展示，锁不依赖它）
├── redisson-inventory-service            # 库存服务 :8082（6 个锁场景）
└── redisson-order-service                # 订单服务 :8081（4 个锁场景 + Feign 跨服务调用）
```

技术栈版本：

| 组件 | 版本 |
|---|---|
| JDK | 21 |
| Spring Boot | 3.3.5 |
| Spring Cloud | 2023.0.4 |
| Redisson | 3.45.1 |
| Redis | 任意 5.0+（单节点即可） |

## 二、快速开始

### 1. 前置条件

- JDK 21、Maven 3.6+
- 本地或远程 Redis，默认连接 `redis://127.0.0.1:6379`；
  如需修改，编辑各服务 `application.yml` 中的 `redisson.address / database / password`

### 2. 编译

```bash
mvn clean package -DskipTests
```

> 注：`redisson-inventory-service` 中的并发测试需要 Redis 处于运行状态，
> 若 Redis 未启动请先加 `-DskipTests`。

### 3. 启动（按顺序）

```bash
# 1. 注册中心（可选，仅 Feign 跨服务调用与架构演示需要）
java -jar redisson-eureka-server/target/redisson-eureka-server-1.0.0.jar

# 2. 库存服务
java -jar redisson-inventory-service/target/redisson-inventory-service-1.0.0.jar

# 3. 订单服务
java -jar redisson-order-service/target/redisson-order-service-1.0.0.jar
```

验证：访问 http://localhost:8761 可看到两个服务已注册。

> **说明**：Eureka 只是为了让架构更贴近真实微服务。Redisson 分布式锁的正确性
> 只依赖 Redis——即使 Eureka 宕机，所有锁场景依然有效。

## 三、场景总览

| # | 场景 | Redisson 能力 | 位置 | 端口 |
|---|---|---|---|---|
| 1 | 秒杀扣库存防超卖 | `RLock` 可重入锁 + 看门狗 | inventory /stock | 8082 |
| 2 | 缓存击穿保护 | 锁 + 双重检查 | inventory /product | 8082 |
| 3 | 秒杀公平排队 | `RFairLock` | inventory /fair | 8082 |
| 4 | 价格表读写分离 | `RReadWriteLock` | inventory /price | 8082 |
| 5 | 定时任务防重复执行 | `tryLock()` 立即失败 | inventory StockSettleTask | 8082 |
| 6 | 高可用红锁 | `RedissonRedLock` | inventory /redlock | 8082 |
| 7 | 接口幂等防重复下单 | `@Idempotent` 注解 + AOP | order /order | 8081 |
| 8 | 支付锁（注解式 + 异步化） | `@DistributedLock` / `tryLockAsync` | order /pay | 8081 |
| 9 | 转账多资源联锁 | `RedissonMultiLock` | order /transfer | 8081 |
| 10 | 跨服务秒杀（组合拳） | Feign + 幂等 + 库存锁 | order /seckill | 8081 |

---

## 四、场景详解与测试

### 场景 1：秒杀扣库存防超卖（可重入锁 + 看门狗）

**问题**：「查库存 → 判断 → 扣减」是复合操作，并发下多请求读到相同库存值 → 超卖。

**解法**：用 `RLock` 把临界区串行化；`lock()` 不传租约，交给看门狗自动续期。

代码：[StockService.java](redisson-inventory-service/src/main/java/com/github/wuzhudexiaopacai/redisson/inventory/service/StockService.java)

```bash
# 初始化库存 100
curl -X POST "http://localhost:8082/stock/init?productId=1001&stock=100"

# 一键压测：200 并发安全扣减（JDK21 虚拟线程发起）
curl "http://localhost:8082/stock/stress?productId=1001&count=200&mode=safe"
```

预期：`扣减成功数=100`，`剩余库存=0`，`是否超卖=否`。

**对照组**（亲眼看到超卖）：

```bash
curl -X POST "http://localhost:8082/stock/init?productId=1001&stock=100"
curl "http://localhost:8082/stock/stress?productId=1001&count=200&mode=unsafe"
```

预期：剩余库存变成**负数** —— 这就是不加锁的后果。

**反例**（固定短租约）：`mode=short-lease` 下锁 1 秒过期、业务执行 1.5 秒，
锁提前失效期间其他请求进入临界区，同样超卖。结论：**租约宁可交给看门狗，也不要拍脑袋设小**。

### 场景 2：缓存击穿保护（锁 + 双重检查）

**问题**：热点 key 过期瞬间，海量请求同时回源，数据库被打爆。

**解法**：缓存未命中先抢锁，抢到锁后**再查一次缓存**（双重检查），只有第一个线程真正回源。

代码：[ProductService.java](redisson-inventory-service/src/main/java/com/github/wuzhudexiaopacai/redisson/inventory/service/ProductService.java)

```bash
# 删除缓存，模拟热点 key 过期
curl -X DELETE "http://localhost:8082/product/cache/1001"

# 50 并发同时查询（模拟回源耗时 2 秒）
curl "http://localhost:8082/product/stress/1001?count=50"
```

预期：`数据库回源次数=1`。没有锁的话回源次数会接近 50。

### 场景 3：秒杀公平排队（公平锁）

**问题**：普通锁是非公平的，锁释放时谁抢到凭运气，可能线程饥饿。

**解法**：`RFairLock` 内部维护 FIFO 队列，严格按请求到达顺序发锁。

代码：[FairLockController.java](redisson-inventory-service/src/main/java/com/github/wuzhudexiaopacai/redisson/inventory/controller/FairLockController.java)

```bash
curl -X POST "http://localhost:8082/fair/reset"
# 并发发起多个不同 userId 的请求（每个请求持锁 200ms，效果更明显）
for id in 1 2 3 4 5; do curl -X POST "http://localhost:8082/fair/seckill?userId=$id" & done
# 查看实际获取锁的顺序
curl "http://localhost:8082/fair/queue"
```

预期：队列顺序与请求到达顺序基本一致。代价是吞吐量低于非公平锁，非必要不用。

### 场景 4：价格表读写分离（读写锁）

**语义**：读读共享、读写互斥、写写互斥，跨进程跨实例生效。适合「读多写少」的配置/价格表。

代码：[ReadWriteLockController.java](redisson-inventory-service/src/main/java/com/github/wuzhudexiaopacai/redisson/inventory/controller/ReadWriteLockController.java)

```bash
curl -X PUT "http://localhost:8082/price/write?value=99.9"   # 写操作故意 sleep 3 秒
# 在写操作执行期间，另一个终端发起读请求，会明显阻塞 3 秒
curl "http://localhost:8082/price/read"
# 而多个读请求之间互不影响
```

### 场景 5：定时任务防重复执行（tryLock 立即失败）

**问题**：`@Scheduled` 任务在多实例部署时，每个实例都会触发 → 重复结算、重复发券。

**解法**：任务执行前 `tryLock()` 无参调用（抢不到立即失败），抢到的实例执行，其余跳过。
这也是 ShedLock 等框架的核心原理。

代码：[StockSettleTask.java](redisson-inventory-service/src/main/java/com/github/wuzhudexiaopacai/redisson/inventory/task/StockSettleTask.java)

验证（需要多实例，见第五节）：观察两个实例日志，每分钟只有一个打印
`>>> 抢到锁，开始执行库存对账任务 <<<`。

### 场景 6：红锁 RedLock（高可用锁）

**原理**：向 N 个**相互独立**的 Redis 节点同时加锁，超过半数（N/2+1）成功才算成功，
单节点宕机不影响锁服务整体可用。

代码：[RedLockController.java](redisson-inventory-service/src/main/java/com/github/wuzhudexiaopacai/redisson/inventory/controller/RedLockController.java)

```bash
curl -X POST "http://localhost:8082/redlock/seckill?userId=10086"
```

> **重要说明**：本 Demo 只有单节点 Redis，3 个客户端指向同一地址，仅演示 API 用法。
> 生产环境必须部署多个独立节点才有意义。RedLock **不支持看门狗续期**，必须显式指定租约。
>
> **争议**（面试高频）：Martin Kleppmann（DDIA 作者）质疑 RedLock 在时钟跳变、
> GC 长停顿（STW）场景下可能出现两个客户端同时持锁；antirez 撰文反驳。
> 业内务实共识：
> - **效率型互斥**（防并发、防重复）：单节点 Redis 锁 + 看门狗足够，RedLock 是过度设计；
> - **正确性型互斥**（金融扣款等）：用数据库唯一约束、乐观锁，或 Zookeeper/etcd（共识协议）兜底。

### 场景 7：接口幂等防重复下单（@Idempotent 注解）

**问题**：双击提交、前端超时重试、支付回调重发 → 重复订单。

**解法**：自定义 `@Idempotent` 注解，以「用户ID + 请求流水号」为 key 抢锁，
窗口期内重复请求直接拒绝。

代码：[OrderController.java](redisson-order-service/src/main/java/com/github/wuzhudexiaopacai/redisson/order/controller/OrderController.java)

```bash
# 快速连续发两个相同参数的请求（模拟双击）
curl -X POST "http://localhost:8081/order/submit?userId=1001&requestId=REQ-001" &
curl -X POST "http://localhost:8081/order/submit?userId=1001&requestId=REQ-001" &

# 验证：实际只生成了一个订单
curl "http://localhost:8081/order/count"
```

预期：一个成功、一个返回「订单正在处理中，请勿重复提交」，订单数 = 1。

### 场景 8：支付锁 —— 注解式加锁 + 锁异步化

**8a. 注解式加锁**（redisson-common 的 `@DistributedLock`，SpEL 指定锁 key，看门狗续期）：

```bash
curl -X POST "http://localhost:8081/pay/sync?orderId=1001"
```

**8b. 锁异步化**：`tryLockAsync` + `CompletableFuture`，抢锁等待期不占用 Tomcat 线程。

```bash
curl -X POST "http://localhost:8081/pay/async?orderId=1001"
```

> **教学要点**：RLock 记录持有线程 ID，`unlock()` 必须由持有线程发起。异步编排中
> 回调线程已切换，直接 `unlock()` 会抛 `IllegalMonitorStateException`，
> 跨线程释放要用 `forceUnlockAsync()`。见
> [AsyncLockController.java](redisson-order-service/src/main/java/com/github/wuzhudexiaopacai/redisson/order/controller/AsyncLockController.java)

### 场景 9：转账多资源联锁（MultiLock）

**问题**：转账要同时保护转出账户、转入账户、流水记录三把锁。分开加锁时，
A→B 与 B→A 并发容易死锁（各自持有对方需要的锁）。

**解法**：`RedissonMultiLock` 把多把锁打包，**要么全部抢到、要么全部放弃**。

代码：[MultiLockController.java](redisson-order-service/src/main/java/com/github/wuzhudexiaopacai/redisson/order/controller/MultiLockController.java)

```bash
curl -X POST "http://localhost:8081/transfer/init"          # A=1000, B=1000
# 并发发起双向转账
curl -X POST "http://localhost:8081/transfer?from=A&to=B&amount=100" &
curl -X POST "http://localhost:8081/transfer?from=B&to=A&amount=50" &
curl -X POST "http://localhost:8081/transfer/balance"       # 总额恒为 2000，不死锁
```

> 扩展思考：另一种防死锁方案是「锁排序」——所有线程按固定顺序（如账户名字典序）加锁，
> 打破循环等待条件。MultiLock 在使用层面更直观。

### 场景 10：跨服务秒杀组合拳（Feign + 幂等 + 库存锁）

**调用链路**：

```
order-service /seckill/order
  ├── @Idempotent 幂等锁：拦截同一用户的重复点击（防「同一个请求来多次」）
  └── Feign → inventory-service /stock/deduct
        └── RLock + 看门狗：保证不同用户并发争抢不超卖（防「不同请求同时来」）
```

两个锁防护维度不同，企业实战通常叠加使用。

```bash
curl -X POST "http://localhost:8082/stock/init?productId=2001&stock=10"
# 同一用户重复点击：第二次被幂等拦截
curl -X POST "http://localhost:8081/seckill/order?userId=1001&productId=2001"
curl -X POST "http://localhost:8081/seckill/order?userId=1001&productId=2001"
# 不同用户并发抢购：最终中奖人数 = 10
curl -X POST "http://localhost:8081/seckill/winners?productId=2001"
```

---

## 五、多实例验证分布式锁（关键）

单机演示只能验证「跨线程」，多实例才能验证「跨进程」——这才是分布式锁的真正价值。

以两个库存服务实例为例：

```bash
# 实例 1（默认 8082）
java -jar redisson-inventory-service/target/redisson-inventory-service-1.0.0.jar

# 实例 2（8083）
java -jar redisson-inventory-service/target/redisson-inventory-service-1.0.0.jar --server.port=8083
```

验证点：

1. **定时任务**：两个实例的日志中，每分钟只有一个打印「抢到锁，开始执行库存对账任务」；
2. **跨实例扣库存**：8082 和 8083 同时压测同一 productId，成功总数仍然不超过库存；
3. **为什么不能只用 synchronized / JVM 锁**：两个实例是两个 JVM 进程，
   `synchronized` 管不到对方，只有锁状态放在 Redis 里才全局生效。

## 六、核心原理速查（面试高频）

### 1. 可重入锁的实现

Redisson 锁是 Redis 中的一个 **Hash**：

```
Key:    lock:stock:1001
Field:  <线程标识 UUID:threadId>
Value:  重入次数
```

加锁 / 解锁 / 重入判断都是 Lua 脚本原子执行：Hash 不存在 → 创建并记 1；
持有者是当前线程 → 次数 +1（重入）；否则进入等待。

### 2. 看门狗（Watchdog）自动续期

- `lock()` 不传 `leaseTime` 时生效，默认锁超时 30s（`lockWatchdogTimeout`）；
- 后台定时任务每 `30s / 3 = 10s` 检查一次：只要业务还持有锁，就续期回 30s；
- 进程宕机 → 续期停止 → 锁最多残留 30s 自动过期，**不会死锁**；
- **一旦显式指定 `leaseTime`，看门狗失效**，到期锁立即释放——业务没执行完就危险了（见场景 1 反例）。

### 3. tryLock 的三种常用形态

| 调用 | 语义 | 适用 |
|---|---|---|
| `tryLock()` | 抢不到立即失败 | 定时任务、接口幂等 |
| `tryLock(wait, unit)` | 最多等 wait，看门狗续期 | 常规业务互斥 |
| `tryLock(wait, lease, unit)` | 最多等 wait，固定租约 lease | 能精确预估耗时的场景 |

### 4. 公平锁的实现

非公平锁：等待线程被唤醒后一起争抢，顺序随机。
公平锁：额外维护一个 **FIFO 队列（List）+ 等待超时集合（ZSet）**，
锁释放时只唤醒队首线程，严格先来先得。代价：吞吐量下降。

### 5. 为什么释放锁要用 isHeldByCurrentThread 判断

业务执行超过锁租约时，锁可能已被其他线程获得。此时直接 `unlock()`
会抛 `IllegalMonitorStateException`，甚至误删别人的锁。规范写法：

```java
} finally {
    if (lock.isHeldByCurrentThread()) {
        lock.unlock();
    }
}
```

### 6. 分布式锁方案对比

| 方案 | 优点 | 缺点 | 适用 |
|---|---|---|---|
| Redis（Redisson） | 性能高、API 完善、看门狗 | 极端主从切换可能丢锁 | 绝大多数业务互斥 |
| Zookeeper | 强一致、临时节点自动释放 | 性能较低、运维重 | 正确性敏感、并发不高 |
| 数据库（唯一索引/乐观锁） | 无额外组件、强一致 | 性能差、无阻塞等待 | 兜底保障、低频场景 |
| etcd | 强一致、租约机制 | 生态较小众 | 云原生体系 |

## 七、常见问题 FAQ

**Q1：Redis 主从架构下锁会丢吗？**
会。锁写入 master 后异步同步到 slave，若此时 master 宕机、slave 升主，
新 master 上没有锁记录，另一个客户端可对同一把锁加锁成功。
对丢锁零容忍 → 考虑 RedLock（多独立节点）或 Zookeeper；一般业务可接受
（概率极低，且通常有业务层兜底如数据库唯一约束）。

**Q2：锁的 key 怎么设计？**
`业务前缀:资源标识`，如 `lock:stock:1001`。粒度越细并发度越高；
切忌一把大锁锁全表。

**Q3：看门狗会让我死锁吗？**
不会。续期线程随进程存活，进程宕机续期即停，锁自然过期。
真正的风险是反过来：显式设小 `leaseTime` 导致锁提前释放。

**Q4：@Idempotent 和数据库唯一索引哪个好？**
叠加使用：注解锁在入口快速拦截（响应友好），唯一索引在 DB 层兜底（绝对正确）。

## 八、压测建议

- 每个场景都内置了基于 **JDK 21 虚拟线程** 的 `/stress` 压测接口，开箱即用；
- 需要更大并发时可用 JMeter / wrk / Apache Bench 直接打 HTTP 接口；
- 观察 Redis 中锁的状态可用 `redis-cli`：`HGETALL lock:stock:1001`、
  `TTL lock:stock:1001`（看门狗续期时 TTL 会反复回到 30）。
