package com.github.wuzhudexiaopacai.redisson.common.web;

import com.github.wuzhudexiaopacai.redisson.common.exception.DuplicateSubmitException;
import com.github.wuzhudexiaopacai.redisson.common.exception.LockAcquireException;
import com.github.wuzhudexiaopacai.redisson.common.result.Result;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：把锁相关异常转换为友好的统一返回。
 *
 * @author wuzhudexiaopacai
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(LockAcquireException.class)
    public Result<Void> handleLockAcquire(LockAcquireException e) {
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(DuplicateSubmitException.class)
    public Result<Void> handleDuplicateSubmit(DuplicateSubmitException e) {
        return Result.fail(e.getMessage());
    }
}
