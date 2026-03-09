package io.runcycles.admin.model.shared;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.Map;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class ErrorResponse {
    @NotNull @JsonProperty("error") private ErrorCode error;
    @NotNull @JsonProperty("message") private String message;
    @NotNull @JsonProperty("request_id") private String requestId;
    @JsonProperty("details") private Map<String, Object> details;
}
