package top.egon.cola.component.common.id.probe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkerProcessProbeTest {
    @TempDir
    Path directory;

    @Test
    void scriptRejectsStaleAndRecycledProcessesEvenWithAnOldReadySnapshot() throws Exception {
        Path proc = Files.createDirectory(directory.resolve("proc"));
        Path process = Files.createDirectory(proc.resolve("123"));
        Path state = directory.resolve("state");
        Path script = isolatedScript(proc, state);
        Files.writeString(proc.resolve("uptime"), "420.01 0.00\n");
        Files.writeString(process.resolve("stat"), stat(123, 345));
        Files.writeString(state, "v1 123 345 420 1 1\n");

        assertThat(run(script, "startup")).isZero();
        assertThat(run(script, "liveness")).isZero();
        assertThat(run(script, "readiness")).isZero();
        assertThat(run(script, "other")).isNotZero();

        Files.writeString(state, "v1 123 345 420 1 0\n");
        assertThat(run(script, "liveness")).isZero();
        assertThat(run(script, "readiness")).isNotZero();

        Files.writeString(state, "v1 123 345 420 1 1\n");
        Files.writeString(proc.resolve("uptime"), "425.01 0.00\n");
        assertThat(run(script, "liveness")).isNotZero();

        Files.writeString(proc.resolve("uptime"), "421.01 0.00\n");
        Files.writeString(process.resolve("stat"), stat(123, 346));
        assertThat(run(script, "readiness")).isNotZero();

        Files.writeString(process.resolve("stat"), stat(123, 345).replace(") S ", ") Z "));
        assertThat(run(script, "liveness")).isNotZero();
        Files.writeString(process.resolve("stat"), stat(123, 345));
        Files.writeString(state, "v1 123 345 422 1 1\n");
        assertThat(run(script, "liveness")).isNotZero();
        Files.writeString(state, "v1 123 345 421 1 wrong\n");
        assertThat(run(script, "readiness")).isNotZero();
        Files.delete(state);
        assertThat(run(script, "startup")).isNotZero();
    }

    @Test
    void thePackagedScriptObservesTheWriterAcrossRealAvailabilityAndLeaseTransitions() throws Exception {
        Path proc = Files.createDirectory(directory.resolve("proc"));
        long pid = ProcessHandle.current().pid();
        Path process = Files.createDirectory(proc.resolve(Long.toString(pid)));
        Path state = directory.resolve("state");
        Path script = isolatedScript(proc, state);
        Files.writeString(proc.resolve("uptime"), "600.99 0.00\n");
        Files.writeString(process.resolve("stat"), stat(pid, 123));
        var availability = mock(ApplicationAvailability.class);
        when(availability.getLivenessState()).thenReturn(LivenessState.BROKEN);
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
        var lease = new AtomicBoolean(true);
        var role = new AtomicBoolean(true);
        var probe = new WorkerProcessProbe(availability, () -> {
            if (!lease.get()) {
                throw new IllegalStateException("lease unavailable");
            }
        }, role::get, proc, state);

        probe.publish();
        assertThat(run(script, "startup")).isNotZero();
        when(availability.getLivenessState()).thenReturn(LivenessState.CORRECT);
        probe.publish();
        assertThat(run(script, "startup")).isZero();
        assertThat(run(script, "readiness")).isNotZero();

        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        probe.publish();
        assertThat(run(script, "readiness")).isZero();
        assertThat(Files.getPosixFilePermissions(state)).isEqualTo(PosixFilePermissions.fromString("rw-------"));

        role.set(false);
        probe.publish();
        assertThat(run(script, "readiness")).isNotZero();
        role.set(true);
        lease.set(false);
        probe.publish();
        assertThat(run(script, "readiness")).isNotZero();
        assertThat(run(script, "liveness")).isZero();

        lease.set(true);
        probe.start();
        try {
            assertThat(probe.isRunning()).isTrue();
            assertThat(run(script, "readiness")).isZero();
            lease.set(false);
            long started = System.nanoTime();
            int readiness;
            do {
                Thread.sleep(Duration.ofMillis(25));
                readiness = run(script, "readiness");
            } while (readiness == 0 && System.nanoTime() - started < Duration.ofSeconds(4).toNanos());
            assertThat(readiness).isNotZero();
            assertThat(run(script, "liveness")).isZero();
        } finally {
            probe.stop();
        }
        assertThat(state).doesNotExist();
        assertThat(run(script, "liveness")).isNotZero();
    }

    private Path isolatedScript(Path proc, Path state) throws IOException {
        try (var script = Objects.requireNonNull(getClass().getResourceAsStream("/META-INF/stellar/worker-probe"))) {
            String source = new String(script.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("/var/run/stellar/probe/state", state.toString())
                    .replace("proc_root=/proc", "proc_root=" + proc);
            return Files.writeString(directory.resolve("probe"), source);
        }
    }

    private static String stat(long pid, long birth) {
        return pid + " (java worker) with spaces) S " + "0 ".repeat(18) + birth + " 0\n";
    }

    private static int run(Path script, String mode) throws Exception {
        var process = new ProcessBuilder("/bin/sh", script.toString(), mode)
                .redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(2, TimeUnit.SECONDS)).isTrue();
            return process.exitValue();
        } finally {
            process.destroyForcibly();
        }
    }
}
