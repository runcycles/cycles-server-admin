package io.runcycles.admin.model.budget;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.*;
import java.util.Map;
@Data @NoArgsConstructor @AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class BudgetStatusTransitionRequest {
    @Size(max = 512) @JsonProperty("reason") private String reason;
    @JsonProperty("metadata") private Map<String, Object> metadata;
}
