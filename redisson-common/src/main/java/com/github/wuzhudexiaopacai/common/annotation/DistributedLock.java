package com.github.wuzhudexiaopacai.redisson.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 通用分布式锁注解：在方法维度声明式加锁。
 *
 * <p>使用示例：</p>
 * <pre>
 * &#64;DistributedLock(key = "#orderId", waitTime = 3)
 * public void pay(Long orderId) { ... }
 * </pre>
 *
 * @author wuzhudexiaopacai
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {

    /**
     * 锁的业务 key，支持 SpEL 表达式（可直接引用方法参数名，如 #orderId）。
     */
    String key();

    /**
     * 锁前缀，用于区分业务域，最终锁名 = prefix + key。
     */
    String prefix() default "lock:";

    /**
     * 获取锁的最大等待时间。等待期内未抢到锁则抛出 {@code LockAcquireException}。
     */
    long waitTime() default 5;

    /**
     * 锁的持有时间（租约）。默认 -1 表示不指定，交给看门狗自动续期，
     * 业务执行多久锁就持有多久，进程宕机后最多残留 lockWatchdogTimeout（30s）。
     */
    long leaseTime() default -1;

    /**
     * 时间单位。
     */
    TimeUnit unit() default TimeUnit.SECONDS;

    /**
     * 是否使用公平锁。公平锁按请求到达顺序排队获取，适合秒杀排队等场景，
     * 但吞吐量低于普通可重入锁。
     */
    boolean fair() default false;
}
