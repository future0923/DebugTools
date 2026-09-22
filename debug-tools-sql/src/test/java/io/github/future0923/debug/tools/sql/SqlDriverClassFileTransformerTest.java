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
package io.github.future0923.debug.tools.sql;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.sql.Connection;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlDriverClassFileTransformerTest {

    private static final String DRIVER_CLASS_NAME = "com.mysql.cj.jdbc.NonRegisteringDriver";
    private static final String DRIVER_RESOURCE = "/com/mysql/cj/jdbc/NonRegisteringDriver.class";

    @Test
    void transformedDriverCanProxyConnectionWhenAgentClassesAreHiddenFromDriverLoader() throws Exception {
        byte[] originalDriverBytes = readResourceBytes(DRIVER_RESOURCE);
        byte[] transformedDriverBytes = new SqlDriverClassFileTransformer().transform(
                SqlDriverClassFileTransformerTest.class.getClassLoader(),
                DRIVER_CLASS_NAME.replace('.', '/'),
                null,
                (ProtectionDomain) null,
                originalDriverBytes
        );

        assertNotNull(transformedDriverBytes);

        HiddenAgentClassLoader driverLoader = new HiddenAgentClassLoader(transformedDriverBytes);
        Class<?> driverClass = driverLoader.loadClass(DRIVER_CLASS_NAME);
        Object driver = driverClass.getDeclaredConstructor().newInstance();
        Method connect = driverClass.getDeclaredMethod("connect", String.class, Properties.class);

        Object connection = connect.invoke(driver, "jdbc:mysql://localhost/test", new Properties());

        assertTrue(connection instanceof Connection);
    }

    @Test
    void transformedDriverKeepsNullWhenDriverRejectsUrl() throws Exception {
        byte[] originalDriverBytes = readResourceBytes(DRIVER_RESOURCE);
        byte[] transformedDriverBytes = new SqlDriverClassFileTransformer().transform(
                SqlDriverClassFileTransformerTest.class.getClassLoader(),
                DRIVER_CLASS_NAME.replace('.', '/'),
                null,
                (ProtectionDomain) null,
                originalDriverBytes
        );

        assertNotNull(transformedDriverBytes);

        HiddenAgentClassLoader driverLoader = new HiddenAgentClassLoader(transformedDriverBytes);
        Class<?> driverClass = driverLoader.loadClass(DRIVER_CLASS_NAME);
        Object driver = driverClass.getDeclaredConstructor().newInstance();
        Method connect = driverClass.getDeclaredMethod("connect", String.class, Properties.class);

        Object connection = connect.invoke(driver, "jdbc:h2:mem:test", new Properties());

        assertNull(connection);
    }

    private static byte[] readResourceBytes(String resource) throws Exception {
        try (InputStream inputStream = SqlDriverClassFileTransformerTest.class.getResourceAsStream(resource);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            assertNotNull(inputStream);
            byte[] buffer = new byte[1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
        }
    }

    private static class HiddenAgentClassLoader extends ClassLoader {

        private final byte[] driverBytes;

        private HiddenAgentClassLoader(byte[] driverBytes) {
            super(ClassLoader.getSystemClassLoader().getParent());
            this.driverBytes = driverBytes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("io.github.future0923.debug.tools")) {
                throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (DRIVER_CLASS_NAME.equals(name)) {
                return defineClass(name, driverBytes, 0, driverBytes.length);
            }
            throw new ClassNotFoundException(name);
        }
    }
}
