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
package io.github.future0923.debug.tools.extension.spring;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpringEnvUtilTest {

    @AfterEach
    void tearDown() throws Exception {
        System.getProperties().remove("server.port");
        setStaticField("init", false);
        setStaticField("beanFactories", null);
        setStaticField("applicationContexts", null);
    }

    @Test
    void getSpringConfigReadsApplicationContextEnvironmentBeforeEnvironmentBeanFallback() throws Exception {
        StaticApplicationContext webContext = new StaticApplicationContext();
        ConfigurableEnvironment webEnvironment = webContext.getEnvironment();
        webEnvironment.getPropertySources()
                .addFirst(new MapPropertySource("web-test", Collections.singletonMap("server.port", "3041")));
        StaticApplicationContext bootstrapContext = new StaticApplicationContext();
        bootstrapContext.getBeanFactory().registerSingleton("environment", new StandardEnvironment());
        setStaticField("init", true);
        setStaticField("beanFactories", Collections.emptyList());
        setStaticField("applicationContexts", Arrays.asList(webContext, bootstrapContext));

        Object port = SpringEnvUtil.getSpringConfig("server.port");

        assertEquals("3041", port);
    }

    private static void setStaticField(String fieldName, Object value) throws Exception {
        Field field = SpringEnvUtil.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }
}
