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


import io.github.future0923.debug.tools.base.hutool.core.convert.Convert;
import io.github.future0923.debug.tools.base.hutool.core.util.ReflectUtil;
import io.github.future0923.debug.tools.base.hutool.core.util.StrUtil;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Date;


public enum DataSourceDriverClassEnum {
    /**
     * mysql
     */
    MYSQL(
            "mysql",
            "com.mysql",
            (sta, parameters) -> {
                String sql = sta.toString().replace("** BYTE ARRAY DATA **", "NULL");
                return sql.replace("com.mysql.jdbc.ClientPreparedStatement:", "")
                        .replace("com.mysql.cj.jdbc.ClientPreparedStatement:", "")
                        .replaceFirst("com\\.mysql\\.jdbc\\.ServerPreparedStatement\\[\\d+]:\\s*", "")
                        .replaceFirst("com\\.mysql\\.cj\\.jdbc\\.ServerPreparedStatement\\[\\d+]:\\s*", "");
            }
    ),

    /**
     * postgresql
     */
    POSTGRESQL(
            "postgresql",
            "org.postgresql",
            (sta, parameters) -> formatStringSql(sta.toString(), parameters)
    ),

    /**
     * kingbase
     */
    KINGBASE(
            "kingbase",
            "com.kingbase8",
            (sta, parameters) -> formatStringSql(sta.toString(), parameters)
    ),

    /**
     * sqlserver
     */
    SQLSERVER(
            "sqlserver",
            "com.microsoft.sqlserver",
            (sta, parameters) -> {
                Object[] inOutParam = (Object[]) ReflectUtil.getFieldValue(sta, "inOutParam");
                Object[] parameterValues = new Object[inOutParam.length];
                for (int i = 0; i < inOutParam.length; i++) {
                    parameterValues[i] = ReflectUtil.invoke(inOutParam[i], "getSetterValue");
                }
                final String statementQuery = (String) ReflectUtil.getFieldValue(sta, "userSQL");
                return formatStringSql(statementQuery, parameterValues);
            }
    ),
    /**
     * clickhouse
     */
    CLICKHOUSE(
            "clickhouse",
            "com.clickhouse",
            (sta, parameters) -> sta.toString()
    ),

    /**
     * oracle
     */
    ORACLE(
            "oracle",
            "oracle.jdbc",
            (sta, parameters) -> {
                String statementQuery = ReflectUtil.getFieldValue(ReflectUtil.getFieldValue(sta, "preparedStatement"), "sqlObject").toString();
                return formatStringSql(statementQuery, parameters);
            }
    ),

    /**
     * dm
     */
    DM(
            "dm",
            "dm.jdbc",
            (sta, parameters) -> {
                String statementQuery;
                Object rpstmt = ReflectUtil.getFieldValue(sta, "rpstmt");
                if (rpstmt == null) {
                    statementQuery = ReflectUtil.getFieldValue(sta, "nativeSql").toString();
                } else {
                    statementQuery = ReflectUtil.getFieldValue(rpstmt, "originalSql").toString();
                }
                return formatStringSql(statementQuery, parameters);
            }
    ),
    ;

    DataSourceDriverClassEnum(String type, String packagePrefix, SqlFormat format) {
        this.type = type;
        this.packagePrefix = packagePrefix;
        this.format = format;
    }

    public String getType() {
        return type;
    }

    public SqlFormat getFormat() {
        return format;
    }

    private final String type;
    private final String packagePrefix;
    private final SqlFormat format;

    /**
     * 格式化sql
     *
     * @param statementQuery  带有占位符的sql
     * @param parameterValues 参数值
     * @return 格式化后的sql
     */
    private static String formatStringSql(String statementQuery, Object[] parameterValues) {
        final StringBuilder sb = new StringBuilder();
        int currentParameter = 0;
        for (int pos = 0; pos < statementQuery.length(); pos++) {
            char character = statementQuery.charAt(pos);
            if (statementQuery.charAt(pos) == '?' && currentParameter <= parameterValues.length) {
                Object getSetterValue = parameterValues[currentParameter];
                if ("NULL".equals(getSetterValue)) {
                    sb.append("NULL"); // 输出 SQL NULL
                } else if (getSetterValue instanceof String) {
                    sb.append("'").append(getSetterValue).append("'");
                } else if (
                        getSetterValue instanceof Date
                                || getSetterValue instanceof LocalDateTime
                                || getSetterValue instanceof LocalDate
                                || getSetterValue instanceof LocalTime) {
                    sb.append("'").append(getSetterValue).append("'");
                } else {
                    sb.append(Convert.toStr(getSetterValue));
                }
                currentParameter++;
            } else {
                sb.append(character);
            }
        }
        return sb.toString();
    }

    /**
     * 根据statement类名获取数据库类型
     *
     * @param statementClassName statement类名
     * @return 数据驱动枚举
     */
    public static DataSourceDriverClassEnum of(String statementClassName) {
        if (StrUtil.isBlank(statementClassName)) {
            return null;
        }
        for (DataSourceDriverClassEnum dbType : values()) {
            if (statementClassName.startsWith(dbType.packagePrefix)) {
                return dbType;
            }
        }
        return null;
    }

    /**
     * 是否为有效的数据库驱动
     *
     * @param className 驱动类
     * @return 布尔值
     */
    public static boolean isTargetDriver(String className) {
        if (StrUtil.isBlank(className)) {
            return Boolean.FALSE;
        }
        return JdbcDriverClasses.isDriverClass(className);
    }

    /**
     * 获取数据库类型，简称
     *
     * @param className 驱动力
     * @return 数据库类型，如mysql
     */
    public static String getSqlDriverType(String className) {
        if (StrUtil.isBlank(className)) {
            return "";
        }
        return JdbcDriverClasses.getDriverType(className);
    }
}
