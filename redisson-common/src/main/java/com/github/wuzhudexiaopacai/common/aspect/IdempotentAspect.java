package com.github.wuzhudexiaopacai.redisson.common.aspect;

import com.github.wuzhudexiaopacai.redisson.common.annotation.Idempotent;
import com.github.wuzhudexiaopacai.redisson.common.exception.DuplicateSubmitException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link Idempotent} 注解的切面实现：基于分布式锁的接口幂等。
 *
 * <p>抢不到锁说明同一幂等 key 已有请求在处理中（或刚处理完、窗口未过期），直接拒绝。</p>
 *
 * <p>注意：业务执行完毕后锁会立即释放。若业务要求"处理完成后一段时间内仍拒绝重复请求"，
 * 可在业务内部把处理结果写入 Redis（key 相同，TTL = 幂等窗口），进入方法前先查结果缓存。
 * 本 Demo 聚焦锁本身，采用 finally 释放的方式。</p>
 *
 * @author wuzhudexiaopacai
 */
@Aspect
@Component
public class IdempotentAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotentAspect.class);

    private final RedissonClient redissonClient;
    private final SpelKeyResolver keyResolver;

    public IdempotentAspect(RedissonClient redissonClient, SpelKeyResolver keyResolver) {
        this.redissonClient = redissonClient;
        this.keyResolver = keyResolver;
    }

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        String lockName = idempotent.prefix() + keyResolver.resolve(joinPoint, idempotent.key());
        RLock lock = redissonClient.getLock(lockName);

        boolean acquired = lock.tryLock(idempotent.waitTime(), idempotent.leaseTime(), idempotent.unit());
        if (!acquired) {
            log.warn("幂等校验拦截重复请求, lockName={}", lockName);
            throw new DuplicateSubmitException(idempotent.message());
        }
        try {
            return joinPoint.proceed();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
