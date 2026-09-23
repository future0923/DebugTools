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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Bounded recent SQL records for the DebugTools diagnostics endpoint. */
public final class SqlRecentBuffer {
    private static final int MAX_ENTRIES = 500;
    private static final Deque<Record> RECORDS = new ArrayDeque<>();
    private SqlRecentBuffer() { }
    public static synchronized void add(String sql, long consumeTimeMillis, String dbType, String applicationName) {
        RECORDS.addLast(new Record(System.currentTimeMillis(), sql, consumeTimeMillis, dbType, applicationName));
        while (RECORDS.size() > MAX_ENTRIES) RECORDS.removeFirst();
    }
    public static synchronized List<Record> recent(int limit, long since, String keyword) {
        List<Record> result = new ArrayList<>();
        for (Record record : RECORDS) {
            if (record.timestamp < since) continue;
            if (keyword != null && !keyword.isEmpty() && !record.sql.contains(keyword)) continue;
            result.add(record);
        }
        int max = Math.max(1, Math.min(limit, 200));
        return result.size() > max ? result.subList(result.size() - max, result.size()) : result;
    }
    public static final class Record {
        public final long timestamp; public final String sql; public final long consumeTimeMillis; public final String dbType; public final String applicationName;
        public Record(long timestamp, String sql, long consumeTimeMillis, String dbType, String applicationName) { this.timestamp = timestamp; this.sql = sql; this.consumeTimeMillis = consumeTimeMillis; this.dbType = dbType; this.applicationName = applicationName; }
    }
}
