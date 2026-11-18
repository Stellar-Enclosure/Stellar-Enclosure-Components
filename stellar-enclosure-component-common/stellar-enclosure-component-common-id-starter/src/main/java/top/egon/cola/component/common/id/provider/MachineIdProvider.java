package top.egon.cola.component.common.id.provider;

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
}
