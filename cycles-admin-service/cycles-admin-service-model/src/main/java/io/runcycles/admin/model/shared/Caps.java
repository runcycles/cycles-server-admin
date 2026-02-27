package io.runcycles.admin.model.shared;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class Caps {
    @JsonProperty("max_tokens") private Long maxTokens;
    @JsonProperty("max_steps_remaining") private Long maxStepsRemaining;
    @JsonProperty("tool_allowlist") private List<String> toolAllowlist;
    @JsonProperty("tool_denylist") private List<String> toolDenylist;
    @JsonProperty("cooldown_ms") private Long cooldownMs;
}
