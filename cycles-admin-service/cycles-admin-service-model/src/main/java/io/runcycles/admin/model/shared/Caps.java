package io.runcycles.admin.model.shared;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.runcycles.admin.model.auth.LenientStringListDeserializer;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class Caps {
    @Min(0) @JsonProperty("max_tokens") private Integer maxTokens;
    @Min(0) @JsonProperty("max_steps_remaining") private Integer maxStepsRemaining;
    // Lenient deserialization: legacy policy records written by the
    // pre-v0.1.25.17 UPDATE_POLICY_LUA path may carry {} in these positions
    // because of the Redis cjson empty-array bug (empty Lua table -> "{}"
    // instead of "[]"). The lenient deserializer accepts both shapes so
    // policy list() and get() don't silently drop those records. See
    // LenientStringListDeserializer.
    @JsonProperty("tool_allowlist")
    @JsonDeserialize(using = LenientStringListDeserializer.class)
    private List<@Size(max = 256) String> toolAllowlist;
    @JsonProperty("tool_denylist")
    @JsonDeserialize(using = LenientStringListDeserializer.class)
    private List<@Size(max = 256) String> toolDenylist;
    @Min(0) @JsonProperty("cooldown_ms") private Integer cooldownMs;
}
