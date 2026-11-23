package top.egon.cola.component.outbox.dispatch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import top.egon.cola.component.outbox.delivery.DeliveryHandlerRegistry;
import top.egon.cola.component.outbox.store.OutboxQueueCoordinationBO;
import top.egon.cola.component.outbox.store.OutboxQueueCoordinationCommand;
import top.egon.cola.component.outbox.store.OutboxQueueKey;
import top.egon.cola.component.outbox.store.OutboxQueueLease;
import top.egon.cola.component.outbox.store.OutboxStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** Elects preferred instances; PostgreSQL remains the authority for live owner tokens. */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public final class OutboxQueueCoordinator {

    @Qualifier("outboxStore")
    private final OutboxStore store;
    @Qualifier("deliveryHandlerRegistry")
    private final DeliveryHandlerRegistry handlers;
    @Qualifier("outboxWorkerIdentity")
    private final OutboxWorkerIdentity identity;

    private final String instanceId = java.util.UUID.randomUUID().toString();
    private final Map<Integer, View> views = new ConcurrentHashMap<>();

    void refresh(int slot, Predicate<OutboxQueueKey> inFlight) {
        if (!identity.canClaim()) {
            views.remove(slot);
            return;
        }
        Set<String> capability = Set.copyOf(handlers.channels());
        View last = views.get(slot);
        Set<String> acquire = new LinkedHashSet<>();
        List<OutboxQueueLease> renew = new ArrayList<>();
        List<OutboxQueueLease> release = new ArrayList<>();
        Set<String> draining = new LinkedHashSet<>();
        if (last != null) {
            last.snapshot().getLeases().values().stream()
                    .filter(lease -> instanceId.equals(lease.instanceId()))
                    .sorted(Comparator.comparing(lease -> lease.key().channel()))
                    .forEach(lease -> {
                        String channel = lease.key().channel();
                        Winner winner = last.winners().get(channel);
                        boolean transfer = winner != null && winner.observations() >= 2
                                && !instanceId.equals(winner.instanceId());
                        if (!capability.contains(channel) || transfer) {
                            draining.add(channel);
                            if (!inFlight.test(lease.key())) {
                                release.add(lease);
                            }
                        } else {
                            renew.add(lease);
                        }
                    });
            for (String channel : capability.stream().sorted().toList()) {
                Winner winner = last.winners().get(channel);
                if (!last.snapshot().getLeases().containsKey(channel)
                        && winner != null && instanceId.equals(winner.instanceId())) {
                    acquire.add(channel);
                }
            }
        }
        var command = OutboxQueueCoordinationCommand.builder().shardSlot(slot).instanceId(instanceId)
                .channels(capability).acquire(Set.copyOf(acquire)).renew(List.copyOf(renew))
                .release(List.copyOf(release)).build();
        try {
            OutboxQueueCoordinationBO snapshot = store.coordinate(command);
            Map<String, Winner> next = new TreeMap<>();
            Map<OutboxQueueKey, OutboxQueueLease> owned = new java.util.LinkedHashMap<>();
            for (String channel : capability.stream().sorted().toList()) {
                OutboxQueueKey key = new OutboxQueueKey(slot, channel);
                String preferred = preferred(key, snapshot.getMembers());
                Winner prior = last == null ? null : last.winners().get(channel);
                if (preferred != null) {
                    next.put(channel, new Winner(preferred,
                            prior != null && preferred.equals(prior.instanceId()) ? Math.min(2, prior.observations() + 1) : 1));
                }
                OutboxQueueLease lease = snapshot.getLeases().get(channel);
                if (lease != null && instanceId.equals(lease.instanceId()) && !draining.contains(channel)) {
                    owned.put(key, lease);
                }
            }
            views.put(slot, new View(snapshot, Map.copyOf(next), Map.copyOf(owned)));
        } catch (RuntimeException failure) {
            views.remove(slot);
            log.warn("Outbox queue heartbeat failed, slot={}, failureType={}", slot, failure.getClass().getSimpleName());
        }
    }

    Map<OutboxQueueKey, OutboxQueueLease> owned(int slot) {
        View view = views.get(slot);
        return view == null ? Map.of() : view.owned();
    }

    void release(int slot) {
        View view = views.remove(slot);
        List<OutboxQueueLease> leases = view == null ? List.of() : view.snapshot().getLeases().values().stream()
                .filter(lease -> instanceId.equals(lease.instanceId())).toList();
        try {
            store.coordinate(OutboxQueueCoordinationCommand.builder().shardSlot(slot).instanceId(instanceId)
                    .draining(true).release(leases).build());
        } catch (RuntimeException failure) {
            log.warn("Outbox queue release left to expiry, slot={}, failureType={}",
                    slot, failure.getClass().getSimpleName());
        }
    }

    private static String preferred(OutboxQueueKey key, List<OutboxQueueCoordinationBO.Member> members) {
        String winner = null;
        String best = "";
        for (OutboxQueueCoordinationBO.Member member : members) {
            if (!member.channels().contains(key.channel())) {
                continue;
            }
            String hash = score(key, member.instanceId());
            int comparison = hash.compareTo(best);
            if (comparison > 0 || comparison == 0 && member.instanceId().compareTo(winner) < 0) {
                winner = member.instanceId();
                best = hash;
            }
        }
        return winner;
    }

    private static String score(OutboxQueueKey key, String instance) {
        try {
            var sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(Integer.toString(key.shardSlot()).getBytes(StandardCharsets.UTF_8));
            sha256.update((byte) 0);
            sha256.update(key.channel().getBytes(StandardCharsets.UTF_8));
            sha256.update((byte) 0);
            return HexFormat.of().formatHex(sha256.digest(instance.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Required SHA-256 digest is unavailable", failure);
        }
    }

    private record Winner(String instanceId, int observations) { }

    private record View(OutboxQueueCoordinationBO snapshot, Map<String, Winner> winners,
                        Map<OutboxQueueKey, OutboxQueueLease> owned) { }
}
