package com.github.wuzhudexiaopacai.redisson.common.aspect;

import com.github.wuzhudexiaopacai.redisson.common.annotation.DistributedLock;
import com.github.wuzhudexiaopacai.redisson.common.exception.LockAcquireException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * {@link DistributedLock} 注解的切面实现。
 *
 * <p>加锁逻辑：</p>
 * <ol>
 *   <li>解析 SpEL 得到最终锁名；</li>
 *   <li>按注解配置选择普通可重入锁或公平锁；</li>
 *   <li>tryLock 等待抢锁，失败抛异常快速失败；</li>
 *   <li>业务执行完毕后在 finally 中释放锁（仅当前线程持有时才释放，防止误解锁）。</li>
 * </ol>
 *
 * @author wuzhudexiaopacai
 */
@Aspect
@Component
public class DistributedLockAspect {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockAspect.class);

    private final RedissonClient redissonClient;
    private final SpelKeyResolver keyResolver;

    public DistributedLockAspect(RedissonClient redissonClient, SpelKeyResolver keyResolver) {
        this.redissonClient = redissonClient;
        this.keyResolver = keyResolver;
    }

    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        String lockName = distributedLock.prefix() + keyResolver.resolve(joinPoint, distributedLock.key());
        RLock lock = distributedLock.fair()
                ? redissonClient.getFairLock(lockName)
                : redissonClient.getLock(lockName);

        long waitTime = distributedLock.waitTime();
        long leaseTime = distributedLock.leaseTime();
        TimeUnit unit = distributedLock.unit();

        boolean acquired = tryLock(lock, waitTime, leaseTime, unit);
        if (!acquired) {
            log.warn("获取分布式锁失败, lockName={}, waitTime={}{}", lockName, waitTime, unit);
            throw new LockAcquireException("系统繁忙，请稍后重试（锁：" + lockName + "）");
        }
        log.debug("获取分布式锁成功, lockName={}", lockName);
        try {
            return joinPoint.proceed();
        } finally {
            // 仅当前线程仍持有锁时才释放，避免业务超时时锁已被看门狗/租约回收后误解锁
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("释放分布式锁, lockName={}", lockName);
            }
        }
    }

    /**
     * leaseTime 为 -1 时使用看门狗自动续期；否则按固定租约加锁（到期自动释放，不续期）。
     */
    private boolean tryLock(RLock lock, long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
        if (leaseTime == -1) {
            return lock.tryLock(waitTime, unit);
        }
        return lock.tryLock(waitTime, leaseTime, unit);
    }
}
