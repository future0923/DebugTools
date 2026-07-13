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
package io.github.future0923.debug.tools.test.spring.boot.three.controller;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

/**
 * 用于验证 IDEA 插件对 Jakarta JAX-RS URL 的搜索支持。
 *
 * @author future0923
 */
@Path("/jakarta-jax-rs")
public class JakartaJaxRsController {

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

    @PATCH
    @Path("/patch/{id}")
    public String patch(@PathParam("id") String id) {
        return "patch:" + id;
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
