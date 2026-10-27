package [=eventExecutorPackage];

import [=eventPackage].[=eventType];
import [=eventConverterPackage].[=eventConverterType];
import [=commandExecutorPackage].[=eventCommandExecutorType];
import [=idempotencyType];
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service("[=eventExecutorType?uncap_first]")
@Validated
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class [=eventExecutorType] {

    @Qualifier("[=idempotencyBean]")
    private final [=idempotencyType?split(".")?last] idempotency;

    @Qualifier("[=eventCommandExecutorType?uncap_first]")
    private final [=eventCommandExecutorType] commandExecutor;

    @Qualifier("[=eventConverterType?uncap_first]Impl")
    private final [=eventConverterType] converter;

    @Transactional
    public void execute(@Valid @NotNull [=eventType] event) {
        if (!"[=eventSchemaVersion]".equals(event.getSchemaVersion())) {
            throw new IllegalArgumentException("unsupported event schema version");
        }
        idempotency.[=idempotencyMethod]("[=eventType]", event.getEventId(), event,
                () -> commandExecutor.execute(converter.toTarget(event)));
    }
}
