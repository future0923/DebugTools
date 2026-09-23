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
package io.github.future0923.debug.tools.base.logging;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Bounded in-process log buffer used by the DebugTools HTTP diagnostics API. */
public final class DebugToolsLogBuffer {
    private static final int MAX_ENTRIES = 500;
    private static final int MAX_BYTES = 512 * 1024;
    private static final Deque<Record> RECORDS = new ArrayDeque<>();
    private static int bytes;

    private DebugToolsLogBuffer() { }

    public static synchronized void add(String level, String logger, String thread, String message, String throwable) {
        String safe = message == null ? "" : message;
        int size = safe.length() * 2;
        RECORDS.addLast(new Record(System.currentTimeMillis(), level, logger, thread, safe, throwable));
        bytes += size;
        while (RECORDS.size() > MAX_ENTRIES || bytes > MAX_BYTES) {
            Record removed = RECORDS.removeFirst();
            bytes -= removed.message == null ? 0 : removed.message.length() * 2;
        }
    }

    public static synchronized List<Record> recent(int limit, long since, String level, String keyword) {
        List<Record> result = new ArrayList<>();
        int max = Math.max(1, Math.min(limit, 200));
        for (Record record : RECORDS) {
            if (record.timestamp < since) continue;
            if (level != null && !level.isEmpty() && !level.equalsIgnoreCase(record.level)) continue;
            if (keyword != null && !keyword.isEmpty() && !record.message.contains(keyword)) continue;
            result.add(record);
        }
        if (result.size() > max) return result.subList(result.size() - max, result.size());
        return result;
    }

    public static final class Record {
        public final long timestamp;
        public final String level;
        public final String logger;
        public final String thread;
        public final String message;
        public final String throwable;
        public Record(long timestamp, String level, String logger, String thread, String message, String throwable) {
            this.timestamp = timestamp; this.level = level; this.logger = logger; this.thread = thread; this.message = message; this.throwable = throwable;
        }
    }
}
