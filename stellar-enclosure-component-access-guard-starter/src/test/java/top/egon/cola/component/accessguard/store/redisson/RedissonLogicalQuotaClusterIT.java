package top.egon.cola.component.accessguard.store.redisson;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.config.Config;
import org.redisson.config.ReadMode;
import top.egon.cola.component.accessguard.common.exception.StoreOperationException;
import top.egon.cola.component.accessguard.store.LogicalQuotaAcquireCommand;
import top.egon.cola.component.accessguard.store.LogicalQuotaDecisionVO;
import top.egon.cola.component.accessguard.store.LogicalQuotaDecisionVO.Reason;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Explicitly selected, unskipped integration tests against six exclusively owned Redis 7.4.12 processes.
 * Set egon.access.guard.redis.server and egon.access.guard.redis.cli to absolute executable paths.
 * Every address is freshly allocated on loopback. No Docker, external Redis or default Redis port is used.
 * Coordinated promotion and orderly outage recovery do not establish crash-durable exactly-once charging.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class RedissonLogicalQuotaClusterIT {
    private static final String METHOD = "egon.tianquan.shoubing.v1.IdentityDirectoryService/GetTenantMembership";
    private static final String DIGEST = "a".repeat(64);
    private static final Duration WINDOW = Duration.ofSeconds(120);
    private static OwnedCluster cluster;
    private static RedissonClient stableClient;
    private static RedissonClient canaryClient;
    private static RedissonLogicalQuotaBackend stable;
    private static RedissonLogicalQuotaBackend canary;
    private static AccessGuardRedisKeyFactory keys;

    @BeforeAll
    static void startOwnedCluster() throws Exception {
        cluster = new OwnedCluster(executable("egon.access.guard.redis.server"), executable("egon.access.guard.redis.cli"));
        try {
            cluster.create();
            stableClient = client();
            canaryClient = client();
            String prefix = "egon:access-guard:cluster-it:" + UUID.randomUUID();
            keys = new AccessGuardRedisKeyFactory(prefix, "logical-quota-it");
            // Deployment versions deliberately share the same canonical application and quota scope.
            stable = new RedissonLogicalQuotaBackend(stableClient, keys);
            canary = new RedissonLogicalQuotaBackend(canaryClient,
                    new AccessGuardRedisKeyFactory(prefix, "logical-quota-it"));
        } catch (Throwable failure) {
            stopOwnedCluster();
            throw failure;
        }
    }

    @AfterAll
    static void stopOwnedCluster() throws Exception {
        try {
            try { if (stableClient != null) { stableClient.shutdown(0, 2, TimeUnit.SECONDS); } }
            finally { if (canaryClient != null) { canaryClient.shutdown(0, 2, TimeUnit.SECONDS); } }
        } finally {
            if (cluster != null) { cluster.close(); }
        }
    }

    @Test
    @Order(1)
    void concurrentStableAndCanaryCopiesDebitOneLogicalCallAndRejectPhysicalDuplicates() throws Exception {
        String quota = scope("same-call");
        LogicalQuotaAcquireCommand first = command(quota, "same-logical-id", DIGEST, 1, 2, 9, 2, WINDOW);
        List<LogicalQuotaDecisionVO> decisions = concurrent(48,
                index -> (index % 2 == 0 ? stable : canary).acquire(first));

        assertThat(decisions.stream().filter(LogicalQuotaDecisionVO::allowed).count()).isEqualTo(1);
        assertThat(decisions).filteredOn(decision -> !decision.allowed())
                .allSatisfy(decision -> assertThat(decision.reason()).isEqualTo(Reason.ATTEMPT_REJECTED));
        assertThat(decisions).allSatisfy(decision -> assertThat(decision.remaining()).isEqualTo(7));
        LogicalQuotaDecisionVO replay = canary.acquire(command(quota, "same-logical-id", DIGEST, 2, 2, 9, 2, WINDOW));
        assertThat(replay.allowed()).isTrue();
        assertThat(replay.reason()).isEqualTo(Reason.REPLAY_ALLOWED);
        assertThat(replay.remaining()).isEqualTo(7);
        assertThat(replay.acceptedAttempt()).isEqualTo(2);
        assertThat(stable.acquire(command(quota, "same-logical-id", DIGEST, 2, 2, 9, 2, WINDOW)).reason())
                .isEqualTo(Reason.ATTEMPT_REJECTED);
    }

    @Test
    @Order(2)
    void distinctLogicalCallsCannotOverdrawTheSharedFixedCapacity() throws Exception {
        String quota = scope("capacity");
        List<LogicalQuotaDecisionVO> decisions = concurrent(64, index ->
                (index % 2 == 0 ? stable : canary).acquire(command(quota, "call-" + index, DIGEST, 1, 1, 17, 1, WINDOW)));
        assertThat(decisions.stream().filter(LogicalQuotaDecisionVO::allowed).count()).isEqualTo(17);
        assertThat(decisions).allSatisfy(decision -> assertThat(decision.remaining()).isBetween(0L, 16L));
        assertThat(decisions).filteredOn(decision -> !decision.allowed())
                .allSatisfy(decision -> assertThat(decision.reason()).isEqualTo(Reason.EXHAUSTED));
        LogicalQuotaDecisionVO exhausted = canary.acquire(command(quota, "one-more", DIGEST, 1, 1, 17, 1, WINDOW));
        assertThat(exhausted.allowed()).isFalse();
        assertThat(exhausted.reason()).isEqualTo(Reason.EXHAUSTED);
        assertThat(exhausted.remaining()).isZero();
    }

    @Test
    @Order(0)
    void aCostAboveCapacityIsAnAuthoritativeDenialWithoutDeductingCredit() {
        String quota = scope("oversize-cost");
        var rejected = stable.acquire(command(quota, "expensive", DIGEST, 1, 2, 3, 4, WINDOW));
        assertThat(rejected.reason()).isEqualTo(Reason.EXHAUSTED);
        assertThat(rejected.remaining()).isEqualTo(3);
        assertThat(canary.acquire(command(quota, "expensive", DIGEST, 2, 2, 3, 4, WINDOW)).reason())
                .isEqualTo(Reason.REPLAY_DENIED);
        assertThat(canary.acquire(command(quota, "fits", DIGEST, 1, 1, 3, 3, WINDOW)).remaining()).isZero();
    }

    @Test
    @Order(3)
    void changingTheRequestDigestCannotReuseAnEarlierAdmission() {
        String quota = scope("conflict");
        assertThat(stable.acquire(command(quota, "logical-id", DIGEST, 1, 2, 2, 1, WINDOW)).remaining()).isEqualTo(1);
        LogicalQuotaDecisionVO conflict = canary.acquire(command(quota, "logical-id", "b".repeat(64), 2, 2, 2, 1, WINDOW));
        assertThat(conflict.allowed()).isFalse();
        assertThat(conflict.reason()).isEqualTo(Reason.CONFLICT);
        assertThat(conflict.remaining()).isEqualTo(1);
        assertThat(stable.acquire(command(quota, "different-id", DIGEST, 1, 1, 2, 1, WINDOW)).remaining()).isZero();
    }

    @Test
    @Order(4)
    void anUnseenSecondAttemptConsumesCapacityAndOnlyApprovedIncreasingAttemptsCanReplay() {
        String quota = scope("attempts");
        LogicalQuotaDecisionVO secondFirst = canary.acquire(command(quota, "first-observed-two", DIGEST, 2, 2, 3, 1, WINDOW));
        assertThat(secondFirst.allowed()).isTrue();
        assertThat(secondFirst.reason()).isEqualTo(Reason.ALLOWED);
        assertThat(secondFirst.remaining()).isEqualTo(2);
        assertThat(secondFirst.acceptedAttempt()).isEqualTo(2);
        assertThat(stable.acquire(command(quota, "first-observed-two", DIGEST, 1, 2, 3, 1, WINDOW)).reason())
                .isEqualTo(Reason.ATTEMPT_REJECTED);
        assertThat(stable.acquire(command(quota, "first-observed-two", DIGEST, 2, 2, 3, 1, WINDOW)).reason())
                .isEqualTo(Reason.ATTEMPT_REJECTED);
        LogicalQuotaDecisionVO notApproved = stable.acquire(command(quota, "unapproved-two", DIGEST, 2, 1, 3, 1, WINDOW));
        assertThat(notApproved.allowed()).isFalse();
        assertThat(notApproved.reason()).isEqualTo(Reason.ATTEMPT_REJECTED);
        assertThat(canary.acquire(command(quota, "new-id", DIGEST, 1, 1, 3, 1, WINDOW)).remaining()).isEqualTo(1);
    }

    @Test
    @Order(5)
    void expiredReplayIsDeniedAndAnAbsentDecisionCanOnlyBecomeANewDebit() throws Exception {
        String quota = scope("expiry");
        Duration window = Duration.ofMillis(1500);
        LogicalQuotaAcquireCommand first = command(quota, "expiring-id", DIGEST, 1, 2, 10, 2, window);
        assertThat(stable.acquire(first).remaining()).isEqualTo(8);
        String decisionKey = keys.logicalQuotaDecision(first);
        await(Duration.ofSeconds(6), () -> {
            long ttl = stableClient.getBucket(decisionKey).remainTimeToLive();
            return ttl > 0 && ttl < window.toMillis() - 100;
        }, "decision must outlive its original replay deadline");
        LogicalQuotaDecisionVO expired = canary.acquire(command(quota, "expiring-id", DIGEST, 2, 2, 10, 2, window));
        assertThat(expired.allowed()).isFalse();
        assertThat(expired.reason()).isEqualTo(Reason.WINDOW_EXPIRED);
        assertThat(expired.remaining()).isEqualTo(8);
        await(Duration.ofSeconds(6), () -> stableClient.getBucket(decisionKey).remainTimeToLive() == -2,
                "decision retention TTL must eventually expire");
        LogicalQuotaDecisionVO newDebit = canary.acquire(first);
        assertThat(newDebit.allowed()).isTrue();
        assertThat(newDebit.reason()).isEqualTo(Reason.ALLOWED);
        assertThat(newDebit.remaining()).isEqualTo(6);
        assertThat(stableClient.getBucket(keys.logicalQuotaState(first)).remainTimeToLive()).isEqualTo(-1);
    }

    @Test
    @Order(6)
    void theRealClusterConfirmsSameSlotKeysAndIndependentlyRejectsCrossSlotEval() throws Exception {
        LogicalQuotaAcquireCommand first = command(scope("slots"), "slot-id", DIGEST, 1, 2, 3, 1, WINDOW);
        String state = keys.logicalQuotaState(first);
        String decision = keys.logicalQuotaDecision(first);
        int stateSlot = cluster.slot(state);
        assertThat(cluster.slot(decision)).isEqualTo(stateSlot);
        assertThat(stateSlot).isBetween(0, 16383);
        assertThat(state.substring(state.indexOf('{'), state.indexOf('}') + 1))
                .isEqualTo(decision.substring(decision.indexOf('{'), decision.indexOf('}') + 1));
        assertThat(stable.acquire(first).allowed()).isTrue();
        String independent;
        do {
            independent = keys.logicalQuotaState(command(scope("other-slot"), "other-id", DIGEST, 1, 1, 3, 1, WINDOW));
        } while (cluster.slot(independent) == stateSlot);
        String response = cluster.cli(cluster.seedPort(), "EVAL", "return 1", "2", state, independent);
        assertThat(response).contains("CROSSSLOT");
    }

    @Test
    @Order(7)
    void coordinatedReplicaPromotionRetainsTheActuallyObservedDecision() throws Exception {
        LogicalQuotaAcquireCommand first = command(scope("coordinated-promotion"), "promotion-id", DIGEST, 1, 2, 3, 1, WINDOW);
        assertThat(stable.acquire(first).remaining()).isEqualTo(2);
        OwnedCluster.ReplicationPair pair = cluster.replicationPair(keys.logicalQuotaState(first));
        cluster.awaitReplicated(pair, keys.logicalQuotaState(first), keys.logicalQuotaDecision(first));
        assertThat(cluster.cli(pair.replicaPort(), "CLUSTER", "FAILOVER")).isEqualTo("OK");
        await(Duration.ofSeconds(20), () -> cluster.isMaster(pair.replicaPort()) && !cluster.isMaster(pair.masterPort()),
                "coordinated FAILOVER must exchange the observed master and replica roles");
        cluster.awaitHealthy();
        LogicalQuotaDecisionVO replay = retryUntilRecovered(canary,
                command(first.quota(), first.logicalCallId(), DIGEST, 2, 2, 3, 1, WINDOW));
        assertThat(replay.allowed()).isTrue();
        assertThat(replay.reason()).isEqualTo(Reason.REPLAY_ALLOWED);
        assertThat(replay.remaining()).isEqualTo(2);
        System.out.println("RedisClusterIT: observed coordinated promotion on owned ports "
                + pair.masterPort() + " -> " + pair.replicaPort() + "; no crash-durability claim");
    }

    @Test
    @Order(8)
    void anActualOwnedClusterOutageFailsClosedAndOrderlyRecoveryRetainsObservedState() throws Exception {
        LogicalQuotaAcquireCommand first = command(scope("owned-outage"), "outage-id", DIGEST, 1, 2, 2, 1, WINDOW);
        assertThat(stable.acquire(first).remaining()).isEqualTo(1);
        OwnedCluster.ReplicationPair pair = cluster.replicationPair(keys.logicalQuotaState(first));
        cluster.awaitReplicated(pair, keys.logicalQuotaState(first), keys.logicalQuotaDecision(first));
        cluster.stopProcesses();
        try {
            assertThatThrownBy(() -> canary.acquire(command(first.quota(), "during-outage", DIGEST, 1, 1, 2, 1, WINDOW)))
                    .isInstanceOf(StoreOperationException.class).hasCauseInstanceOf(RedisException.class);
        } finally {
            cluster.restart();
        }
        LogicalQuotaDecisionVO replay = retryUntilRecovered(stable,
                command(first.quota(), first.logicalCallId(), DIGEST, 2, 2, 2, 1, WINDOW));
        assertThat(replay.allowed()).isTrue();
        assertThat(replay.reason()).isEqualTo(Reason.REPLAY_ALLOWED);
        assertThat(replay.remaining()).isEqualTo(1);
        assertThat(retryUntilRecovered(canary, command(first.quota(), "after-recovery", DIGEST, 1, 1, 2, 1, WINDOW)).remaining())
                .isZero();
        System.out.println("RedisClusterIT: actual six-process transport outage and orderly AOF recovery verified; synthetic state only");
    }

    private static LogicalQuotaDecisionVO retryUntilRecovered(RedissonLogicalQuotaBackend backend,
                                                               LogicalQuotaAcquireCommand command) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        StoreOperationException last = null;
        do {
            try { return backend.acquire(command); }
            catch (StoreOperationException failure) { last = failure; Thread.sleep(50); }
        } while (System.nanoTime() < deadline);
        throw new AssertionError("owned cluster client did not recover", last);
    }

    private static RedissonClient client() {
        Config config = new Config().setThreads(2).setNettyThreads(2);
        config.useClusterServers().addNodeAddress(cluster.addresses()).setReadMode(ReadMode.MASTER)
                .setScanInterval(250).setConnectTimeout(500).setTimeout(750).setRetryAttempts(0)
                .setMasterConnectionMinimumIdleSize(1).setMasterConnectionPoolSize(4)
                .setSlaveConnectionMinimumIdleSize(1).setSlaveConnectionPoolSize(4);
        return Redisson.create(config);
    }

    private static LogicalQuotaAcquireCommand command(String quota, String id, String digest, int attempt, int maximum,
                                                       long capacity, long cost, Duration replayWindow) {
        return new LogicalQuotaAcquireCommand(71, "cluster-test-client", "https://resource.example.test/identity",
                quota, METHOD, id, digest, attempt, maximum, capacity, cost, replayWindow);
    }

    private static String scope(String prefix) { return prefix + '-' + UUID.randomUUID(); }

    private static List<LogicalQuotaDecisionVO> concurrent(int count, IntFunction<LogicalQuotaDecisionVO> task) throws Exception {
        int workers = Math.min(12, count);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(workers)) {
            List<Future<LogicalQuotaDecisionVO>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                int item = index;
                futures.add(executor.submit(() -> { ready.countDown(); start.await(); return task.apply(item); }));
            }
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                List<LogicalQuotaDecisionVO> results = new ArrayList<>();
                for (Future<LogicalQuotaDecisionVO> future : futures) { results.add(future.get(10, TimeUnit.SECONDS)); }
                return results;
            } finally { start.countDown(); executor.shutdownNow(); }
        }
    }

    private static Path executable(String property) {
        String configured = System.getProperty(property);
        if (configured == null) { throw new IllegalStateException("Explicit ClusterIT requires -D" + property + "=/absolute/redis-7.4.12-binary"); }
        Path path = Path.of(configured);
        if (!path.isAbsolute() || !Files.isRegularFile(path) || !Files.isExecutable(path)) {
            throw new IllegalArgumentException(property + " must identify an absolute executable file");
        }
        return path;
    }

    private static void await(Duration timeout, BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            if (condition.getAsBoolean()) { return; }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(description);
    }

    private static final class OwnedCluster implements AutoCloseable {
        private final Path server;
        private final Path cli;
        private final Path directory;
        private final List<Node> nodes = new ArrayList<>();
        private final AtomicInteger commands = new AtomicInteger();
        private boolean closed;

        private OwnedCluster(Path server, Path cli) throws IOException {
            this.server = server;
            this.cli = cli;
            this.directory = Files.createTempDirectory("egon-logical-quota-cluster-");
        }

        private void create() throws Exception {
            CommandResult serverVersion = command(List.of(server.toString(), "--version"), null, Duration.ofSeconds(5));
            CommandResult cliVersion = command(List.of(cli.toString(), "--version"), null, Duration.ofSeconds(5));
            assertThat(serverVersion.exitCode()).isZero();
            assertThat(cliVersion.exitCode()).isZero();
            assertThat(serverVersion.text()).contains("v=7.4.12 ");
            assertThat(cliVersion.text()).isEqualTo("redis-cli 7.4.12");
            List<ServerSocket> reserved = new ArrayList<>();
            try {
                for (int index = 0; index < 12; index++) { reserved.add(reserveLoopbackPort()); }
                for (int index = 0; index < 6; index++) {
                    int port = reserved.get(index * 2).getLocalPort();
                    int bus = reserved.get(index * 2 + 1).getLocalPort();
                    Path nodeDirectory = Files.createDirectory(directory.resolve("node-" + index));
                    Path config = nodeDirectory.resolve("redis.conf");
                    Files.writeString(config, "bind 127.0.0.1\nprotected-mode yes\nport " + port + "\ncluster-port " + bus
                            + "\ncluster-announce-ip 127.0.0.1\ncluster-announce-port " + port + "\ncluster-announce-bus-port " + bus
                            + "\ncluster-enabled yes\ncluster-config-file nodes.conf\ncluster-node-timeout 2000\n"
                            + "cluster-require-full-coverage yes\nrepl-diskless-sync-delay 0\ndaemonize no\nsave \"\"\nappendonly yes\nappendfsync always\n"
                            + "dir \"" + nodeDirectory + "\"\nlogfile \"\"\n");
                    Node node = new Node(port, bus, config, nodeDirectory.resolve("redis.log"));
                    nodes.add(node);
                    reserved.get(index * 2).close();
                    reserved.get(index * 2 + 1).close();
                    node.start(server);
                }
            } finally {
                for (ServerSocket socket : reserved) { socket.close(); }
            }
            awaitPings();
            List<String> create = new ArrayList<>(List.of(cli.toString(), "--cluster", "create"));
            for (Node node : nodes) { create.add("127.0.0.1:" + node.port); }
            create.addAll(List.of("--cluster-replicas", "1", "--cluster-yes"));
            CommandResult created = command(create, null, Duration.ofSeconds(30));
            assertThat(created.exitCode()).isZero();
            assertThat(created.text()).contains("All 16384 slots covered");
            awaitHealthy();
            System.out.println("RedisClusterIT: Redis 7.4.12, three primaries and three replicas, exclusively owned loopback ports "
                    + nodes.stream().map(node -> node.port).toList());
        }

        private int seedPort() { return nodes.getFirst().port; }
        private String[] addresses() { return nodes.stream().map(node -> "redis://127.0.0.1:" + node.port).toArray(String[]::new); }
        private int slot(String key) { return Integer.parseInt(cli(seedPort(), "CLUSTER", "KEYSLOT", key)); }
        private boolean isMaster(int port) { return cli(port, "INFO", "replication").contains("role:master\r\n"); }

        private void awaitPings() throws Exception {
            await(Duration.ofSeconds(15), () -> nodes.stream().allMatch(node -> node.process.isAlive()
                    && "PONG".equals(cli(node.port, "PING"))
                    && cli(node.port, "INFO", "server").contains("process_id:" + node.process.pid() + "\r\n")),
                    "all six loopback listeners must identify the exact owned process IDs");
        }

        private void awaitHealthy() throws Exception {
            await(Duration.ofSeconds(25), () -> nodes.stream().allMatch(node -> {
                String info = cli(node.port, "CLUSTER", "INFO");
                return info.contains("cluster_state:ok") && info.contains("cluster_slots_assigned:16384")
                        && info.contains("cluster_slots_ok:16384") && info.contains("cluster_known_nodes:6");
            }), "all owned nodes must agree the six-node cluster covers every slot");
            await(Duration.ofSeconds(15), () -> nodes.stream().allMatch(node -> {
                String replication = cli(node.port, "INFO", "replication");
                return replication.contains("role:master\r\n") ? replication.contains("connected_slaves:1\r\n")
                        : replication.contains("master_link_status:up\r\n") && replication.contains("master_sync_in_progress:0\r\n");
            }), "all three primaries must have their replicas fully connected");
            await(Duration.ofSeconds(15), () -> {
                String view = cli(seedPort(), "CLUSTER", "NODES");
                return view.lines().filter(line -> Arrays.asList(line.split(" ")[2].split(",")).contains("master")).count() == 3
                        && view.lines().filter(line -> Arrays.asList(line.split(" ")[2].split(",")).contains("slave")).count() == 3;
            }, "the seed topology must converge to exactly three primaries and three replicas");
            String topology = cli(seedPort(), "CLUSTER", "NODES");
            assertThat(topology.lines().filter(line -> Arrays.asList(line.split(" ")[2].split(",")).contains("master")).count()).isEqualTo(3);
            assertThat(topology.lines().filter(line -> Arrays.asList(line.split(" ")[2].split(",")).contains("slave")).count()).isEqualTo(3);
        }

        private ReplicationPair replicationPair(String key) {
            int slot = slot(key);
            List<String[]> topology = cli(seedPort(), "CLUSTER", "NODES").lines().map(line -> line.split(" ")).toList();
            String[] master = topology.stream().filter(fields -> Arrays.asList(fields[2].split(",")).contains("master"))
                    .filter(fields -> ownsSlot(fields, slot)).findFirst().orElseThrow();
            String[] replica = topology.stream().filter(fields -> fields[3].equals(master[0])).findFirst().orElseThrow();
            int primaryPort = announcedPort(master[1]);
            int replicaPort = announcedPort(replica[1]);
            assertThat(nodes.stream().anyMatch(node -> node.port == primaryPort)).isTrue();
            assertThat(nodes.stream().anyMatch(node -> node.port == replicaPort)).isTrue();
            return new ReplicationPair(primaryPort, replicaPort);
        }

        private void awaitReplicated(ReplicationPair pair, String... keys) throws Exception {
            for (String key : keys) {
                byte[] masterValue = dump(pair.masterPort, key, false);
                assertThat(masterValue.length).isGreaterThan(8);
                await(Duration.ofSeconds(6), () -> Arrays.equals(masterValue, dump(pair.replicaPort, key, true)),
                        "actual replica DUMP must match the observed primary key");
            }
        }

        private byte[] dump(int port, String key, boolean readOnly) {
            if (!readOnly) { return cliResult(port, null, "DUMP", key).bytes; }
            byte[] value = cliResult(port, "READONLY\nDUMP " + key + "\n").bytes;
            byte[] prefix = "OK\n".getBytes(StandardCharsets.UTF_8);
            if (value.length < prefix.length || !Arrays.equals(prefix, Arrays.copyOf(value, prefix.length))) { return new byte[0]; }
            return Arrays.copyOfRange(value, prefix.length, value.length);
        }

        private String cli(int port, String... arguments) {
            return cliResult(port, null, arguments).text();
        }

        private CommandResult cliResult(int port, String input, String... arguments) {
            List<String> command = new ArrayList<>(List.of(cli.toString(), "--raw", "-h", "127.0.0.1", "-p", Integer.toString(port)));
            command.addAll(List.of(arguments));
            try { return command(command, input, Duration.ofSeconds(4)); }
            catch (Exception failure) { throw new IllegalStateException("owned Redis CLI did not complete on port " + port, failure); }
        }

        private CommandResult command(List<String> arguments, String input, Duration timeout) throws Exception {
            Path output = directory.resolve("cli-" + commands.incrementAndGet() + ".log");
            Process process = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try {
                if (input != null) { process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8)); }
                process.getOutputStream().close();
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new AssertionError("owned subprocess exceeded its bounded wait: " + arguments.getFirst());
                }
                return new CommandResult(process.exitValue(), Files.readAllBytes(output));
            } finally {
                if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
            }
        }

        private void stopProcesses() throws Exception {
            for (Node node : nodes) { if (node.process != null && node.process.isAlive()) { node.process.destroy(); } }
            for (Node node : nodes) {
                if (node.process != null && !node.process.waitFor(5, TimeUnit.SECONDS)) {
                    node.process.destroyForcibly();
                    assertThat(node.process.waitFor(5, TimeUnit.SECONDS)).isTrue();
                }
            }
        }

        private void restart() throws Exception {
            for (Node node : nodes) {
                assertThat(node.process == null || !node.process.isAlive()).isTrue();
                try (ServerSocket clientPort = reserveExactPort(node.port); ServerSocket busPort = reserveExactPort(node.bus)) {
                    assertThat(clientPort.getLocalPort()).isEqualTo(node.port);
                    assertThat(busPort.getLocalPort()).isEqualTo(node.bus);
                }
                node.start(server);
            }
            awaitPings();
            awaitHealthy();
        }

        @Override
        public void close() throws Exception {
            if (closed) { return; }
            closed = true;
            try { stopProcesses(); }
            finally {
                for (Node node : nodes) {
                    if (node.process != null && node.process.isAlive()) { node.process.destroyForcibly(); }
                }
                for (Node node : nodes) {
                    if (node.process != null) { assertThat(node.process.waitFor(5, TimeUnit.SECONDS)).isTrue(); }
                }
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
                }
            }
        }

        private static ServerSocket reserveLoopbackPort() throws IOException {
            ServerSocket socket;
            do {
                socket = reserveExactPort(0);
                if (socket.getLocalPort() != 6379 && socket.getLocalPort() != 16379) { return socket; }
                socket.close();
            } while (true);
        }

        private static ServerSocket reserveExactPort(int port) throws IOException {
            ServerSocket socket = new ServerSocket();
            try { socket.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)); return socket; }
            catch (IOException failure) { socket.close(); throw failure; }
        }

        private static int announcedPort(String address) { return Integer.parseInt(address.substring(address.indexOf(':') + 1, address.indexOf('@'))); }
        private static boolean ownsSlot(String[] fields, int slot) {
            for (int index = 8; index < fields.length; index++) {
                if (fields[index].startsWith("[")) { continue; }
                String[] range = fields[index].split("-");
                int first = Integer.parseInt(range[0]);
                int last = range.length == 1 ? first : Integer.parseInt(range[1]);
                if (slot >= first && slot <= last) { return true; }
            }
            return false;
        }

        private record ReplicationPair(int masterPort, int replicaPort) { }
        private record CommandResult(int exitCode, byte[] bytes) {
            private String text() { return new String(bytes, StandardCharsets.UTF_8).strip(); }
        }
        private static final class Node {
            private final int port;
            private final int bus;
            private final Path config;
            private final Path output;
            private Process process;
            private Node(int port, int bus, Path config, Path output) { this.port = port; this.bus = bus; this.config = config; this.output = output; }
            private void start(Path server) throws IOException {
                process = new ProcessBuilder(server.toString(), config.toString()).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(output.toFile())).start();
            }
        }
    }
}
