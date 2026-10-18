package com.github.wuzhudexiaopacai.redisson.common.exception;

/**
 * 接口幂等校验未通过（重复提交）时抛出。
 *
 * @author wuzhudexiaopacai
 */
public class DuplicateSubmitException extends RuntimeException {

    public DuplicateSubmitException(String message) {
        super(message);
    }
}
