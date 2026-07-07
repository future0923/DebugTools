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

/**
 * JDBC 驱动类全限定名常量。
 * <p>
 * 独立于 {@link DataSourceDriverClassEnum}，不依赖 hutool 等外部库，
 * 确保 {@link SqlDriverClassFileTransformer} 在 ClassFileTransformer 上下文中
 * 调用时不会触发额外的类加载导致死锁。
 *
 * @author C.
 */
class JdbcDriverClasses {

    static final String[][] DRIVER_MAPPING = {
            {"com.mysql.jdbc.NonRegisteringDriver", "mysql"},
            {"com.mysql.cj.jdbc.NonRegisteringDriver", "mysql"},
            {"org.postgresql.Driver", "postgresql"},
            {"com.kingbase8.Driver", "kingbase"},
            {"com.microsoft.sqlserver.jdbc.SQLServerDriver", "sqlserver"},
            {"com.clickhouse.jdbc.Driver", "clickhouse"},
            {"oracle.jdbc.driver.OracleDriver", "oracle"},
            {"dm.jdbc.driver.DmDriver", "dm"}
    };

    static boolean isDriverClass(String className) {
        for (String[] entry : DRIVER_MAPPING) {
            if (entry[0].equals(className)) {
                return true;
            }
        }
        return false;
    }

    static String getDriverType(String className) {
        for (String[] entry : DRIVER_MAPPING) {
            if (entry[0].equals(className)) {
                return entry[1];
            }
        }
        return "";
    }

    private JdbcDriverClasses() {
    }
}
