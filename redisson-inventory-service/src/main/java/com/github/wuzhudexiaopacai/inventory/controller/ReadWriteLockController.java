package com.github.wuzhudexiaopacai.redisson.inventory.controller;

import com.github.wuzhudexiaopacai.redisson.common.result.Result;
import org.redisson.api.RBucket;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 读写锁演示：价格/配置表「读多写少」场景。
 *
 * <p>语义与 JDK 的 ReentrantReadWriteLock 一致，但作用域是跨进程跨实例的：</p>
 * <ul>
 *   <li>读读共享：多个读请求可并发执行；</li>
 *   <li>读写互斥、写写互斥：写操作执行期间，其他读/写都必须等待。</li>
 * </ul>
 *
 * <p>演示方式：写一个接口故意 sleep 3 秒，写期间发起读请求会明显阻塞；
 * 而多个读请求之间互不影响。</p>
 *
 * @author wuzhudexiaopacai
 */
@RestController
@RequestMapping("/price")
public class ReadWriteLockController {

    private static final Logger log = LoggerFactory.getLogger(ReadWriteLockController.class);

    private static final String PRICE_KEY = "price:config";
    private static final String RW_LOCK_KEY = "lock:rw:price";

    private final RedissonClient redissonClient;

    public ReadWriteLockController(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 读价格（读锁）：GET /price/read
     */
    @GetMapping("/read")
    public Result<String> read() {
        RReadWriteLock rwLock = redissonClient.getReadWriteLock(RW_LOCK_KEY);
        rwLock.readLock().lock();
        try {
            RBucket<String> bucket = redissonClient.getBucket(PRICE_KEY);
            String price = bucket.get();
            log.info("读取价格: {}", price);
            return Result.ok("当前价格：" + (price == null ? "未设置" : price));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 写价格（写锁）：PUT /price/write?value=99.9
     * 故意 sleep 3 秒模拟慢写操作，期间读请求会被阻塞，可直观观察读写互斥。
     */
    @PutMapping("/write")
    public Result<String> write(@RequestParam String value) {
        RReadWriteLock rwLock = redissonClient.getReadWriteLock(RW_LOCK_KEY);
        rwLock.writeLock().lock();
        try {
            log.info("开始写入价格: {}，模拟慢业务 3 秒...", value);
            Thread.sleep(3000);
            redissonClient.getBucket(PRICE_KEY).set(value);
            return Result.ok("价格已更新为：" + value);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("写入被中断");
        } finally {
            rwLock.writeLock().unlock();
        }
    }
}
