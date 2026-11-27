package io.runcycles.admin.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Scheduler capacity for funding dispatch, tenant reconciliation and lease renewal. */
@Configuration(proxyBeanMethods = false)
public class SchedulingConfig {
    static final int MINIMUM_POOL_SIZE = 3;

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler(
            @Value("${spring.task.scheduling.pool.size:3}") int configuredPoolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(MINIMUM_POOL_SIZE, configuredPoolSize));
        scheduler.setThreadNamePrefix("cycles-admin-scheduler-");
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        // Let Spring stop active work before destroying the scheduler and its Redis dependencies.
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
