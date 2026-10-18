package com.github.wuzhudexiaopacai.redisson.order.controller;

import com.github.wuzhudexiaopacai.redisson.common.annotation.Idempotent;
import com.github.wuzhudexiaopacai.redisson.common.result.Result;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接口幂等演示：防止重复下单。
 *
 * <p>典型事故场景：用户双击提交按钮、前端超时重试、支付回调重发，
 * 同一业务请求到达服务端多次，产生重复订单。</p>
 *
 * <p>解法：以「用户ID + 请求流水号」为幂等 key 抢锁，抢不到说明相同请求正在处理，
 * 直接拒绝。窗口期（leaseTime）内的重复请求全部拦截。</p>
 *
 * <p>演示方式：连续快速调用两次相同参数的 /order/submit，
 * 第一次成功，第二次被幂等拦截；/order/count 确认只生成了一个订单。</p>
 *
 * @author wuzhudexiaopacai
 */
@RestController
@RequestMapping("/order")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final RedissonClient redissonClient;

    public OrderController(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 提交订单：POST /order/submit?userId=1001&requestId=REQ-001
     *
     * @param requestId 前端生成的请求流水号（每次点击生成一次；双击/重试时流水号不变）
     */
    @PostMapping("/submit")
    @Idempotent(key = "#userId + ':' + #requestId", leaseTime = 10, message = "订单正在处理中，请勿重复提交")
    public Result<String> submit(@RequestParam long userId, @RequestParam String requestId) throws InterruptedException {
        // 模拟下单业务耗时 1 秒（双击场景下第二次请求必然在窗口期内到达）
        Thread.sleep(1000);
        RSet<String> orders = redissonClient.getSet("order:submitted");
        orders.add(userId + ":" + requestId);
        log.info("下单成功, userId={}, requestId={}", userId, requestId);
        return Result.ok("下单成功，流水号=" + requestId);
    }

    /**
     * 查看实际生成的订单数（验证重复请求没有产生重复订单）。
     */
    @GetMapping("/count")
    public Result<String> count() {
        RSet<String> orders = redissonClient.getSet("order:submitted");
        return Result.ok("实际订单数=" + orders.size() + "，订单列表=" + orders.readAll());
    }

    /**
     * 清空订单记录，便于重复演示。
     */
    @PostMapping("/reset")
    public Result<String> reset() {
        redissonClient.getSet("order:submitted").delete();
        return Result.ok("订单记录已清空");
    }
}
