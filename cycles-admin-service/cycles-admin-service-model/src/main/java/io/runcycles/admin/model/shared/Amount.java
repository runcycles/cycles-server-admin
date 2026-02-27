package io.runcycles.admin.model.shared;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.*;
@Data @NoArgsConstructor @AllArgsConstructor
public class Amount {
    @NotNull @JsonProperty("unit") private UnitEnum unit;
    @NotNull @Min(0) @JsonProperty("amount") private Long amount;
}
