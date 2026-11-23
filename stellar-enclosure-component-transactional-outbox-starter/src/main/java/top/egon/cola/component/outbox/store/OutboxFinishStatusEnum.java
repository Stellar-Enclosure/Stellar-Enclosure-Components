package top.egon.cola.component.outbox.store;

public enum OutboxFinishStatusEnum {
    APPLIED,
    ALREADY_TERMINAL,
    LEASE_LOST,
    INDETERMINATE
}
