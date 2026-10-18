package com.github.wuzhudexiaopacai.redisson.order.controller;

import com.github.wuzhudexiaopacai.redisson.common.result.Result;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 联锁（MultiLock）演示：转账场景同时锁定多个资源。
 *
 * <p>转账需要同时保护「转出账户」「转入账户」「流水记录」三个资源。
 * 如果分多次单独加锁，两个反向转账并发时容易死锁（A锁1等2，B锁2等1）。</p>
 *
 * <p>MultiLock 把多个 RLock 打包成一把联锁，一次性全部锁定或全部失败，
 * 从使用层面规避了加锁顺序不一致导致的死锁问题。</p>
 *
 * <p>演示方式：/transfer/init 初始化两个账户各 1000 元，然后并发发起
 * A→B 与 B→A 两笔转账，观察最终总额恒为 2000 且不死锁。</p>
 *
 * @author wuzhudexiaopacai
 */
@RestController
@RequestMapping("/transfer")
public class MultiLockController {

    private static final Logger log = LoggerFactory.getLogger(MultiLockController.class);

    private static final String BALANCE_KEY_PREFIX = "account:balance:";

    private final RedissonClient redissonClient;

    public MultiLockController(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 初始化账户：POST /transfer/init
     */
    @PostMapping("/init")
    public Result<String> init() {
        redissonClient.getBucket(BALANCE_KEY_PREFIX + "A").set("1000");
        redissonClient.getBucket(BALANCE_KEY_PREFIX + "B").set("1000");
        return Result.ok("账户初始化完成：A=1000, B=1000");
    }

    /**
     * 查询余额：POST /transfer/balance
     */
    @PostMapping("/balance")
    public Result<String> balance() {
        String a = redissonClient.<String>getBucket(BALANCE_KEY_PREFIX + "A").get();
        String b = redissonClient.<String>getBucket(BALANCE_KEY_PREFIX + "B").get();
        return Result.ok("A=" + a + ", B=" + b);
    }

    /**
     * 转账：POST /transfer?from=A&to=B&amount=100
     */
    @PostMapping
    public Result<String> transfer(@RequestParam String from,
                                   @RequestParam String to,
                                   @RequestParam BigDecimal amount) {
        RLock fromLock = redissonClient.getLock("lock:account:" + from);
        RLock toLock = redissonClient.getLock("lock:account:" + to);
        RLock flowLock = redissonClient.getLock("lock:account:flow");

        // 联锁：三把锁要么全部抢到，要么全部放弃，避免反向转账并发时死锁
        // Redisson 3.x 新 API：getMultiLock 返回普通 RLock 接口
        RLock multiLock = redissonClient.getMultiLock(fromLock, toLock, flowLock);
        try {
            // 等待 5s 抢锁，租约 10s 自动释放兜底
            boolean acquired = multiLock.tryLock(5, 10, java.util.concurrent.TimeUnit.SECONDS);
            if (!acquired) {
                return Result.fail("转账繁忙，请稍后重试");
            }
            try {
                RBucket<String> fromBucket = redissonClient.getBucket(BALANCE_KEY_PREFIX + from);
                RBucket<String> toBucket = redissonClient.getBucket(BALANCE_KEY_PREFIX + to);
                BigDecimal fromBalance = new BigDecimal(fromBucket.get());
                BigDecimal toBalance = new BigDecimal(toBucket.get());
                if (fromBalance.compareTo(amount) < 0) {
                    return Result.fail("账户 " + from + " 余额不足");
                }
                fromBucket.set(fromBalance.subtract(amount).toPlainString());
                toBucket.set(toBalance.add(amount).toPlainString());
                log.info("转账成功: {} -> {}, 金额={}", from, to, amount);
                return Result.ok("转账成功：" + from + " -> " + to + "，金额=" + amount);
            } finally {
                multiLock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("转账被中断");
        }
    }
}
