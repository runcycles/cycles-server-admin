-- Each int64 is represented by two signed decimal limbs. All intermediate
-- limb sums are well inside the exact integer range of Redis Lua doubles.
local base = 1000000
local function limbs(value)
    local negative = value:sub(1, 1) == '-'
    local digits = negative and value:sub(2) or value
    local high = tonumber(digits:sub(1, -7)) or 0
    local low = tonumber(digits:sub(-6))
    if negative then return -high, -low end
    return high, low
end

local function add(left, right)
    local ah, al = limbs(left)
    local bh, bl = limbs(right)
    local low = al + bl
    local carry = math.floor(low / base)
    local high = ah + bh + carry
    low = low - carry * base
    if high < 0 and low > 0 then high, low = high + 1, low - base end
    if high == 0 then return string.format('%.0f', low) end
    return string.format('%.0f%06d', high, math.abs(low))
end

local function subtract(a, b)
    if b:sub(1, 1) == '-' then return add(a, b:sub(2)) end
    return add(a, '-' .. b)
end

local function less(a, b)
    return subtract(a, b):sub(1, 1) == '-'
end

local ledger = KEYS[1]
if redis.call('EXISTS', ledger) == 0 then return {'NOT_FOUND'} end
local owner = redis.call('HGET', ledger, 'tenant_id') or ''
if ARGV[4] ~= '' and owner ~= ARGV[4] then return {'FORBIDDEN'} end
local tenant = redis.call('GET', 'tenant:' .. owner)
if tenant and cjson.decode(tenant).status == 'CLOSED' then return {'TENANT_CLOSED'} end
local token, fingerprint = ARGV[5], ARGV[6]
local cache, legacy
if token ~= '' then
    cache = 'idempotency:fund:v3:' .. #owner .. ':' .. owner .. ':' .. #ledger .. ':' .. ledger .. ':' .. token
    legacy = 'idempotency:fund:v2:' .. token
    local previous = redis.call('HMGET', cache, 'hash', 'response')
    if previous[1] then
        if previous[1] ~= fingerprint then return {'IDEMPOTENCY_MISMATCH'} end
        return {'IDEMPOTENT_HIT', previous[2]}
    end
    -- A pre-upgrade envelope cannot prove which ledger was mutated.
    local old = redis.call('GET', legacy)
    local oldHash = redis.call('GET', legacy .. ':hash')
    -- The legacy value and hash share a namespace with user-supplied tokens.
    -- Preserve both until expiry, including a token ending in ':hash'.
    if (old and old ~= 'v3') or (oldHash and oldHash ~= 'v3') then
        return {'IDEMPOTENCY_MISMATCH'}
    end
end

local status = redis.call('HGET', ledger, 'status')
if status == 'FROZEN' then return {'BUDGET_FROZEN'} end
if status == 'CLOSED' then return {'BUDGET_CLOSED'} end
local function field(name) return redis.call('HGET', ledger, name) or '0' end
local allocated, remaining = field('allocated'), field('remaining')
local spent, reserved, debt = field('spent'), field('reserved'), field('debt')
local oldAllocated, oldRemaining, oldDebt, oldSpent = allocated, remaining, debt, spent
local operation, amount, now = ARGV[1], ARGV[2], ARGV[3]
if operation == 'CREDIT' then
    allocated, remaining = add(allocated, amount), add(remaining, amount)
elseif operation == 'DEBIT' then
    if less(remaining, amount) then return {'INSUFFICIENT_FUNDS'} end
    allocated, remaining = subtract(allocated, amount), subtract(remaining, amount)
elseif operation == 'RESET' or operation == 'RESET_SPENT' then
    allocated = amount
    if operation == 'RESET_SPENT' then
        spent = ARGV[7] ~= '' and ARGV[7] or '0'
        if less(spent, '0') then return {'INVALID_REQUEST', 'spent must be >= 0'} end
    end
    remaining = subtract(subtract(subtract(allocated, spent), reserved), debt)
elseif operation == 'REPAY_DEBT' then
    local paid = less(debt, amount) and debt or amount
    debt = subtract(debt, paid)
    allocated = add(allocated, subtract(amount, paid))
    remaining = add(remaining, amount)
else
    return {'INVALID_REQUEST', 'Unknown funding operation'}
end

if less('9223372036854775807', allocated) or less(remaining, '-9223372036854775808')
        or less('9223372036854775807', remaining) or less(allocated, '0') then
    return {'INVALID_REQUEST', 'Funding result exceeds the supported ledger range'}
end
local over = tostring(less(field('overdraft_limit'), debt))
local result = {oldAllocated, allocated, oldRemaining, remaining, oldDebt, debt, over, oldSpent, spent, now}
-- Publish the event and its lookup indexes in the ledger transaction. Each JSON
-- object below is serialized by Jackson. Preserve exact int64s as number tokens.
local function state(a, r, d, s)
    return '{"allocated":' .. a .. ',"remaining":' .. r .. ',"debt":' .. d .. ',"spent":' .. s .. '}'
end
local id, envelope, data, correlation, ttl = ARGV[8], ARGV[9], ARGV[10], ARGV[11], ARGV[12]
local payload = data:sub(1, -2) .. ',"previous_state":' .. state(oldAllocated, oldRemaining, oldDebt, oldSpent)
    .. ',"new_state":' .. state(allocated, remaining, debt, spent) .. '}'
local event = envelope:sub(1, -2) .. ',"tenant_id":' .. cjson.encode(owner) .. ',"data":' .. payload .. '}'
-- Redis does not roll back writes if a subsequent command errors. Check index
-- types first so a damaged index cannot leave a partially published mutation.
redis.call('ZSCORE', 'events:' .. owner, id)
redis.call('ZSCORE', 'events:_all', id)
redis.call('ZSCORE', 'funding:dispatch:due', id)
if correlation ~= '' then redis.call('SISMEMBER', 'events:correlation:' .. correlation, id) end
redis.call('SET', 'event:' .. id, event, 'EX', ttl)
redis.call('ZADD', 'events:' .. owner, now, id)
redis.call('ZADD', 'events:_all', now, id)
if correlation ~= '' then
    redis.call('SADD', 'events:correlation:' .. correlation, id)
    redis.call('EXPIRE', 'events:correlation:' .. correlation, ttl)
end
-- Foreground dispatch and crash recovery use the same stable delivery IDs.
redis.call('ZADD', 'funding:dispatch:due', now, id)
redis.call('HSET', ledger, 'allocated', allocated, 'remaining', remaining, 'debt', debt,
           'spent', spent, 'is_over_limit', over, 'updated_at', now)
if cache then
    redis.call('HSET', cache, 'hash', fingerprint, 'response', table.concat(result, '|'))
    redis.call('EXPIRE', cache, 86400)
    -- Prevent older nodes from repeating this write during a rolling upgrade.
    redis.call('SET', legacy, 'v3', 'EX', 86400)
    redis.call('SET', legacy .. ':hash', 'v3', 'EX', 86400)
end
table.insert(result, 1, 'OK')
return result
