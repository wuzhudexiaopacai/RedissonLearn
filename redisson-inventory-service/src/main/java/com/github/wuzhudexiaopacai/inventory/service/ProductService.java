package com.github.wuzhudexiaopacai.redisson.inventory.service;

import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 商品详情查询：演示【缓存击穿】场景下「分布式锁 + 双重检查（Double Check）」的标准解法。
 *
 * <p>缓存击穿：热点 key 过期的瞬间，海量请求同时穿透到数据库。
 * 解法要点：</p>
 * <ol>
 *   <li>缓存未命中时，不直接查库，先抢分布式锁；</li>
 *   <li>抢到锁后【再次检查缓存】——第一个抢到锁的线程已回源并重建缓存，
 *       后续线程直接命中，只有 1 次 DB 查询；</li>
 *   <li>确认缓存仍为空才查库，并写回缓存。</li>
 * </ol>
 *
 * @author wuzhudexiaopacai
 */
@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private static final String CACHE_KEY_PREFIX = "cache:product:";
    private static final String LOCK_KEY_PREFIX = "lock:product:";

    private final RedissonClient redissonClient;

    /** 记录真实回源（查库）次数，用于压测时验证双重检查效果 */
    private final AtomicInteger dbHitCount = new AtomicInteger();

    public ProductService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    public String getProduct(long productId) {
        String cacheKey = CACHE_KEY_PREFIX + productId;
        RBucket<String> bucket = redissonClient.getBucket(cacheKey);

        // 第一次检查：命中缓存直接返回（绝大多数请求走这里，无锁开销）
        String cached = bucket.get();
        if (cached != null) {
            return "[缓存命中] " + cached;
        }

        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + productId);
        lock.lock();
        try {
            // 双重检查：拿到锁后再查一次缓存，可能已被前一个持锁线程重建
            cached = bucket.get();
            if (cached != null) {
                return "[双重检查命中缓存] " + cached;
            }
            String product = queryFromDb(productId);
            bucket.set(product, 30, TimeUnit.SECONDS);
            return "[回源查库] " + product;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 删除商品缓存，模拟热点 key 过期，便于重复演示。
     */
    public void evictCache(long productId) {
        redissonClient.getBucket(CACHE_KEY_PREFIX + productId).delete();
    }

    public int getDbHitCount() {
        return dbHitCount.get();
    }

    public void resetDbHitCount() {
        dbHitCount.set(0);
    }

    /**
     * 模拟数据库查询：耗时 2 秒，放大并发回源的差异。
     */
    private String queryFromDb(long productId) {
        dbHitCount.incrementAndGet();
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("回源查询数据库, productId={}", productId);
        return "商品-" + productId + " 详情（模拟数据库数据）";
    }
}
