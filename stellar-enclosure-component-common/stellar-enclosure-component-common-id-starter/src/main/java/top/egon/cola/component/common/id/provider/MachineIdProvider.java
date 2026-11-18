package top.egon.cola.component.common.id.provider;

import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;

/**
 * Strategy interface for resolving a Snowflake machine identifier ({@code 0..1023})
 * when no static {@code machine-id} is configured.
 */
@FunctionalInterface
public interface MachineIdProvider {

    /**
     * Returns the assigned machine identifier in the range {@code 0..1023}.
     *
     * @return machine identifier from 0 to 1023
     */
    long getMachineId();

    /**
     * Issues through this provider's authority, checking validity again after any generator wait.
     * Dynamic implementations may serialize issuance and validate the decoded absolute UTC millisecond.
     */
    default long issue(LongSupplier generator, LongUnaryOperator timestampDecoder) {
        Objects.requireNonNull(generator, "generator");
        Objects.requireNonNull(timestampDecoder, "timestampDecoder");
        assertIssuanceAvailable();
        long id = generator.getAsLong();
        assertIssuanceAvailable();
        return id;
    }

    /** Static/custom implementations remain source compatible; leased providers override this fence. */
    default void assertIssuanceAvailable() {
    }
}
