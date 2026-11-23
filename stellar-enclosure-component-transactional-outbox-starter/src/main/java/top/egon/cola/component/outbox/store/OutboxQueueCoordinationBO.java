package top.egon.cola.component.outbox.store;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
public class OutboxQueueCoordinationBO {

    private Instant databaseTime;
    @Builder.Default
    private List<Member> members = List.of();
    @Builder.Default
    private Map<String, OutboxQueueLease> leases = Map.of();

    public record Member(String instanceId, Set<String> channels, Instant expiresAt) {
        public Member {
            channels = Set.copyOf(channels);
        }
    }
}
