package io.runcycles.admin.data.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.admin.data.exception.GovernanceException;
import io.runcycles.admin.data.repository.*;
import io.runcycles.admin.data.service.KeyService;
import io.runcycles.admin.model.audit.AuditLogEntry;
import io.runcycles.admin.model.auth.*;
import io.runcycles.admin.model.budget.*;
import io.runcycles.admin.model.policy.*;
import io.runcycles.admin.model.shared.Amount;
import io.runcycles.admin.model.shared.UnitEnum;
import io.runcycles.admin.model.tenant.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static JedisPool jedisPool;
    private static ObjectMapper objectMapper;
    private static KeyService keyService;

    private static TenantRepository tenantRepository;
    private static ApiKeyRepository apiKeyRepository;
    private static BudgetRepository budgetRepository;
    private static PolicyRepository policyRepository;
    private static AuditRepository auditRepository;

    @BeforeAll
    static void setupAll() throws Exception {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(10);
        jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379));

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        keyService = new KeyService();

        tenantRepository = new TenantRepository();
        injectField(tenantRepository, "jedisPool", jedisPool);
        injectField(tenantRepository, "objectMapper", objectMapper);

        apiKeyRepository = new ApiKeyRepository();
        injectField(apiKeyRepository, "jedisPool", jedisPool);
        injectField(apiKeyRepository, "objectMapper", objectMapper);
        injectField(apiKeyRepository, "keyService", keyService);

        budgetRepository = new BudgetRepository();
        injectField(budgetRepository, "jedisPool", jedisPool);
        injectField(budgetRepository, "objectMapper", objectMapper);

        policyRepository = new PolicyRepository();
        injectField(policyRepository, "jedisPool", jedisPool);
        injectField(policyRepository, "objectMapper", objectMapper);

        auditRepository = new AuditRepository();
        injectField(auditRepository, "jedisPool", jedisPool);
        injectField(auditRepository, "objectMapper", objectMapper);
    }

    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @AfterAll
    static void tearDownAll() {
        if (jedisPool != null) jedisPool.close();
    }

    // --- Tenant lifecycle ---

    @Test
    @Order(1)
    void tenant_create_succeeds() {
        TenantCreateRequest request = new TenantCreateRequest();
        request.setTenantId("integ-tenant");
        request.setName("Integration Tenant");

        var result = tenantRepository.create(request);

        assertThat(result.created()).isTrue();
        assertThat(result.tenant().getTenantId()).isEqualTo("integ-tenant");
        assertThat(result.tenant().getStatus()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    @Order(2)
    void tenant_create_idempotent_sameName() {
        TenantCreateRequest request = new TenantCreateRequest();
        request.setTenantId("integ-tenant");
        request.setName("Integration Tenant");

        var result = tenantRepository.create(request);

        assertThat(result.created()).isFalse();
        assertThat(result.tenant().getName()).isEqualTo("Integration Tenant");
    }

    @Test
    @Order(3)
    void tenant_create_conflict_differentName_throws409() {
        TenantCreateRequest request = new TenantCreateRequest();
        request.setTenantId("integ-tenant");
        request.setName("Different Name");

        assertThatThrownBy(() -> tenantRepository.create(request))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ge = (GovernanceException) e;
                    assertThat(ge.getHttpStatus()).isEqualTo(409);
                });
    }

    @Test
    @Order(4)
    void tenant_get_succeeds() {
        Tenant tenant = tenantRepository.get("integ-tenant");
        assertThat(tenant.getName()).isEqualTo("Integration Tenant");
    }

    @Test
    @Order(5)
    void tenant_get_notFound_throws() {
        assertThatThrownBy(() -> tenantRepository.get("nonexistent"))
                .isInstanceOf(GovernanceException.class);
    }

    @Test
    @Order(6)
    void tenant_list_returnsTenants() {
        List<Tenant> tenants = tenantRepository.list(null, null, null, 50);
        assertThat(tenants).isNotEmpty();
        assertThat(tenants.stream().anyMatch(t -> "integ-tenant".equals(t.getTenantId()))).isTrue();
    }

    @Test
    @Order(7)
    void tenant_update_name() {
        TenantUpdateRequest request = new TenantUpdateRequest();
        request.setName("Updated Name");

        Tenant updated = tenantRepository.update("integ-tenant", request);

        assertThat(updated.getName()).isEqualTo("Updated Name");
    }

    @Test
    @Order(8)
    void tenant_update_suspend() {
        TenantUpdateRequest request = new TenantUpdateRequest();
        request.setStatus(TenantStatus.SUSPENDED);

        Tenant updated = tenantRepository.update("integ-tenant", request);

        assertThat(updated.getStatus()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(updated.getSuspendedAt()).isNotNull();
    }

    @Test
    @Order(9)
    void tenant_update_reactivate() {
        TenantUpdateRequest request = new TenantUpdateRequest();
        request.setStatus(TenantStatus.ACTIVE);

        Tenant updated = tenantRepository.update("integ-tenant", request);

        assertThat(updated.getStatus()).isEqualTo(TenantStatus.ACTIVE);
    }

    // --- API Key lifecycle ---

    private static String createdKeySecret;

    @Test
    @Order(10)
    void apiKey_create_succeeds() {
        ApiKeyCreateRequest request = new ApiKeyCreateRequest();
        request.setTenantId("integ-tenant");
        request.setName("Integration Key");

        ApiKeyCreateResponse response = apiKeyRepository.create(request);

        assertThat(response.getKeyId()).isNotNull();
        assertThat(response.getKeySecret()).isNotNull();
        assertThat(response.getTenantId()).isEqualTo("integ-tenant");
        createdKeySecret = response.getKeySecret();
    }

    @Test
    @Order(11)
    void apiKey_validate_succeeds() {
        ApiKeyValidationResponse response = apiKeyRepository.validate(createdKeySecret);

        assertThat(response.getValid()).isTrue();
        assertThat(response.getTenantId()).isEqualTo("integ-tenant");
    }

    @Test
    @Order(12)
    void apiKey_validate_wrongSecret_fails() {
        ApiKeyValidationResponse response = apiKeyRepository.validate("gov_definitely_wrong_key_12345");

        assertThat(response.getValid()).isFalse();
    }

    @Test
    @Order(13)
    void apiKey_list_returnsKeys() {
        List<ApiKey> keys = apiKeyRepository.list("integ-tenant");
        assertThat(keys).isNotEmpty();
    }

    @Test
    @Order(14)
    void apiKey_revoke_succeeds() {
        List<ApiKey> keys = apiKeyRepository.list("integ-tenant");
        String keyId = keys.get(0).getKeyId();

        ApiKey revoked = apiKeyRepository.revoke(keyId, "test revocation");

        assertThat(revoked.getStatus()).isEqualTo(ApiKeyStatus.REVOKED);
        assertThat(revoked.getRevokedReason()).isEqualTo("test revocation");
    }

    @Test
    @Order(15)
    void apiKey_validate_afterRevoke_fails() {
        ApiKeyValidationResponse response = apiKeyRepository.validate(createdKeySecret);
        assertThat(response.getValid()).isFalse();
        assertThat(response.getReason()).isEqualTo("KEY_REVOKED");
    }

    // --- Budget lifecycle ---

    @Test
    @Order(20)
    void budget_create_succeeds() {
        BudgetCreateRequest request = new BudgetCreateRequest();
        request.setScope("integ/scope1");
        request.setUnit(UnitEnum.USD_MICROCENTS);
        request.setAllocated(new Amount(UnitEnum.USD_MICROCENTS, 1000000L));

        BudgetLedger ledger = budgetRepository.create("integ-tenant", request);

        assertThat(ledger.getScope()).isEqualTo("integ/scope1");
        assertThat(ledger.getAllocated().getAmount()).isEqualTo(1000000L);
        assertThat(ledger.getRemaining().getAmount()).isEqualTo(1000000L);
    }

    @Test
    @Order(21)
    void budget_create_duplicate_throws() {
        BudgetCreateRequest request = new BudgetCreateRequest();
        request.setScope("integ/scope1");
        request.setUnit(UnitEnum.USD_MICROCENTS);
        request.setAllocated(new Amount(UnitEnum.USD_MICROCENTS, 500L));

        assertThatThrownBy(() -> budgetRepository.create("integ-tenant", request))
                .isInstanceOf(GovernanceException.class);
    }

    @Test
    @Order(22)
    void budget_fund_credit_succeeds() {
        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.CREDIT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 500000L));

        BudgetFundingResponse response = budgetRepository.fund("integ-tenant", "integ/scope1", UnitEnum.USD_MICROCENTS, request);

        assertThat(response.getOperation()).isEqualTo(FundingOperation.CREDIT);
        assertThat(response.getNewAllocated().getAmount()).isEqualTo(1500000L);
        assertThat(response.getNewRemaining().getAmount()).isEqualTo(1500000L);
    }

    @Test
    @Order(23)
    void budget_fund_debit_succeeds() {
        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.DEBIT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 200000L));

        BudgetFundingResponse response = budgetRepository.fund("integ-tenant", "integ/scope1", UnitEnum.USD_MICROCENTS, request);

        assertThat(response.getNewAllocated().getAmount()).isEqualTo(1300000L);
        assertThat(response.getNewRemaining().getAmount()).isEqualTo(1300000L);
    }

    @Test
    @Order(24)
    void budget_fund_debit_insufficientFunds_throws() {
        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.DEBIT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 99999999L));

        assertThatThrownBy(() -> budgetRepository.fund("integ-tenant", "integ/scope1", UnitEnum.USD_MICROCENTS, request))
                .isInstanceOf(GovernanceException.class);
    }

    @Test
    @Order(25)
    void budget_list_returnsBudgets() {
        List<BudgetLedger> ledgers = budgetRepository.list("integ-tenant");
        assertThat(ledgers).isNotEmpty();
        assertThat(ledgers.get(0).getScope()).isEqualTo("integ/scope1");
    }

    @Test
    @Order(26)
    void budget_fund_withIdempotencyKey_returnsCached() {
        BudgetFundingRequest request = new BudgetFundingRequest();
        request.setOperation(FundingOperation.CREDIT);
        request.setAmount(new Amount(UnitEnum.USD_MICROCENTS, 100L));
        request.setIdempotencyKey("integ-idem-1");

        BudgetFundingResponse first = budgetRepository.fund("integ-tenant", "integ/scope1", UnitEnum.USD_MICROCENTS, request);
        BudgetFundingResponse second = budgetRepository.fund("integ-tenant", "integ/scope1", UnitEnum.USD_MICROCENTS, request);

        assertThat(second.getNewAllocated().getAmount()).isEqualTo(first.getNewAllocated().getAmount());
    }

    // --- Policy lifecycle ---

    @Test
    @Order(30)
    void policy_create_succeeds() {
        PolicyCreateRequest request = new PolicyCreateRequest();
        request.setName("Integration Policy");
        request.setScopePattern("integ/*");

        Policy policy = policyRepository.create("integ-tenant", request);

        assertThat(policy.getPolicyId()).startsWith("pol_");
        assertThat(policy.getName()).isEqualTo("Integration Policy");
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    @Order(31)
    void policy_list_returnsPolicies() {
        List<Policy> policies = policyRepository.list("integ-tenant", null, null, null, 50);
        assertThat(policies).isNotEmpty();
    }

    @Test
    @Order(32)
    void policy_list_filtersByScopePattern() {
        List<Policy> policies = policyRepository.list("integ-tenant", "integ/*", null, null, 50);
        assertThat(policies).isNotEmpty();

        List<Policy> noMatch = policyRepository.list("integ-tenant", "nonexistent/*", null, null, 50);
        assertThat(noMatch).isEmpty();
    }

    // --- Audit lifecycle ---

    @Test
    @Order(40)
    void audit_log_succeeds() {
        AuditLogEntry entry = AuditLogEntry.builder()
                .tenantId("integ-tenant")
                .operation("createTenant")
                .status(201)
                .build();

        auditRepository.log(entry);

        assertThat(entry.getLogId()).startsWith("log_");
        assertThat(entry.getTimestamp()).isNotNull();
    }

    @Test
    @Order(41)
    void audit_list_returnsLogs() {
        List<AuditLogEntry> logs = auditRepository.list("integ-tenant", 50);
        assertThat(logs).isNotEmpty();
    }

    @Test
    @Order(42)
    void audit_list_filtersByOperation() {
        // Add a second log with different operation
        AuditLogEntry entry = AuditLogEntry.builder()
                .tenantId("integ-tenant")
                .operation("fundBudget")
                .status(200)
                .build();
        auditRepository.log(entry);

        List<AuditLogEntry> logs = auditRepository.list("integ-tenant", null, "createTenant", null, null, null, null, 50);
        assertThat(logs).allSatisfy(l -> assertThat(l.getOperation()).isEqualTo("createTenant"));
    }
}
