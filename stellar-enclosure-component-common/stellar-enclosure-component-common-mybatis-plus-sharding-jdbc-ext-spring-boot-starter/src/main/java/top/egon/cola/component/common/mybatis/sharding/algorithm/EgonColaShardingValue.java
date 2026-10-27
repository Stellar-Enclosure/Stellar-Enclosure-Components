package top.egon.cola.component.common.mybatis.sharding.algorithm;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Normalizes JDBC BIGINT parameters and integral SQL literals without truncation. */
final class EgonColaShardingValue {

    private EgonColaShardingValue() {
    }

    static long positive(Object value) {
        long result;
        try {
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                result = ((Number) value).longValue();
            } else if (value instanceof BigInteger integer) {
                result = integer.longValueExact();
            } else if (value instanceof BigDecimal decimal) {
                result = decimal.longValueExact();
            } else {
                throw new IllegalArgumentException("SHARDING_KEY");
            }
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("SHARDING_KEY", failure);
        }
        if (result <= 0) {
            throw new IllegalArgumentException("SHARDING_KEY");
        }
        return result;
    }
}
