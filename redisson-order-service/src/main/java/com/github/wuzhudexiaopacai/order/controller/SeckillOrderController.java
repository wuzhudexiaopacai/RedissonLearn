package com.github.wuzhudexiaopacai.redisson.order.controller;

import com.github.wuzhudexiaopacai.redisson.common.annotation.Idempotent;
import com.github.wuzhudexiaopacai.redisson.common.result.Result;
import com.github.wuzhudexiaopacai.redisson.order.feign.StockFeignClient;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秒杀下单演示：Feign 跨服务调用 + 接口幂等 + 下游分布式锁的组合拳。
 *
 * <p>调用链路：order-service（@Idempotent 幂等拦截重复点击）
 *   → Feign → inventory-service（RLock + 看门狗保证扣库存不超卖）</p>
 *
 * <p>说明：幂等锁解决「同一个用户重复提交」，库存锁解决「不同用户并发争抢」，
 * 两者防护维度不同，企业实战中通常叠加使用。</p>
 *
 * @author wuzhudexiaopacai
 */
@RestController
@RequestMapping("/seckill")
public class SeckillOrderController {

    private static final Logger log = LoggerFactory.getLogger(SeckillOrderController.class);

    private final StockFeignClient stockFeignClient;
    private final RedissonClient redissonClient;

    public SeckillOrderController(StockFeignClient stockFeignClient, RedissonClient redissonClient) {
        this.stockFeignClient = stockFeignClient;
        this.redissonClient = redissonClient;
    }

    /**
     * 秒杀下单：POST /seckill/order?userId=1001&productId=1001
     * 同一用户同一商品 5 秒内的重复请求会被幂等拦截。
     */
    @PostMapping("/order")
    @Idempotent(key = "#userId + ':' + #productId", leaseTime = 5, message = "秒杀请求处理中，请勿重复点击")
    public Result<String> seckillOrder(@RequestParam long userId, @RequestParam long productId) {
        // Feign 跨服务扣库存：库存服务的分布式锁保证并发下不超卖
        Result<String> deductResult = stockFeignClient.deduct(productId);
        if (deductResult.code() != 200 || deductResult.data() == null
                || !deductResult.data().startsWith("扣减成功")) {
            return Result.fail("秒杀失败：" + deductResult.data());
        }
        // 扣减成功，创建订单（演示用 Set 记录中奖用户）
        RSet<Long> winners = redissonClient.getSet("seckill:winners:" + productId);
        winners.add(userId);
        log.info("秒杀成功, userId={}, productId={}", userId, productId);
        return Result.ok("秒杀成功！userId=" + userId + "，" + deductResult.data());
    }

    /**
     * 查看某商品的秒杀成功用户列表。
     */
    @PostMapping("/winners")
    public Result<String> winners(@RequestParam long productId) {
        RSet<Long> winners = redissonClient.getSet("seckill:winners:" + productId);
        return Result.ok("秒杀成功人数=" + winners.size() + "，名单=" + winners.readAll());
    }

    /**
     * 清空秒杀记录，便于重复演示。
     */
    @PostMapping("/reset")
    public Result<String> reset(@RequestParam long productId) {
        redissonClient.getSet("seckill:winners:" + productId).delete();
        return Result.ok("秒杀记录已清空");
    }
}
