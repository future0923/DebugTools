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
package io.github.future0923.debug.tools.utils;

import io.github.future0923.debug.tools.base.hutool.core.util.StrUtil;
import io.github.future0923.debug.tools.base.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SQL文件写入工具类
 */
public class SqlFileWriter {

    private static final Logger logger = Logger.getLogger(SqlFileWriter.class);

    private static final String SQL_DIR = "sql";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final ReentrantLock lock = new ReentrantLock();

    /**
     * 写入 SQL 记录到当前应用当天的历史文件。
     */
    public static void writeSqlRecord(String sql, long consumeTime, String dbType, String applicationName) {
        writeSqlRecord(sql, consumeTime, dbType, applicationName, null, null);
    }

    /**
     * 写入 SQL 记录；存在 IDEA 项目上下文时按项目和主类隔离。
     */
    public static void writeSqlRecord(
            String sql, long consumeTime, String dbType, String applicationName, String projectName, String projectPathHash) {
        lock.lock();
        try {
            LocalDateTime now = LocalDateTime.now();
            String dateStr = now.format(DATE_FORMATTER);
            String timeStr = now.format(TIME_FORMATTER);

            String content = String.format(
                    "-- %s | %s | %dms\n%s\n\n",
                    timeStr, dbType, consumeTime, sql
            );

            Path sqlDir = sqlDir(applicationName, projectName, projectPathHash);
            if (!Files.exists(sqlDir)) {
                Files.createDirectories(sqlDir);
            }

            Path sqlFile = sqlDir.resolve(dateStr + ".sql");
            Files.write(
                    sqlFile,
                    content.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
            logger.debug("SQL record written to file: {}", sqlFile);
        } catch (IOException e) {
            logger.error("Failed to write SQL record to file", e);
        } finally {
            lock.unlock();
        }
    }

    private static Path sqlDir(String applicationName, String projectName, String projectPathHash) {
        Path root = Paths.get(System.getProperty("user.home"), ".debugTools", SQL_DIR);
        if (StrUtil.isBlank(projectName) || StrUtil.isBlank(projectPathHash)) {
            return root.resolve(safeApplicationName(applicationName));
        }
        String projectDirName = safeApplicationName(projectName + "-" + projectPathHash);
        return root.resolve(projectDirName).resolve(safeApplicationName(applicationName));
    }

    private static String safeApplicationName(String applicationName) {
        String safeName = StrUtil.blankToDefault(applicationName, "application")
                .replaceAll("[\\\\/:*?\"<>|\\s]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^[\\-._]+|[\\-._]+$", "");
        return StrUtil.blankToDefault(safeName, "application");
    }
}
