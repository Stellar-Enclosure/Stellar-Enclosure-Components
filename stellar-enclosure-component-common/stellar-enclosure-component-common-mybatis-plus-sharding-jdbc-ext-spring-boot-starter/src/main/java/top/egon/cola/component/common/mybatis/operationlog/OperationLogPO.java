package top.egon.cola.component.common.mybatis.operationlog;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;
import top.egon.cola.component.common.mybatis.model.EgonColaModelValidationGroups.Insert;
import top.egon.cola.component.common.mybatis.model.EgonColaModelValidationGroups.Persisted;
import top.egon.cola.component.common.mybatis.model.EgonModel;

import org.apache.ibatis.type.JdbcType;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName(value = "egon_cola_operation_log", autoResultMap = true)
public class OperationLogPO extends EgonModel<OperationLogPO> {

    @NotNull(groups = {Insert.class, Persisted.class})
    @Size(max = 64, groups = {Insert.class, Persisted.class})
    private String operationId;

    @NotNull(groups = {Insert.class, Persisted.class})
    private Integer schemaVersion;

    @NotNull(groups = {Insert.class, Persisted.class})
    @Size(max = 128, groups = {Insert.class, Persisted.class})
    private String sourceService;

    @NotNull(groups = {Insert.class, Persisted.class})
    @Size(max = 64, groups = {Insert.class, Persisted.class})
    private String moduleCode;

    @NotNull(groups = {Insert.class, Persisted.class})
    @Size(max = 96, groups = {Insert.class, Persisted.class})
    private String actionCode;

    @NotNull(groups = {Insert.class, Persisted.class})
    @Size(max = 64, groups = {Insert.class, Persisted.class})
    private String resourceType;

    @Size(max = 128, groups = {Insert.class, Persisted.class})
    private String resourceId;

    @Size(max = 128, groups = {Insert.class, Persisted.class})
    private String bizCode;

    @Size(max = 128, groups = {Insert.class, Persisted.class})
    private String namespaceCode;

    @Size(max = 32, groups = {Insert.class, Persisted.class})
    private String env;

    @Size(max = 128, groups = {Insert.class, Persisted.class})
    private String appCode;

    @NotNull(groups = {Insert.class, Persisted.class})
    @Size(max = 16, groups = {Insert.class, Persisted.class})
    private String actorType;

    @NotNull(groups = {Insert.class, Persisted.class})
    @Size(max = 512, groups = {Insert.class, Persisted.class})
    private String actorId;

    @Size(max = 128, groups = {Insert.class, Persisted.class})
    private String actorName;

    @NotNull(groups = {Insert.class, Persisted.class})
    @Size(max = 16, groups = {Insert.class, Persisted.class})
    private String triggerType;

    @NotNull(groups = {Insert.class, Persisted.class})
    private OperationLogPhaseEnum phase;

    @NotNull(groups = {Insert.class, Persisted.class})
    private OperationLogOutcomeEnum outcome;

    @Size(max = 96, groups = {Insert.class, Persisted.class})
    private String reasonCode;

    @Size(max = 16, groups = {Insert.class, Persisted.class})
    private String httpMethod;

    @Size(max = 2048, groups = {Insert.class, Persisted.class})
    private String requestUrl;

    @Size(max = 512, groups = {Insert.class, Persisted.class})
    private String routePattern;

    @NotNull(groups = {Insert.class, Persisted.class})
    @Size(max = 1024, groups = {Insert.class, Persisted.class})
    private String handlerMethod;

    @Size(max = 64, groups = {Insert.class, Persisted.class})
    private String traceId;

    @Size(max = 128, groups = {Insert.class, Persisted.class})
    private String requestId;

    @NotNull(groups = {Insert.class, Persisted.class})
    private Instant occurredAt;

    @NotNull(groups = {Insert.class, Persisted.class})
    @PositiveOrZero(groups = {Insert.class, Persisted.class})
    private Long durationMs;

    @NotNull(groups = {Insert.class, Persisted.class})
    @TableField(typeHandler = JsonbTypeHandler.class)
    private ObjectNode details;
    /** Bind JSONB without a CAST expression in ShardingSphere's rewritten SQL. */
    public static final class JsonbTypeHandler extends JacksonTypeHandler {

        private static final JsonbBinder BINDER = JsonbBinder.load();

        public JsonbTypeHandler(Class<?> type) {
            super(type);
        }

        public JsonbTypeHandler(Class<?> type, Field field) {
            super(type, field);
        }

        @Override
        public void setNonNullParameter(PreparedStatement statement, int index, Object parameter, JdbcType jdbcType)
                throws SQLException {
            statement.setObject(index, BINDER.bind(toJson(parameter)), Types.OTHER);
        }
    }
    /** Loads the PostgreSQL driver's binding type already required by Egon sharding. */
    private record JsonbBinder(MethodHandle constructor, MethodHandle setType, MethodHandle setValue) {

        static JsonbBinder load() {
            try {
                Class<?> type = Class.forName("org.postgresql.util.PGobject");
                var lookup = MethodHandles.publicLookup();
                var setter = MethodType.methodType(void.class, String.class);
                return new JsonbBinder(lookup.findConstructor(type, MethodType.methodType(void.class)),
                        lookup.findVirtual(type, "setType", setter), lookup.findVirtual(type, "setValue", setter));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("OPERATION_LOG_POSTGRESQL_DRIVER_REQUIRED", failure);
            }
        }

        Object bind(String json) throws SQLException {
            try {
                Object value = constructor.invoke();
                setType.invoke(value, "jsonb");
                setValue.invoke(value, json);
                return value;
            } catch (SQLException failure) {
                throw failure;
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new SQLException("OPERATION_LOG_JSONB_BINDING_FAILED", failure);
            }
        }
    }
}
