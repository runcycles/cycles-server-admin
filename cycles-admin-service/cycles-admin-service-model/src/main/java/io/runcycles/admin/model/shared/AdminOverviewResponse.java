package io.runcycles.admin.model.shared;

import com.fasterxml.jackson.annotation.*;
import io.runcycles.admin.model.event.Event;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class AdminOverviewResponse {
    @JsonProperty("as_of") private Instant asOf;
    @JsonProperty("event_window_seconds") private int eventWindowSeconds;
    @JsonProperty("tenant_counts") private TenantCounts tenantCounts;
    @JsonProperty("budget_counts") private BudgetCounts budgetCounts;
    @JsonProperty("over_limit_scopes") private List<OverLimitScope> overLimitScopes;
    @JsonProperty("debt_scopes") private List<DebtScope> debtScopes;
    @JsonProperty("webhook_counts") private WebhookCounts webhookCounts;
    @JsonProperty("failing_webhooks") private List<FailingWebhook> failingWebhooks;
    @JsonProperty("event_counts") private EventCounts eventCounts;
    @JsonProperty("recent_denials") private List<Event> recentDenials;
    @JsonProperty("recent_expiries") private List<Event> recentExpiries;

    // v0.1.25.8 additions (all optional, NON_NULL)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("recent_denials_by_reason") private Map<String, Integer> recentDenialsByReason;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("quota_health") private QuotaHealth quotaHealth;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("access_control_stats") private AccessControlStats accessControlStats;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TenantCounts {
        private int total;
        private int active;
        private int suspended;
        private int closed;
        // v0.1.25.8: optional, populated by v0.1.26+ servers with observe_mode extension
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("in_observe_mode")
        private Integer inObserveMode;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QuotaHealth {
        @JsonProperty("counters_above_80pct") private Integer countersAbove80Pct;
        @JsonProperty("counters_at_limit") private Integer countersAtLimit;
        @JsonProperty("top_offenders") private List<QuotaOffender> topOffenders;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QuotaOffender {
        private String scope;
        @JsonProperty("action_kind") private String actionKind;
        private String window;
        private Long used;
        private Long limit;
        @JsonProperty("utilization_pct") private Double utilizationPct;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AccessControlStats {
        @JsonProperty("policies_with_allow_list") private Integer policiesWithAllowList;
        @JsonProperty("policies_with_deny_list") private Integer policiesWithDenyList;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BudgetCounts {
        private int total;
        private int active;
        private int frozen;
        private int closed;
        @JsonProperty("over_limit") private int overLimit;
        @JsonProperty("with_debt") private int withDebt;
        @JsonProperty("by_unit") private Map<String, Integer> byUnit;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OverLimitScope {
        private String scope;
        private UnitEnum unit;
        private long allocated;
        private long remaining;
        private long debt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DebtScope {
        private String scope;
        private UnitEnum unit;
        private long debt;
        @JsonProperty("overdraft_limit") private long overdraftLimit;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WebhookCounts {
        private int total;
        private int active;
        private int disabled;
        @JsonProperty("with_failures") private int withFailures;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FailingWebhook {
        @JsonProperty("subscription_id") private String subscriptionId;
        private String url;
        @JsonProperty("consecutive_failures") private int consecutiveFailures;
        @JsonProperty("last_failure_at") private Instant lastFailureAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class EventCounts {
        @JsonProperty("total_recent") private int totalRecent;
        @JsonProperty("by_category") private Map<String, Integer> byCategory;
    }
}
