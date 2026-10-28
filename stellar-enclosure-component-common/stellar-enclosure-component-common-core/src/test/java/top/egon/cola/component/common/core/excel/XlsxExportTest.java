package top.egon.cola.component.common.core.excel;

import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class XlsxExportTest {
    private final XlsxExport exporter = new XlsxExport();

    @Test void pagesTheFilteredQueryAndWritesOnlyProjectedCells() throws Exception {
        List<Integer> pages = new ArrayList<>();
        AtomicInteger authorizedChecks = new AtomicInteger();
        AtomicInteger count = new AtomicInteger();
        byte[] file = exporter.export("Results", List.of("value"), XlsxExport.Range.FILTERED, 4,
                new XlsxExport.Limits(2, 5, 1_000_000), (page, size) -> {
                    pages.add(page);
                    return new XlsxExport.Page<>(page == 1 ? List.of("one", "two") : List.of("three"), 3);
                }, values -> values.stream().<List<?>>map(value -> List.of(value + "-safe")).toList(),
                authorizedChecks::incrementAndGet, count::set);
        assertEquals(List.of(1, 2), pages);
        assertEquals(3, count.get());
        assertTrue(authorizedChecks.get() >= 4);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(file))) {
            assertEquals("one-safe", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
            assertEquals("three-safe", workbook.getSheetAt(0).getRow(3).getCell(0).getStringCellValue());
        }
    }

    @Test void rejectsAnOversizedResultAndStopsWhenTheAuthorizationFenceChanges() {
        var limits = new XlsxExport.Limits(2, 3, 1_000_000);
        assertThrows(XlsxExport.ExportLimitException.class, () -> exporter.export("Results", List.of("value"),
                XlsxExport.Range.FILTERED, 1, limits, (page, size) -> new XlsxExport.Page<>(List.of("a"), 4),
                values -> values.stream().<List<?>>map(List::of).toList(), () -> {}, count -> {}));
        AtomicInteger checks = new AtomicInteger();
        assertThrows(SecurityException.class, () -> exporter.export("Results", List.of("value"), XlsxExport.Range.FILTERED,
                1, limits, (page, size) -> new XlsxExport.Page<>(List.of("a"), 1),
                values -> values.stream().<List<?>>map(List::of).toList(),
                () -> {if (checks.incrementAndGet() == 2) throw new SecurityException("changed");}, count -> fail("must not publish")));
    }

    @Test void rejectsAProjectionThatDropsRows() {
        assertThrows(IllegalStateException.class, () -> exporter.export("Results", List.of("value"), XlsxExport.Range.PAGE,
                1, new XlsxExport.Limits(2, 5, 1_000_000), (page, size) -> new XlsxExport.Page<>(List.of("a"), 1),
                values -> List.of(), () -> {}, count -> {}));
    }

    @Test void boundsProjectedContentBeforeWritingTemporaryWorkbookData() {
        assertThrows(XlsxExport.ExportLimitException.class, () -> exporter.export("Results", List.of("value"), XlsxExport.Range.PAGE,
                1, new XlsxExport.Limits(2, 5, 130), (page, size) -> new XlsxExport.Page<>(List.of("too long"), 1),
                values -> values.stream().<List<?>>map(List::of).toList(), () -> {}, count -> fail("must not publish")));
    }
}
