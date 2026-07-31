/*
 * Copyright (C) 2024-2025 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.future0923.debug.tools.sql;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLWarning;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 覆盖 GBase 驱动跨版本字段布局，防止再出现打印 {@code 0;} 的回归。
 */
class GbaseSqlSupportTest {

    @Test
    void extractSql_from365Layout_shouldPreferStringB_notParentShortV() {
        // 模拟 3.6.5：父类 short v=0，SQL 在子类 B / commandString
        Gbase365Statement statement = new Gbase365Statement();
        statement.v = 0;
        statement.B = "select * from t where id = ?";
        statement.commandString = "select * from t where id = ?";

        String sql = GbaseSqlSupport.extractSql(statement);

        assertEquals("select * from t where id = ?", sql);
        assertFalse("0".equals(sql), "must not read parent short v as SQL");
    }

    @Test
    void extractSql_from363Layout_shouldReadChildStringV() {
        // 模拟 3.6.3：子类 String v 存原始 SQL
        Gbase363Statement statement = new Gbase363Statement();
        statement.v = "update user set name = ? where id = ?";
        statement.commandString = "update user set name = ? where id = ?";

        String sql = GbaseSqlSupport.extractSql(statement);

        assertEquals("update user set name = ? where id = ?", sql);
    }

    @Test
    void extractSql_fallbackCommandString_whenObfuscatedFieldMissing() {
        CommandStringOnlyStatement statement = new CommandStringOnlyStatement();
        statement.commandString = "select 1 from dual";

        assertEquals("select 1 from dual", GbaseSqlSupport.extractSql(statement));
    }

    @Test
    void format_gbasedbt_shouldFillPlaceholders_for365Layout() {
        Gbase365Statement statement = new Gbase365Statement();
        statement.v = 0;
        statement.B = "select * from t where id = ? and name = ?";
        statement.commandString = "select * from t where id = ? and name = ?";

        String formatted = DataSourceDriverClassEnum.GBASEDBT.getFormat()
                .format(statement, new Object[]{1, "tom"});

        assertEquals("select * from t where id = 1 and name = 'tom'", formatted);
    }

    /** 3.6.5 字段布局：父类 short v + 子类 String B */
    private static class Gbase365Base implements Statement {
        short v;
        public String commandString;

        @Override public ResultSet executeQuery(String sql) { return null; }
        @Override public int executeUpdate(String sql) { return 0; }
        @Override public void close() { }
        @Override public int getMaxFieldSize() { return 0; }
        @Override public void setMaxFieldSize(int max) { }
        @Override public int getMaxRows() { return 0; }
        @Override public void setMaxRows(int max) { }
        @Override public void setEscapeProcessing(boolean enable) { }
        @Override public int getQueryTimeout() { return 0; }
        @Override public void setQueryTimeout(int seconds) { }
        @Override public void cancel() { }
        @Override public SQLWarning getWarnings() { return null; }
        @Override public void clearWarnings() { }
        @Override public void setCursorName(String name) { }
        @Override public boolean execute(String sql) { return false; }
        @Override public ResultSet getResultSet() { return null; }
        @Override public int getUpdateCount() { return 0; }
        @Override public boolean getMoreResults() { return false; }
        @Override public void setFetchDirection(int direction) { }
        @Override public int getFetchDirection() { return 0; }
        @Override public void setFetchSize(int rows) { }
        @Override public int getFetchSize() { return 0; }
        @Override public int getResultSetConcurrency() { return 0; }
        @Override public int getResultSetType() { return 0; }
        @Override public void addBatch(String sql) { }
        @Override public void clearBatch() { }
        @Override public int[] executeBatch() { return new int[0]; }
        @Override public Connection getConnection() { return null; }
        @Override public boolean getMoreResults(int current) { return false; }
        @Override public ResultSet getGeneratedKeys() { return null; }
        @Override public int executeUpdate(String sql, int autoGeneratedKeys) { return 0; }
        @Override public int executeUpdate(String sql, int[] columnIndexes) { return 0; }
        @Override public int executeUpdate(String sql, String[] columnNames) { return 0; }
        @Override public boolean execute(String sql, int autoGeneratedKeys) { return false; }
        @Override public boolean execute(String sql, int[] columnIndexes) { return false; }
        @Override public boolean execute(String sql, String[] columnNames) { return false; }
        @Override public int getResultSetHoldability() { return 0; }
        @Override public boolean isClosed() { return false; }
        @Override public void setPoolable(boolean poolable) { }
        @Override public boolean isPoolable() { return false; }
        @Override public void closeOnCompletion() { }
        @Override public boolean isCloseOnCompletion() { return false; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    private static class Gbase365Statement extends Gbase365Base {
        String B;
    }

    /** 3.6.3 字段布局：子类 String v */
    private static class Gbase363Statement extends Gbase365Base {
        String v;
    }

    private static class CommandStringOnlyStatement extends Gbase365Base {
    }
}
