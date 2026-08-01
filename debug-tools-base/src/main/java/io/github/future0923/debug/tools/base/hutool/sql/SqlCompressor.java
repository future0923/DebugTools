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
package io.github.future0923.debug.tools.base.hutool.sql;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author future0923
 */
public class SqlCompressor {

    /**
     * 将格式化的 SQL 压缩为单行，保留字符串字面量，移除注释与多余空白
     *
     * @param sql 格式化或缩进过的 SQL 语句
     * @return 压缩后的 SQL 字符串
     */
    public static String compressSql(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }

        // 步骤 1：保护字符串字面量内容（避免误删字符串中的注释或空格）
        Pattern strPattern = Pattern.compile("('[^']*')|(\"[^\"]*\")");
        Matcher matcher = strPattern.matcher(sql);

        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        int strIndex = 0;
        Map<String, String> strLiterals = new LinkedHashMap<>();

        while (matcher.find()) {
            // 非字符串部分：清除注释、压缩空格
            String before = sql.substring(lastEnd, matcher.start());
            String cleaned = removeComments(before);
            result.append(normalizeWhitespace(cleaned));

            // 占位替换字符串
            String literal = matcher.group();
            String key = "__STR_" + (strIndex++) + "__";
            result.append(key);
            strLiterals.put(key, literal);

            lastEnd = matcher.end();
        }

        // 处理最后一段
        String tail = sql.substring(lastEnd);
        result.append(normalizeWhitespace(removeComments(tail)));

        // 步骤 2：还原被占位的字符串字面量
        String compressed = result.toString();
        for (Map.Entry<String, String> entry : strLiterals.entrySet()) {
            compressed = compressed.replace(entry.getKey(), entry.getValue());
        }

        return compressed.trim();
    }

    /**
     * 清除 SQL 注释（行注释 -- 和块注释 /* *\/）
     */
    private static String removeComments(String input) {
        // 移除 -- 注释（直至行尾）
        input = input.replaceAll("(?m)--.*?$", "");
        // 移除 /**/ 块注释
        // input = input.replaceAll("/\\*.*?\\*/", "");
        return input;
    }

    /**
     * 压缩多余空白字符为单个空格
     */
    private static String normalizeWhitespace(String input) {
        return input.replaceAll("[\\s\\u00A0]+", " ");
    }

}
