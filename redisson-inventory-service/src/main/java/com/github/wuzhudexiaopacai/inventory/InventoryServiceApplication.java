package com.github.wuzhudexiaopacai.redisson.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 库存服务：演示扣库存防超卖、缓存击穿、公平锁、读写锁、定时任务防重复、红锁。
 *
 * @author wuzhudexiaopacai
 */
@EnableScheduling
@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = "com.github.wuzhudexiaopacai.redisson")
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
