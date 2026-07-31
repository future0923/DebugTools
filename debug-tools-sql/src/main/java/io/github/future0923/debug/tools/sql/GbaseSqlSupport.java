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

import io.github.future0923.debug.tools.base.hutool.core.util.ReflectUtil;
import io.github.future0923.debug.tools.base.hutool.core.util.StrUtil;

import java.lang.reflect.Field;
import java.sql.Statement;

/**
 * GBase 8s（gbasedbtjdbc）SQL 提取。
 * <p>
 * 驱动字段名经混淆且跨版本不一致，不能简单 {@code getFieldValue("v")}：
 * <ul>
 *   <li>3.6.3：子类 {@code IfxPreparedStatement.v} 为 {@link String}，存原始 SQL</li>
 *   <li>3.6.5：父类 {@code IfxStatement.v} 为 {@code short}（默认 0），原始 SQL 在子类字段 {@code B}；
 *       若误读父类 {@code v} 会打印成 {@code 0;}</li>
 * </ul>
 * 因此只接受 {@link String} 类型字段，并优先读子类声明字段，再回退 {@code commandString}。
 *
 * @author C.
 */
final class GbaseSqlSupport {

    private GbaseSqlSupport() {
    }

    /**
     * 从 GBase Statement 提取带 {@code ?} 的 SQL 文本。
     *
     * @param sta JDBC Statement（通常为 IfxPreparedStatement）
     * @return SQL 文本；无法提取时回退 {@link Object#toString()}
     */
    static String extractSql(Statement sta) {
        if (sta == null) {
            return "";
        }
        // 子类混淆字段：3.6.5 用 B，3.6.3 用 v（必须限定 String，避开父类 short v）
        String fromDeclared = findFirstStringField(sta, "B", "v");
        if (StrUtil.isNotBlank(fromDeclared)) {
            return fromDeclared;
        }
        // 构造时会写入 nativeSQL 结果，两个版本都较稳定
        Object commandString = ReflectUtil.getFieldValue(sta, "commandString");
        if (commandString != null && StrUtil.isNotBlank(commandString.toString())) {
            return commandString.toString();
        }
        return sta.toString();
    }

    /**
     * 在类继承链上查找指定名称且类型为 String 的字段值。
     * <p>
     * 使用 {@link Class#getDeclaredField(String)} 逐级查找，并显式校验类型，
     * 避免 hutool {@code getFieldValue("v")} 命中父类非 String 字段。
     */
    private static String findFirstStringField(Object obj, String... names) {
        Class<?> type = obj.getClass();
        while (type != null && type != Object.class) {
            for (String name : names) {
                try {
                    Field field = type.getDeclaredField(name);
                    if (field.getType() != String.class) {
                        // 同名但类型不对（如 3.6.5 父类 short v），跳过继续向上找
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(obj);
                    if (value != null && StrUtil.isNotBlank(value.toString())) {
                        return value.toString();
                    }
                } catch (NoSuchFieldException ignored) {
                    // 当前类没有该字段，尝试下一个名字 / 父类
                } catch (IllegalAccessException ignored) {
                    // 不可访问时继续其他候选，避免打断 SQL 打印主流程
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }
}
