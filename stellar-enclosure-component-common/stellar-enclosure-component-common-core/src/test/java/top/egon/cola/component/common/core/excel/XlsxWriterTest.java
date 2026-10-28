package top.egon.cola.component.common.core.excel;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class XlsxWriterTest {
    @TempDir Path temporary;
    private final XlsxWriter writer = new XlsxWriter();

    @Test
    void writesTextWithoutFormulasAndPreservesLongIdentifiers() throws Exception {
        var output = new ByteArrayOutputStream();
        writer.write(output, "Results", List.of("Formula", "Identifier", "Amount", "Enabled"),
                List.<List<?>>of(List.of("=1+1", 123456789012345678L, new BigDecimal("12.34"), true)).iterator(), 10);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(output.toByteArray()))) {
            var row = workbook.getSheetAt(0).getRow(1);
            assertEquals(CellType.STRING, row.getCell(0).getCellType());
            assertEquals("=1+1", row.getCell(0).getStringCellValue());
            assertEquals("123456789012345678", row.getCell(1).getStringCellValue());
            assertEquals(12.34, row.getCell(2).getNumericCellValue());
            assertTrue(row.getCell(3).getBooleanCellValue());
        }
    }

    @Test
    void rejectsExcessRowsBeforeWritingAnOutputWorkbook() {
        var output = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class, () -> writer.write(output, "Results", List.of("A"),
                List.<List<?>>of(List.of("one"), List.of("two")).iterator(), 1));
        assertEquals(0, output.size());
    }

    @Test
    void rejectsRowsThatDoNotMatchTheDeclaredColumns() {
        var output = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class, () -> writer.write(output, "Results", List.of("A", "B"),
                List.<List<?>>of(List.of("one")).iterator(), 10));
        assertEquals(0, output.size());
    }

    @Test
    void preservesDecimalsOutsideExcelNumericRangeAsText() throws Exception {
        var output = new ByteArrayOutputStream();
        var tiny = new BigDecimal("1e-400");
        var huge = new BigDecimal("1e400");
        writer.write(output, "Results", List.of("Small", "Large"),
                List.<List<?>>of(List.of(tiny, huge)).iterator(), 1);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(output.toByteArray()))) {
            var row = workbook.getSheetAt(0).getRow(1);
            assertEquals(tiny.toPlainString(), row.getCell(0).getStringCellValue());
            assertEquals(huge.toPlainString(), row.getCell(1).getStringCellValue());
        }
    }

    @Test
    void rejectsAnExtremeDecimalBeforeExpandingItToText() {
        var output = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class, () -> writer.write(output, "Results", List.of("A"),
                List.<List<?>>of(List.of(new BigDecimal(java.math.BigInteger.ONE, -1_000_000_000))).iterator(), 1));
        assertEquals(0, output.size());
    }

    @Test
    void removesPoiTemporaryDataWhenAnAuthorizationCheckFailsMidExport() throws Exception {
        org.apache.poi.util.TempFile.setTempFileCreationStrategy(new org.apache.poi.util.DefaultTempFileCreationStrategy(temporary.toFile()));
        try {
            var rows = new java.util.Iterator<List<?>>() {
                private boolean first = true;
                @Override public boolean hasNext() { if (first) return true; throw new SecurityException("authorization changed"); }
                @Override public List<?> next() { first = false; return List.of("sensitive value"); }
            };
            assertThrows(SecurityException.class, () -> writer.write(new ByteArrayOutputStream(), "Results", List.of("A"), rows, 10));
            try (var files = Files.list(temporary)) { assertEquals(0, files.count()); }
        } finally {
            org.apache.poi.util.TempFile.setTempFileCreationStrategy(new org.apache.poi.util.DefaultTempFileCreationStrategy());
        }
    }
}
