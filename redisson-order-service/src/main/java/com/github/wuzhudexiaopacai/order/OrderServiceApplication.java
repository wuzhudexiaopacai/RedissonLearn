package com.github.wuzhudexiaopacai.redisson.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 订单服务：演示接口幂等、锁异步化、联锁 MultiLock、Feign 跨服务调用下的分布式锁。
 *
 * @author wuzhudexiaopacai
 */
@EnableFeignClients
@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = "com.github.wuzhudexiaopacai.redisson")
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
