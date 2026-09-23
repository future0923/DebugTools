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
package io.github.future0923.debug.tools.test.tomcat.legacy.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller used to verify a traditional Spring MVC application on Tomcat 8.5.
 *
 * @author future0923
 */
@RestController
public class TestController {

    @GetMapping("/")
    public String test(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Integer age) {
        return "name = " + name + ", age = " + age;
    }

    @GetMapping("/b")
    public String test2() {
        return "b";
    }

    @GetMapping("/c")
    public String c() {
        return "c";
    }
}
