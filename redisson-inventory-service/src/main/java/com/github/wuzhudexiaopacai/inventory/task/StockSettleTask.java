package com.github.wuzhudexiaopacai.redisson.inventory.task;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时任务防重复执行演示。
 *
 * <p>问题：@Scheduled 任务在单体应用没问题，但微服务多实例部署后，
 * 每个实例都会触发同一任务（如每日结算、账单生成），造成重复执行。</p>
 *
 * <p>解法：任务执行前 tryLock() 立即抢锁——抢到的实例执行，抢不到的直接跳过。
 * 这正是 ShedLock 等框架的核心原理。</p>
 *
 * <p>验证方式：用不同端口启动两个 inventory-service 实例，观察日志，
 * 每分钟只有一个实例真正执行任务。</p>
 *
 * @author wuzhudexiaopacai
 */
@Component
public class StockSettleTask {

    private static final Logger log = LoggerFactory.getLogger(StockSettleTask.class);

    private static final String TASK_LOCK_KEY = "lock:task:stock-settle";

    private final RedissonClient redissonClient;

    public StockSettleTask(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 每分钟执行一次库存对账任务。
     * tryLock() 无参 = 不等待，抢不到立即返回 false，多实例下只有一个实例能抢到。
     */
    @Scheduled(cron = "0 * * * * *")
    public void stockSettle() {
        RLock lock = redissonClient.getLock(TASK_LOCK_KEY);
        boolean acquired = lock.tryLock();
        if (!acquired) {
            log.info("[定时任务] 未抢到锁，本实例跳过执行（其他实例正在执行）");
            return;
        }
        try {
            log.info("[定时任务] >>> 抢到锁，开始执行库存对账任务 <<<");
            // 模拟任务耗时
            Thread.sleep(3000);
            log.info("[定时任务] >>> 库存对账任务执行完成 <<<");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
