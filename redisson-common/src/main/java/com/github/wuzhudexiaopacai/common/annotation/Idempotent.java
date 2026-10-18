package com.github.wuzhudexiaopacai.redisson.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 接口幂等注解：防止同一请求被重复提交（双击、重试、网络重发等）。
 *
 * <p>原理：以业务唯一标识（如 userId + 请求流水号）作为锁 key，尝试立即抢锁，
 * 抢不到说明有相同请求正在处理中，直接拒绝。</p>
 *
 * <p>使用示例：</p>
 * <pre>
 * &#64;Idempotent(key = "#userId + ':' + #requestId", leaseTime = 10)
 * public Result&lt;String&gt; submit(Long userId, String requestId) { ... }
 * </pre>
 *
 * @author wuzhudexiaopacai
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /**
     * 幂等 key，支持 SpEL 表达式（可引用方法参数名）。
     * 同一 key 在 leaseTime 窗口内只允许一个请求进入。
     */
    String key();

    /**
     * 锁前缀，最终锁名 = prefix + key。
     */
    String prefix() default "idem:";

    /**
     * 获取锁的等待时间。幂等场景通常设为 0：抢不到立即失败，快速拒绝重复请求。
     */
    long waitTime() default 0;

    /**
     * 锁持有时间。即使业务执行很快，窗口期内的重复请求也会被拒绝。
     * 注意必须显式指定一个合理的正数，避免锁长期残留。
     */
    long leaseTime() default 10;

    /**
     * 时间单位。
     */
    TimeUnit unit() default TimeUnit.SECONDS;

    /**
     * 拒绝重复提交时的提示信息。
     */
    String message() default "请求正在处理中，请勿重复提交";
}
