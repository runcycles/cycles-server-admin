package io.runcycles.admin.model.shared;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.*;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class Amount {
    @NotNull @JsonProperty("unit") private UnitEnum unit;
    @NotNull @Min(0) @JsonProperty("amount") private Long amount;
}
