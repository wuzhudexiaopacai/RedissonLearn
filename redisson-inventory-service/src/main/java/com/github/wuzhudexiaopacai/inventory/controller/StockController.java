package com.github.wuzhudexiaopacai.redisson.inventory.controller;

import com.github.wuzhudexiaopacai.redisson.common.result.Result;
import com.github.wuzhudexiaopacai.redisson.inventory.service.StockService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 库存扣减演示接口。
 *
 * <p>重点演示：</p>
 * <ul>
 *   <li>/stock/deduct —— 可重入锁 + 看门狗，并发安全</li>
 *   <li>/stock/deduct-unsafe —— 不加锁对照组，并发必超卖</li>
 *   <li>/stock/deduct-short-lease —— 固定短租约反例，锁提前失效照样超卖</li>
 *   <li>/stock/stress —— 基于 JDK21 虚拟线程的一键并发压测</li>
 * </ul>
 *
 * @author wuzhudexiaopacai
 */
@RestController
@RequestMapping("/stock")
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    /**
     * 初始化库存，例如：POST /stock/init?productId=1001&stock=100
     */
    @PostMapping("/init")
    public Result<String> init(@RequestParam long productId, @RequestParam int stock) {
        stockService.initStock(productId, stock);
        return Result.ok("初始化成功，productId=" + productId + ", stock=" + stock);
    }

    /**
     * 查询库存：GET /stock/query?productId=1001
     */
    @GetMapping("/query")
    public Result<String> query(@RequestParam long productId) {
        return Result.ok("当前库存：" + stockService.queryStock(productId));
    }

    /**
     * 安全扣减：可重入锁 + 看门狗自动续期。
     */
    @PostMapping("/deduct")
    public Result<String> deduct(@RequestParam long productId) {
        return Result.ok(stockService.deduct(productId));
    }

    /**
     * 对照组：不加锁扣减。
     */
    @PostMapping("/deduct-unsafe")
    public Result<String> deductUnsafe(@RequestParam long productId) {
        return Result.ok(stockService.deductUnsafe(productId));
    }

    /**
     * 反例：固定短租约（1s），慢业务（1.5s）下锁提前失效。
     */
    @PostMapping("/deduct-short-lease")
    public Result<String> deductShortLease(@RequestParam long productId) {
        return Result.ok(stockService.deductWithShortLease(productId));
    }

    /**
     * 一键并发压测：GET /stock/stress?productId=1001&count=200&mode=safe
     *
     * @param mode safe（加锁）/ unsafe（不加锁）/ short-lease（短租约反例）
     */
    @GetMapping("/stress")
    public Result<Map<String, Object>> stress(@RequestParam long productId,
                                              @RequestParam(defaultValue = "200") int count,
                                              @RequestParam(defaultValue = "safe") String mode) throws InterruptedException {
        AtomicInteger success = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(count);

        // JDK21 虚拟线程：轻量并发，单机轻松发起成百上千并发请求
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                executor.submit(() -> {
                    try {
                        String result = switch (mode) {
                            case "unsafe" -> stockService.deductUnsafe(productId);
                            case "short-lease" -> stockService.deductWithShortLease(productId);
                            default -> stockService.deduct(productId);
                        };
                        if (result.startsWith("扣减成功")) {
                            success.incrementAndGet();
                        } else {
                            soldOut.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        }

        Map<String, Object> report = new HashMap<>();
        report.put("mode", mode);
        report.put("并发请求数", count);
        report.put("扣减成功数", success.get());
        report.put("库存不足数", soldOut.get());
        report.put("压测后剩余库存", stockService.queryStock(productId));
        report.put("是否超卖", stockService.queryStock(productId) < 0 ? "是（出现超卖！）" : "否");
        return Result.ok(report);
    }
}
