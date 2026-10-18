package com.github.wuzhudexiaopacai.redisson.common.result;

/**
 * 统一返回结果。
 *
 * @author wuzhudexiaopacai
 */
public record Result<T>(int code, String message, T data) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "success", data);
    }

    public static <T> Result<T> fail(String message) {
        return new Result<>(500, message, null);
    }
}
