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
import io.github.future0923.debug.tools.base.logging.DebugToolsLogBuffer;

import java.util.List;

public class RecentLogsHttpHandler extends BaseHttpHandler<RecentLogsHttpHandler.Request, List<DebugToolsLogBuffer.Record>> {
    public static final RecentLogsHttpHandler INSTANCE = new RecentLogsHttpHandler();
    public static final String PATH = "/logs/recent";
    private RecentLogsHttpHandler() { }
    @Override protected List<DebugToolsLogBuffer.Record> doHandle(Request req, Headers headers) {
        Request request = req == null ? new Request() : req;
        return DebugToolsLogBuffer.recent(request.limit, request.since, request.level, request.keyword);
    }
    public static class Request { public int limit = 100; public long since; public String level; public String keyword; }
}
