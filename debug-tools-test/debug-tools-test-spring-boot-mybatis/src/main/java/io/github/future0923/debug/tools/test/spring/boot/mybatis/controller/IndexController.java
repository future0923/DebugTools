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

import lombok.Data;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Arrays;

/**
 * @author future0923
 */
@Controller
public class IndexController {

    @GetMapping("/thymeleaf")
    public String thymeleaf(Model model) {
        model.addAttribute("message", "Thymeleaf");
        model.addAttribute("test", "test");
        return "thymeleaf";
    }

    @GetMapping("/freemarker")
    public String freemarker(Model model) {
        model.addAttribute("name", "FreeMarker");
        model.addAttribute("users", Arrays.asList(new User("1", 18, "男"), new User("2", 19, "女")));
        return "index";
    }

    @Data
    public static class User {
        private String name;
        private Integer age;
        private String sss;
        public User() {
        }
        public User(String name, Integer age, String sex) {
            this.name = name;
            this.age = age;
            this.sss = sex;
        }
    }
}