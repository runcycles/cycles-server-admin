package io.runcycles.admin.api;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.boot.SpringApplication;

import static org.mockito.Mockito.mockStatic;

class BudgetGovernanceApplicationTest {

    @Test
    void main_startsSpringApplication() {
        try (MockedStatic<SpringApplication> mocked = mockStatic(SpringApplication.class)) {
            mocked.when(() -> SpringApplication.run(BudgetGovernanceApplication.class, new String[]{}))
                    .thenReturn(null);

            BudgetGovernanceApplication.main(new String[]{});

            mocked.verify(() -> SpringApplication.run(BudgetGovernanceApplication.class, new String[]{}));
        }
    }
}
