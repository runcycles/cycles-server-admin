package io.runcycles.admin.model.shared;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import java.util.List;
@Data @NoArgsConstructor @AllArgsConstructor
public class Action {
    @JsonProperty("kind") private String kind;
    @JsonProperty("name") private String name;
    @JsonProperty("tags") private List<String> tags;
}
