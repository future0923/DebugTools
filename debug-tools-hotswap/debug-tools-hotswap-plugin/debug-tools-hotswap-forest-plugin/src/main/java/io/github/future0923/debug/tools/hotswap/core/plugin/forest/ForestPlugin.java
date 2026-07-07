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
package io.github.future0923.debug.tools.hotswap.core.plugin.forest;

import io.github.future0923.debug.tools.base.logging.Logger;
import io.github.future0923.debug.tools.base.utils.DebugToolsStringUtils;
import io.github.future0923.debug.tools.hotswap.core.annotation.Init;
import io.github.future0923.debug.tools.hotswap.core.annotation.LoadEvent;
import io.github.future0923.debug.tools.hotswap.core.annotation.OnClassLoadEvent;
import io.github.future0923.debug.tools.hotswap.core.annotation.Plugin;
import io.github.future0923.debug.tools.hotswap.core.command.Scheduler;
import io.github.future0923.debug.tools.hotswap.core.plugin.forest.command.ForestReloadCommand;
import io.github.future0923.debug.tools.hotswap.core.plugin.forest.utils.ForestUtil;
import io.github.future0923.debug.tools.hotswap.core.plugin.forest.watcher.ForestWatchEventListener;
import io.github.future0923.debug.tools.hotswap.core.util.IOUtils;
import io.github.future0923.debug.tools.hotswap.core.util.PluginManagerInvoker;
import io.github.future0923.debug.tools.hotswap.core.util.classloader.ClassLoaderHelper;
import io.github.future0923.debug.tools.hotswap.core.watch.Watcher;
import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.NotFoundException;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.URL;
import java.util.Enumeration;
import java.util.List;

/**
 * Forest热重载插件
 */
@Plugin(
        name = "Forest",
        description = "Reload Forest after class change.",
        testedVersions = {"1.5.32"}, expectedVersions = {"1.5.x"}
)
public class ForestPlugin {

    private static final Logger logger = Logger.getLogger(ForestPlugin.class);

    @Init
    static Watcher watcher;

    @Init
    static Scheduler scheduler;

    @Init
    static ClassLoader appClassLoader;

    private static Object scanner;

    private static Object forestConfiguration;

    /**
     * 不能使用注解，因为注解只能获取AppClassLoader
     */
    private static ClassLoader userClassLoader;

    /**
     * 获取Forest的类加载器和注册者
     */
    public void init(ClassLoader classLoader, Object forestClientsRegistrar) {
        ForestPlugin.userClassLoader = classLoader;
    }

    public static ClassLoader getUserClassLoader() {
        return userClassLoader == null ? appClassLoader : userClassLoader;
    }

    public static void setScanner(Object scanner) {
        ForestPlugin.scanner = scanner;
    }

    public static Object getScanner() {
        return ForestPlugin.scanner;
    }


    public static void setForestConfiguration(Object forestConfiguration) {
        ForestPlugin.forestConfiguration = forestConfiguration;
    }

    public static Object getForestConfiguration() {
        return ForestPlugin.forestConfiguration;
    }


    public static void registerForestBasePackage(final List<String> basePackages) {
        for (String basePackage : basePackages) {
            String classNameRegExp = DebugToolsStringUtils.getClassNameRegExp(basePackage);
            Enumeration<URL> resourceUrls;
            try {
                resourceUrls = ClassLoaderHelper.getResources(ForestPlugin.getUserClassLoader(), classNameRegExp);
            } catch (IOException e) {
                logger.error("Unable to resolve forest base package {} in classloader {}.", classNameRegExp, ForestPlugin.getUserClassLoader());
                return;
            }
            while (resourceUrls.hasMoreElements()) {
                URL basePackageURL = resourceUrls.nextElement();
                if (!IOUtils.isFileURL(basePackageURL)) {
                    logger.debug("forest basePackage '{}' - unable to watch files on URL '{}' for changes (JAR file?), limited hotswap reload support. Use extraClassPath configuration to locate class file on filesystem.", basePackage, basePackageURL);
                } else {
                    watcher.addEventListener(ForestPlugin.getUserClassLoader(), basePackage, basePackageURL, new ForestWatchEventListener(scheduler, ForestPlugin.getUserClassLoader(), basePackage));
                }
            }
        }

    }

    @OnClassLoadEvent(classNameRegexp = "com.dtflys.forest.springboot.annotation.ForestScannerRegister")
    public static void patchForestClientsRegistrar(CtClass ctClass, ClassPool classPool) throws NotFoundException, CannotCompileException {
        StringBuilder src = new StringBuilder("{");
        src.append(PluginManagerInvoker.buildInitializePlugin(ForestPlugin.class));
        src.append(PluginManagerInvoker.buildCallPluginMethod(ForestPlugin.class, "init",
                "com.dtflys.forest.springboot.annotation.ForestScannerRegister.class.getClassLoader()", ClassLoader.class.getName(),
                "this", Object.class.getName()));
        src.append("}");
        CtConstructor[] constructors = ctClass.getConstructors();
        for (CtConstructor constructor : constructors) {
            constructor.insertAfter(src.toString());
        }

        CtMethod getBasePackages = ctClass.getDeclaredMethod("getBasePackages");
        getBasePackages.insertAfter("{" +
                "   io.github.future0923.debug.tools.hotswap.core.plugin.forest.ForestPlugin.registerForestBasePackage($_);" +
                "}");
    }

    @OnClassLoadEvent(classNameRegexp = "com.dtflys.forest.config.ForestConfiguration")
    public static void patchForestConfiguration(CtClass ctClass, ClassPool classPool) throws NotFoundException, CannotCompileException {
        CtMethod configuration = ctClass.getDeclaredMethod("configuration");
        configuration.insertAfter("{" +
                "   io.github.future0923.debug.tools.hotswap.core.plugin.forest.ForestPlugin.setForestConfiguration($_);" +
                "}");
    }

    @OnClassLoadEvent(classNameRegexp = "com.dtflys.forest.scanner.ClassPathClientScanner")
    public static void patchForestScanner(CtClass ctClass, ClassPool classPool) throws NotFoundException, CannotCompileException {
        CtConstructor[] declaredConstructors = ctClass.getDeclaredConstructors();
        for (CtConstructor constructor : declaredConstructors) {
            constructor.insertAfter("{" +
                    "   io.github.future0923.debug.tools.hotswap.core.plugin.forest.ForestPlugin.setScanner(this);" +
                    "}");
        }
    }

    /**
     * 重新加载forest client代理类
     */
    @OnClassLoadEvent(classNameRegexp = ".*", events = LoadEvent.REDEFINE)
    public static void redefineForestClass(final Class<?> clazz, final ClassLoader appClassLoader, final byte[] bytes) throws ClassNotFoundException {
        boolean forestClient = ForestUtil.isForestClient(appClassLoader, clazz);
        logger.debug("forestClient '{}' for class '{}'", forestClient, clazz.getName());
        logger.debug("forestClient is Interface :{}", clazz.isInterface());
        logger.debug("forestClient is Post :{}", clazz.getAnnotation((Class<? extends Annotation>) appClassLoader.loadClass("com.dtflys.forest.annotation.Post")) != null);
        logger.debug("forestClient is Get :{}", clazz.getAnnotation((Class<? extends Annotation>) appClassLoader.loadClass("com.dtflys.forest.annotation.Get")) != null);


        if (ForestUtil.isForestClient(appClassLoader, clazz)) {
            scheduler.scheduleCommand(new ForestReloadCommand(appClassLoader, clazz.getName(), bytes, ""), 1000);
        }
    }
}
