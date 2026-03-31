package io.runcycles.admin.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

import static org.mockito.Mockito.*;

@DisplayName("BudgetGovernanceApplication")
class BudgetGovernanceApplicationTest {

    @Test
    void mainShouldBootApplication() {
        try (MockedStatic<SpringApplication> mocked = mockStatic(SpringApplication.class)) {
            mocked.when(() -> SpringApplication.run(eq(BudgetGovernanceApplication.class), any(String[].class)))
                    .thenReturn(null);

            BudgetGovernanceApplication.main(new String[]{});

            mocked.verify(() -> SpringApplication.run(eq(BudgetGovernanceApplication.class), any(String[].class)));
        }
    }
}
