package top.egon.cola.component.agentflow.agentic;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand.JsonValues;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A normalized, immutable plan resolved from one exact, published template.
 * The publisher owns wire defaults; execution accepts only a fully resolved plan.
 */
public record AgenticPlanVO(
        @NotNull StrategyEnum strategy,
        @NotNull @Size(min = 1, max = 16) List<@NotNull @Valid LeafVO> agents,
        @NotNull @Size(max = 64) List<@NotNull @Valid LinkVO> links,
        @Min(1) @Max(32) Integer maxRounds,
        @Min(1) @Max(32) int maxParallelism,
        String outputAgentKey,
        @NotNull MergeEnum merge,
        @NotNull @Size(max = 32) List<@NotNull String> sharedFields,
        @NotNull @Valid TerminationVO termination) {

    private static final Pattern AGENT_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Pattern REFERENCE_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.:-]{0,127}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final String FIELD = "[A-Za-z_][A-Za-z0-9_]*(?:\\[(?:0|[1-9][0-9]{0,2})\\])*";
    private static final Pattern VALUE_PATH = Pattern.compile(FIELD + "(?:\\." + FIELD + ")*");
    private static final long MAX_EXACT_VERSION = 999_999_999_999_999L;

    public AgenticPlanVO {
        agents = boundedCopy(agents, 1, 16);
        links = boundedCopy(links, 0, 64);
        sharedFields = boundedCopy(sharedFields, 0, 32);
        require(strategy != null && merge != null && termination != null);
        var keys = new HashSet<String>();
        require(agents.stream().allMatch(leaf -> keys.add(leaf.agentKey())));
        require(strategy != StrategyEnum.REASONING || agents.size() == 1);

        boolean hasRounds = strategy == StrategyEnum.BOUNDED_LOOP || strategy == StrategyEnum.HANDOFF;
        require(hasRounds ? maxRounds != null && maxRounds >= 1 && maxRounds <= 32 : maxRounds == null);
        require(maxParallelism >= 1 && maxParallelism <= 32);
        require(strategy == StrategyEnum.PARALLEL || maxParallelism == 1);

        require(strategy == StrategyEnum.HANDOFF || links.isEmpty());
        var distinctLinks = new HashSet<LinkVO>();
        require(links.stream().allMatch(link -> keys.contains(link.source()) && keys.contains(link.target())
                && distinctLinks.add(link)));

        if (merge == MergeEnum.OUTPUT_AGENT) {
            require(outputAgentKey != null && keys.contains(outputAgentKey));
        } else {
            require(outputAgentKey == null && (strategy == StrategyEnum.SEQUENTIAL || strategy == StrategyEnum.PARALLEL));
        }
        require(new HashSet<>(sharedFields).size() == sharedFields.size());
        require(sharedFields.stream().allMatch(path -> allowedPath(path, keys, false)));
        if (termination instanceof PredicateVO predicate) {
            require(hasRounds);
            validateCondition(predicate.condition(), keys, 1, new int[]{0});
        }
    }

    /** Exact host authorizations required by sharing and predicate evaluation. */
    public Set<String> valuePaths() {
        Set<String> required = new HashSet<>(sharedFields);
        if (termination instanceof PredicateVO predicate) {
            addValuePaths(predicate.condition(), required);
        }
        return Set.copyOf(required);
    }

    private static void addValuePaths(ConditionVO condition, Set<String> paths) {
        switch (condition) {
            case PathVO path -> {
                if (!"round".equals(path.path())) {
                    paths.add(path.path());
                }
            }
            case OperationVO operation -> operation.args().forEach(child -> addValuePaths(child, paths));
            case LiteralVO ignored -> { }
        }
    }

    public record DependencyRefVO(@NotNull String key, @Min(1) @Max(MAX_EXACT_VERSION) long version,
                                  @NotNull String contentSha256) {

        public DependencyRefVO {
            require(validReference(key) && version >= 1 && version <= MAX_EXACT_VERSION);
            require(contentSha256 != null && SHA256.matcher(contentSha256).matches());
        }
    }

    public record LeafVO(@NotNull String agentKey, @NotNull @Valid DependencyRefVO instructionRef,
                         @NotNull String modelRef,
                         @NotNull @Size(max = 32) List<@NotNull @Valid DependencyRefVO> toolRefs) {

        public LeafVO {
            require(validAgentKey(agentKey) && instructionRef != null && validReference(modelRef));
            toolRefs = boundedCopy(toolRefs, 0, 32);
        }
    }

    public record LinkVO(@NotNull String source, @NotNull String target) {

        public LinkVO {
            require(validAgentKey(source) && validAgentKey(target) && !source.equals(target));
        }
    }

    public sealed interface TerminationVO permits RootTerminalVO, PredicateVO {
    }

    public record RootTerminalVO(@NotNull TerminationModeEnum mode) implements TerminationVO {

        public RootTerminalVO {
            require(mode == TerminationModeEnum.ROOT_TERMINAL);
        }
    }

    public record PredicateVO(@NotNull TerminationModeEnum mode, @NotNull @Valid ConditionVO condition)
            implements TerminationVO {

        public PredicateVO {
            require(mode == TerminationModeEnum.PREDICATE && condition != null);
            validateCondition(condition, null, 1, new int[]{0});
        }
    }

    public sealed interface ConditionVO permits OperationVO, PathVO, LiteralVO {
    }

    public record OperationVO(@NotNull ConditionOpEnum op, @NotNull List<@NotNull @Valid ConditionVO> args)
            implements ConditionVO {

        public OperationVO {
            args = boundedCopy(args, 1, 63);
            require(op != null && switch (op) {
                case NOT -> args.size() == 1;
                case AND, OR -> args.size() >= 2;
                default -> args.size() == 2;
            });
        }
    }

    public record PathVO(@NotNull String path) implements ConditionVO {

        public PathVO {
            require(allowedPath(path, null, true));
        }
    }

    public record LiteralVO(Object literal) implements ConditionVO {

        public LiteralVO {
            literal = JsonValues.copyObject(Collections.singletonMap("literal", literal), 64 * 1024).get("literal");
        }
    }

    private static void validateCondition(ConditionVO condition, Set<String> keys, int depth, int[] nodes) {
        require(depth <= 8 && ++nodes[0] <= 64);
        switch (condition) {
            case PathVO path -> require(allowedPath(path.path(), keys, true));
            case OperationVO operation -> operation.args().forEach(child -> validateCondition(child, keys, depth + 1, nodes));
            case LiteralVO ignored -> { }
        }
    }

    private static boolean allowedPath(String path, Set<String> keys, boolean allowRound) {
        if (allowRound && "round".equals(path)) {
            return true;
        }
        if (path == null || path.length() > 256 || path.chars().filter(ch -> ch == '.' || ch == '[').count() >= 8) {
            return false;
        }
        if (path.startsWith("input.")) {
            return VALUE_PATH.matcher(path.substring(6)).matches();
        }
        if (!path.startsWith("agents.")) {
            return false;
        }
        int separator = path.indexOf('.', 7);
        if (separator < 0 || !path.startsWith(".output.", separator)) {
            return false;
        }
        String key = path.substring(7, separator);
        return validAgentKey(key) && (keys == null || keys.contains(key))
                && VALUE_PATH.matcher(path.substring(separator + 8)).matches();
    }

    static boolean validAgentKey(String value) {
        return value != null && AGENT_KEY.matcher(value).matches();
    }

    private static boolean validReference(String value) {
        return value != null && REFERENCE_KEY.matcher(value).matches();
    }

    private static <T> List<T> boundedCopy(List<T> values, int min, int max) {
        require(values != null && values.size() >= min && values.size() <= max && values.stream().noneMatch(java.util.Objects::isNull));
        return List.copyOf(values);
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Invalid bounded agentic plan");
        }
    }

    public enum StrategyEnum {
        REASONING("REASONING"), SEQUENTIAL("SEQUENTIAL"), PARALLEL("PARALLEL"), BOUNDED_LOOP("BOUNDED_LOOP"), HANDOFF("HANDOFF");

        @JsonValue
        private final String code;

        StrategyEnum(String code) {
            this.code = code;
        }
    }

    public enum MergeEnum {
        OUTPUT_AGENT("OUTPUT_AGENT"), COLLECT_BY_AGENT("COLLECT_BY_AGENT");

        @JsonValue
        private final String code;

        MergeEnum(String code) {
            this.code = code;
        }
    }

    public enum TerminationModeEnum {
        ROOT_TERMINAL("ROOT_TERMINAL"), PREDICATE("PREDICATE");

        @JsonValue
        private final String code;

        TerminationModeEnum(String code) {
            this.code = code;
        }
    }

    public enum ConditionOpEnum {
        EQ("EQ"), NE("NE"), GT("GT"), GE("GE"), LT("LT"), LE("LE"), AND("AND"), OR("OR"), NOT("NOT");

        @JsonValue
        private final String code;

        ConditionOpEnum(String code) {
            this.code = code;
        }
    }
}
