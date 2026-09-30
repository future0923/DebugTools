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
package io.github.future0923.debug.tools.hotswap.core.plugin.freemarker;

import io.github.future0923.debug.tools.base.logging.Logger;
import io.github.future0923.debug.tools.hotswap.core.annotation.Init;
import io.github.future0923.debug.tools.hotswap.core.annotation.LoadEvent;
import io.github.future0923.debug.tools.hotswap.core.annotation.OnClassLoadEvent;
import io.github.future0923.debug.tools.hotswap.core.annotation.Plugin;
import io.github.future0923.debug.tools.hotswap.core.command.Command;
import io.github.future0923.debug.tools.hotswap.core.command.Scheduler;
import io.github.future0923.debug.tools.hotswap.core.plugin.freemarker.command.FreemarkerClearCacheCommand;
import io.github.future0923.debug.tools.hotswap.core.util.PluginManagerInvoker;
import io.github.future0923.debug.tools.hotswap.core.util.ReflectionHelper;
import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

/**
 * @author future0923
 */
@Plugin(
        name = "Freemarker",
        description = "Hot reload support for FreeMarker.",
        testedVersions = {"2.3.31"}
)
public class FreemarkerPlugin {

    private static final Logger logger = Logger.getLogger(FreemarkerPlugin.class);

    private static Command clearCacheCommand;

    @Init
    private static Scheduler scheduler;

    /**
     * SpringMVC
     */
    @OnClassLoadEvent(classNameRegexp = "freemarker.ext.servlet.FreemarkerServlet")
    public static void patchFreemarkerServlet(final CtClass ctClass) throws NotFoundException, CannotCompileException {
        String initPlugin = "{" + PluginManagerInvoker.buildInitializePlugin(FreemarkerPlugin.class) +
                PluginManagerInvoker.buildCallPluginMethod(FreemarkerPlugin.class, "registerFreemarkerServlet", "this", "java.lang.Object") +
                "}";
        CtMethod init = ctClass.getDeclaredMethod("init");
        init.insertAfter(initPlugin);
        logger.info("patch FreemarkerServlet init success");
    }

    public void registerFreemarkerServlet(Object freemarkerServlet) {
        clearCacheCommand = new FreemarkerClearCacheCommand(ReflectionHelper.get(ReflectionHelper.get(freemarkerServlet, "config"), "objectWrapper"));
    }

    /**
     * SpringBoot
     */
    @OnClassLoadEvent(classNameRegexp = "org.springframework.web.servlet.view.freemarker.FreeMarkerView")
    public static void patchFreeMarkerView(final CtClass ctClass) throws NotFoundException, CannotCompileException {
        CtMethod getObjectWrapper = ctClass.getDeclaredMethod("getObjectWrapper");
        getObjectWrapper.insertAfter("{" +
                "   io.github.future0923.debug.tools.hotswap.core.plugin.freemarker.FreemarkerPlugin.registerObjectWrapper($_);" +
                "}");
        logger.info("patch FreeMarkerView success");
    }

    public static void registerObjectWrapper(Object objectWrapper) {
        clearCacheCommand = new FreemarkerClearCacheCommand(objectWrapper);
    }

    /**
     * 禁用缓存
     */
    @OnClassLoadEvent(classNameRegexp = "freemarker.template.Configuration")
    public static void patchConfiguration(final CtClass ctClass) throws NotFoundException, CannotCompileException {
        CtMethod method = ctClass.getDeclaredMethod("createTemplateCache");
        method.instrument(new ExprEditor() {
            @Override
            public void edit(MethodCall m) throws CannotCompileException {
                if ("freemarker.cache.TemplateCache".equals(m.getClassName())
                        && "setDelay".equals(m.getMethodName())
                        && "(J)V".equals(m.getSignature())) {
                    m.replace("{ $proceed(0L); }");
                    logger.info("patch Freemarker Configuration success");
                }
            }
        });
    }

    /**
     * 清除缓存
     */
    @OnClassLoadEvent(classNameRegexp = ".*", events = LoadEvent.REDEFINE)
    public static void redefineClass() {
        scheduler.scheduleCommand(clearCacheCommand, 500);
    }
}
