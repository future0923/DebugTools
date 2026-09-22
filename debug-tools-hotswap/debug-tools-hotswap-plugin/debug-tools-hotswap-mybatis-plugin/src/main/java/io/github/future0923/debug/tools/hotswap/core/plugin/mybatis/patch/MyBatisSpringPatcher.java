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
package io.github.future0923.debug.tools.hotswap.core.plugin.mybatis.patch;

import io.github.future0923.debug.tools.base.constants.ProjectConstants;
import io.github.future0923.debug.tools.base.logging.Logger;
import io.github.future0923.debug.tools.base.utils.DebugToolsStringUtils;
import io.github.future0923.debug.tools.hotswap.core.annotation.Init;
import io.github.future0923.debug.tools.hotswap.core.annotation.LoadEvent;
import io.github.future0923.debug.tools.hotswap.core.annotation.OnClassLoadEvent;
import io.github.future0923.debug.tools.hotswap.core.command.Scheduler;
import io.github.future0923.debug.tools.hotswap.core.plugin.mybatis.MyBatisPlugin;
import io.github.future0923.debug.tools.hotswap.core.plugin.mybatis.command.MyBatisSpringMapperReloadCommand;
import io.github.future0923.debug.tools.hotswap.core.plugin.mybatis.reload.MyBatisSpringResourceManager;
import io.github.future0923.debug.tools.hotswap.core.plugin.mybatis.utils.MyBatisUtils;
import io.github.future0923.debug.tools.hotswap.core.plugin.mybatis.watcher.MyBatisPlusEntityWatchEventListener;
import io.github.future0923.debug.tools.hotswap.core.plugin.mybatis.watcher.MyBatisPlusMapperWatchEventListener;
import io.github.future0923.debug.tools.hotswap.core.plugin.mybatis.watcher.MyBatisSpringMapperWatchEventListener;
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
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author future0923
 */
public class MyBatisSpringPatcher {

    private static final Logger logger = Logger.getLogger(MyBatisSpringPatcher.class);

    @Init
    static Scheduler scheduler;

    @Init
    static Watcher watcher;

    /**
     * ClassPathMapperScanner 构造函数插桩，获取ClassPathMapperScanner实例
     */
    @OnClassLoadEvent(classNameRegexp = "org.mybatis.spring.mapper.ClassPathMapperScanner")
    public static void patchClassPathMapperScanner(CtClass ctClass, ClassPool classPool) throws NotFoundException, CannotCompileException {
        CtConstructor[] declaredConstructors = ctClass.getDeclaredConstructors();
        for (CtConstructor constructor : declaredConstructors) {
            constructor.insertAfter(
                    "{" +
                            MyBatisSpringResourceManager.class.getName() + ".loadScanner(this);" +
                            "}");
        }
    }

    /**
     * MybatisProperties.resolveMapperLocations 插桩，获取mapperLocations
     * <p>
     * Spring+Mybatis工程中：限制加载的Mapper.xml必须为<code>mybatis.mapper-locations</code>配置的文件
     * </p>
     *
     * @param ctClass   ctClass
     * @param classPool classPool
     */
    @OnClassLoadEvent(classNameRegexp = "org.mybatis.spring.boot.autoconfigure.MybatisProperties")
    public static void patchMapperLocations(CtClass ctClass, ClassPool classPool) throws NotFoundException, CannotCompileException {
        CtMethod resolveMapperLocations = ctClass.getDeclaredMethod("resolveMapperLocations");
        resolveMapperLocations.insertAfter("{" +
                MyBatisSpringResourceManager.class.getName() + ".addMapperLocations(this.mapperLocations);" +
                "}");
    }

    @OnClassLoadEvent(classNameRegexp = "org.mybatis.spring.SqlSessionFactoryBean")
    public static void patchSqlSessionFactoryBean(CtClass ctClass, ClassPool classPool) throws Exception {
        logger.debug("org.mybatis.spring.SqlSessionFactoryBean patched.");
        // @OnResourceFileEvent只能在主插件作用与实例对象，所以要初始化插件对象
        StringBuilder src = new StringBuilder("{");
        src.append(PluginManagerInvoker.buildInitializePlugin(MyBatisPlugin.class));
        src.append(PluginManagerInvoker.buildCallPluginMethod(MyBatisPlugin.class, "init", "org.mybatis.spring.SqlSessionFactoryBean.class.getClassLoader()", ClassLoader.class.getName()));
        src.append("}");
        CtConstructor[] constructors = ctClass.getConstructors();
        for (CtConstructor constructor : constructors) {
            constructor.insertAfter(src.toString());
        }
        CtMethod afterPropertiesSet = ctClass.getDeclaredMethod("afterPropertiesSet");
        afterPropertiesSet.insertAfter(MyBatisSpringResourceManager.class.getName() + ".registerConfiguration(this.sqlSessionFactory.getConfiguration());");
    }

