/**
 * Restricted raw-Protobuf unary invocation using exact native {@code Service/Method} identities.
 *
 * <p>Each call resolves an approved binding ID and shares the stable Service channel,
 * interceptor chain and single-attempt invocation executor. The cache is bounded by
 * trusted target revision, logical identity and exact method; byte payloads are copied
 * across the API boundary. Callers cannot supply network addresses or transport metadata.
 */
package top.egon.cola.component.rpc.consumer.generic;
