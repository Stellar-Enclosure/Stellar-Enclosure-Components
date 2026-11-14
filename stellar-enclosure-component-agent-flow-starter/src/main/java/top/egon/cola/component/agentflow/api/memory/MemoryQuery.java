package top.egon.cola.component.agentflow.api.memory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor(onConstructor_ = @JsonCreator(mode = JsonCreator.Mode.DISABLED))
@Accessors(chain = true)
@Builder
public class MemoryQuery {
    @Size(max = 8192)
    private String text;

    @Min(1) @Max(50)
    private int limit;

    @NotNull @Size(max = 3)
    private Set<@NotNull MemoryFactTypeEnum> types;

    // Accept the wire array before converting it to the Java API's set.
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public static MemoryQuery fromWire(@JsonProperty("text") String text, @JsonProperty(value = "limit", required = true) int limit,
                                       @JsonProperty(value = "types", required = true) List<MemoryFactTypeEnum> selected) {
        if (selected == null || selected.size() > 3 || selected.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Invalid Memory query types");
        }
        Set<MemoryFactTypeEnum> distinct = Set.copyOf(selected);
        if (distinct.size() != selected.size()) {
            throw new IllegalArgumentException("Duplicate Memory query types");
        }
        return new MemoryQuery(text, limit, distinct);
    }

    public MemoryQuery snapshot() {
        String query = text;
        if (query != null && (query.length() > 8192 || query.indexOf(0) >= 0
                || !StandardCharsets.UTF_8.newEncoder().canEncode(query))) {
            throw new IllegalArgumentException("Invalid Memory query text");
        }
        Set<MemoryFactTypeEnum> selected = types;
        if (selected == null || selected.size() > 3 || selected.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Invalid Memory query types");
        }
        return new MemoryQuery(query, limit, Set.copyOf(selected));
    }
}
