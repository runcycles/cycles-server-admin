package io.runcycles.admin.model.shared;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import java.util.Map;
@Data @NoArgsConstructor @AllArgsConstructor
public class Subject {
    @JsonProperty("tenant") private String tenant;
    @JsonProperty("workspace") private String workspace;
    @JsonProperty("app") private String app;
    @JsonProperty("workflow") private String workflow;
    @JsonProperty("agent") private String agent;
    @JsonProperty("tool_group") private String toolGroup;
    @JsonProperty("dimensions") private Map<String, String> dimensions;
}
