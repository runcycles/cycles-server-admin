package io.runcycles.admin.model.shared;
import com.fasterxml.jackson.annotation.*;
import lombok.*;
import java.util.Map;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ErrorResponse {
    @JsonProperty("error") private ErrorCode error;
    @JsonProperty("message") private String message;
    @JsonProperty("request_id") private String requestId;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("details") private Map<String, Object> details;
}
