package top.egon.cola.component.common.core.excel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** Orchestrates bounded paging over a caller-owned query and authorized projection. */
public final class XlsxExport {
    public enum Range { PAGE, FILTERED }

    @FunctionalInterface
    public interface PageSource<T> {
        Page<T> fetch(int pageNumber, int pageSize);
    }

    public record Page<T>(List<T> records, long total) {
        public Page {
            records = List.copyOf(Objects.requireNonNull(records, "records"));
            if (total < 0) throw new IllegalArgumentException("Negative export total");
        }
    }

    public record Limits(int pageSize, int maxRows, long maxBytes) {
        public Limits {
            if (pageSize < 1 || pageSize > 500 || maxRows < 1 || maxRows > 1_048_575 || maxBytes < 1) {
                throw new IllegalArgumentException("Invalid export limits");
            }
        }
    }

    public <T> byte[] export(String sheetName, List<String> headers, Range range, int currentPage,
            Limits limits, PageSource<T> source, Function<List<T>, List<List<?>>> projection,
            Runnable verifyAuthorization, Consumer<Integer> completedRows) throws IOException {
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(verifyAuthorization, "verifyAuthorization");
        Objects.requireNonNull(completedRows, "completedRows");
        if (currentPage < 1 || currentPage > limits.maxRows()) throw new IllegalArgumentException("Invalid export page");
        verifyAuthorization.run();
        int firstPage = range == Range.FILTERED ? 1 : currentPage;
        Page<T> first = Objects.requireNonNull(source.fetch(firstPage, limits.pageSize()), "first page");
        if (range == Range.FILTERED && first.total() > limits.maxRows()) throw new ExportLimitException();
        Path file = Files.createTempFile("stellar-export-", ".xlsx");
        int[] count = {0};
        long[] estimatedBytes = {0};
        try {
            try (var output = Files.newOutputStream(file)) {
                Iterator<List<?>> rows = new Iterator<>() {
                    private int page = firstPage;
                    private boolean more = true;
                    private boolean firstBatch = true;
                    private Iterator<List<?>> current = List.<List<?>>of().iterator();

                    @Override public boolean hasNext() {
                        while (!current.hasNext() && more) {
                            Page<T> batch = firstBatch ? first : Objects.requireNonNull(source.fetch(page, limits.pageSize()), "page");
                            firstBatch = false;
                            if (batch.records().size() > limits.pageSize()) throw new IllegalStateException("Export page exceeds requested size");
                            List<List<?>> projected = List.copyOf(Objects.requireNonNull(projection.apply(batch.records()), "projected page"));
                            if (projected.size() != batch.records().size()) throw new IllegalStateException("Export projection changed the row count");
                            for (List<?> row : projected) for (Object cell : row) {
                                estimatedBytes[0] = Math.addExact(estimatedBytes[0], estimatedCellBytes(cell));
                                if (estimatedBytes[0] > limits.maxBytes()) throw new ExportLimitException();
                            }
                            verifyAuthorization.run();
                            current = projected.iterator();
                            more = range == Range.FILTERED && batch.records().size() == limits.pageSize();
                            page++;
                        }
                        if (current.hasNext() && count[0] >= limits.maxRows()) throw new ExportLimitException();
                        return current.hasNext();
                    }

                    @Override public List<?> next() {
                        if (!hasNext()) throw new NoSuchElementException();
                        if (++count[0] > limits.maxRows()) throw new ExportLimitException();
                        return current.next();
                    }
                };
                new XlsxWriter().write(output, sheetName, headers, rows, limits.maxRows());
            }
            if (Files.size(file) > limits.maxBytes()) throw new ExportLimitException();
            verifyAuthorization.run();
            completedRows.accept(count[0]);
            verifyAuthorization.run();
            return Files.readAllBytes(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    public static final class ExportLimitException extends RuntimeException {
        public ExportLimitException() { super("Export exceeds its configured limit"); }
    }

    private static long estimatedCellBytes(Object value) {
        long characters = value instanceof String text ? text.length()
                : value instanceof java.math.BigDecimal decimal ? decimal.scale() >= 0
                ? Math.max((long) decimal.precision(), (long) decimal.scale() + 1) + 2
                : (long) decimal.precision() - decimal.scale() + 1
                : value instanceof java.math.BigInteger integer ? (long) integer.bitLength() * 30103 / 100000 + 2 : 0;
        if (characters > 32_767) throw new ExportLimitException();
        return characters * 6 + 128;
    }
}
