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
package io.github.future0923.debug.tools.test.spring.boot.mybatis.controller;

import javax.ws.rs.POST;
import javax.ws.rs.Path;

/**
 * 用于验证 IDEA 插件对声明在接口上的 Javax JAX-RS URL 搜索支持。
 *
 * @author future0923
 */
@Path("user")
public interface JavaxJaxRsInterface {

    @POST
    @Path("/add")
    String addUser(String name);
}
