package io.runcycles.admin.model.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;
import java.util.Map;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class AuthIntrospectResponse {
    @JsonProperty("authenticated") private boolean authenticated;
    @JsonProperty("auth_type") private String authType;
    @JsonProperty("permissions") private List<String> permissions;
    @JsonProperty("capabilities") private Map<String, Boolean> capabilities;
}
