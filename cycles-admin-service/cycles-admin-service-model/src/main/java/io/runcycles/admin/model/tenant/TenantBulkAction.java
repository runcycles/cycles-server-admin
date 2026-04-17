package io.runcycles.admin.model.tenant;
/**
 * Lifecycle action applied by POST /v1/admin/tenants/bulk-action
 * (spec v0.1.25.21).
 *
 * <p>SUSPEND: ACTIVE → SUSPENDED. Idempotent on already-SUSPENDED.
 * Invalid from CLOSED (row → failed[] with INVALID_TRANSITION).
 * <p>REACTIVATE: SUSPENDED → ACTIVE. Idempotent on already-ACTIVE.
 * Invalid from CLOSED.
 * <p>CLOSE: any → CLOSED. Terminal; not reversible via this API.
 */
public enum TenantBulkAction { SUSPEND, REACTIVATE, CLOSE }
