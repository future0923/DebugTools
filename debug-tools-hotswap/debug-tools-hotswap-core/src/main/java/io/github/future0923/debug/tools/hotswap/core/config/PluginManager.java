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
package io.github.future0923.debug.tools.hotswap.core.config;

import io.github.future0923.debug.tools.base.logging.Logger;
import io.github.future0923.debug.tools.hotswap.core.annotation.Plugin;
import io.github.future0923.debug.tools.hotswap.core.annotation.handler.InitHandler;
import io.github.future0923.debug.tools.hotswap.core.command.Command;
import io.github.future0923.debug.tools.hotswap.core.command.Scheduler;
import io.github.future0923.debug.tools.hotswap.core.command.impl.SchedulerImpl;
import io.github.future0923.debug.tools.hotswap.core.util.HotswapTransformer;
import io.github.future0923.debug.tools.hotswap.core.util.classloader.ClassLoaderDefineClassPatcher;
import io.github.future0923.debug.tools.hotswap.core.util.classloader.URLClassLoaderPathHelper;
import io.github.future0923.debug.tools.hotswap.core.watch.Watcher;
import io.github.future0923.debug.tools.hotswap.core.watch.WatcherFactory;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 插件管理器
 */
public class PluginManager {

    private static final Logger logger = Logger.getLogger(PluginManager.class);

    /**
     * 扫描的插件包路径
     */
    public static final String PLUGIN_PACKAGE = "io.github.future0923.debug.tools.hotswap.core.plugin";

    ClassLoaderDefineClassPatcher classLoaderPatcher = new ClassLoaderDefineClassPatcher();

    /**
     * ClassLoader中的插件配置信息
     */
    private final Map<ClassLoader, PluginConfiguration> classLoaderConfigurations = new HashMap<>();

    /**
     * ClassLoader初始化监听者
     */
    private final Set<ClassLoaderInitListener> classLoaderInitListeners = new HashSet<>();

    @Getter
    private Instrumentation instrumentation;

    /**
     * instrumentation的redefineClasses锁
     */
    private final Object hotswapLock = new Object();

    /**
     * 返回所有 class 重定义通道共用的锁。
     *
     * @return 热重载锁
     */
    public Object getHotswapLock() {
        return hotswapLock;
    }

    /**
     * 单例
     */
    private static final PluginManager INSTANCE = new PluginManager();

    @Setter
    @Getter
    private PluginRegistry pluginRegistry;

    /**
     * 热重载转换器
     */
    @Getter
    protected HotswapTransformer hotswapTransformer;

    @Getter
    protected Watcher watcher;

    @Getter
    protected Scheduler scheduler;

    private PluginManager() {
        hotswapTransformer = new HotswapTransformer();
        pluginRegistry = new PluginRegistry(this, classLoaderPatcher);
    }

    /**
     * 获取插件管理器的单例实例
     */
    public static PluginManager getInstance() {
        return INSTANCE;
    }

    /**
     * 获取指定类加载器中的插件实例
     */
    public Object getPlugin(String clazz, ClassLoader classLoader) {
        try {
            return getPlugin(Class.forName(clazz), classLoader);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Plugin class not found " + clazz, e);
        }
    }

    /**
     * 获取指定类加载器中的插件实例
     */
    public <T> T getPlugin(Class<T> clazz, ClassLoader classLoader) {
        return pluginRegistry.getPlugin(clazz, classLoader);
    }

    /**
     * 插件是否在指定ClassLoader中已经初始化
     */
    public boolean isPluginInitialized(String pluginClassName, ClassLoader classLoader) {
        Class<Object> pluginClass = pluginRegistry.getPluginClass(pluginClassName);
        return pluginClass != null && pluginRegistry.hasPlugin(pluginClass, classLoader, false);
    }

    /**
     * 初始化热重载
     *
     * <p>使用{@link WatcherFactory}创建单独的线程{@link Watcher}资源
     * <p>创建{@link Scheduler}用来调度{@link Command}的执行
     * <p>识别{@link Plugin}插件
     * <p>使用{@link HotswapTransformer}来检查agent instrumentation class
     */
    public void init(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;

        if (watcher == null) {
            try {
                watcher = new WatcherFactory().getWatcher();
            } catch (IOException e) {
                logger.debug("Unable to create default watcher.", e);
            }
        }
        watcher.run();

        if (scheduler == null) {
            scheduler = new SchedulerImpl();
        }
        scheduler.run();

        ClassLoader classLoader = getClass().getClassLoader();

        PluginConfiguration configuration = new PluginConfiguration(classLoader);

        classLoaderConfigurations.put(classLoader, configuration);

        // 扫描插件
        pluginRegistry.scanPlugins(getClass().getClassLoader(), PLUGIN_PACKAGE);

        // 注册转换器
        instrumentation.addTransformer(hotswapTransformer);
    }

    /**
     * 增强ClassLoader，让其可以加载热部署过来的类文件. {@code SpringBootClassLoaderPatcher}
     */
    public static ClassLoader enhanceClassLoader(ClassLoader classLoader) {
        return URLClassLoaderPathHelper.prependClassPath(classLoader);
    }

