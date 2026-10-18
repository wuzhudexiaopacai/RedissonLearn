package com.github.wuzhudexiaopacai.redisson.inventory.controller;

import com.github.wuzhudexiaopacai.redisson.common.result.Result;
import jakarta.annotation.PreDestroy;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 红锁（RedLock）演示。
 *
 * <p>RedLock 是 Redis 作者 antirez 提出的高可用分布式锁算法：向 N 个（通常 5 个）
 * 【相互独立】的 Redis 节点同时加锁，超过半数（N/2+1）成功才算加锁成功。
 * 即使个别节点宕机，锁服务整体仍可用。</p>
 *
 * <p>注意：本 Demo 只有单节点 Redis，因此 3 个客户端指向同一地址，
 * 仅用于演示 API 用法。生产环境必须部署多个独立节点才有意义。</p>
 *
 * <p>争议提示：Martin Kleppmann（《数据密集型应用系统设计》作者）公开质疑
 * RedLock 在时钟跳变、GC 长停顿（STW）等场景下的安全性，antirez 撰文反驳。
 * 业内共识：对正确性要求极高的场景（如金融扣款）建议用数据库唯一约束、
 * Zookeeper /etcd（基于共识协议）兜底；对效率型互斥（防并发、幂等）普通
 * Redis 单节点锁 + 看门狗已足够。详见 README。</p>
 *
 * @author wuzhudexiaopacai
 */
@RestController
@RequestMapping("/redlock")
public class RedLockController {

    private final List<RedissonClient> clients;

    public RedLockController(@Value("${redisson.address:redis://127.0.0.1:6379}") String address) {
        // 演示用：创建 3 个客户端指向同一节点；生产环境应指向 3 个独立 Redis 节点
        this.clients = Stream.generate(() -> {
            Config config = new Config();
            config.useSingleServer().setAddress(address);
            return Redisson.create(config);
        }).limit(3).toList();
    }

    @PreDestroy
    public void destroy() {
        clients.forEach(RedissonClient::shutdown);
    }

    /**
     * 红锁保护的秒杀下单：POST /redlock/seckill?userId=10086
     */
    @PostMapping("/seckill")
    public Result<String> seckill(@RequestParam long userId) {
        String lockKey = "lock:redlock:seckill";
        RLock lock1 = clients.get(0).getLock(lockKey);
        RLock lock2 = clients.get(1).getLock(lockKey);
        RLock lock3 = clients.get(2).getLock(lockKey);

        // 红锁：多数节点加锁成功才算成功（Redisson 3.x 新 API：getRedLock 返回普通 RLock 接口）
        RLock redLock = clients.get(0).getRedLock(lock1, lock2, lock3);
        try {
            // 最多等待 5s，锁持有 30s（RedLock 不支持看门狗续期，必须显式指定租约）
            boolean acquired = redLock.tryLock(5, 30, TimeUnit.SECONDS);
            if (!acquired) {
                return Result.fail("未抢到红锁，秒杀失败");
            }
            try {
                // 模拟秒杀核心业务
                Thread.sleep(500);
                return Result.ok("用户 " + userId + " 在红锁保护下完成秒杀");
            } finally {
                redLock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("秒杀被中断");
        }
    }
}
