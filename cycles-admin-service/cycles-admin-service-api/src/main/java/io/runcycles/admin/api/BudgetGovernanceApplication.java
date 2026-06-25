package io.runcycles.admin.api;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

@SpringBootApplication
@ComponentScan(basePackages = "io.runcycles.admin")
// v0.1.25.20: @EnableScheduling activates AuditRepository.sweepStaleIndexEntries
// (cron-driven index-pointer GC) and any future @Scheduled tasks.
@EnableScheduling
public class BudgetGovernanceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BudgetGovernanceApplication.class, args);
    }

    @Component
    static class StartupBanner implements CommandLineRunner {
        private static final Logger LOG = LoggerFactory.getLogger(BudgetGovernanceApplication.class);
        @Autowired(required = false) private BuildProperties buildProperties;

        @Override
        public void run(String... args) {
            String version = buildProperties != null ? buildProperties.getVersion() : "unknown";
            LOG.info("Cycles Admin service started: version={} scheduling_enabled=true api_prefix=/v1",
                version);
        }
    }
}
