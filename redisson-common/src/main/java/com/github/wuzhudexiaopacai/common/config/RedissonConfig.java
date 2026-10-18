package com.github.wuzhudexiaopacai.redisson.common.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Redisson 客户端配置。
 *
 * <p>演示环境使用单节点模式；生产可按需替换为集群（useClusterServers）、
 * 哨兵（useSentinelServers）等模式，业务代码无需任何改动。</p>
 *
 * @author wuzhudexiaopacai
 */
@Configuration
public class RedissonConfig {

    /**
     * 看门狗默认超时时间：30s。
     * 当业务持锁未显式指定 leaseTime 时，看门狗每隔 30s/3 = 10s 自动续期至 30s，
     * 直到业务主动释放锁或进程宕机（宕机后锁最多残留 30s 自动过期，避免死锁）。
     */
    private static final long LOCK_WATCHDOG_TIMEOUT_MS = 30_000L;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${redisson.address:redis://127.0.0.1:6379}") String address,
            @Value("${redisson.database:0}") int database,
            @Value("${redisson.password:}") String password) {
        Config config = new Config();
        config.setLockWatchdogTimeout(LOCK_WATCHDOG_TIMEOUT_MS);
        SingleServerConfig singleServerConfig = config.useSingleServer()
                .setAddress(address)
                .setDatabase(database);
        if (StringUtils.hasText(password)) {
            singleServerConfig.setPassword(password);
        }
        return Redisson.create(config);
    }
}
