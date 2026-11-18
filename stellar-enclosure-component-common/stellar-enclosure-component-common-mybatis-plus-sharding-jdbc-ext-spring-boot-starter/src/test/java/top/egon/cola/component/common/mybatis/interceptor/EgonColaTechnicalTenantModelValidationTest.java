package top.egon.cola.component.common.mybatis.interceptor;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.resultset.ResultSetHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.common.mybatis.business.EgonColaTenantIdProvider;
import top.egon.cola.component.common.mybatis.extension.EgonColaExplicitTenantScopeMapper;
import top.egon.cola.component.common.mybatis.extension.EgonColaMapper;
import top.egon.cola.component.common.mybatis.extension.EgonColaTechnicalTenantScope;
import top.egon.cola.component.common.mybatis.model.EgonColaModelValidationGroups;
import top.egon.cola.component.common.mybatis.model.EgonModel;
import top.egon.cola.component.common.mybatis.support.TestBusinessModel;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EgonColaTechnicalTenantModelValidationTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();

    private final EgonColaModelValidationInterceptor realValidation = new EgonColaModelValidationInterceptor(
            VALIDATOR_FACTORY.getValidator(), new EgonColaMybatisPlusProperties());

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void changingMdcToZeroDoesNotAuthorizeAnOrdinaryModel() throws Exception {
        TestBusinessModel model = new TestBusinessModel();
        model.setTenantId(0L);
        ParameterHandler handler = mock(ParameterHandler.class);
        when(handler.getParameterObject()).thenReturn(model);
        Invocation invocation = new Invocation(handler,
                ParameterHandler.class.getMethod("setParameters", PreparedStatement.class),
                new Object[]{mock(PreparedStatement.class)});
        EgonColaModelValidationInterceptor validation = new EgonColaModelValidationInterceptor(
                mock(Validator.class), new EgonColaMybatisPlusProperties());

        try (var ignored = MDC.putCloseable(EgonColaTenantIdProvider.DEFAULT_MDC_KEY, "0")) {
            assertThatThrownBy(() -> validation.intercept(invocation))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("TECHNICAL_TENANT_SCOPE_REQUIRED");
        }
    }

    @Test
    void trustedFixedMapperAndModelAllowTechnicalZeroForInsertAndLoadedResults() {
        TechnicalFixtureModel model = technicalModel();
        Invocation insert = parameterInvocation(TechnicalFixtureMapper.class, SqlCommandType.INSERT, model);
        List<TechnicalFixtureModel> rows = List.of(model);
        Invocation select = resultInvocation(TechnicalFixtureMapper.class, rows);

        try (var ignored = MDC.putCloseable(EgonColaTenantIdProvider.DEFAULT_MDC_KEY, "0")) {
            String result = EgonColaTechnicalTenantScope.execute(TechnicalFixtureMapper.class, () -> {
                assertThat(intercept(insert)).isNull();
                assertThat(intercept(select)).isSameAs(rows);
                return "accepted";
            });
            assertThat(result).isEqualTo("accepted");
            assertThat(MDC.get(EgonColaTenantIdProvider.DEFAULT_MDC_KEY)).isEqualTo("0");
        }
    }

    @Test
    void markerAndMatchingMappedStatementDoNotReplaceTheTrustedScopeForEitherHandler() {
        try (var ignored = MDC.putCloseable(EgonColaTenantIdProvider.DEFAULT_MDC_KEY, "0")) {
            assertScopeRequired(parameterInvocation(TechnicalFixtureMapper.class,
                    SqlCommandType.INSERT, technicalModel()));
            assertScopeRequired(resultInvocation(TechnicalFixtureMapper.class, List.of(technicalModel())));
        }
    }

    @Test
    void registeredTechnicalMapperScalarStatementRequiresScopeBeforeParameterBinding() throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(TechnicalFixtureMapper.class);
        StatementParameterHandler handler = mock(StatementParameterHandler.class);
        handler.mappedStatement = new MappedStatement.Builder(configuration,
                TechnicalFixtureMapper.class.getName() + ".selectScalar",
                new StaticSqlSource(configuration,
                        "SELECT id FROM platform_control.technical_fixture WHERE tenant_id = 0 AND id = ?"),
                SqlCommandType.SELECT).build();
        when(handler.getParameterObject()).thenReturn(11L);
        PreparedStatement statement = mock(PreparedStatement.class);
        Invocation invocation = new Invocation(handler,
                ParameterHandler.class.getMethod("setParameters", PreparedStatement.class),
                new Object[]{statement});

        try (var ignored = MDC.putCloseable(EgonColaTenantIdProvider.DEFAULT_MDC_KEY, "0")) {
            assertScopeRequired(invocation);
            EgonColaTechnicalTenantScope.execute(TechnicalFixtureMapper.class, () -> {
                assertThat(intercept(invocation)).isNull();
                return null;
            });
            verify(handler).setParameters(statement);
        }
    }

    @Test
    void trustedScopeRejectsAnotherMappedStatementForBothHandlers() {
        try (var ignored = MDC.putCloseable(EgonColaTenantIdProvider.DEFAULT_MDC_KEY, "0")) {
            EgonColaTechnicalTenantScope.execute(TechnicalFixtureMapper.class, () -> {
                assertScopeRequired(parameterInvocation(OtherTechnicalMapper.class,
                        SqlCommandType.INSERT, technicalModel()));
                assertScopeRequired(resultInvocation(OtherTechnicalMapper.class, List.of(technicalModel())));
                return null;
            });
        }
    }

    @Test
    void trustedMapperScopeDoesNotAuthorizeAnOrdinaryModelForEitherHandler() {
        TestBusinessModel ordinary = businessModel(0L);
        try (var ignored = MDC.putCloseable(EgonColaTenantIdProvider.DEFAULT_MDC_KEY, "0")) {
            EgonColaTechnicalTenantScope.execute(TechnicalFixtureMapper.class, () -> {
                assertScopeRequired(parameterInvocation(TechnicalFixtureMapper.class,
                        SqlCommandType.INSERT, ordinary));
                assertScopeRequired(resultInvocation(TechnicalFixtureMapper.class, List.of(ordinary)));
                return null;
            });
        }
    }

    @Test
    void trustedScopeWithoutStatementMetadataFailsClosedForBothHandlers() {
        try (var ignored = MDC.putCloseable(EgonColaTenantIdProvider.DEFAULT_MDC_KEY, "0")) {
            EgonColaTechnicalTenantScope.execute(TechnicalFixtureMapper.class, () -> {
                assertScopeRequired(parameterInvocation(null, SqlCommandType.INSERT, technicalModel()));
                assertScopeRequired(resultInvocation(null, List.of(technicalModel())));
                return null;
            });
        }
    }

    @Test
    void scopeDoesNotRewriteMdcOrAdmitANonzeroTechnicalTenant() {
        try (var ignored = MDC.putCloseable(EgonColaTenantIdProvider.DEFAULT_MDC_KEY, "7")) {
            EgonColaTechnicalTenantScope.execute(TechnicalFixtureMapper.class, () -> {
                assertThatThrownBy(() -> intercept(parameterInvocation(TechnicalFixtureMapper.class,
                        SqlCommandType.INSERT, technicalModel()))).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> intercept(resultInvocation(TechnicalFixtureMapper.class,
                        List.of(technicalModel())))).isInstanceOf(IllegalStateException.class);
                TechnicalFixtureModel nonzero = technicalModel();
                nonzero.setTenantId(7L);
                assertThatThrownBy(() -> intercept(parameterInvocation(TechnicalFixtureMapper.class,
                        SqlCommandType.INSERT, nonzero))).isInstanceOf(IllegalStateException.class);
                assertThat(MDC.get(EgonColaTenantIdProvider.DEFAULT_MDC_KEY)).isEqualTo("7");
                return null;
            });
            assertThat(MDC.get(EgonColaTenantIdProvider.DEFAULT_MDC_KEY)).isEqualTo("7");
        }
    }

    @Test
    void failingAndNestedScopesRestoreThePreviousExactMapperAndThenRemoveIt() {
        try (var ignored = MDC.putCloseable(EgonColaTenantIdProvider.DEFAULT_MDC_KEY, "0")) {
            EgonColaTechnicalTenantScope.execute(TechnicalFixtureMapper.class, () -> {
                assertThatThrownBy(() -> EgonColaTechnicalTenantScope.execute(TechnicalFixtureMapper.class, () -> {
                    assertThat(intercept(parameterInvocation(TechnicalFixtureMapper.class,
                            SqlCommandType.INSERT, technicalModel()))).isNull();
                    throw new IllegalStateException("fixture inner failure");
                })).isInstanceOf(IllegalStateException.class).hasMessage("fixture inner failure");
                AtomicBoolean otherMapperEntered = new AtomicBoolean();
                assertThatThrownBy(() -> EgonColaTechnicalTenantScope.execute(OtherTechnicalMapper.class, () -> {
                    otherMapperEntered.set(true);
                    return null;
                })).isInstanceOf(IllegalArgumentException.class);
                assertThat(otherMapperEntered).isFalse();
                assertThat(intercept(parameterInvocation(TechnicalFixtureMapper.class,
                        SqlCommandType.INSERT, technicalModel()))).isNull();
                assertScopeRequired(parameterInvocation(OtherTechnicalMapper.class,
                        SqlCommandType.INSERT, otherTechnicalModel()));
                return null;
            });
            assertScopeRequired(parameterInvocation(TechnicalFixtureMapper.class,
                    SqlCommandType.INSERT, technicalModel()));
            assertScopeRequired(parameterInvocation(OtherTechnicalMapper.class,
                    SqlCommandType.INSERT, otherTechnicalModel()));

            assertThatThrownBy(() -> EgonColaTechnicalTenantScope.execute(TechnicalFixtureMapper.class, () -> {
                throw new IllegalStateException("fixture outer failure");
            })).isInstanceOf(IllegalStateException.class).hasMessage("fixture outer failure");
            assertScopeRequired(resultInvocation(TechnicalFixtureMapper.class, List.of(technicalModel())));
            assertThat(MDC.get(EgonColaTenantIdProvider.DEFAULT_MDC_KEY)).isEqualTo("0");
        }
    }

    @Test
    void technicalScopeKeepsAllOtherInsertAndLoadedBeanValidationConstraints() {
        try (var ignored = MDC.putCloseable(EgonColaTenantIdProvider.DEFAULT_MDC_KEY, "0")) {
            EgonColaTechnicalTenantScope.execute(TechnicalFixtureMapper.class, () -> {
                TechnicalFixtureModel invalidId = technicalModel();
                invalidId.setId(0L);
                assertConstraint(parameterInvocation(TechnicalFixtureMapper.class,
                        SqlCommandType.INSERT, invalidId), "id");

                TechnicalFixtureModel invalidVersion = technicalModel();
                invalidVersion.setVersion(-1L);
                assertConstraint(resultInvocation(TechnicalFixtureMapper.class,
                        List.of(invalidVersion)), "version");

                TechnicalFixtureModel missingAudit = technicalModel();
                missingAudit.setCreateUserId(null);
                assertConstraint(parameterInvocation(TechnicalFixtureMapper.class,
                        SqlCommandType.INSERT, missingAudit), "createUserId");

                TechnicalFixtureModel missingAuditTime = technicalModel();
                missingAuditTime.setUpdateTime(null);
                assertConstraint(resultInvocation(TechnicalFixtureMapper.class,
                        List.of(missingAuditTime)), "updateTime");

                TechnicalFixtureModel invalidBusinessField = technicalModel().setLabel(" ");
                assertConstraint(parameterInvocation(TechnicalFixtureMapper.class,
                        SqlCommandType.INSERT, invalidBusinessField), "label");
                assertConstraint(resultInvocation(TechnicalFixtureMapper.class,
                        List.of(invalidBusinessField)), "label");
                return null;
            });
        }
    }

    @Test
    void ordinaryPositiveTenantRetainsFullValidationAndOwnerMatching() {
        try (var ignored = MDC.putCloseable(EgonColaTenantIdProvider.DEFAULT_MDC_KEY, "7")) {
            TestBusinessModel model = businessModel(7L);
            assertThat(intercept(parameterInvocation(OrdinaryMapper.class, SqlCommandType.INSERT, model))).isNull();
            List<TestBusinessModel> rows = List.of(model);
            assertThat(intercept(resultInvocation(OrdinaryMapper.class, rows))).isSameAs(rows);

            TestBusinessModel invalidId = businessModel(7L);
            invalidId.setId(0L);
            assertConstraint(parameterInvocation(OrdinaryMapper.class, SqlCommandType.INSERT, invalidId), "id");
            TestBusinessModel invalidVersion = businessModel(7L);
            invalidVersion.setVersion(-1L);
            assertConstraint(resultInvocation(OrdinaryMapper.class, List.of(invalidVersion)), "version");
            TestBusinessModel missingAudit = businessModel(7L);
            missingAudit.setCreateTime(null);
            assertConstraint(parameterInvocation(OrdinaryMapper.class, SqlCommandType.INSERT, missingAudit), "createTime");
            TestBusinessModel invalidBusinessField = businessModel(7L).setTitle(" ");
            assertConstraint(resultInvocation(OrdinaryMapper.class, List.of(invalidBusinessField)), "title");

            TestBusinessModel wrongOwner = businessModel(8L);
            assertThatThrownBy(() -> intercept(parameterInvocation(OrdinaryMapper.class,
                    SqlCommandType.INSERT, wrongOwner))).isInstanceOf(IllegalStateException.class)
                    .hasMessage("TENANT_CONTEXT_MISMATCH");
            assertThatThrownBy(() -> intercept(resultInvocation(OrdinaryMapper.class, List.of(wrongOwner))))
                    .isInstanceOf(IllegalStateException.class).hasMessage("TENANT_CONTEXT_MISMATCH");
        }
    }

    private void assertScopeRequired(Invocation invocation) {
        assertThatThrownBy(() -> intercept(invocation)).isInstanceOf(IllegalStateException.class)
                .hasMessage("TECHNICAL_TENANT_SCOPE_REQUIRED");
        if (invocation.getTarget() instanceof ParameterHandler handler) {
            try {
                verify(handler, never()).setParameters((PreparedStatement) invocation.getArgs()[0]);
            } catch (java.sql.SQLException impossibleForMock) {
                throw new AssertionError(impossibleForMock);
            }
        }
    }

    private void assertConstraint(Invocation invocation, String property) {
        assertThatThrownBy(() -> intercept(invocation)).isInstanceOfSatisfying(
                ConstraintViolationException.class, failure -> assertThat(failure.getConstraintViolations())
                        .extracting(violation -> violation.getPropertyPath().toString()).contains(property));
    }

    private Object intercept(Invocation invocation) {
        try {
            return realValidation.intercept(invocation);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable unexpected) {
            throw new AssertionError("Unexpected checked failure from the mocked MyBatis handler", unexpected);
        }
    }

    private static Invocation parameterInvocation(Class<?> mapperType, SqlCommandType command, Object parameter) {
        StatementParameterHandler handler = mock(StatementParameterHandler.class);
        handler.mappedStatement = mapperType == null ? null : mappedStatement(mapperType, command);
        when(handler.getParameterObject()).thenReturn(parameter);
        try {
            return new Invocation(handler, ParameterHandler.class.getMethod("setParameters", PreparedStatement.class),
                    new Object[]{mock(PreparedStatement.class)});
        } catch (NoSuchMethodException impossibleForMyBatis) {
            throw new AssertionError(impossibleForMyBatis);
        }
    }

    private static Invocation resultInvocation(Class<?> mapperType, List<?> rows) {
        StatementResultSetHandler handler = mock(StatementResultSetHandler.class);
        handler.mappedStatement = mapperType == null ? null : mappedStatement(mapperType, SqlCommandType.SELECT);
        Statement statement = mock(Statement.class);
        try {
            doReturn(rows).when(handler).handleResultSets(statement);
            return new Invocation(handler, ResultSetHandler.class.getMethod("handleResultSets", Statement.class),
                    new Object[]{statement});
        } catch (ReflectiveOperationException | java.sql.SQLException impossibleForMock) {
            throw new AssertionError(impossibleForMock);
        }
    }

    private static MappedStatement mappedStatement(Class<?> mapperType, SqlCommandType command) {
        Configuration configuration = new Configuration();
        String operation = command == SqlCommandType.INSERT ? "insert" : "select";
        return new MappedStatement.Builder(configuration, mapperType.getName() + "." + operation,
                new StaticSqlSource(configuration, "SELECT 1"), command).build();
    }

    private static TechnicalFixtureModel technicalModel() {
        TechnicalFixtureModel model = new TechnicalFixtureModel().setLabel("technical record");
        fillPersisted(model, 0L);
        return model;
    }

    private static OtherTechnicalModel otherTechnicalModel() {
        OtherTechnicalModel model = new OtherTechnicalModel().setLabel("other technical record");
        fillPersisted(model, 0L);
        return model;
    }

    private static TestBusinessModel businessModel(long tenant) {
        TestBusinessModel model = new TestBusinessModel().businessValues("business record", "payload");
        fillPersisted(model, tenant);
        return model;
    }

    private static void fillPersisted(EgonModel<?> model, long tenant) {
        model.setId(11L);
        model.setTenantId(tenant);
        model.setCreateUserId("technical-writer");
        model.setCreateTime(Instant.parse("2026-11-18T00:00:00Z"));
        model.setUpdateUserId("technical-writer");
        model.setUpdateTime(Instant.parse("2026-11-18T00:00:00Z"));
        model.setVersion(0L);
    }

    /** These are class mocks because MyBatis reads this real private field reflectively. */
    private abstract static class StatementParameterHandler implements ParameterHandler {
        private MappedStatement mappedStatement;
    }

    private abstract static class StatementResultSetHandler implements ResultSetHandler {
        private MappedStatement mappedStatement;
    }

    @EgonColaExplicitTenantScopeMapper(technicalModel = TechnicalFixtureModel.class,
            technicalTable = "platform_control.technical_fixture")
    private interface TechnicalFixtureMapper extends EgonColaMapper<TechnicalFixtureModel> { }

    @EgonColaExplicitTenantScopeMapper(technicalModel = OtherTechnicalModel.class,
            technicalTable = "platform_control.other_technical_fixture")
    private interface OtherTechnicalMapper extends EgonColaMapper<OtherTechnicalModel> { }

    private interface OrdinaryMapper extends EgonColaMapper<TestBusinessModel> { }

    @TableName(value = "technical_fixture", schema = "platform_control")
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    @SuperBuilder
    @EqualsAndHashCode(callSuper = true)
    static class TechnicalFixtureModel extends EgonModel<TechnicalFixtureModel> {
        @NotBlank(groups = {EgonColaModelValidationGroups.Insert.class, EgonColaModelValidationGroups.Persisted.class})
        private String label;
    }

    @TableName(value = "other_technical_fixture", schema = "platform_control")
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    @SuperBuilder
    @EqualsAndHashCode(callSuper = true)
    static class OtherTechnicalModel extends EgonModel<OtherTechnicalModel> {
        @NotBlank(groups = {EgonColaModelValidationGroups.Insert.class, EgonColaModelValidationGroups.Persisted.class})
        private String label;
    }
}
