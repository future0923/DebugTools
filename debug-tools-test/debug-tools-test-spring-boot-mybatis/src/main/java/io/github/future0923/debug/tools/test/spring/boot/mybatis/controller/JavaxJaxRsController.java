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

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

/**
 * 用于验证 IDEA 插件对 Javax JAX-RS URL 的搜索支持。
 *
 * @author future0923
 */
@Path("/javax-jax-rs")
public class JavaxJaxRsController {

    /**
     * jax-rs注释
     */
    @GET
    @Path("/get")
    public String get() {
        return "get";
    }

    @POST
    @Path("/post")
    public String post() {
        return "post";
    }

    @PUT
    @Path("/put/{id}")
    public String put(@PathParam("id") String id) {
        return "put:" + id;
    }

    @DELETE
    @Path("/delete/{id}")
    public String delete(@PathParam("id") String id) {
        return "delete:" + id;
    }

    @HEAD
    @Path("/head")
    public void head() {
    }

    @OPTIONS
    @Path("/options")
    public String options() {
        return "options";
    }
}
