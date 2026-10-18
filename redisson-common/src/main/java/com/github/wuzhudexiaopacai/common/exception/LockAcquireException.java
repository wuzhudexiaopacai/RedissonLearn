package com.github.wuzhudexiaopacai.redisson.common.exception;

/**
 * 获取分布式锁失败时抛出。
 *
 * @author wuzhudexiaopacai
 */
public class LockAcquireException extends RuntimeException {

    public LockAcquireException(String message) {
        super(message);
    }
}
