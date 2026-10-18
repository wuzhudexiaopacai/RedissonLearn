package com.github.wuzhudexiaopacai.redisson.eureka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Eureka 注册中心。
 *
 * <p>注意：注册中心仅用于展示微服务架构与跨实例调用，Redisson 分布式锁本身
 * 只依赖 Redis，即使 Eureka 宕机，锁的正确性也不受影响。</p>
 *
 * @author wuzhudexiaopacai
 */
@EnableEurekaServer
@SpringBootApplication
public class EurekaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}
