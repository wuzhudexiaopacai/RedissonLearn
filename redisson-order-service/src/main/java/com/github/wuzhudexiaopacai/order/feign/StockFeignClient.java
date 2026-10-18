package com.github.wuzhudexiaopacai.redisson.order.feign;

import com.github.wuzhudexiaopacai.redisson.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 库存服务 Feign 客户端：通过服务名跨服务调用，演示微服务架构下
 * 分布式锁依然能保证正确性（锁的状态在 Redis 中，与调用链路无关）。
 *
 * @author wuzhudexiaopacai
 */
@FeignClient(name = "redisson-inventory-service")
public interface StockFeignClient {

    /**
     * 调用库存服务的安全扣减接口（内部由 RLock + 看门狗保护）。
     */
    @PostMapping("/stock/deduct")
    Result<String> deduct(@RequestParam long productId);
}
