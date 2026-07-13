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
package io.github.future0923.debug.tools.base.constants;

/**
 * @author future0923
 */
public interface ProjectConstants {

    Boolean DEBUG = false;

    String NAME = "DebugTools";

    String VERSION = "5.1.0";

    String SPRING_EXTENSION_JAR_NAME = "debug-tools-extension-spring";

    String SPRING_SERVLET_EXTENSION_JAR_NAME = "debug-tools-extension-spring-servlet";

    String SPRING_REACTIVE_EXTENSION_JAR_NAME = "debug-tools-extension-spring-reactive";

    String SPRING_JAKARTA_EXTENSION_JAR_NAME = "debug-tools-extension-spring-jakarta";

    String SOLON_EXTENSION_JAR_NAME = "debug-tools-extension-solon";

    String XXMLJOB_EXTENSION_JAR_NAME = "debug-tools-extension-xxljob";

    String CONFIG_FILE = "debug-tools.properties";

    String AUTO_ATTACH_FLAG_FILE = NAME + "/auto_attach.txt";

    String PROJECT_PACKAGE_PREFIX = "io.github.future0923.debug.tools";

    String PROJECT_PACKAGE_PATH = "io/github/future0923/debug/tools";

    String PLUGIN_ID = "io.github.future0923.DebugPower";
}
