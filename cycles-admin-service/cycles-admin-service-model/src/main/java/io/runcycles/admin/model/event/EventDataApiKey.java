package io.runcycles.admin.model.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.runcycles.admin.model.auth.ApiKeyStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventDataApiKey {

    @JsonProperty("key_id")
    private String keyId;

    @JsonProperty("key_name")
    private String keyName;

    @JsonProperty("previous_status")
    private ApiKeyStatus previousStatus;

    @JsonProperty("new_status")
    private ApiKeyStatus newStatus;

    @JsonProperty("permissions")
    private List<String> permissions;

    @JsonProperty("failure_reason")
    private String failureReason;

    @JsonProperty("source_ip")
    private String sourceIp;
}
