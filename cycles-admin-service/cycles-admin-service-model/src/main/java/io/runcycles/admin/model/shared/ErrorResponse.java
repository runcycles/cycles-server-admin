package io.runcycles.admin.model.shared;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import java.util.Map;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ErrorResponse {
    @JsonProperty("error") private ErrorCode error;
    @JsonProperty("message") private String message;
    @JsonProperty("request_id") private String requestId;
    @JsonProperty("details") private Map<String, Object> details;
}