    /**
     * 添加类加载器初始化监听者并立即调用，给{@link InitHandler}使用
     */
    public void registerClassLoaderInitListener(ClassLoaderInitListener classLoaderInitListener) {
        classLoaderInitListeners.add(classLoaderInitListener);
        // 因为ClassLoader已经初始化了，所以立即在此类加载器上调用init，
        classLoaderInitListener.onInit(getClass().getClassLoader());
    }

    /**
     * 初始化类加载器
     */
    public void initClassLoader(ClassLoader classLoader) {
        initClassLoader(classLoader, classLoader.getClass().getProtectionDomain());
    }

    /**
     * 初始化类加载器
     */
    public void initClassLoader(ClassLoader classLoader, ProtectionDomain protectionDomain) {
        // 存在说明ClassLoader中已经初始化过了，直接退出
        if (classLoaderConfigurations.containsKey(classLoader)) {
            return;
        }
        // system/bootstrap 类加载器不初始化
        if (getClass().getClassLoader() != null &&
                classLoader != null &&
                classLoader.equals(getClass().getClassLoader().getParent())) {
            return;
        }
        synchronized (this) {
            // 如果已经初始化过了
            if (classLoaderConfigurations.containsKey(classLoader)) {
                return;
            }
            // 从AgentClassLoader复制插件到初始化ClassLoader中
            if (classLoader != null && classLoaderPatcher.isPatchAvailable(classLoader)) {
                classLoaderPatcher.patch(getClass().getClassLoader(), PLUGIN_PACKAGE.replace(".", "/"),
                        classLoader, protectionDomain);
            }

            // 创建这个ClassLoader中的插件配置
            PluginConfiguration pluginConfiguration = new PluginConfiguration(getPluginConfiguration(getClass().getClassLoader()), classLoader, false);
            classLoaderConfigurations.put(classLoader, pluginConfiguration);
            pluginConfiguration.init();
        }

        // 调用类初始化监听者
        for (ClassLoaderInitListener classLoaderInitListener : classLoaderInitListeners) {
            classLoaderInitListener.onInit(classLoader);
        }
    }

    /**
     * 从指定类加载器中移除热重载插件
     */
    public void closeClassLoader(ClassLoader classLoader) {
        pluginRegistry.closeClassLoader(classLoader);
        classLoaderConfigurations.remove(classLoader);
        hotswapTransformer.closeClassLoader(classLoader);
    }


    public PluginConfiguration getPluginConfiguration(ClassLoader classLoader) {
        ClassLoader loader = classLoader;
        while (loader != null && !classLoaderConfigurations.containsKey(loader)) {
            loader = loader.getParent();
        }
        return classLoaderConfigurations.get(loader);
    }

    /**
     * 通过传入的字节码进行热重载，
     *
     * <p>此方法对集合进行操作，以允许同时对多个类进行相互依赖的更改
     *
     * @param reloadMap 要重载的字节码
     * @see Instrumentation#redefineClasses(ClassDefinition...)
     */
    public void hotswap(final Map<Class<?>, byte[]> reloadMap) {
        if (instrumentation == null) {
            throw new IllegalStateException("Plugin manager is not correctly initialized - no instrumentation available.");
        }

        synchronized (reloadMap) {
            ClassDefinition[] definitions = new ClassDefinition[reloadMap.size()];
            String[] classNames = new String[reloadMap.size()];
            int i = 0;
            for (Map.Entry<Class<?>, byte[]> entry : reloadMap.entrySet()) {
                classNames[i] = entry.getKey().getName();
                definitions[i++] = new ClassDefinition(entry.getKey(), entry.getValue());
            }
            try {
                logger.debug("Reloading classes {} (autoHotswap)", Arrays.toString(classNames));
                synchronized (hotswapLock) {
                    instrumentation.redefineClasses(definitions);
                }
                logger.reload("reloaded classes {} (autoHotswap)", Arrays.toString(classNames));
            } catch (Exception e) {
                logger.debug("... Fail to reload classes {} (autoHotswap), msg is {}", Arrays.toString(classNames), e);
                throw new IllegalStateException("Unable to redefine classes", e);
            }
            reloadMap.clear();
        }
    }

    /**
     * 通过调度器传入的字节码进行热重载，
     *
     * <p>此方法对集合进行操作，以允许同时对多个类进行相互依赖的更改
     *
     * @param timeout   延迟运行的时间
     * @param reloadMap 要重载的字节码
     * @see Instrumentation#redefineClasses(ClassDefinition...)
     */
    public void scheduleHotswap(Map<Class<?>, byte[]> reloadMap, int timeout) {
        if (instrumentation == null) {
            throw new IllegalStateException("Plugin manager is not correctly initialized - no instrumentation available.");
        }
        getScheduler().scheduleCommand(new ScheduledHotswapCommand(reloadMap), timeout);
    }

}
