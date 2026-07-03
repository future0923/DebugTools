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
package io.github.future0923.debug.tools.hotswap.core.plugin.forest.patch;

import io.github.future0923.debug.tools.base.logging.Logger;
import io.github.future0923.debug.tools.hotswap.core.annotation.OnClassLoadEvent;
import io.github.future0923.debug.tools.hotswap.core.plugin.forest.reload.ForestReload;
import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.NotFoundException;

public class ForestPatcher {

    private static final Logger logger = Logger.getLogger(ForestPatcher.class);

    @OnClassLoadEvent(classNameRegexp = "com.dtflys.forest.scanner.ClassPathClientScanner")
    public static void patchForestScanner(CtClass ctClass, ClassPool classPool) throws NotFoundException, CannotCompileException {
        logger.debug("enhance forest package ClassPathClientScanner");
        CtConstructor[] declaredConstructors = ctClass.getDeclaredConstructors();
        for (CtConstructor constructor : declaredConstructors) {
            constructor.insertAfter(
                    "{" +
                            ForestReload.class.getName() + ".initScanner(this);" +
                            "}");
        }
    }


    @OnClassLoadEvent(classNameRegexp = "com.dtflys.forest.proxy.InterfaceProxyHandler")
    public static void patchForestProxyInvoke(CtClass ctClass, ClassPool classPool) throws NotFoundException, CannotCompileException {
        logger.debug("enhance forest package InterfaceProxyHandler");
        CtMethod invokeMethod = ctClass.getDeclaredMethod("invoke",
                new CtClass[]{
                        classPool.get("java.lang.Object"),
                        classPool.get("java.lang.reflect.Method"),
                        classPool.get("java.lang.Object[]")
                }
        );

        invokeMethod.insertBefore("{ initMethods(); }");
    }

    @OnClassLoadEvent(classNameRegexp = "com.dtflys.forest.reflection.ForestMethod")
    public static void patchDefaultReflectorFactory(CtClass ctClass, ClassPool classPool) throws NotFoundException, CannotCompileException {
        logger.debug("enhance forest package ForestMethod");
        CtMethod initMethods = ctClass.getDeclaredMethod("initMethod");
        String newBody =
                "{ " +
                        "    synchronized (INIT_LOCK) { " +
                        "        processBaseProperties(); " +
                        "        processMethodAnnotations(); " +
                        "        initialized = true; " +
                        "    } " +
                        "}";

        initMethods.setBody(newBody);
    }
}
