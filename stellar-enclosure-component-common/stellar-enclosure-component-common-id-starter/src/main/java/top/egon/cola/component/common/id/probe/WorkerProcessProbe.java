package top.egon.cola.component.common.id.probe;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Pod-local probe state for explicitly selected non-Web workers. */
@Slf4j
public final class WorkerProcessProbe implements SmartLifecycle {
    public static final String ENABLED_PROPERTY = "stellar.worker.probe.enabled";
    private static final String SCRIPT_RESOURCE = "/META-INF/stellar/worker-probe";

    private final ApplicationAvailability availability;
    private final Runnable checkLease;
    private final BooleanSupplier roleReady;
    private final Path proc;
    private final Path state;
    private final long pid = ProcessHandle.current().pid();
    private volatile ScheduledExecutorService heartbeat;

    public WorkerProcessProbe(ApplicationAvailability availability, Runnable checkLease, BooleanSupplier roleReady) {
        this(availability, checkLease, roleReady, Path.of("/proc"), Path.of("/var/run/stellar/probe/state"));
    }

    WorkerProcessProbe(ApplicationAvailability availability, Runnable checkLease, BooleanSupplier roleReady,
                       Path proc, Path state) {
        this.availability = Objects.requireNonNull(availability);
        this.checkLease = Objects.requireNonNull(checkLease);
        this.roleReady = Objects.requireNonNull(roleReady);
        this.proc = Objects.requireNonNull(proc);
        this.state = Objects.requireNonNull(state);
    }

    @Override
    public synchronized void start() {
        if (isRunning()) {
            return;
        }
        try {
            publish();
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Unable to initialize the worker process probe", error);
        }
        heartbeat = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true)
                .name("stellar-worker-probe").factory());
        heartbeat.scheduleWithFixedDelay(this::refresh, 1, 1, TimeUnit.SECONDS);
    }

    private synchronized void refresh() {
        if (isRunning()) {
            try {
                publish();
            } catch (IOException | RuntimeException error) {
                log.warn("Worker process probe heartbeat failed; failureType={}", error.getClass().getSimpleName());
            }
        }
    }

    void publish() throws IOException {
        String uptime = Files.readString(proc.resolve("uptime"));
        long sample = Long.parseLong(uptime.substring(0, uptime.indexOf('.')));
        String procStat = Files.readString(proc.resolve(Long.toString(pid)).resolve("stat"));
        int commEnd = procStat.lastIndexOf(") ");
        if (commEnd < 0) {
            throw new IOException("Missing process start metadata");
        }
        long startTick = Long.parseLong(procStat.substring(commEnd + 2).strip().split(" +")[19]);

        boolean live = availability.getLivenessState() == LivenessState.CORRECT;
        boolean ready = live && availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC
                && authorityReady();
        String snapshot = String.format(Locale.ROOT, "v1 %d %d %d %d %d\n",
                pid, startTick, sample, live ? 1 : 0, ready ? 1 : 0);
        Path temporary = Files.createTempFile(state.getParent(), ".probe-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(temporary, snapshot);
            Files.move(temporary, state, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private boolean authorityReady() {
        try {
            checkLease.run();
            return roleReady.getAsBoolean();
        } catch (RuntimeException unavailable) {
            log.debug("Worker readiness is unavailable; failureType={}", unavailable.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public synchronized void stop() {
        if (heartbeat == null) {
            return;
        }
        heartbeat.shutdownNow();
        heartbeat = null;
        try {
            Files.deleteIfExists(state);
        } catch (IOException error) {
            log.warn("Worker process probe cleanup failed; failureType={}", error.getClass().getSimpleName());
        }
    }

    @Override
    public boolean isRunning() {
        return heartbeat != null;
    }

    /** Emits the POSIX executable from this artifact during image assembly. */
    public static void main(String[] args) throws IOException {
        if (args.length != 1 || !"write-script".equals(args[0])) {
            throw new IllegalArgumentException("Expected write-script");
        }
        try (var script = Objects.requireNonNull(WorkerProcessProbe.class.getResourceAsStream(SCRIPT_RESOURCE))) {
            script.transferTo(System.out);
        }
    }
}
