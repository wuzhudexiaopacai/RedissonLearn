package com.github.wuzhudexiaopacai.redisson.order.controller;

import com.github.wuzhudexiaopacai.redisson.common.annotation.DistributedLock;
import com.github.wuzhudexiaopacai.redisson.common.result.Result;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 锁异步化演示：基于 RLock 的异步 API + CompletableFuture 编排。
 *
 * <p>同步锁的痛点：tryLock 等待和业务执行都会占用 Tomcat 工作线程。
 * 异步化后等待期不阻塞线程，适合高吞吐 IO 密集型接口。</p>
 *
 * <p>【重要教学点】RLock 内部记录持有锁的线程 ID，unlock 必须由持有线程发起。
 * 异步编排中回调线程与加锁线程往往不是同一个，直接 unlock() 会抛出
 * IllegalMonitorStateException。跨线程释放应使用 forceUnlockAsync()，
 * 或者在回调中重新 lock() 再操作（不推荐）。本例演示前者。</p>
 *
 * @author wuzhudexiaopacai
 */
@RestController
@RequestMapping("/pay")
public class AsyncLockController {

    private static final Logger log = LoggerFactory.getLogger(AsyncLockController.class);

    private final RedissonClient redissonClient;

    public AsyncLockController(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 同步支付：POST /pay/sync?orderId=1001
     * 演示 redisson-common 提供的 @DistributedLock 注解式加锁（看门狗自动续期）。
     */
    @PostMapping("/sync")
    @DistributedLock(key = "#orderId", prefix = "lock:pay:", waitTime = 3)
    public Result<String> syncPay(@RequestParam long orderId) throws InterruptedException {
        // 模拟支付业务耗时
        Thread.sleep(500);
        return Result.ok("订单 " + orderId + " 同步支付完成（@DistributedLock 注解保护）");
    }

    /**
     * 异步支付：POST /pay/async?orderId=1001
     */
    @PostMapping("/async")
    public CompletableFuture<Result<String>> asyncPay(@RequestParam long orderId) {
        RLock lock = redissonClient.getLock("lock:pay:" + orderId);

        // 异步抢锁：最多等待 3s，抢锁过程不阻塞当前线程
        return lock.tryLockAsync(3, TimeUnit.SECONDS)
                .toCompletableFuture()
                .thenCompose(acquired -> {
                    if (!acquired) {
                        log.warn("异步支付未抢到锁, orderId={}", orderId);
                        return CompletableFuture.completedFuture(Result.fail("支付处理中，请勿重复请求"));
                    }
                    log.info("异步支付抢到锁, orderId={}", orderId);
                    // 异步执行支付业务
                    return CompletableFuture.supplyAsync(() -> {
                                try {
                                    // 模拟调用第三方支付渠道耗时
                                    Thread.sleep(500);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                return Result.ok("订单 " + orderId + " 异步支付完成");
                            })
                            // 教学要点：supplyAsync 已切换线程，unlock() 会因线程归属校验失败，
                            // 跨线程场景使用 forceUnlockAsync() 释放锁
                            .whenComplete((result, ex) -> lock.forceUnlockAsync());
                });
    }
}
