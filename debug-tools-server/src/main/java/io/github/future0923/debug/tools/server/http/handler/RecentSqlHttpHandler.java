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
package io.github.future0923.debug.tools.server.http.handler;

import com.sun.net.httpserver.Headers;
import io.github.future0923.debug.tools.sql.SqlRecentBuffer;
import java.util.List;

public class RecentSqlHttpHandler extends BaseHttpHandler<RecentSqlHttpHandler.Request, List<SqlRecentBuffer.Record>> {
    public static final RecentSqlHttpHandler INSTANCE = new RecentSqlHttpHandler();
    public static final String PATH = "/sql/recent";
    private RecentSqlHttpHandler() { }
    @Override protected List<SqlRecentBuffer.Record> doHandle(Request req, Headers headers) {
        Request request = req == null ? new Request() : req;
        return SqlRecentBuffer.recent(request.limit, request.since, request.keyword);
    }
    public static class Request { public int limit = 50; public long since; public String keyword; }
}
