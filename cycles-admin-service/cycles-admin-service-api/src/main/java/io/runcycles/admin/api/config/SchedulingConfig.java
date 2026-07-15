package io.runcycles.admin.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Scheduler capacity reserved for reconciliation and its lease heartbeat. */
@Configuration(proxyBeanMethods = false)
public class SchedulingConfig {
    static final int MINIMUM_POOL_SIZE = 2;

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler(
            @Value("${spring.task.scheduling.pool.size:2}") int configuredPoolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(MINIMUM_POOL_SIZE, configuredPoolSize));
        scheduler.setThreadNamePrefix("cycles-admin-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
