package top.egon.cola.component.outbox.migration;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;
import top.egon.cola.component.outbox.persistence.OutboxShardContext;

import java.sql.SQLException;
import java.util.Locale;

/** Rejects outbox statements that could fan out or change the shard key. */
final class OutboxShardSqlGuard {

    private static final String TABLE = "egon_cola_outbox_message";

    private OutboxShardSqlGuard() {
    }

    static void validate(String sql) throws SQLException {
        if (!sql.toLowerCase(Locale.ROOT).contains(TABLE)) {
            return;
        }
        try {
            var statements = CCJSqlParserUtil.parseStatements(sql);
            if (statements.size() != 1) { throw invalid(); }
            var statement = statements.get(0);
            var tables = new TablesNamesFinder<Void>().getTables(statement);
            boolean outbox = tables.stream().anyMatch(name -> name.replace("\"", "").toLowerCase(Locale.ROOT)
                    .matches("(?:[a-z_][a-z0-9_]*\\.)?" + TABLE));
            if (!outbox) { return; }
            if (!OutboxShardContext.isActive()) { throw invalid(); }
            if (tables.size() != 1) { throw invalid(); }
            long selectCount = java.util.regex.Pattern.compile("(?i)\\bselect\\b").matcher(statement.toString()).results().count();
            if (selectCount > (statement instanceof Select ? 1 : 0)) { throw invalid(); }
            if (statement instanceof Insert insert) {
                validateInsert(insert);
                return;
            }
            Expression where;
            if (statement instanceof Select select && select instanceof PlainSelect plain
                    && plain.getFromItem() instanceof Table && (plain.getJoins() == null || plain.getJoins().isEmpty())
                    && (select.getWithItemsList() == null || select.getWithItemsList().isEmpty())) {
                where = plain.getWhere();
            } else if (statement instanceof Update update) {
                if (update.getFromItem() != null || update.getJoins() != null && !update.getJoins().isEmpty()
                        || update.getStartJoins() != null && !update.getStartJoins().isEmpty()
                        || update.getUpdateSets().stream().flatMap(set -> set.getColumns().stream())
                        .anyMatch(OutboxShardSqlGuard::isSlot)) {
                    throw invalid();
                }
                where = update.getWhere();
            } else if (statement instanceof Delete delete) {
                if (delete.getUsingList() != null && !delete.getUsingList().isEmpty()
                        || delete.getJoins() != null && !delete.getJoins().isEmpty()) {
                    throw invalid();
                }
                where = delete.getWhere();
            } else {
                throw invalid();
            }
            if (!mandatorySlot(where)) { throw invalid(); }
        } catch (net.sf.jsqlparser.JSQLParserException | IllegalArgumentException failure) {
            throw new SQLException("OUTBOX_SHARD_SQL_INVALID", failure);
        }
    }

    private static void validateInsert(Insert insert) throws SQLException {
        if (insert.getColumns() == null || !(insert.getSelect() instanceof Values values)
                || insert.getConflictAction() != null
                        && insert.getConflictAction().getConflictActionType()
                        != net.sf.jsqlparser.statement.insert.ConflictActionType.DO_NOTHING) {
            throw invalid();
        }
        int slotIndex = -1;
        for (int i = 0; i < insert.getColumns().size(); i++) {
            if (isSlot(insert.getColumns().get(i))) {
                if (slotIndex >= 0) { throw invalid(); }
                slotIndex = i;
            }
        }
        var expressions = values.getExpressions();
        if (slotIndex < 0 || expressions == null || expressions.size() != insert.getColumns().size()
                || !(expressions.get(slotIndex) instanceof JdbcParameter || expressions.get(slotIndex) instanceof LongValue)) {
            throw invalid();
        }
    }

    private static boolean mandatorySlot(Expression expression) {
        while (expression instanceof ParenthesedExpressionList<?> list && list.size() == 1
                && list.getFirst() instanceof Expression nested) {
            expression = nested;
        }
        if (expression instanceof AndExpression and) {
            return mandatorySlot(and.getLeftExpression()) || mandatorySlot(and.getRightExpression());
        }
        if (expression instanceof EqualsTo equal) {
            return isSlot(equal.getLeftExpression()) && scalar(equal.getRightExpression())
                    || isSlot(equal.getRightExpression()) && scalar(equal.getLeftExpression());
        }
        return false;
    }

    private static boolean isSlot(Expression expression) {
        return expression instanceof Column column
                && "shard_slot".equalsIgnoreCase(column.getColumnName().replace("\"", "").replace("`", ""));
    }

    private static boolean scalar(Expression expression) {
        return expression instanceof JdbcParameter || expression instanceof LongValue value && value.getValue() >= 0;
    }

    private static SQLException invalid() {
        return new SQLException("OUTBOX_SHARD_SQL_INVALID");
    }
}
