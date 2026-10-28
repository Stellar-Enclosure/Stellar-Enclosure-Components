# Excel 导出组件

`stellar-enclosure-component-common-core` 提供有界 XLSX 写入和分页导出流程。业务服务负责提供与列表一致的查询、已授权的列顺序、列标题、行投影以及当前用户的权限复核；组件不读取天枢或鉴神，也不会自动应用数据范围或字段脱敏。

`XlsxExport` 接收业务分页策略、逐页授权投影、权限复核回调和生成后的审计回调。它在开始、每页投影后及返回文件前执行权限复核；任一回调失败时不返回文件。导出受行数、估算的中间内容大小和最终文件大小限制，临时文件在结束时删除。分页查询应使用稳定排序；查询期间业务数据变化时，组件不保证数据库快照一致性。

单元格支持字符串、布尔值和常用数字类型。字符串始终作为文本写入；超过 Excel 数字精度的整数或小数也写为文本。调用方应先完成字段过滤和脱敏，不能把原始业务对象直接作为投影结果。

Excel 使用 Apache POI 5.4.1；该依赖在 common-core 中为 optional，使用 Excel 的宿主应用需显式引入 `org.apache.poi:poi-ooxml:5.4.1`，避免所有 common-core 用户都加载 POI。其 Commons Compress 版本为 1.27.1；宿主应用还应检查 Maven 版本仲裁，避免选中不兼容的旧 ZIP API。

```java
byte[] file = new XlsxExport().export("Orders", headers, XlsxExport.Range.FILTERED, pageNo,
        new XlsxExport.Limits(500, 50_000, 64L * 1024 * 1024),
        (page, size) -> queryAuthorizedPage(filter, page, size),
        rows -> projectAuthorizedColumns(rows, columns),
        () -> verifyCurrentPermissionAndTemplate(),
        rowCount -> recordGeneratedExport(rowCount));
```

`queryAuthorizedPage`、`projectAuthorizedColumns`、`verifyCurrentPermissionAndTemplate` 和 `recordGeneratedExport` 是业务方的示意方法。`PageSource` 返回 `XlsxExport.Page<T>`，投影必须保持行数不变。生成文件后再向客户端写入，避免权限复核失败时返回部分文件。不要把跨服务的检查点误认为跨数据库事务；已经下载的文件也无法在后续撤权时收回。
