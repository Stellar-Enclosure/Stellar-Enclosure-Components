package top.egon.cola.component.agentflow.context;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.agentflow.api.capability.CallPermitVO;
import top.egon.cola.component.agentflow.api.context.ContextFragmentResult;
import top.egon.cola.component.agentflow.api.context.ContextQuery;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.context.ContextSource;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand;
import top.egon.cola.component.common.core.enums.ResultCode;
import top.egon.cola.component.common.core.exception.CommonException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Validated
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class DefaultContextAssembler {

    @Qualifier("agentExecutionClock")
    private final Clock clock;

    @Qualifier("defaultValidator")
    private final Validator validator;

    public ContextDataVO resolve(@Valid ContextQuery query, @Valid ContextScopeVO scope, ContextSource source,
                                 @Valid CallPermitVO permit, int availableBytes) {
        Objects.requireNonNull(query, "query");
        String sourceRef = query.getSourceRef();
        String purpose = query.getPurpose();
        int maxCharacters = query.getMaxCharacters();
        int maxFragments = query.getMaxFragments();
        var boundedQuery = new ContextQuery(sourceRef, purpose, maxCharacters, maxFragments);
        var violations = validator.validate(boundedQuery);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        ContextScopeVO.requireIdentifier(sourceRef);
        ContextScopeVO.requireIdentifier(purpose);
        permit.requireCurrent(scope, sourceRef, purpose, clock);
        ContextFragmentResult result = Objects.requireNonNull(source.resolve(boundedQuery, scope, permit),
                "context result");
        permit.requireCurrent(scope, sourceRef, purpose, clock);
        var resultViolations = validator.validate(result);
        if (!resultViolations.isEmpty()) {
            throw new ConstraintViolationException(resultViolations);
        }
        var fragments = result.checkedFragments();
        if (result.getUnavailableReason() != null) {
            throw failure("CONTEXT_UNAVAILABLE", true);
        }
        int maxBytes = Math.min(availableBytes, ContextFragmentResult.MAX_BYTES);
        int size = 2; // JSON array delimiters
        boolean trimming = maxBytes < size;
        var accepted = new ArrayList<ContextFragmentResult.FragmentVO>();
        for (var fragment : fragments) {
            if (!sourceRef.equals(fragment.sourceRef()) || !sha256(fragment.content()).equals(fragment.digest())
                    || fragment.content().length() > maxCharacters) {
                throw failure("INVALID_CONTEXT_FRAGMENT", false);
            }
            int bytes = AgentExecutionCommand.JsonValues.canonicalBytes(Map.of(
                    "fragmentId", fragment.fragmentId(), "sourceRef", fragment.sourceRef(),
                    "revision", fragment.revision(), "content", fragment.content(),
                    "digest", fragment.digest(), "observedAt", fragment.observedAt().toString())).length;
            if (!accepted.isEmpty()) {
                bytes++; // JSON array separator
            }
            if (accepted.size() == maxFragments || bytes > maxBytes - size) {
                trimming = true;
            }
            if (!trimming) {
                accepted.add(fragment);
                size += bytes;
            }
        }
        return new ContextDataVO(accepted, accepted.isEmpty() ? 0 : size);
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static CommonException failure(String code, boolean retryable) {
        return new CommonException(ResultCode.SYSTEM_ERROR.getCode(), code, code, retryable, null);
    }

    /** This data never participates in the trusted instruction role. */
    public record ContextDataVO(List<ContextFragmentResult.FragmentVO> fragments, int bytes) {

        public ContextDataVO {
            fragments = List.copyOf(fragments);
        }
    }
}