    /**
     * 当 AbstractApplicationContext refresh方法执行完成后（Spring应用上下文已经初始化完成）初始化
     */
    @OnClassLoadEvent(classNameRegexp = "org.springframework.context.support.AbstractApplicationContext")
    public static void patchAbstractApplicationContext(CtClass ctClass, ClassPool classPool) throws NotFoundException, CannotCompileException {
        CtMethod buildSqlSessionFactory = ctClass.getDeclaredMethod("refresh");
        buildSqlSessionFactory.insertAfter("{}");
    }

    /**
     * 获取{@link MapperScan}信息
     */
    @OnClassLoadEvent(classNameRegexp = "org.mybatis.spring.annotation.MapperScannerRegistrar")
    public static void patchMapperScannerRegistrar(CtClass ctClass, ClassPool classPool) throws NotFoundException, CannotCompileException {
        try {
            CtMethod registerBeanDefinitions = ctClass.getDeclaredMethod(
                    "registerBeanDefinitions",
                    new CtClass[]{
                            classPool.get("org.springframework.core.type.AnnotationMetadata"),
                            classPool.get("org.springframework.core.annotation.AnnotationAttributes"),
                            classPool.get("org.springframework.beans.factory.support.BeanDefinitionRegistry"),
                            classPool.get("java.lang.String")
                    }
            );
            registerBeanDefinitions.insertAfter("{" +
                    MyBatisSpringPatcher.class.getName() + ".baseMapperPackage($1, $2);" +
                    "}");
        } catch (NotFoundException e) {
            try {
                // mybatis-spring 2.0.2
                CtMethod registerBeanDefinitions = ctClass.getDeclaredMethod(
                        "registerBeanDefinitions",
                        new CtClass[]{
                                classPool.get("org.springframework.core.annotation.AnnotationAttributes"),
                                classPool.get("org.springframework.beans.factory.support.BeanDefinitionRegistry"),
                                classPool.get("java.lang.String")
                        }
                );
                registerBeanDefinitions.insertAfter("{" +
                        MyBatisSpringPatcher.class.getName() + ".baseMapperPackage(null, $1);" +
                        "}");
            } catch (NotFoundException ex) {
                // 找不到解析主方法
                CtMethod registerBeanDefinitions = ctClass.getDeclaredMethod(
                        "registerBeanDefinitions",
                        new CtClass[]{
                                classPool.get("org.springframework.core.type.AnnotationMetadata"),
                                classPool.get("org.springframework.beans.factory.support.BeanDefinitionRegistry"),
                        });
                registerBeanDefinitions.insertAfter("{" +
                        MyBatisSpringPatcher.class.getName() + ".baseMapperPackage(null, $1);" +
                        "}");
            }
        }

    }

    /**
     * 获取{@link MapperScan}的mapper路径
     * {@link #patchMapperScannerRegistrar}调用
     */
    public static void baseMapperPackage(Object annoMetaObj, Object annoAttrsObj) {
        AnnotationAttributes annoAttrs = null;
        if (annoAttrsObj instanceof AnnotationMetadata) {
            annoAttrs = AnnotationAttributes.fromMap(((AnnotationMetadata) annoAttrsObj).getAnnotationAttributes(MapperScan.class.getName()));
        } else if (annoAttrsObj instanceof AnnotationAttributes) {
            // 插件启动时会扫描 {@link Plugin}相关所有类的属性和方法，参数直接写如果没有对应的类文件会报错，所以这里用 Object接收
            annoAttrs = (AnnotationAttributes) annoAttrsObj;
        }
        if (annoAttrs == null) {
            logger.error("baseMapperPackage annoAttrs is null");
            return;
        }
        Set<String> basePackages = new HashSet<>();
        basePackages.addAll(Arrays.stream(annoAttrs.getStringArray("value")).filter(StringUtils::hasText).collect(Collectors.toList()));
        basePackages.addAll(Arrays.stream(annoAttrs.getStringArray("basePackages")).filter(StringUtils::hasText).collect(Collectors.toList()));
        basePackages.addAll(Arrays.stream(annoAttrs.getClassArray("basePackageClasses")).map(ClassUtils::getPackageName).collect(Collectors.toList()));
        if (basePackages.isEmpty()) {
            if (annoMetaObj instanceof AnnotationMetadata) {
                AnnotationMetadata annoMeta = (AnnotationMetadata) annoMetaObj;
                basePackages.add(ClassUtils.getPackageName(annoMeta.getClassName()));
            }
        }
        for (String basePackage : basePackages) {
            registerMapperTransformer(basePackage);
        }
    }

