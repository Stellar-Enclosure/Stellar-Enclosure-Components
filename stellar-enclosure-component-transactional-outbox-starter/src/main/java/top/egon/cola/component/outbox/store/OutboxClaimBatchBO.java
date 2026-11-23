package top.egon.cola.component.outbox.store;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
public class OutboxClaimBatchBO {

    private Instant databaseTime;
    @Builder.Default
    private List<OutboxRecord> records = List.of();
    private int exhaustedCount;
    private boolean notOwner;
}
