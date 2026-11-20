package top.egon.cola.component.outbox.dispatch;

import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.UUID;

@Slf4j
public class OutboxWorkerIdentity {

    private final String nodeId;
    private final Runnable claimAuthority;

    public OutboxWorkerIdentity(String configuredNodeId) {
        this(configuredNodeId, () -> { });
    }

    public OutboxWorkerIdentity(String configuredNodeId, Runnable claimAuthority) {
        this.claimAuthority = Objects.requireNonNull(claimAuthority, "claimAuthority");
        this.nodeId = configuredNodeId == null || configuredNodeId.isBlank()
                ? generatedNodeId()
                : sanitize(configuredNodeId);
    }

    public String nodeId() {
        return nodeId;
    }

    boolean canClaim() {
        try {
            claimAuthority.run();
            return true;
        } catch (RuntimeException unavailable) {
            log.debug("Transactional outbox claim authority is unavailable; failureType={}",
                    unavailable.getClass().getSimpleName());
            return false;
        }
    }

    public String nextLeaseOwner() {
        return nodeId + ":" + UUID.randomUUID();
    }

    private String generatedNodeId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException exception) {
            host = "unknown";
        }
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return sanitize(host) + "-" + ProcessHandle.current().pid() + "-" + suffix;
    }

    private String sanitize(String value) {
        String sanitized = value.trim().replaceAll("[^A-Za-z0-9._-]", "-");
        return sanitized.isBlank() ? "unknown" : sanitized;
    }
}
