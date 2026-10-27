package [=eventExecutorPackage];

import [=eventPackage].[=eventType];
[#if eventAction == "delegate"]
import [=handlerType];
[#else]
import [=eventConverterPackage].[=eventConverterType];
import [=commandExecutorPackage].[=eventCommandExecutorType];
[/#if]
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

[#if eventAction == "delegate"]
    @Qualifier("[=handlerBean]")
    private final [=handlerType?split(".")?last] handler;
[#else]
    @Qualifier("[=eventCommandExecutorType?uncap_first]")
    private final [=eventCommandExecutorType] commandExecutor;

    @Qualifier("[=eventConverterType?uncap_first]Impl")
    private final [=eventConverterType] converter;
[/#if]

    @Transactional
    public void execute(@Valid @NotNull [=eventType] event) {
        if (!"[=eventSchemaVersion]".equals(event.getSchemaVersion())) {
            throw new IllegalArgumentException("unsupported event schema version");
        }
        idempotency.[=idempotencyMethod]("[=eventType]", event.getEventId(), event,
[#if eventAction == "delegate"]
                () -> handler.[=handlerMethod](event));
[#else]
                () -> commandExecutor.execute(converter.toTarget(event)));
[/#if]
    }
}
