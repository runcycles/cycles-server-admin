package io.runcycles.admin.api;
import org.slf4j.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
@SpringBootApplication
@ComponentScan(basePackages = "io.runcycles.admin")
public class BudgetGovernanceApplication {
    private static final Logger LOG = LoggerFactory.getLogger(BudgetGovernanceApplication.class);
    public static void main(String[] args) {
        LOG.info("===========================================================");
        LOG.info("Complete Budget Governance System v0.1.24");
        LOG.info("ALL 17 Endpoints Implemented");
        LOG.info("===========================================================");
        SpringApplication.run(BudgetGovernanceApplication.class, args);
    }
}
