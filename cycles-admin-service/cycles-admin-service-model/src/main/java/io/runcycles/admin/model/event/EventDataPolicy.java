package io.runcycles.admin.model.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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
public class EventDataPolicy {

    @JsonProperty("policy_id")
    private String policyId;

    @JsonProperty("name")
    private String name;

    @JsonProperty("scope_pattern")
    private String scopePattern;

    @JsonProperty("changed_fields")
    private List<String> changedFields;
}
