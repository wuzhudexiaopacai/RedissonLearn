package com.github.wuzhudexiaopacai.redisson.inventory.controller;

import com.github.wuzhudexiaopacai.redisson.common.result.Result;
import com.github.wuzhudexiaopacai.redisson.inventory.service.ProductService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 缓存击穿演示接口。
 *
 * <p>演示步骤：</p>
 * <ol>
 *   <li>DELETE /product/cache/1001 删除缓存，模拟热点 key 过期；</li>
 *   <li>GET /product/stress/1001?count=50 并发查询；</li>
 *   <li>观察返回的【数据库回源次数】——加锁 + 双重检查后只有 1 次回源。</li>
 * </ol>
 *
 * @author wuzhudexiaopacai
 */
@RestController
@RequestMapping("/product")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/{productId}")
    public Result<String> get(@PathVariable long productId) {
        return Result.ok(productService.getProduct(productId));
    }

    @DeleteMapping("/cache/{productId}")
    public Result<String> evict(@PathVariable long productId) {
        productService.evictCache(productId);
        productService.resetDbHitCount();
        return Result.ok("缓存已删除（模拟热点 key 过期），DB 回源计数已重置");
    }

    /**
     * 并发压测商品查询：缓存为空时并发请求，验证只有 1 次 DB 回源。
     */
    @GetMapping("/stress/{productId}")
    public Result<Map<String, Object>> stress(@PathVariable long productId,
                                              @RequestParam(defaultValue = "50") int count) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(count);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                executor.submit(() -> {
                    try {
                        productService.getProduct(productId);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        }
        Map<String, Object> report = new HashMap<>();
        report.put("并发请求数", count);
        report.put("数据库回源次数", productService.getDbHitCount());
        report.put("结论", productService.getDbHitCount() <= 1
                ? "锁 + 双重检查生效：仅 1 次回源，数据库被保护"
                : "回源次数过多，请检查是否重复压测未先清缓存");
        return Result.ok(report);
    }
}
