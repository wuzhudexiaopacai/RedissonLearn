package com.github.wuzhudexiaopacai.redisson.inventory.service;

import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 库存服务核心逻辑：演示【可重入锁 + 看门狗】如何防止并发超卖。
 *
 * <p>超卖本质：「查库存 → 判断 → 扣减」是复合操作，不加锁时多个请求会同时读到
 * 相同库存值并都扣减成功。分布式锁把这段临界区串行化，保证原子性。</p>
 *
 * @author wuzhudexiaopacai
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    public static final String STOCK_KEY_PREFIX = "stock:";
    public static final String STOCK_LOCK_PREFIX = "lock:stock:";

    private final RedissonClient redissonClient;

    public StockService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 初始化库存（写入 Redis）。
     */
    public void initStock(long productId, int stock) {
        redissonClient.<Integer>getBucket(STOCK_KEY_PREFIX + productId).set(stock);
        log.info("初始化库存, productId={}, stock={}", productId, stock);
    }

    /**
     * 查询当前库存，未初始化返回 -1。
     */
    public int queryStock(long productId) {
        Integer stock = redissonClient.<Integer>getBucket(STOCK_KEY_PREFIX + productId).get();
        return stock == null ? -1 : stock;
    }

    /**
     * 安全扣减（推荐姿势）：lock() 不指定租约，由看门狗自动续期。
     * 业务执行多久锁就持有多久；进程宕机时锁最多残留 30s 后自动释放，不会死锁。
     */
    public String deduct(long productId) {
        RLock lock = redissonClient.getLock(STOCK_LOCK_PREFIX + productId);
        lock.lock();
        try {
            return doDeduct(productId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 危险扣减（对照组）：完全不加锁，并发压测时必现超卖（库存会被扣成负数）。
     */
    public String deductUnsafe(long productId) {
        return doDeduct(productId);
    }

    /**
     * 固定租约扣减（反例）：锁 1 秒后自动过期，但业务故意执行 1.5 秒。
     * 锁提前释放期间其他请求会进入临界区，同样会超卖。
     * 用于说明：leaseTime 设置过小等于变相失去了看门狗保护。
     */
    public String deductWithShortLease(long productId) {
        RLock lock = redissonClient.getLock(STOCK_LOCK_PREFIX + productId);
        // 固定租约 1s，不续期
        lock.lock(1, TimeUnit.SECONDS);
        try {
            // 模拟慢业务：执行时间超过租约，锁会提前失效
            Thread.sleep(1500);
            return doDeduct(productId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "扣减被中断";
        } finally {
            // 锁可能已过期被其他线程持有，必须判断持有线程再释放
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 临界区：查库存 → 判断 → 扣减。
     */
    private String doDeduct(long productId) {
        RBucket<Integer> bucket = redissonClient.getBucket(STOCK_KEY_PREFIX + productId);
        Integer stock = bucket.get();
        if (stock == null) {
            return "库存未初始化，请先调用 /stock/init";
        }
        if (stock <= 0) {
            return "库存不足";
        }
        bucket.set(stock - 1);
        return "扣减成功，剩余库存：" + (stock - 1);
    }
}
