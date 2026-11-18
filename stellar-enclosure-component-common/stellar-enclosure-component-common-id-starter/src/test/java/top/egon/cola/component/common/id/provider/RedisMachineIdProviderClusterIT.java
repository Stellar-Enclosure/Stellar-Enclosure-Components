package top.egon.cola.component.common.id.provider;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.redisson.Redisson;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.redisson.config.ReadMode;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import top.egon.cola.component.common.id.snowflake.SnowflakeLongIdGenerator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Explicit, unskipped tests for one registered privately owned Redis 7.4.12 Cluster.
 * Requires -Degon.id.redis.cluster.registration=/absolute/private/cluster.properties.
 * Only the exact registered 1025-key synthetic pool is seeded/cleaned; Redis processes belong to the harness.
 * Re-seeding between isolated cases is a test fixture operation, never a production recovery procedure.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class RedisMachineIdProviderClusterIT {

    private static final String REGISTRATION_PROPERTY = "egon.id.redis.cluster.registration";
    private static final long EPOCH_MILLIS = 1_767_225_600_000L;
    private static final Duration LEASE = Duration.ofSeconds(3);
    private static final Duration RENEW = Duration.ofMillis(500);
    private static Registration fixture;
    private static RedissonClient stable;
    private static RedissonClient canary;
    private static final List<RedisMachineIdProvider> providers = new ArrayList<>();

    @BeforeAll
    static void verifyTheExplicitRegisteredClusterBeforeCreatingAnyClientOrWriting() throws Exception {
        fixture = Registration.load();
        fixture.verify();
        fixture.verifyAcl();
        stable = fixture.client();
        canary = fixture.client();
    }

    @BeforeEach
    void seedOnlyTheRegisteredSyntheticPersistentPool() throws Exception {
        fixture.verify();
        fixture.seed();
    }

    @AfterEach
    void stopOnlyThisCasesProvidersBeforeAnotherFixtureSeed() {
        for (RedisMachineIdProvider provider : providers) { provider.close(); }
        providers.clear();
    }

    @AfterAll
    static void closeClientsAndCleanOnlyTheRegisteredExplicitKeys() throws Exception {
        stopProviders();
        try {
            if (stable != null) { stable.shutdown(0, 2, TimeUnit.SECONDS); }
        } finally {
            try {
                if (canary != null) { canary.shutdown(0, 2, TimeUnit.SECONDS); }
            } finally {
                if (fixture != null && fixture.seeded) {
                    fixture.keyCommand("DEL", fixture.keys.toArray(String[]::new));
                }
            }
        }
    }

    @Test
    @Order(1)
    void realClusterEnforcesSameSlotDeclaredKeysAclAndMovedRouting() throws Exception {
        int slot = fixture.slot();
        for (String key : fixture.keys) {
            assertThat(fixture.number(fixture.command(fixture.nodes.getFirst(), "CLUSTER", "KEYSLOT", key)))
                    .isEqualTo(slot);
            assertThat(fixture.number(fixture.keyCommand("PTTL", key))).isEqualTo(-1);
        }
        Node primary = fixture.primary();
        String outside = fixture.prefix + "not-authorized";
        try (RespConnection app = fixture.connection(primary, false)) {
            assertThatThrownBy(() -> app.command("GET", outside)).hasMessageStartingWith("NOPERM");
            assertThatThrownBy(() -> app.command("EVAL", "return 1", "1", outside))
                    .hasMessageStartingWith("NOPERM");
        }
        String cursor = fixture.string(fixture.keyCommand("GET", fixture.keys.getFirst()));
        Object rejected = stable.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
                RedisMachineIdProvider.ACQUIRE_SCRIPT, RScript.ReturnType.LIST,
                new ArrayList<Object>(fixture.keys.subList(0, 1024)), "short-keys", "3000");
        assertThat(tuple(rejected).getFirst()).isNotEqualTo(1L);
        assertThat(fixture.string(fixture.keyCommand("GET", fixture.keys.getFirst()))).isEqualTo(cursor);

        Node wrong = fixture.nodes.stream().filter(node -> !node.equals(primary)).findFirst().orElseThrow();
        try (RespConnection app = fixture.connection(wrong, false)) {
            List<String> command = new ArrayList<>(List.of("EVAL", RedisMachineIdProvider.ACQUIRE_SCRIPT, "1025"));
            command.addAll(fixture.keys);
            command.addAll(List.of("moved-owner", "3000"));
            assertThatThrownBy(() -> app.command(command.toArray(String[]::new)))
                    .hasMessageStartingWith("MOVED " + slot + " 127.0.0.1:" + primary.port);
        }
        RedisMachineIdProvider provider = provider(stable, "cluster-routed");
        assertThat(provider.getMachineId()).isZero();
        provider.assertIssuanceAvailable();
    }

    @Test
    @Order(2)
    void actualSlot1023AllocationAndAll1024OccupiedSlotsRejectOverflow() throws Exception {
        long now = fixture.redisMillis();
        fixture.fillOccupied(now + 60_000);
        fixture.keyCommand("SET", fixture.slotKey(1023), "F|0");
        fixture.keyCommand("SET", fixture.keys.getFirst(), "V1|" + fixture.authority(fixture.primary()) + "|1023");
        RedisMachineIdProvider last = provider(stable, "last-capacity-slot");
        assertThat(last.getMachineId()).isEqualTo(1023);
        assertThatThrownBy(() -> provider(canary, "overflow-owner").getMachineId()).isInstanceOf(RuntimeException.class);
        List<Object> states = fixture.array(fixture.keyCommand("MGET", fixture.keys.subList(1, 1025).toArray(String[]::new)));
        assertThat(states).hasSize(1024).allSatisfy(value -> assertThat(fixture.string(value)).startsWith("A|"));
        assertThat(fixture.keys.stream().distinct().count()).isEqualTo(1025);
    }

    @Test
    @Order(3)
    void twoIndependentJvmsGenerateCompleteIdsWithoutOverlapInTheSamePool() throws Exception {
        try (Child first = new Child("stable-process"); Child second = new Child("canary-process")) {
            var readers = Executors.newFixedThreadPool(2);
            try {
            Future<ChildResult> firstResult = readers.submit(first::collect);
            Future<ChildResult> secondResult = readers.submit(second::collect);
            assertThat(first.ready.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(second.ready.await(15, TimeUnit.SECONDS)).isTrue();
            first.start();
            second.start();
            ChildResult a = firstResult.get(20, TimeUnit.SECONDS);
            ChildResult b = secondResult.get(20, TimeUnit.SECONDS);
            assertThat(a.machineId).isNotEqualTo(b.machineId);
            Set<Long> complete = new HashSet<>(a.ids);
            assertThat(complete).hasSize(10_000);
            assertThat(b.ids).hasSize(10_000);
            for (long id : b.ids) { assertThat(complete.add(id)).isTrue(); }
            assertThat(complete).hasSize(20_000).allSatisfy(id -> assertThat(id).isPositive());
            for (ChildResult result : List.of(a, b)) {
                assertThat(result.ids).allSatisfy(id -> assertThat((id >>> 12) & 1023).isEqualTo(result.machineId));
                String[] released = fixture.string(fixture.keyCommand("GET", fixture.slotKey(result.machineId))).split("\\|");
                assertThat(released[0]).isEqualTo("R");
                long upper = Long.parseLong(released[1]);
                assertThat(result.ids).allSatisfy(id -> assertThat(timestamp(id)).isLessThanOrEqualTo(upper));
                assertThat(fixture.number(fixture.keyCommand("PTTL", fixture.slotKey(result.machineId)))).isEqualTo(-1);
            }
            } finally { readers.shutdownNow(); }
        }
    }

    @Test
    @Order(4)
    void stoppedOwnerIsFencedAndReuseStrictlyPassesItsPersistentIssuanceWatermark() throws Exception {
        RedisMachineIdProvider old = provider(stable, "old-owner");
        long slot = old.getMachineId();
        SnowflakeLongIdGenerator oldEngine = new SnowflakeLongIdGenerator(slot);
        Set<Long> oldIds = new HashSet<>();
        for (int count = 0; count < 1000; count++) { oldIds.add(old.issue(oldEngine::nextLongId, RedisMachineIdProviderClusterIT::timestamp)); }
        old.close();
        assertThatThrownBy(old::assertIssuanceAvailable).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> old.issue(oldEngine::nextLongId, RedisMachineIdProviderClusterIT::timestamp))
                .isInstanceOf(RuntimeException.class);
        String[] released = fixture.string(fixture.keyCommand("GET", fixture.slotKey(slot))).split("\\|");
        assertThat(released[0]).isEqualTo("R");
        long upper = Long.parseLong(released[1]);
        assertThat(oldIds).allSatisfy(id -> assertThat(timestamp(id)).isLessThanOrEqualTo(upper));
        fixture.fillOtherSlots(slot, fixture.redisMillis() + 60_000);
        fixture.keyCommand("SET", fixture.keys.getFirst(), "V1|" + fixture.authority(fixture.primary()) + "|" + slot);
        assertThat(fixture.redisMillis()).as("fixture checks before the protected old upper bound").isLessThanOrEqualTo(upper);
        assertThatThrownBy(() -> provider(canary, "too-early").getMachineId()).isInstanceOf(RuntimeException.class);
        await(Duration.ofSeconds(6), () -> fixture.redisMillis() > upper, "Redis time must strictly pass old issue upper");
        RedisMachineIdProvider replacement = provider(canary, "replacement-owner");
        assertThat(replacement.getMachineId()).isEqualTo(slot);
        String[] active = fixture.string(fixture.keyCommand("GET", fixture.slotKey(slot))).split("\\|");
        assertThat(Long.parseLong(active[3])).isEqualTo(upper + 1);
        SnowflakeLongIdGenerator newEngine = new SnowflakeLongIdGenerator(slot);
        for (int count = 0; count < 1000; count++) {
            long id = replacement.issue(newEngine::nextLongId, RedisMachineIdProviderClusterIT::timestamp);
            assertThat(timestamp(id)).isGreaterThan(upper);
            assertThat(oldIds).doesNotContain(id);
        }
    }

    @Test
    @Order(5)
    void ownershipConflictFencesTheOldProcessAndConditionalCloseRetainsTheNewOwner() throws Exception {
        RedisMachineIdProvider provider = provider(stable, "former-owner");
        long slot = provider.getMachineId();
        String[] active = fixture.string(fixture.keyCommand("GET", fixture.slotKey(slot))).split("\\|");
        active[1] = "replacement-owner";
        String replacement = String.join("|", active);
        fixture.keyCommand("SET", fixture.slotKey(slot), replacement);
        assertThat(provider.renewOnce()).isNotEqualTo(1);
        assertThatThrownBy(provider::assertIssuanceAvailable).isInstanceOf(RuntimeException.class);
        provider.close();
        assertThat(fixture.string(fixture.keyCommand("GET", fixture.slotKey(slot)))).isEqualTo(replacement);
        assertThat(fixture.number(fixture.keyCommand("PTTL", fixture.slotKey(slot)))).isEqualTo(-1);
    }

    @Test
    @Order(6)
    void scriptCacheFlushReloadsSafelyAndMissingPersistentStateCannotBecomeAnEmptyPool() throws Exception {
        RedisMachineIdProvider provider = provider(stable, "script-owner");
        provider.getMachineId();
        String renewSha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1")
                .digest(RedisMachineIdProvider.RENEW_SCRIPT.getBytes(StandardCharsets.UTF_8)));
        for (Node node : fixture.nodes) {
            assertThat(fixture.command(node, "SCRIPT", "FLUSH", "SYNC")).isEqualTo("OK");
            assertThat(fixture.array(fixture.command(node, "SCRIPT", "EXISTS", renewSha))).containsExactly(0L);
        }
        assertThat(provider.renewOnce()).isEqualTo(1);
        provider.assertIssuanceAvailable();
        assertThat(provider(canary, "after-script-flush").getMachineId()).isEqualTo(1);
        String cursor = fixture.string(fixture.keyCommand("GET", fixture.keys.getFirst()));
        assertThat(fixture.keyCommand("PEXPIRE", fixture.slotKey(512), "60000")).isEqualTo(1L);
        assertThatThrownBy(() -> provider(canary, "expiring-pool-entry").getMachineId()).isInstanceOf(RuntimeException.class);
        assertThat(fixture.string(fixture.keyCommand("GET", fixture.keys.getFirst()))).isEqualTo(cursor);
        assertThat(fixture.keyCommand("PERSIST", fixture.slotKey(512))).isEqualTo(1L);
        fixture.keyCommand("DEL", fixture.slotKey(512));
        assertThatThrownBy(() -> provider(canary, "missing-pool-entry").getMachineId()).isInstanceOf(RuntimeException.class);
        assertThat(fixture.string(fixture.keyCommand("GET", fixture.keys.getFirst()))).isEqualTo(cursor);
        assertThat(fixture.keyCommand("GET", fixture.slotKey(512))).isNull();
    }

    @Test
    @Order(7)
    void unavailableRedisConsumesOnlyTheLastConfirmedBudgetAndNeverRestoresTheOldProvider() throws Exception {
        RedissonClient interrupted = fixture.client();
        RedisMachineIdProvider provider = provider(interrupted, "disconnected-owner");
        try {
            provider.getMachineId();
            interrupted.shutdown(0, 1, TimeUnit.SECONDS);
            await(Duration.ofSeconds(6), () -> {
                try { provider.assertIssuanceAvailable(); return false; }
                catch (RuntimeException fenced) { return true; }
            }, "issuance must stop when its last confirmed lease budget expires");
            assertThat(provider.renewOnce()).isNotEqualTo(1);
            assertThatThrownBy(provider::assertIssuanceAvailable).isInstanceOf(RuntimeException.class);
        } finally {
            provider.close();
            interrupted.shutdown(0, 1, TimeUnit.SECONDS);
        }
    }

    @Test
    @Order(8)
    void controlledReplicaPromotionKeepsTheOldRunIdMarkerFrozenAndRejectsOldAndNewIssuers() throws Exception {
        RedisMachineIdProvider provider = provider(stable, "pre-promotion-owner");
        provider.getMachineId();
        Node previous = fixture.primary();
        Node replica = fixture.replicaOf(previous);
        String cursor = fixture.string(fixture.keyCommand("GET", fixture.keys.getFirst()));
        String originalAuthority = cursor.split("\\|")[1];
        fixture.awaitStableReplication(previous, replica, 0);
        long firstBarrier = fixture.replicationBarrier(previous, cursor);
        fixture.awaitStableReplication(previous, replica, firstBarrier);
        assertThat(fixture.command(replica, "CLUSTER", "FAILOVER")).isEqualTo("OK");
        await(Duration.ofSeconds(20), () -> fixture.primary().equals(replica), "registered replica must own the pool slot");
        assertThat(replica.runId).isNotEqualTo(previous.runId);
        fixture.awaitStableReplication(replica, previous, firstBarrier);
        fixture.assertAuthorityMismatch("direct-post-promotion-owner");
        assertThat(provider.renewOnce()).isNotEqualTo(1);
        assertThatThrownBy(provider::assertIssuanceAvailable).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> provider(canary, "post-promotion-owner").getMachineId()).isInstanceOf(RuntimeException.class);
        assertThat(fixture.string(fixture.keyCommand("GET", fixture.keys.getFirst()))).isEqualTo(cursor)
                .contains("|" + originalAuthority + "|");
        fixture.verify();
        // Fail back to the same Redis process: unchanged run_id alone cannot authenticate a new primary term.
        // INFO can report an attached replica before the new primary has accepted the inverse cluster relationship.
        long secondBarrier = fixture.replicationBarrier(replica, cursor);
        fixture.awaitStableReplication(replica, previous, secondBarrier);
        assertThat(fixture.command(previous, "CLUSTER", "FAILOVER")).isEqualTo("OK");
        await(Duration.ofSeconds(20), () -> fixture.primary().equals(previous), "same owned process must become primary again");
        fixture.awaitStableReplication(previous, replica, secondBarrier);
        assertThat(fixture.authority(previous)).startsWith(previous.runId + ":").isNotEqualTo(originalAuthority);
        fixture.assertAuthorityMismatch("direct-post-failback-owner");
        assertThatThrownBy(() -> provider(canary, "post-failback-owner").getMachineId()).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(provider::assertIssuanceAvailable).isInstanceOf(RuntimeException.class);
        assertThat(fixture.string(fixture.keyCommand("GET", fixture.keys.getFirst()))).isEqualTo(cursor);
        fixture.verify();
    }

    private static RedisMachineIdProvider provider(RedissonClient client, String owner) {
        RedisMachineIdProvider provider = new RedisMachineIdProvider(client, fixture.env, fixture.app, LEASE, RENEW, owner);
        providers.add(provider);
        return provider;
    }

    private static void stopProviders() {
        for (RedisMachineIdProvider provider : providers) { provider.close(); }
        providers.clear();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> tuple(Object raw) { return (List<Object>) raw; }
    private static long timestamp(long id) { return EPOCH_MILLIS + (id >>> 22); }

    private static void await(Duration timeout, BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            try { if (condition.getAsBoolean()) { return; } }
            catch (RuntimeException transientTopology) { /* Retry only bounded fixture observations. */ }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(message);
    }

    /** Each child has its own production static engine and uses only the registered synthetic app credentials. */
    public static final class GeneratorProcess {
        public static void main(String[] args) throws Exception {
            Registration registration = Registration.load();
            registration.verify();
            RedissonClient client = registration.client();
            try (RedisMachineIdProvider provider = new RedisMachineIdProvider(client, registration.env, registration.app,
                    LEASE, RENEW, args[0])) {
                long machineId = provider.getMachineId();
                SnowflakeIdGenerator.initialize(machineId, Duration.ofMillis(5), provider);
                System.out.println("ID_CLUSTER_READY " + machineId);
                if (!"GO".equals(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine())) {
                    throw new IllegalStateException("owned child requires its explicit start gate");
                }
                StringBuilder ids = new StringBuilder("ID_CLUSTER_IDS ");
                for (int count = 0; count < 10_000; count++) {
                    if (count > 0) { ids.append(','); }
                    ids.append(count % 2 == 0 ? SnowflakeIdGenerator.nextLongId() : Long.parseLong(SnowflakeIdGenerator.nextId()));
                }
                provider.close();
                boolean fenced;
                try { SnowflakeIdGenerator.nextLongId(); fenced = false; }
                catch (RuntimeException expected) { fenced = true; }
                if (!fenced) { throw new AssertionError("closed child returned another ID"); }
                System.out.println(ids);
                System.out.println("ID_CLUSTER_CLOSED");
            } finally {
                client.shutdown(0, 2, TimeUnit.SECONDS);
            }
        }
    }

    private static final class Child implements AutoCloseable {
        private final Process process;
        private final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);

        private Child(String owner) throws IOException {
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            if (classpath == null || classpath.isBlank()) { throw new IllegalStateException("explicit test classpath is required"); }
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-D" + REGISTRATION_PROPERTY + "=" + fixture.registration,
                    "-cp", classpath, GeneratorProcess.class.getName(), owner).redirectErrorStream(true).start();
        }

        private void start() {
            PrintWriter input = new PrintWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8), true);
            input.println("GO");
        }

        private ChildResult collect() throws Exception {
            long machineId = -1;
            List<Long> ids = new ArrayList<>();
            boolean closed = false;
            try (BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = output.readLine()) != null) {
                    if (line.startsWith("ID_CLUSTER_READY ")) {
                        machineId = Long.parseLong(line.substring("ID_CLUSTER_READY ".length()));
                        ready.countDown();
                    } else if (line.startsWith("ID_CLUSTER_IDS ")) {
                        assertThat(line.length()).isLessThan(300_000);
                        for (String value : line.substring("ID_CLUSTER_IDS ".length()).split(",")) { ids.add(Long.parseLong(value)); }
                    } else if (line.equals("ID_CLUSTER_CLOSED")) { closed = true; }
                }
            }
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            assertThat(machineId).isBetween(0L, 1023L);
            assertThat(closed).isTrue();
            assertThat(ids).hasSize(10_000);
            return new ChildResult(machineId, List.copyOf(ids));
        }

        @Override
        public void close() throws IOException, InterruptedException {
            process.getOutputStream().close();
            if (process.isAlive()) { process.destroy(); }
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertThat(process.waitFor(3, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private record ChildResult(long machineId, List<Long> ids) {}
    private record Node(int port, int busPort, long pid, String runId, String nodeId) {}

    private static final class Registration {
        private final Path registration;
        private final String env;
        private final String app;
        private final String prefix;
        private final String appUser;
        private final String appPassword;
        private final String adminUser;
        private final String adminPassword;
        private final List<Node> nodes;
        private final List<String> keys;
        private boolean verified;
        private boolean seeded;

        private Registration(Path registration, Properties values) throws IOException {
            this.registration = registration;
            assertThat(required(values, "registration.version")).isEqualTo("1");
            assertThat(required(values, "redis.version")).isEqualTo("7.4.12");
            String identity = required(values, "fixture.id");
            UUID.fromString(identity);
            Path root = Path.of(required(values, "fixture.root")).toRealPath();
            assertThat(root).isEqualTo(registration.getParent());
            assertThat(Files.readString(root.resolve("fixture.owner")).trim()).isEqualTo(identity);
            env = required(values, "env");
            app = required(values, "app");
            assertThat(env).matches("[A-Za-z0-9_-]{1,80}");
            assertThat(app).matches("[A-Za-z0-9_-]{1,80}");
            prefix = "egon:cola:id:{" + env + ":" + app + "}:";
            appUser = required(values, "app.user");
            appPassword = required(values, "app.password");
            assertThat(appUser).isNotEqualTo("default");
            adminUser = required(values, "admin.user");
            adminPassword = values.getProperty("admin.password", "");
            List<Node> registered = new ArrayList<>();
            Set<Integer> ports = new HashSet<>();
            for (int index = 0; index < 6; index++) {
                String name = "node." + index + ".";
                assertThat(required(values, name + "host")).isEqualTo("127.0.0.1");
                int port = Integer.parseInt(required(values, name + "port"));
                int bus = Integer.parseInt(required(values, name + "bus-port"));
                for (int candidate : List.of(port, bus)) {
                    assertThat(candidate).isBetween(1024, 65535).isNotIn(6379, 16379, 26379);
                    assertThat(ports.add(candidate)).isTrue();
                }
                long pid = Long.parseLong(required(values, name + "pid"));
                assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isTrue();
                String runId = required(values, name + "run-id");
                String nodeId = required(values, name + "node-id");
                assertThat(runId).matches("[0-9a-f]{40}");
                assertThat(nodeId).matches("[0-9a-f]{40}");
                registered.add(new Node(port, bus, pid, runId, nodeId));
            }
            nodes = List.copyOf(registered);
            List<String> owned = new ArrayList<>(List.of(prefix + "cursor"));
            for (int slot = 0; slot < 1024; slot++) { owned.add(slotKey(slot)); }
            keys = List.copyOf(owned);
        }

        private static Registration load() throws IOException {
            String configured = System.getProperty(REGISTRATION_PROPERTY);
            if (configured == null || configured.isBlank()) {
                throw new IllegalStateException("Explicit owned Redis Cluster registration is required; no default target is allowed");
            }
            Path file = Path.of(configured);
            if (!file.isAbsolute() || !Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
                throw new IllegalArgumentException("registration must be an absolute regular private fixture file");
            }
            assertThat(Files.getPosixFilePermissions(file)).isEqualTo(Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            Properties values = new Properties();
            try (var stream = Files.newInputStream(file)) { values.load(stream); }
            return new Registration(file.toRealPath(), values);
        }

        private void verify() throws IOException {
            Set<String> expected = new HashSet<>(nodes.stream().map(Node::nodeId).toList());
            Set<String> observed = new HashSet<>();
            int masters = 0;
            for (Node node : nodes) {
                Map<String, String> info = info(string(command(node, "INFO", "server")));
                assertThat(info.get("redis_version")).isEqualTo("7.4.12");
                assertThat(info.get("redis_mode")).isEqualTo("cluster");
                assertThat(info.get("run_id")).isEqualTo(node.runId);
                assertThat(info.get("process_id")).isEqualTo(Long.toString(node.pid));
                assertThat(ProcessHandle.of(node.pid).map(ProcessHandle::isAlive).orElse(false)).isTrue();
                assertThat(string(command(node, "CLUSTER", "MYID"))).isEqualTo(node.nodeId);
                observed.add(node.nodeId);
                Map<String, String> cluster = info(string(command(node, "CLUSTER", "INFO")));
                assertThat(cluster.get("cluster_state")).isEqualTo("ok");
                assertThat(cluster.get("cluster_slots_ok")).isEqualTo("16384");
                assertThat(cluster.get("cluster_known_nodes")).isEqualTo("6");
                Map<String, String> replication = info(string(command(node, "INFO", "replication")));
                if ("master".equals(replication.get("role"))) {
                    masters++;
                    assertThat(replication.get("connected_slaves")).isEqualTo("1");
                } else {
                    assertThat(replication.get("master_link_status")).isEqualTo("up");
                    assertThat(replication.get("master_sync_in_progress")).isEqualTo("0");
                }
                List<String[]> topology = topology(node);
                assertThat(topology).hasSize(6);
                for (String[] member : topology) {
                    assertThat(expected.contains(member[0])).isTrue();
                    String address = member[1].split(",", 2)[0];
                    assertThat(nodes.stream().anyMatch(known -> address.equals("127.0.0.1:" + known.port + "@" + known.busPort))).isTrue();
                }
            }
            assertThat(masters).isEqualTo(3);
            assertThat(observed).isEqualTo(expected);
            verified = true;
        }

        private void verifyAcl() throws IOException {
            Set<String> expected = new HashSet<>(keys.stream().map(key -> "~" + key).toList());
            for (Node node : nodes) {
                List<Object> user = array(command(node, "ACL", "GETUSER", appUser));
                Object keyPatterns = field(user, "keys");
                assertThat(new HashSet<>(Arrays.asList(string(keyPatterns).trim().split("\\s+")))).isEqualTo(expected);
                assertThat(array(field(user, "selectors"))).isEmpty();
                assertThat(string(field(user, "commands"))).doesNotContain("+@all", "+@admin", "+script|flush", "+acl");
            }
        }

        private void seed() throws IOException {
            assertThat(verified).isTrue();
            List<String> pairs = new ArrayList<>(List.of(keys.getFirst(), "V1|" + authority(primary()) + "|0"));
            for (int slot = 0; slot < 1024; slot++) { pairs.addAll(List.of(slotKey(slot), "F|0")); }
            keyCommand("MSET", pairs.toArray(String[]::new));
            seeded = true;
        }

        private void fillOccupied(long upper) throws IOException { fillOtherSlots(-1, upper); }
        private void fillOtherSlots(long omitted, long upper) throws IOException {
            List<String> pairs = new ArrayList<>();
            for (int slot = 0; slot < 1024; slot++) {
                if (slot != omitted) { pairs.addAll(List.of(slotKey(slot), "A|synthetic-" + slot + "|" + upper + "|0|" + upper)); }
            }
            keyCommand("MSET", pairs.toArray(String[]::new));
        }

        private RedissonClient client() {
            Config config = new Config();
            config.setUseScriptCache(true);
            config.useClusterServers().addNodeAddress(nodes.stream().map(node -> "redis://127.0.0.1:" + node.port).toArray(String[]::new))
                    .setUsername(appUser).setPassword(appPassword).setReadMode(ReadMode.MASTER)
                    .setScanInterval(250).setConnectTimeout(750).setTimeout(1500).setRetryAttempts(0)
                    .setMasterConnectionMinimumIdleSize(1).setMasterConnectionPoolSize(3)
                    .setSlaveConnectionMinimumIdleSize(1).setSlaveConnectionPoolSize(2);
            return Redisson.create(config);
        }

        private String slotKey(long slot) { return prefix + "slot:" + slot; }
        private int slot() throws IOException { return (int) number(command(nodes.getFirst(), "CLUSTER", "KEYSLOT", keys.getFirst())); }
        private List<String[]> topology(Node node) throws IOException {
            return string(command(node, "CLUSTER", "NODES")).lines().filter(line -> !line.isBlank()).map(line -> line.split(" ")).toList();
        }

        private Node primary() {
            try {
                int slot = slot();
                for (String[] member : topology(nodes.getFirst())) {
                    if (!Arrays.asList(member[2].split(",")).contains("master")) { continue; }
                    for (int index = 8; index < member.length; index++) {
                        String value = member[index];
                        if (value.startsWith("[")) { continue; }
                        String[] range = value.split("-");
                        int from = Integer.parseInt(range[0]);
                        int to = range.length == 1 ? from : Integer.parseInt(range[1]);
                        if (slot >= from && slot <= to) {
                            return nodes.stream().filter(node -> node.nodeId.equals(member[0])).findFirst().orElseThrow();
                        }
                    }
                }
                throw new IllegalStateException("registered cluster has no current owner of the fixture slot");
            } catch (IOException failure) { throw new IllegalStateException("registered topology observation failed", failure); }
        }

        private Node replicaOf(Node primary) throws IOException {
            for (String[] member : topology(nodes.getFirst())) {
                if (member[3].equals(primary.nodeId)) {
                    return nodes.stream().filter(node -> node.nodeId.equals(member[0])).findFirst().orElseThrow();
                }
            }
            throw new IllegalStateException("registered primary requires its registered replica");
        }

        private void awaitStableReplication(Node master, Node replica, long minimumOffset) throws InterruptedException {
            long[] stableSince = {Long.MIN_VALUE};
            await(Duration.ofSeconds(20), () -> {
                boolean ready;
                try { ready = replicationReady(master, replica, minimumOffset); }
                catch (IOException | RuntimeException transientTopology) { ready = false; }
                if (!ready) {
                    stableSince[0] = Long.MIN_VALUE;
                    return false;
                }
                long now = System.nanoTime();
                if (stableSince[0] == Long.MIN_VALUE) { stableSince[0] = now; }
                return now - stableSince[0] >= Duration.ofMillis(500).toNanos();
            }, "all registered views and the exact online replica must remain converged before a controlled promotion");
        }

        private boolean replicationReady(Node master, Node replica, long minimumOffset) throws IOException {
            Map<String, String> leader = info(string(command(master, "INFO", "replication")));
            Map<String, String> follower = info(string(command(replica, "INFO", "replication")));
            if (!"master".equals(leader.get("role")) || !"1".equals(leader.get("connected_slaves"))
                    || !"slave".equals(follower.get("role")) || !"127.0.0.1".equals(follower.get("master_host"))
                    || !Integer.toString(master.port).equals(follower.get("master_port"))
                    || !"up".equals(follower.get("master_link_status"))
                    || !"0".equals(follower.get("master_sync_in_progress"))
                    || !leader.get("master_replid").equals(follower.get("master_replid"))
                    || Long.parseLong(follower.get("slave_repl_offset")) < minimumOffset) {
                return false;
            }
            String onlineReplica = leader.get("slave0");
            if (onlineReplica == null || !onlineReplica.contains("ip=127.0.0.1,")
                    || !onlineReplica.contains("port=" + replica.port + ",")
                    || !onlineReplica.contains("state=online,")) {
                return false;
            }
            int protectedSlot = slot();
            for (Node observer : nodes) {
                List<String[]> view = topology(observer);
                String[] currentMaster = view.stream().filter(member -> member[0].equals(master.nodeId))
                        .findFirst().orElseThrow();
                String[] currentReplica = view.stream().filter(member -> member[0].equals(replica.nodeId))
                        .findFirst().orElseThrow();
                if (!healthyRole(currentMaster, "master") || !"-".equals(currentMaster[3])
                        || !healthyRole(currentReplica, "slave") || !master.nodeId.equals(currentReplica[3])
                        || !ownsSlot(currentMaster, protectedSlot)
                        || view.stream().filter(member -> healthyRole(member, "master") && ownsSlot(member, protectedSlot)).count() != 1) {
                    return false;
                }
            }
            return true;
        }

        private static boolean healthyRole(String[] member, String role) {
            Set<String> flags = Set.copyOf(Arrays.asList(member[2].split(",")));
            return flags.contains(role) && flags.stream().noneMatch(flag -> flag.equals("fail")
                    || flag.equals("fail?") || flag.equals("handshake") || flag.equals("noaddr"))
                    && "connected".equals(member[7]);
        }

        private static boolean ownsSlot(String[] member, int slot) {
            for (int index = 8; index < member.length; index++) {
                if (member[index].startsWith("[")) { continue; }
                String[] range = member[index].split("-");
                int from = Integer.parseInt(range[0]);
                int to = range.length == 1 ? from : Integer.parseInt(range[1]);
                if (slot >= from && slot <= to) { return true; }
            }
            return false;
        }

        private long replicationBarrier(Node master, String expectedCursor) throws IOException {
            try (RespConnection admin = connection(master, true)) {
                assertThat(admin.command("GET", keys.getFirst())).isEqualTo(expectedCursor);
                assertThat(admin.command("SET", keys.getFirst(), expectedCursor)).isEqualTo("OK");
                long offset = Long.parseLong(info(string(admin.command("INFO", "replication"))).get("master_repl_offset"));
                assertThat(number(admin.command("WAIT", "1", "5000"))).isEqualTo(1);
                return offset;
            }
        }

        private void assertAuthorityMismatch(String owner) throws IOException {
            List<String> eval = new ArrayList<>(List.of("EVAL", RedisMachineIdProvider.ACQUIRE_SCRIPT, "1025"));
            eval.addAll(keys);
            eval.addAll(List.of(owner, Long.toString(LEASE.toMillis())));
            try (RespConnection app = connection(primary(), false)) {
                assertThat(array(app.command(eval.toArray(String[]::new)))).containsExactly(-3L);
            }
        }

        private String authority(Node node) throws IOException {
            for (String[] member : topology(node)) {
                List<String> flags = Arrays.asList(member[2].split(","));
                if (member[0].equals(node.nodeId) && flags.contains("myself") && flags.contains("master")
                        && "-".equals(member[3]) && "connected".equals(member[7])) {
                    assertThat(member[6]).matches("0|[1-9][0-9]*");
                    return node.runId + ":" + member[6];
                }
            }
            throw new IllegalStateException("registered node is not an authoritative healthy current primary");
        }

        private long redisMillis() {
            try {
                List<Object> time = array(command(primary(), "TIME"));
                return Long.parseLong(string(time.get(0))) * 1000 + Long.parseLong(string(time.get(1))) / 1000;
            } catch (IOException failure) { throw new IllegalStateException("registered Redis time unavailable", failure); }
        }

        private Object keyCommand(String name, String... arguments) throws IOException {
            List<String> command = new ArrayList<>(List.of(name));
            command.addAll(List.of(arguments));
            return command(primary(), command.toArray(String[]::new));
        }

        private Object command(Node node, String... arguments) throws IOException {
            try (RespConnection connection = connection(node, true)) { return connection.command(arguments); }
        }

        private RespConnection connection(Node node, boolean admin) throws IOException {
            assertThat(nodes.contains(node)).isTrue();
            return new RespConnection(node.port, admin ? adminUser : appUser, admin ? adminPassword : appPassword);
        }

        private static Map<String, String> info(String info) {
            var entries = new java.util.HashMap<String, String>();
            info.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).forEach(line -> {
                int separator = line.indexOf(':');
                if (separator > 0) { entries.put(line.substring(0, separator), line.substring(separator + 1)); }
            });
            return Map.copyOf(entries);
        }

        private static Object field(List<Object> fields, String name) {
            for (int index = 0; index < fields.size(); index += 2) {
                if (name.equals(fields.get(index))) { return fields.get(index + 1); }
            }
            throw new IllegalStateException("registered ACL response is incomplete");
        }

        @SuppressWarnings("unchecked") private List<Object> array(Object value) { return (List<Object>) value; }
        private String string(Object value) { return (String) value; }
        private long number(Object value) { return ((Number) value).longValue(); }
        private static String required(Properties values, String key) {
            String value = values.getProperty(key);
            if (value == null || value.isBlank()) { throw new IllegalArgumentException("missing registered fixture field: " + key); }
            return value;
        }
    }

    /** Minimal bounded RESP2 fixture administration, restricted to explicitly registered loopback nodes. */
    private static final class RespConnection implements AutoCloseable {
        private final Socket socket;
        private final BufferedInputStream input;
        private final BufferedOutputStream output;

        private RespConnection(int port, String user, String password) throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            socket.setSoTimeout(5000);
            input = new BufferedInputStream(socket.getInputStream());
            output = new BufferedOutputStream(socket.getOutputStream());
            if (!"default".equals(user) || !password.isEmpty()) { command("AUTH", user, password); }
        }

        private Object command(String... arguments) throws IOException {
            write("*" + arguments.length + "\r\n");
            for (String argument : arguments) {
                byte[] value = argument.getBytes(StandardCharsets.UTF_8);
                write("$" + value.length + "\r\n");
                output.write(value);
                write("\r\n");
            }
            output.flush();
            return read();
        }

        private void write(String value) throws IOException { output.write(value.getBytes(StandardCharsets.UTF_8)); }
        private Object read() throws IOException {
            int kind = input.read();
            if (kind < 0) { throw new EOFException("registered Redis closed the fixture connection"); }
            return switch (kind) {
                case '+' -> line();
                case '-' -> throw new RedisReplyException(line());
                case ':' -> Long.parseLong(line());
                case '$' -> {
                    int length = Integer.parseInt(line());
                    if (length < 0) { yield null; }
                    if (length > 2_000_000) { throw new IOException("fixture bulk response exceeds bound"); }
                    byte[] value = input.readNBytes(length);
                    if (value.length != length || input.read() != '\r' || input.read() != '\n') { throw new EOFException("incomplete fixture bulk reply"); }
                    yield new String(value, StandardCharsets.UTF_8);
                }
                case '*' -> {
                    int length = Integer.parseInt(line());
                    if (length < 0) { yield null; }
                    if (length > 20_000) { throw new IOException("fixture array response exceeds bound"); }
                    List<Object> values = new ArrayList<>(length);
                    for (int index = 0; index < length; index++) { values.add(read()); }
                    yield values;
                }
                default -> throw new IOException("unexpected registered Redis RESP2 reply");
            };
        }

        private String line() throws IOException {
            StringBuilder value = new StringBuilder();
            while (value.length() < 2_000_000) {
                int next = input.read();
                if (next < 0) { throw new EOFException("incomplete fixture RESP line"); }
                if (next == '\r') {
                    if (input.read() != '\n') { throw new IOException("invalid fixture RESP line terminator"); }
                    return value.toString();
                }
                value.append((char) next);
            }
            throw new IOException("fixture RESP line exceeds bound");
        }

        @Override public void close() throws IOException { socket.close(); }
    }

    private static final class RedisReplyException extends RuntimeException {
        private RedisReplyException(String message) { super(message); }
    }
}
