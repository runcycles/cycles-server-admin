package io.runcycles.admin.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigTest {

    @Test
    void taskScheduler_hasCapacityForFundingDispatchAndTenantLeaseRenewal() {
        ThreadPoolTaskScheduler scheduler = new SchedulingConfig().taskScheduler(1);
        scheduler.initialize();
        try {
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(3);
            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("cycles-admin-scheduler-");
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void shutdownCancelsWorkScheduledForLater() {
        ThreadPoolTaskScheduler scheduler = new SchedulingConfig().taskScheduler(3);
        scheduler.initialize();
        var pending = scheduler.schedule(() -> {}, java.time.Instant.now().plusSeconds(3600));
        scheduler.shutdown();
        assertThat(pending.isCancelled()).isTrue();
    }

    @Test
    void taskScheduler_honorsLargerConfiguredPool() {
        ThreadPoolTaskScheduler scheduler = new SchedulingConfig().taskScheduler(4);
        scheduler.initialize();
        try {
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(4);
        } finally {
            scheduler.shutdown();
        }
    }
}