    public static void registerMapperTransformer(final String basePackage) {
        String classNameRegExp = DebugToolsStringUtils.getClassNameRegExp(basePackage);
        Enumeration<URL> resourceUrls;
        try {
            resourceUrls = ClassLoaderHelper.getResources(MyBatisSpringPatcher.class.getClassLoader(), classNameRegExp);
        } catch (IOException e) {
            logger.error("Unable to resolve mapper base package {} in classloader {}.", classNameRegExp, MyBatisPlugin.getUserClassLoader());
            return;
        }
        while (resourceUrls.hasMoreElements()) {
            URL basePackageURL = resourceUrls.nextElement();
            if (!IOUtils.isFileURL(basePackageURL)) {
                logger.debug("mybatis mapper basePackage '{}' - unable to watch files on URL '{}' for changes (JAR file?), limited hotswap reload support. Use extraClassPath configuration to locate class file on filesystem.", basePackage, basePackageURL);
            } else {
                watcher.addEventListener(MyBatisPlugin.getUserClassLoader(), basePackage, basePackageURL, new MyBatisPlusMapperWatchEventListener(scheduler, MyBatisPlugin.getUserClassLoader(), basePackage));
                watcher.addEventListener(MyBatisPlugin.getUserClassLoader(), basePackage, basePackageURL, new MyBatisSpringMapperWatchEventListener(scheduler, MyBatisPlugin.getUserClassLoader(), basePackage));
            }
        }
    }


    @OnClassLoadEvent(classNameRegexp = "org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider")
    public static void patchClassPathScanningCandidateComponentProvider(CtClass clazz, ClassPool classPool) throws NotFoundException, CannotCompileException {
        if (classPool.getOrNull("org.mybatis.spring.mapper.ClassPathMapperScanner") == null) {
            return;
        }
        CtMethod method = clazz.getDeclaredMethod("findCandidateComponents", new CtClass[]{classPool.get("java.lang.String")});
        method.insertAfter(
                "if (this instanceof org.springframework.context.annotation.ClassPathBeanDefinitionScanner) {" +
                        MyBatisSpringPatcher.class.getName() + ".registerEntityTransformer($1);" +
                        "}"
        );
    }

    public static void registerEntityTransformer(String basePackage) {
        String classNameRegExp = DebugToolsStringUtils.getClassNameRegExp(basePackage);
        Enumeration<URL> resourceUrls;
        try {
            resourceUrls = ClassLoaderHelper.getResources(MyBatisSpringPatcher.class.getClassLoader(), classNameRegExp);
        } catch (IOException e) {
            logger.error("Unable to resolve entity base package {} in classloader {}.", classNameRegExp, MyBatisPlugin.getUserClassLoader());
            return;
        }
        while (resourceUrls.hasMoreElements()) {
            URL basePackageURL = resourceUrls.nextElement();
            if (!IOUtils.isFileURL(basePackageURL)) {
                logger.debug("mybatis entity basePackage '{}' - unable to watch files on URL '{}' for changes (JAR file?), limited hotswap reload support. Use extraClassPath configuration to locate class file on filesystem.", basePackage, basePackageURL);
            } else {
                watcher.addEventListener(MyBatisPlugin.getUserClassLoader(), basePackage, basePackageURL, new MyBatisPlusEntityWatchEventListener(scheduler, MyBatisPlugin.getUserClassLoader(), basePackage));
            }
        }
    }

    @OnClassLoadEvent(classNameRegexp = ".*", events = LoadEvent.REDEFINE)
    public static void redefineMyBatisSpringMapper(final Class<?> clazz, final ClassLoader appClassLoader, final byte[] bytes) {
        if (ProjectConstants.DEBUG) {
            logger.info("redefine class {}", clazz.getName());
        }
        if (MyBatisUtils.isMyBatisSpring(appClassLoader) && MyBatisUtils.isMyBatisMapper(appClassLoader, clazz)) {
            scheduler.scheduleCommand(new MyBatisSpringMapperReloadCommand(appClassLoader, clazz.getName(), bytes, ""), 1000);
        }
    }
}
