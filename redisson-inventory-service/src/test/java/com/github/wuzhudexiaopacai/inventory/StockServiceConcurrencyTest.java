package com.github.wuzhudexiaopacai.redisson.inventory;

import com.github.wuzhudexiaopacai.redisson.inventory.service.StockService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存扣减并发正确性测试。
 *
 * <p>注意：运行本测试需要本地 Redis（默认 127.0.0.1:6379）处于启动状态。</p>
 *
 * @author wuzhudexiaopacai
 */
@SpringBootTest
class StockServiceConcurrencyTest {

    @Autowired
    private StockService stockService;

    @Autowired
    private RedissonClient redissonClient;

    /**
     * 加锁扣减：库存 100，200 并发争抢，必须恰好 100 人成功、库存恰好归零。
     */
    @Test
    void concurrentDeduct_withLock_shouldNotOversell() throws InterruptedException {
        long productId = 9001L;
        int stock = 100;
        int threads = 200;
        stockService.initStock(productId, stock);

        AtomicInteger success = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(threads);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        if (stockService.deduct(productId).startsWith("扣减成功")) {
                            success.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        }

        assertEquals(stock, success.get(), "成功数必须等于初始库存");
        assertEquals(0, stockService.queryStock(productId), "库存必须恰好扣到 0，不能为负");
    }

    /**
     * 可重入性验证：同一线程对同一把锁重复加锁不会阻塞自己。
     */
    @Test
    void reentrantLock_sameThread_canLockTwice() {
        RLock lock = redissonClient.getLock(StockService.STOCK_LOCK_PREFIX + "reentrant-test");
        lock.lock();
        try {
            // 同一线程再次加锁：可重入，立即成功
            boolean reentrant = lock.tryLock();
            try {
                assertTrue(reentrant, "同一线程应能重入同一把锁");
            } finally {
                lock.unlock();
            }
        } finally {
            lock.unlock();
        }
    }
}
