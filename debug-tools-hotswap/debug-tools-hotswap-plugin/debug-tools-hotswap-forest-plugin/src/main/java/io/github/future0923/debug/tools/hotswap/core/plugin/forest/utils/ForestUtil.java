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
package io.github.future0923.debug.tools.hotswap.core.plugin.forest.utils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

public class ForestUtil {

    @SuppressWarnings("unchecked")
    public static boolean isForestClient(ClassLoader loader, Class<?> clazz) {
        try {
            // 1.是个接口
            // 2.接口上有BaseRequest注解
            // 3.接口方法上有forest的注解
            if (clazz.isInterface() && (clazz.getAnnotation((Class<? extends Annotation>) loader.loadClass("com.dtflys.forest.annotation.BaseRequest")) != null || classHasForestAnnotation(clazz))) {
                return true;
            }
        } catch (ClassNotFoundException ignored) {
        }
        return false;
    }

    public static boolean classHasForestAnnotation(Class<?> clazz) {
        for (Method method : clazz.getMethods()) {
            for (Annotation annotation : method.getAnnotations()) {
                if (annotation.annotationType().getName().startsWith("com.dtflys.forest.annotation.")) {
                    return true;
                }
            }
        }

        return false;
    }
}
