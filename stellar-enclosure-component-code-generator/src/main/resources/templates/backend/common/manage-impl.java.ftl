package [=manageImplPackage];

import [=managePackage].[=manageType];
import [=commandPackage].[=createCommandType];
import [=commandPackage].[=updateCommandType];
import [=commandPackage].[=deleteCommandType];
import [=queryPackage].[=detailQueryType];
import [=queryPackage].[=pageQueryType];
import [=resultPackage].[=resultType];
import [=commandExecutorPackage].[=createExecutorType];
import [=commandExecutorPackage].[=updateExecutorType];
import [=commandExecutorPackage].[=deleteExecutorType];
import [=queryExecutorPackage].[=detailExecutorType];
import [=queryExecutorPackage].[=pageExecutorType];
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.common.core.pojo.PageSlice;

@Service("[=manageBean]")
@Validated
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class [=manageImplType] implements [=manageType] {

    @Qualifier("[=createExecutorType?uncap_first]")
    private final [=createExecutorType] [=createExecutorType?uncap_first];

    @Qualifier("[=updateExecutorType?uncap_first]")
    private final [=updateExecutorType] [=updateExecutorType?uncap_first];

    @Qualifier("[=deleteExecutorType?uncap_first]")
    private final [=deleteExecutorType] [=deleteExecutorType?uncap_first];

    @Qualifier("[=detailExecutorType?uncap_first]")
    private final [=detailExecutorType] [=detailExecutorType?uncap_first];

    @Qualifier("[=pageExecutorType?uncap_first]")
    private final [=pageExecutorType] [=pageExecutorType?uncap_first];

    @Override
    @Transactional
    public [=resultType] create(@Valid [=createCommandType] command) {
        return [=createExecutorType?uncap_first].execute(command);
    }

    @Override
    @Transactional
    public [=resultType] update(@Valid [=updateCommandType] command) {
        return [=updateExecutorType?uncap_first].execute(command);
    }

    @Override
    @Transactional
    public void delete(@Valid [=deleteCommandType] command) {
        [=deleteExecutorType?uncap_first].execute(command);
    }

    @Override
    @Transactional(readOnly = true)
    public [=resultType] detail(@Valid [=detailQueryType] query) {
        return [=detailExecutorType?uncap_first].execute(query);
    }

    @Override
    @Transactional(readOnly = true)
    public PageSlice<[=resultType]> page(@Valid [=pageQueryType] query) {
        return [=pageExecutorType?uncap_first].execute(query);
    }
}
