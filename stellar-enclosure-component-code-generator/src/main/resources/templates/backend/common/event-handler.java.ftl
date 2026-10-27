package [=eventHandlerPackage];

import [=eventPackage].[=eventType];
import [=eventExecutorPackage].[=eventExecutorType];
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component("[=eventHandlerType?uncap_first]")
@Validated
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class [=eventHandlerType] {

    @Qualifier("[=eventExecutorType?uncap_first]")
    private final [=eventExecutorType] [=eventExecutorType?uncap_first];

    public void handle(@Valid @NotNull [=eventType] event) {
        [=eventExecutorType?uncap_first].execute(event);
    }
}
