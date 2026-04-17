package io.runcycles.admin.model.webhook;
/**
 * Lifecycle action applied by POST /v1/admin/webhooks/bulk-action
 * (spec v0.1.25.21).
 *
 * <p>PAUSE: ACTIVE → PAUSED. Idempotent on already-PAUSED or DISABLED
 * (row → skipped[] with ALREADY_IN_TARGET_STATE).
 * <p>RESUME: PAUSED → ACTIVE. Idempotent on already-ACTIVE. DISABLED
 * cannot be resumed (row → failed[] with INVALID_TRANSITION).
 * <p>DELETE: removes permanently. Rows already missing → skipped[] with
 * ALREADY_DELETED.
 */
public enum WebhookBulkAction { PAUSE, RESUME, DELETE }
