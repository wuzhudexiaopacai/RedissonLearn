package com.github.wuzhudexiaopacai.redisson.inventory.controller;

import com.github.wuzhudexiaopacai.redisson.common.result.Result;
import org.redisson.api.RList;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 公平锁演示：秒杀排队场景。
 *
 * <p>普通可重入锁是非公平的：锁释放时所有等待线程竞争，先到先得全凭运气，
 * 可能产生线程饥饿。公平锁内部维护一个 FIFO 队列（Redis List + 超时 Set），
 * 严格按请求到达顺序发放锁，适合「秒杀排队」「工单按序处理」等场景。</p>
 *
 * <p>演示方式：并发提交多个不同 userId 的请求，获取锁成功的顺序会记录在
 * Redis 列表中，GET /fair/queue 查看——顺序与请求到达顺序基本一致。</p>
 *
 * <p>注意：公平锁吞吐量低于非公平锁，非必要不使用。</p>
 *
 * @author wuzhudexiaopacai
 */
@RestController
@RequestMapping("/fair")
public class FairLockController {

    private static final Logger log = LoggerFactory.getLogger(FairLockController.class);

    private static final String FAIR_LOCK_KEY = "lock:fair:seckill";
    private static final String QUEUE_RECORD_KEY = "fair:seckill:queue-record";

    private final RedissonClient redissonClient;

    public FairLockController(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 公平锁秒杀：POST /fair/seckill?userId=10086
     */
    @PostMapping("/seckill")
    public Result<String> seckill(@RequestParam long userId) {
        RLock fairLock = redissonClient.getFairLock(FAIR_LOCK_KEY);
        fairLock.lock();
        try {
            // 记录获取到锁的顺序
            RList<Long> queueRecord = redissonClient.getList(QUEUE_RECORD_KEY);
            queueRecord.add(userId);
            log.info("用户 {} 抢到公平锁，排队位置={}", userId, queueRecord.size());
            // 模拟秒杀业务耗时
            Thread.sleep(200);
            return Result.ok("用户 " + userId + " 按排队顺序完成秒杀，当前序号=" + queueRecord.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("秒杀被中断");
        } finally {
            if (fairLock.isHeldByCurrentThread()) {
                fairLock.unlock();
            }
        }
    }

    /**
     * 查看获取锁的先后顺序。
     */
    @GetMapping("/queue")
    public Result<List<Long>> queue() {
        return Result.ok(redissonClient.<Long>getList(QUEUE_RECORD_KEY).readAll());
    }

    /**
     * 清空排队记录，便于重复演示。
     */
    @PostMapping("/reset")
    public Result<String> reset() {
        redissonClient.getList(QUEUE_RECORD_KEY).delete();
        return Result.ok("排队记录已清空");
    }
}
