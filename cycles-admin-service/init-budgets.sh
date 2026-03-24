#!/bin/bash
# Initialize sample budgets for Budget Governance v0.1.24
# ✅ FIXED: Using HMSET to store as Redis HASH (compatible with Lua scripts)

CLI="redis-cli"
echo "Initializing budgets for Budget Governance v0.1.24..."

# Create tenant budget using HMSET (Redis HASH format)
$CLI HMSET "budget:tenant:demo-corp:TOKENS" \
    ledger_id "demo-ledger-001" \
    scope "tenant:demo-corp" \
    unit "TOKENS" \
    allocated 1000000 \
    remaining 1000000 \
    reserved 0 \
    spent 0 \
    debt 0 \
    overdraft_limit 100000 \
    is_over_limit false \
    status "ACTIVE" \
    created_at $(date +%s)000

echo "✅ Sample budget created as HASH: budget:tenant:demo-corp:TOKENS"
echo ""
echo "Verification:"
$CLI TYPE "budget:tenant:demo-corp:TOKENS"
echo ""
$CLI HGETALL "budget:tenant:demo-corp:TOKENS"
