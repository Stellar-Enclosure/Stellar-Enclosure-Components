package top.egon.cola.component.outbox.dispatch;

import org.junit.jupiter.api.Test;
import top.egon.cola.component.outbox.common.exception.OutboxStorageException;
import top.egon.cola.component.outbox.delivery.DeliveryHandler;
import top.egon.cola.component.outbox.delivery.DeliveryHandlerRegistry;
import top.egon.cola.component.outbox.store.OutboxQueueCoordinationBO;
import top.egon.cola.component.outbox.store.OutboxQueueCoordinationCommand;
import top.egon.cola.component.outbox.store.OutboxQueueKey;
import top.egon.cola.component.outbox.store.OutboxQueueLease;
import top.egon.cola.component.outbox.store.OutboxStore;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxQueueCoordinatorTest {

    @Test
    void two_shards_rank_three_heterogeneous_members_from_a_full_committed_snapshot() throws Exception {
        CoordinationStore fixture = new CoordinationStore(Set.of("http", "rabbitmq"));
        fixture.competitors = Map.of("http-only", Set.of("http"), "rabbit-only", Set.of("rabbitmq"));
        Object coordinator = coordinator(fixture.store, fixture.channels);
        for (int slot : List.of(0, 1)) {
            refresh(coordinator, slot, key -> false);
            assertThat(fixture.last(slot).getAcquire()).as("first tick declares capability").isEmpty();
            refresh(coordinator, slot, key -> false);
            Set<String> expected = fixture.channels.stream()
                    .filter(channel -> fixture.preferred(slot, channel).equals(fixture.instanceId))
                    .collect(java.util.stream.Collectors.toSet());
            assertThat(fixture.last(slot).getAcquire()).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(owned(coordinator, slot).keySet())
                    .containsExactlyInAnyOrderElementsOf(expected.stream()
                            .map(channel -> new OutboxQueueKey(slot, channel)).toList());
        }
        fixture.reverseSnapshot = true;
        for (int slot : List.of(0, 1)) {
            Map<OutboxQueueKey, OutboxQueueLease> before = owned(coordinator, slot);
            refresh(coordinator, slot, key -> false);
            assertThat(owned(coordinator, slot).keySet()).isEqualTo(before.keySet());
            assertThat(fixture.last(slot).getRelease()).isEmpty();
        }
    }

    @Test
    void a_better_candidate_needs_two_prior_views_and_release_waits_for_active_claims() throws Exception {
        CoordinationStore fixture = new CoordinationStore(Set.of("http"));
        Object coordinator = coordinator(fixture.store, fixture.channels);
        refresh(coordinator, 0, key -> false);
        refresh(coordinator, 0, key -> false);
        OutboxQueueKey key = new OutboxQueueKey(0, "http");
        OutboxQueueLease original = owned(coordinator, 0).get(key);
        assertThat(original).isNotNull();
        String candidate = betterCandidate(0, "http", fixture.instanceId);
        fixture.competitors = Map.of(candidate, Set.of("http"));

        refresh(coordinator, 0, queue -> false);
        assertThat(fixture.last(0).getRelease()).isEmpty();
        refresh(coordinator, 0, queue -> false);
        assertThat(fixture.last(0).getRelease()).isEmpty();
        refresh(coordinator, 0, queue -> true);
        assertThat(fixture.last(0).getRelease()).isEmpty();
        assertThat(owned(coordinator, 0)).isEmpty();
        refresh(coordinator, 0, queue -> false);
        assertThat(fixture.last(0).getRelease()).containsExactly(original);
        assertThat(owned(coordinator, 0)).isEmpty();
    }

    @Test
    void renewal_loss_and_unknown_commit_close_cached_ownership_instead_of_using_old_epochs() throws Exception {
        CoordinationStore fixture = new CoordinationStore(Set.of("http"));
        Object coordinator = coordinator(fixture.store, fixture.channels);
        refresh(coordinator, 0, key -> false);
        refresh(coordinator, 0, key -> false);
        OutboxQueueKey key = new OutboxQueueKey(0, "http");
        OutboxQueueLease old = owned(coordinator, 0).get(key);
        fixture.leases.put(key, new OutboxQueueLease(key, "successor", old.epoch() + 1, Instant.now().plusSeconds(60)));
        fixture.epochs.put(key, old.epoch() + 1);
        refresh(coordinator, 0, queue -> false);
        assertThat(owned(coordinator, 0)).isEmpty();

        fixture.leases.clear();
        refresh(coordinator, 0, queue -> false);
        assertThat(owned(coordinator, 0)).isEmpty();
        refresh(coordinator, 0, queue -> false);
        assertThat(owned(coordinator, 0)).containsKey(key);
        fixture.failNext = true;
        try {
            refresh(coordinator, 0, queue -> false);
        } catch (OutboxStorageException expected) {
            // Both propagation and local logging are compatible; retaining authority is not.
        }
        assertThat(owned(coordinator, 0)).isEmpty();
    }

    @Test
    void release_drains_the_member_and_preserves_the_last_epoch_evidence() throws Exception {
        CoordinationStore fixture = new CoordinationStore(Set.of("http"));
        Object coordinator = coordinator(fixture.store, fixture.channels);
        refresh(coordinator, 0, key -> false);
        refresh(coordinator, 0, key -> false);
        OutboxQueueLease current = owned(coordinator, 0).values().iterator().next();
        invoke(coordinator, "release", new Class<?>[]{int.class}, 0);
        assertThat(fixture.last(0).isDraining()).isTrue();
        assertThat(fixture.last(0).getRelease()).containsExactly(current);
        assertThat(owned(coordinator, 0)).isEmpty();
        assertThat(fixture.epochs.get(current.key())).isEqualTo(current.epoch());
    }

    private static Object coordinator(OutboxStore store, Set<String> channels) throws Exception {
        List<DeliveryHandler> handlers = new ArrayList<>();
        for (String channel : channels) {
            DeliveryHandler handler = mock(DeliveryHandler.class);
            when(handler.channel()).thenReturn(channel);
            handlers.add(handler);
        }
        return Class.forName("top.egon.cola.component.outbox.dispatch.OutboxQueueCoordinator")
                .getConstructor(OutboxStore.class, DeliveryHandlerRegistry.class, OutboxWorkerIdentity.class)
                .newInstance(store, new DeliveryHandlerRegistry(handlers), new OutboxWorkerIdentity("coordinator-test"));
    }

    private static void refresh(Object coordinator, int slot, Predicate<OutboxQueueKey> inFlight) throws Exception {
        invoke(coordinator, "refresh", new Class<?>[]{int.class, Predicate.class}, slot, inFlight);
    }

    @SuppressWarnings("unchecked")
    private static Map<OutboxQueueKey, OutboxQueueLease> owned(Object coordinator, int slot) throws Exception {
        return Map.copyOf((Map<OutboxQueueKey, OutboxQueueLease>) invoke(coordinator, "owned", new Class<?>[]{int.class}, slot));
    }

    private static Object invoke(Object target, String name, Class<?>[] parameters, Object... arguments) throws Exception {
        try {
            Method method = target.getClass().getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }

    private static String betterCandidate(int slot, String channel, String current) throws Exception {
        for (int index = 0; index < 100_000; index++) {
            String candidate = "candidate-" + index;
            if (weight(slot, channel, candidate).compareTo(weight(slot, channel, current)) > 0) return candidate;
        }
        throw new AssertionError("Could not generate a deterministic better candidate");
    }

    private static String weight(int slot, String channel, String instance) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((slot + "\0" + channel + "\0" + instance).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class CoordinationStore {
        final OutboxStore store = mock(OutboxStore.class);
        final Set<String> channels;
        final List<OutboxQueueCoordinationCommand> commands = new ArrayList<>();
        final Map<OutboxQueueKey, OutboxQueueLease> leases = new HashMap<>();
        final Map<OutboxQueueKey, Long> epochs = new HashMap<>();
        Map<String, Set<String>> competitors = Map.of();
        String instanceId;
        boolean reverseSnapshot;
        boolean failNext;

        CoordinationStore(Set<String> channels) {
            this.channels = channels;
            when(store.dispatchSlots()).thenReturn(List.of(0, 1));
            when(store.coordinate(any())).thenAnswer(invocation -> coordinate(invocation.getArgument(0)));
        }

        private OutboxQueueCoordinationBO coordinate(OutboxQueueCoordinationCommand command) {
            commands.add(OutboxQueueCoordinationCommand.builder().shardSlot(command.getShardSlot())
                    .instanceId(command.getInstanceId()).channels(Set.copyOf(command.getChannels()))
                    .acquire(Set.copyOf(command.getAcquire())).renew(List.copyOf(command.getRenew()))
                    .release(List.copyOf(command.getRelease())).draining(command.isDraining()).build());
            instanceId = command.getInstanceId();
            if (failNext) {
                failNext = false;
                throw new OutboxStorageException("OUTBOX_COORDINATION_TRANSACTION_INDETERMINATE");
            }
            Instant now = Instant.now();
            for (OutboxQueueLease release : command.getRelease()) {
                if (release.equals(leases.get(release.key()))) leases.remove(release.key());
            }
            for (String channel : command.getAcquire()) {
                OutboxQueueKey key = new OutboxQueueKey(command.getShardSlot(), channel);
                if (!leases.containsKey(key)) {
                    long epoch = epochs.merge(key, 1L, Long::sum);
                    leases.put(key, new OutboxQueueLease(key, instanceId, epoch, now.plusSeconds(60)));
                }
            }
            List<OutboxQueueCoordinationBO.Member> members = new ArrayList<>();
            members.add(new OutboxQueueCoordinationBO.Member(instanceId, channels, now.plusSeconds(60)));
            competitors.forEach((id, supported) -> members.add(
                    new OutboxQueueCoordinationBO.Member(id, supported, now.plusSeconds(60))));
            if (reverseSnapshot) java.util.Collections.reverse(members);
            Map<String, OutboxQueueLease> active = new LinkedHashMap<>();
            leases.forEach((key, lease) -> {
                if (key.shardSlot() == command.getShardSlot()) active.put(key.channel(), lease);
            });
            return OutboxQueueCoordinationBO.builder().databaseTime(now).members(List.copyOf(members)).leases(Map.copyOf(active)).build();
        }

        OutboxQueueCoordinationCommand last(int slot) {
            return commands.reversed().stream().filter(command -> command.getShardSlot() == slot).findFirst().orElseThrow();
        }

        String preferred(int slot, String channel) {
            List<String> candidates = new ArrayList<>();
            if (channels.contains(channel)) candidates.add(instanceId);
            competitors.forEach((id, supported) -> { if (supported.contains(channel)) candidates.add(id); });
            return candidates.stream().sorted(Comparator.<String, String>comparing(id -> weight(slot, channel, id))
                    .reversed().thenComparing(Comparator.naturalOrder())).findFirst().orElseThrow();
        }
    }
}
