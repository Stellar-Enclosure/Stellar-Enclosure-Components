package top.egon.cola.component.common.core.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/** Writes already-authorized, ordered rows. The caller owns authorization and the output stream. */
public final class XlsxWriter {
    private static final int MAX_COLUMNS = 16_384;
    private static final int MAX_DATA_ROWS = 1_048_575;
    private static final int MAX_TEXT_LENGTH = 32_767;

    @SuppressWarnings("deprecation")
    public void write(OutputStream output, String sheetName, List<String> headers,
            Iterator<? extends List<?>> rows, int rowLimit) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(rows, "rows");
        if (headers == null || headers.isEmpty() || headers.size() > MAX_COLUMNS
                || rowLimit < 1 || rowLimit > MAX_DATA_ROWS) {
            throw new IllegalArgumentException("Invalid export columns or row limit");
        }
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        Throwable failure = null;
        try {
            workbook.setCompressTempFiles(true);
            Sheet sheet = workbook.createSheet(sheetName);
            var header = sheet.createRow(0);
            for (int index = 0; index < headers.size(); index++) {
                writeText(header.createCell(index), Objects.requireNonNull(headers.get(index), "header"));
            }
            int count = 0;
            while (rows.hasNext()) {
                if (count == rowLimit) throw new IllegalArgumentException("Export row limit exceeded");
                List<?> values = Objects.requireNonNull(rows.next(), "row");
                if (values.size() != headers.size()) throw new IllegalArgumentException("Export row width differs from headers");
                var row = sheet.createRow(++count);
                for (int index = 0; index < values.size(); index++) {
                    writeValue(row.createCell(index), values.get(index));
                }
            }
            workbook.write(output);
        } catch (IOException | RuntimeException | Error problem) {
            failure = problem;
            throw problem;
        } finally {
            // POI 5.4.1 closes the writer before flushing on close(); dispose first on abort.
            boolean removed = workbook.dispose();
            try {
                workbook.close();
            } catch (IOException closeFailure) {
                if (failure == null) throw closeFailure;
                failure.addSuppressed(closeFailure);
            }
            if (!removed) {
                IOException cleanup = new IOException("XLSX temporary data could not be removed");
                if (failure == null) throw cleanup;
                failure.addSuppressed(cleanup);
            }
        }
    }

    private void writeValue(Cell cell, Object value) {
        if (value == null) return;
        if (value instanceof String text) {
            writeText(cell, text);
        } else if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
        } else if (value instanceof BigDecimal number) {
            double converted = number.doubleValue();
            if (number.precision() > 15 || !Double.isFinite(converted) || converted == 0 && number.signum() != 0) {
                long length = number.scale() >= 0 ? Math.max((long) number.precision(), (long) number.scale() + 1) + 2
                        : (long) number.precision() - number.scale() + 1;
                if (length > MAX_TEXT_LENGTH) throw new IllegalArgumentException("Export decimal exceeds XLSX cell limit");
                writeText(cell, number.toPlainString());
            }
            else cell.setCellValue(converted);
        } else if (value instanceof BigInteger number) {
            if (number.bitLength() > MAX_TEXT_LENGTH * 4) throw new IllegalArgumentException("Export integer exceeds XLSX cell limit");
            if (number.abs().toString().length() > 15) writeText(cell, number.toString());
            else cell.setCellValue(number.doubleValue());
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            long number = ((Number) value).longValue();
            if (number > 999_999_999_999_999L || number < -999_999_999_999_999L) writeText(cell, Long.toString(number));
            else cell.setCellValue(number);
        } else if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) throw new IllegalArgumentException("Non-finite export number");
            cell.setCellValue(number);
        } else {
            throw new IllegalArgumentException("Unsupported export cell type: " + value.getClass().getName());
        }
    }

    private void writeText(Cell cell, String value) {
        if (value.length() > MAX_TEXT_LENGTH) throw new IllegalArgumentException("Export text exceeds XLSX cell limit");
        cell.setCellValue(value);
    }
}
