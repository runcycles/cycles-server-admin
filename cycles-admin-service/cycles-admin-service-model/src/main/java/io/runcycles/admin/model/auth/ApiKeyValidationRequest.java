package io.runcycles.admin.model.auth;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
@Data @NoArgsConstructor @AllArgsConstructor
public class ApiKeyValidationRequest {
    @NotBlank @JsonProperty("key_secret") private String keySecret;
}
