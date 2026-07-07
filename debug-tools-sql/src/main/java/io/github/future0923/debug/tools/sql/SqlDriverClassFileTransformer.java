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

import io.github.future0923.debug.tools.base.logging.Logger;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;

import java.io.ByteArrayInputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;

/**
 * 转换驱动类字节码
 * @author future0923
 */
public class SqlDriverClassFileTransformer implements ClassFileTransformer {

    private static final Logger logger = Logger.getLogger(SqlDriverClassFileTransformer.class);

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        try {
            if (className == null) {
                return null;
            }
            String dotClassName = className.replace('/', '.');
            if (!JdbcDriverClasses.isDriverClass(dotClassName)) {
                return null;
            }
            ClassPool classPool = new ClassPool();
            classPool.appendSystemPath();
            CtClass ctClass = classPool.makeClass(new ByteArrayInputStream(classfileBuffer));
            CtMethod connectMethod = ctClass.getDeclaredMethod("connect", new CtClass[]{classPool.get("java.lang.String"), classPool.get("java.util.Properties")});
            connectMethod.insertAfter(buildProxyConnectionCode());
            logger.info("Print {} log bytecode enhancement successful", JdbcDriverClasses.getDriverType(dotClassName));
            byte[] result = ctClass.toBytecode();
            ctClass.detach();
            return result;
        } catch (Throwable t) {
            logger.error("Failed to print SQL log bytecode enhancement", t);
        }
        return null;
    }

    private String buildProxyConnectionCode() {
        String interceptorClassName = SqlPrintInterceptor.class.getName();
        return "{ " +
                "   java.lang.Class __debugToolsInterceptorClass = java.lang.ClassLoader.getSystemClassLoader().loadClass(\"" + interceptorClassName + "\");" +
                "   java.lang.reflect.Method __debugToolsProxyConnectionMethod = __debugToolsInterceptorClass.getDeclaredMethod(\"proxyConnection\", new java.lang.Class[]{java.sql.Connection.class});" +
                "   return (java.sql.Connection) __debugToolsProxyConnectionMethod.invoke(null, new java.lang.Object[]{$_});" +
                "}";
    }
}
