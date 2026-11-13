package top.egon.cola.component.agentflow.session;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.agentflow.api.capability.CallPermitVO;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand;
import top.egon.cola.component.agentflow.api.session.SessionStateResult;
import top.egon.cola.component.agentflow.api.session.SessionStore;
import top.egon.cola.component.agentflow.api.session.SessionWriteCommand;

import top.egon.cola.component.common.core.enums.ResultCode;
import top.egon.cola.component.common.core.exception.CommonException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Validated
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class InMemorySessionStore implements SessionStore {

    @Qualifier("agentExecutionClock")
    private final Clock clock;

    @Qualifier("defaultValidator")
    private final Validator validator;

    private final Map<SessionKeyVO, SessionSlot> slots = new ConcurrentHashMap<>();

    @Override
    public SessionStateResult read(ContextScopeVO scope, String sessionId) {
        SessionSlot slot = slots.get(key(scope, sessionId));
        return slot == null ? SessionStateResult.absent() : slot.read();
    }

    @Override
    public SessionStateResult compareAndSet(SessionWriteCommand command, ContextScopeVO scope, CallPermitVO permit) {
        Objects.requireNonNull(command, "command");
        command = new SessionWriteCommand(command.getSessionId(), command.getExpectedRevision(),
                command.getCommandId(), command.getState());
        var violations = validator.validate(command);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        SessionKeyVO key = key(scope, command.getSessionId());
        String commandId = command.getCommandId();
        ContextScopeVO.requireIdentifier(commandId);
        long expectedRevision = command.getExpectedRevision();
        Map<String, Object> state = command.checkedState();
        String fingerprint = fingerprint(key.sessionId(), expectedRevision, state);
        Objects.requireNonNull(permit, "permit").requireCurrent(scope, CAPABILITY_REF, WRITE_PURPOSE, clock);
        SessionSlot slot = slots.get(key);
        if (slot == null) {
            if (expectedRevision != 0) {
                throw failure("VERSION_CONFLICT");
            }
            slot = slots.computeIfAbsent(key, ignored -> new SessionSlot());
        }
        return slot.write(commandId, fingerprint, expectedRevision, state,
                () -> permit.requireCurrent(scope, CAPABILITY_REF, WRITE_PURPOSE, clock));
    }

    private static SessionKeyVO key(ContextScopeVO scope, String sessionId) {
        Objects.requireNonNull(scope, "scope");
        ContextScopeVO.requireIdentifier(sessionId);
        if (!scope.sessionKey().equals(sessionId)) {
            throw failure("SESSION_SCOPE_MISMATCH");
        }
        return new SessionKeyVO(scope, sessionId);
    }

    private static String fingerprint(String sessionId, long expectedRevision, Map<String, Object> state) {
        byte[] domain = "EGON_AGENT_SESSION_WRITE_V1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] value = AgentExecutionCommand.JsonValues.canonicalBytes(new java.util.TreeMap<>(Map.of(
                "sessionId", sessionId, "expectedRevision", expectedRevision, "state", state)));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(domain.length).array());
            digest.update(domain);
            digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static CommonException failure(String code) {
        return new CommonException(ResultCode.SYSTEM_ERROR.getCode(), code, code);
    }

    private record SessionKeyVO(ContextScopeVO scope, String sessionId) {
    }

    private record SessionReceiptVO(String fingerprint, SessionStateResult result) {
    }

    private static final class SessionSlot {

        private final Map<String, SessionReceiptVO> receipts = new HashMap<>();
        private SessionStateResult head = SessionStateResult.absent();

        synchronized SessionStateResult read() {
            return head;
        }

        synchronized SessionStateResult write(String commandId, String fingerprint, long expectedRevision,
                                              Map<String, Object> state, Runnable recheckPermit) {
            recheckPermit.run();
            SessionReceiptVO receipt = receipts.get(commandId);
            if (receipt != null) {
                if (!receipt.fingerprint().equals(fingerprint)) {
                    throw failure("COMMAND_CONFLICT");
                }
                return receipt.result();
            }
            if (head.revision() != expectedRevision) {
                throw failure("VERSION_CONFLICT");
            }
            var result = new SessionStateResult(true, Math.addExact(expectedRevision, 1), state);
            recheckPermit.run();
            receipts.put(commandId, new SessionReceiptVO(fingerprint, result));
            head = result;
            return result;
        }
    }
}
