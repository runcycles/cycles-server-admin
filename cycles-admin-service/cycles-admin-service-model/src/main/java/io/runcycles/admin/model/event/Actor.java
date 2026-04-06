package io.runcycles.admin.model.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
public class Actor {

    @JsonProperty("type")
    private ActorType type;

    @JsonProperty("key_id")
    private String keyId;

    @JsonProperty("source_ip")
    private String sourceIp;
}
