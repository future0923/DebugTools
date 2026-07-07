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

import io.github.future0923.debug.tools.base.constants.ProjectConstants;
import io.github.future0923.debug.tools.base.hutool.core.collection.CollUtil;
import io.github.future0923.debug.tools.base.hutool.core.collection.CollectionUtil;
import io.github.future0923.debug.tools.base.logging.Logger;
import io.github.future0923.debug.tools.common.dto.RunDTO;
import io.github.future0923.debug.tools.extension.spring.method.SpringParamConvertUtils;
import io.github.future0923.debug.tools.server.utils.BeanInstanceUtils;
import org.springframework.aop.SpringProxy;
import org.springframework.aop.TargetClassAware;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.AopProxy;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.DefaultSingletonBeanRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;

import java.beans.Introspector;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author future0923
 */
public class SpringEnvUtil {

    private static final Logger logger = Logger.getLogger(SpringEnvUtil.class);

    private static volatile boolean init = false;

    private static List<BeanFactory> beanFactories = Collections.emptyList();

    private static List<ApplicationContext> applicationContexts = Collections.emptyList();

    private static void initSpringContext() {
        if (!init) {
            synchronized (SpringEnvUtil.class) {
                if (!init) {
                    refreshSpringContext();
                    init = isSpringContextReadyToCache(applicationContexts);
                }
            }
        }
    }

    private static void refreshSpringContext() {
        beanFactories = Arrays.asList(BeanInstanceUtils.getInstances(BeanFactory.class));
        applicationContexts = sort(BeanInstanceUtils.getInstances(ApplicationContext.class));
    }

    static boolean isSpringContextReadyToCache(List<ApplicationContext> applicationContexts) {
        if (applicationContexts == null || applicationContexts.isEmpty()) {
            return false;
        }
        for (ApplicationContext applicationContext : applicationContexts) {
            if (isBootstrapContext(applicationContext) || !isContextActive(applicationContext)) {
                continue;
            }
            if (isContextUsableForInvocation(applicationContext)) {
                return true;
            }
        }
        return false;
    }

    private static List<ApplicationContext> sort(ApplicationContext[] applicationContexts) {
        if (applicationContexts == null || applicationContexts.length == 0) {
            return Collections.emptyList();
        }
        ArrayList<String> contextNames = new ArrayList<>();
        contextNames.add("WebServerApplicationContext");
        contextNames.add("ReactiveWebApplicationContext");
        contextNames.add("WebApplicationContext");
        contextNames.add("ConfigurableApplicationContext");
        List<ApplicationContext> result = Arrays.asList(applicationContexts);
        result.sort(Comparator.comparing(ApplicationContext::getDisplayName, (name1, name2) -> {
            for (String s : contextNames) {
                if (name1.contains(s)) {
                    return -1;
                }
                if (name2.contains(s)) {
                    return 1;
                }
            }
            return 0;
        }));
        for (ApplicationContext applicationContext : result) {
            // 由于没有set进去，会从执行线程获取类加载器
            if (applicationContext instanceof DefaultResourceLoader) {
                ((DefaultResourceLoader) applicationContext).setClassLoader(applicationContext.getClassLoader());
            }
        }
        return result;
    }

    public static <T> T getLastBean(String beanName) {
        return CollUtil.getLast(getBeans(beanName));
    }

    public static <T> List<T> getBeans(String beanName) {
        initSpringContext();
        List<T> beansByApplicationContext = getBeansByApplicationContext(beanName);
        if (!beansByApplicationContext.isEmpty()) {
            return beansByApplicationContext;
        }
        return getBeansByBeanFactory(beanName);
    }

    public static <T> T getLastBean(Class<T> requiredType) {
        return CollectionUtil.getLast(getBeans(requiredType));
    }

    public static <T> List<T> getBeans(Class<T> requiredType) {
        initSpringContext();
        List<T> beansByApplicationContext = getBeansByApplicationContext(requiredType);
        if (!beansByApplicationContext.isEmpty()) {
            return beansByApplicationContext;
        }
        return getBeansByBeanFactory(requiredType);
    }

    public static <T> void registerBean(T bean) {
        registerBean(Introspector.decapitalize(ClassUtils.getShortName(bean.getClass())), bean);
    }

    public static <T> void registerBean(String beanName, T bean) {
        initSpringContext();
        boolean factoryFound = false;
        for (BeanFactory beanFactory : beanFactories) {
            if (beanFactory instanceof ConfigurableListableBeanFactory) {
                ConfigurableListableBeanFactory factory = (ConfigurableListableBeanFactory) beanFactory;
                factory.autowireBean(bean);
                factory.registerSingleton(beanName, bean);
                factoryFound = true;
                break;
            }
        }
        if (!factoryFound) {
            for (ApplicationContext applicationContext : applicationContexts) {
                if (applicationContext instanceof ConfigurableApplicationContext) {
                    ConfigurableListableBeanFactory factory = ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
                    factory.autowireBean(bean);
                    factory.registerSingleton(beanName, bean);
                    break;
                }
            }
        }
    }

    public static <T> void registerBean(String beanName, Class<T> beanClass) {
        initSpringContext();
        boolean factoryFound = false;
        for (BeanFactory beanFactory : beanFactories) {
            if (beanFactory instanceof DefaultListableBeanFactory) {
                DefaultListableBeanFactory registry = (DefaultListableBeanFactory) beanFactory;
                // 1. 构建 Bean 定义
                BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(beanClass);
                // 如果需要延迟加载
                // builder.setLazyInit(true);
                // 2. 注册定义 (此时并没有创建对象)
                registry.registerBeanDefinition(beanName, builder.getBeanDefinition());
                factoryFound = true;
                break;
            }
        }
        if (!factoryFound) {
            for (ApplicationContext applicationContext : applicationContexts) {
                if (applicationContext instanceof BeanDefinitionRegistry) {
                    BeanDefinitionRegistry registry = (BeanDefinitionRegistry) applicationContext;
                    // 2. 构建 Bean 定义
                    BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(beanClass);
                    // builder.setLazyInit(true); // 如果需要延迟
                    // 3. 注册定义
                    registry.registerBeanDefinition(beanName, builder.getBeanDefinition());
                    break;
                }
            }
        }
    }

    public static void unregisterBean(String beanName) {
        initSpringContext();
        boolean factoryFound = false;
        for (BeanFactory beanFactory : beanFactories) {
            if (beanFactory instanceof DefaultSingletonBeanRegistry) {
                DefaultSingletonBeanRegistry registry = (DefaultSingletonBeanRegistry) beanFactory;
                // 原有的销毁单例
                if (registry.containsSingleton(beanName)) {
                    registry.destroySingleton(beanName);
                    logger.warning("Bean " + beanName + " has been unregistered");
                }
                factoryFound = true;
                break;
            }
        }
        if (!factoryFound) {
            for (ApplicationContext applicationContext : applicationContexts) {
                if (applicationContext instanceof ConfigurableApplicationContext) {
                    ConfigurableListableBeanFactory factory = ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
                    if (factory instanceof DefaultSingletonBeanRegistry) {
                        ((DefaultSingletonBeanRegistry) factory).destroySingleton(beanName);
                        break;
                    }
                }
            }
        }
    }

    public static void unregisterBeanAndDefinition(String beanName) {
        initSpringContext();
        boolean factoryFound = false;
        for (BeanFactory beanFactory : beanFactories) {
            if (beanFactory instanceof DefaultSingletonBeanRegistry) {
                DefaultSingletonBeanRegistry registry = (DefaultSingletonBeanRegistry) beanFactory;
                // 原有的销毁单例
                if (registry.containsSingleton(beanName)) {
                    registry.destroySingleton(beanName);
                    logger.warning("Bean " + beanName + " has been unregistered");
                }
                // 新增：如果是DefaultListableBeanFactory，移除Bean定义
                if (beanFactory instanceof DefaultListableBeanFactory) {
                    DefaultListableBeanFactory defaultBeanFactory = (DefaultListableBeanFactory) beanFactory;

                    // 确保Bean定义也被移除
                    if (defaultBeanFactory.containsBeanDefinition(beanName)) {
                        defaultBeanFactory.removeBeanDefinition(beanName);
                        logger.warning("Bean definition for " + beanName + " has been removed");
                    }

                    // 可选：清理Bean的别名
                    try {
                        String[] aliases = defaultBeanFactory.getAliases(beanName);
                        for (String alias : aliases) {
                            defaultBeanFactory.removeAlias(alias);
                            logger.warning("Alias " + alias + " for bean " + beanName + " has been removed");
                        }
                    } catch (Exception e) {
                        // 忽略别名异常
                        logger.warning("Error removing alias for bean " + beanName, e);
                    }
                }

                factoryFound = true;
                break;
            }
        }
        if (!factoryFound) {
            for (ApplicationContext applicationContext : applicationContexts) {
                if (applicationContext instanceof ConfigurableApplicationContext) {
                    ConfigurableListableBeanFactory factory = ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
                    if (factory instanceof DefaultSingletonBeanRegistry) {
                        ((DefaultSingletonBeanRegistry) factory).destroySingleton(beanName);
                        break;
                    }
                    if (factory instanceof BeanDefinitionRegistry) {
                        ((BeanDefinitionRegistry) factory).removeBeanDefinition(beanName);
                    }
                }
            }
        }
    }

    private static <T> List<T> getBeansByApplicationContext(Class<T> requiredType) {
        List<T> beanList = new LinkedList<>();
        for (ApplicationContext applicationContext : applicationContexts) {
            try {
                beanList.addAll(applicationContext.getBeansOfType(requiredType).values());
            } catch (BeansException e) {
                if (ProjectConstants.DEBUG) {
                    logger.warning("获取bean信息失败", e);
                }
            }
        }
        return beanList;
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> getBeansByApplicationContext(String beanName) {
        List<T> beanList = new LinkedList<>();
        for (ApplicationContext applicationContext : applicationContexts) {
            try {
                beanList.add((T) applicationContext.getBean(beanName));
            } catch (BeansException e) {
                if (ProjectConstants.DEBUG) {
                    logger.warning("获取bean信息失败", e);
                }
            }
        }
        return beanList;
    }

    private static <T> List<T> getBeansByBeanFactory(Class<T> requiredType) {
        List<T> beanList = new LinkedList<>();
        for (BeanFactory beanFactory : beanFactories) {
            try {
                beanList.add(beanFactory.getBean(requiredType));
            } catch (BeansException e) {
                if (ProjectConstants.DEBUG) {
                    logger.warning("获取bean信息失败", e);
                }
            }
        }
        return beanList;
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> getBeansByBeanFactory(String beanName) {
        List<T> beanList = new LinkedList<>();
        for (BeanFactory beanFactory : beanFactories) {
            try {
                beanList.add((T) beanFactory.getBean(beanName));
            } catch (BeansException e) {
                if (ProjectConstants.DEBUG) {
                    logger.warning("获取bean信息失败", e);
                }
            }
        }
        return beanList;
    }

    public static Object getSpringConfig(String value) {
        initSpringContext();
        for (ApplicationContext applicationContext : applicationContexts) {
            Object property = getSpringConfig(applicationContext.getEnvironment(), value);
            if (property != null) {
                return property;
            }
        }
        return getSpringConfig(getLastBean(Environment.class), value);
    }

    private static Object getSpringConfig(Environment environment, String value) {
        if (environment == null) {
            return null;
        }
        Object property = environment.getProperty(value, Object.class);
        if (property == null) {
            // 尝试获取数组
            List<Object> result = new LinkedList<>();
            int i = 0;
            Object object;
            do {
                object = environment.getProperty(value + "[" + i++ + "]", Object.class);
                if (object != null) {
                    result.add(object);
                }
            } while (object != null);
            if (result.isEmpty()) {
                return null;
            } else {
                return result.toArray(new Object[0]);
            }
        } else {
            return property;
        }
    }

    public static Map<String, Object> getSpringReadyStatus() {
        initSpringContext();
        return evaluateSpringReadyStatus(applicationContexts, init);
    }

    static Map<String, Object> evaluateSpringReadyStatus(List<ApplicationContext> applicationContexts) {
        return evaluateSpringReadyStatus(applicationContexts, isSpringContextReadyToCache(applicationContexts));
    }

    private static Map<String, Object> evaluateSpringReadyStatus(List<ApplicationContext> applicationContexts, boolean ready) {
        if (ready) {
            return readyStatus(true, "UP", false);
        }
        if (applicationContexts == null || applicationContexts.isEmpty()) {
            return readyStatus(false, "STARTING", true);
        }
        for (ApplicationContext applicationContext : applicationContexts) {
            if (isBootstrapContext(applicationContext) || !isContextActive(applicationContext)) {
                continue;
            }
            if (isContextUsableForInvocation(applicationContext)) {
                return readyStatus(true, "UP", false);
            }
        }
        return readyStatus(false, "STARTING", true);
    }

    private static Map<String, Object> readyStatus(boolean ready, String state, boolean retryable) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("ready", ready);
        status.put("state", state);
        status.put("retryable", retryable);
        return status;
    }

    private static boolean isContextActive(ApplicationContext applicationContext) {
        return !(applicationContext instanceof ConfigurableApplicationContext) ||
                ((ConfigurableApplicationContext) applicationContext).isActive();
    }

    private static boolean isBootstrapContext(ApplicationContext applicationContext) {
        String id = applicationContext.getId();
        if (id != null && "bootstrap".equalsIgnoreCase(id)) {
            return true;
        }
        String displayName = applicationContext.getDisplayName();
        return displayName != null && displayName.toLowerCase().contains("bootstrap");
    }

    private static boolean isContextUsableForInvocation(ApplicationContext applicationContext) {
        if (isAcceptingTraffic(applicationContext)) {
            return true;
        }
        RefreshState refreshState = getRefreshState(applicationContext);
        if (refreshState == RefreshState.NOT_FINISHED) {
            return false;
        }
        return true;
    }

    private static boolean isAcceptingTraffic(ApplicationContext applicationContext) {
        try {
            ClassLoader classLoader = applicationContext.getClassLoader();
            if (classLoader == null) {
                classLoader = Thread.currentThread().getContextClassLoader();
            }
            Class<?> availabilityClass = Class.forName("org.springframework.boot.availability.ApplicationAvailability", false, classLoader);
            Class<?> readinessStateClass = Class.forName("org.springframework.boot.availability.ReadinessState", false, classLoader);
            Object availability = applicationContext.getBean(availabilityClass);
            Object readinessEvent = availabilityClass.getMethod("getLastChangeEvent", Class.class).invoke(availability, readinessStateClass);
            if (readinessEvent == null) {
                return false;
            }
            Object readinessState = readinessEvent.getClass().getMethod("getState").invoke(readinessEvent);
            return readinessState != null && "ACCEPTING_TRAFFIC".equals(((Enum<?>) readinessState).name());
        } catch (ClassNotFoundException | BeansException e) {
            return false;
        } catch (Exception e) {
            if (ProjectConstants.DEBUG) {
                logger.warning("获取 Spring Boot readiness 状态失败", e);
            }
            return false;
        }
    }

    private static RefreshState getRefreshState(ApplicationContext applicationContext) {
        try {
            Field lifecycleProcessor = findField(applicationContext.getClass(), "lifecycleProcessor");
            if (lifecycleProcessor == null) {
                return RefreshState.UNKNOWN;
            }
            // 只用作避免 refresh 过程中太早 ready；拿不到时不能阻塞 DebugTools 方法调用。
            lifecycleProcessor.setAccessible(true);
            return lifecycleProcessor.get(applicationContext) == null ? RefreshState.NOT_FINISHED : RefreshState.FINISHED;
        } catch (Exception e) {
            if (ProjectConstants.DEBUG) {
                logger.warning("获取 Spring refresh 状态失败", e);
            }
            return RefreshState.UNKNOWN;
        }
    }

    private static Field findField(Class<?> clazz, String fieldName) {
        Class<?> searchType = clazz;
        while (searchType != null && searchType != Object.class) {
            try {
                return searchType.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                searchType = searchType.getSuperclass();
            }
        }
        return null;
    }

    private enum RefreshState {
        FINISHED,
        NOT_FINISHED,
        UNKNOWN
    }

    @SuppressWarnings("unchecked")
    public static <T> T getTargetObject(Object candidate) {
        if (candidate == null) {
            return null;
        }
        try {
            if (isAopProxy(candidate) && candidate instanceof Advised) {
                Object target = ((Advised) candidate).getTargetSource().getTarget();
                if (target != null) {
                    return getTargetObject(target);
                }
            }
        } catch (Throwable e) {
            if (ProjectConstants.DEBUG) {
                logger.warning("获取实例信息失败", e);
            }

        }
        return (T) candidate;
    }

    public static Class<?> getTargetClass(Object candidate) {
        if (candidate == null) {
            return null;
        }
        Class<?> result = null;
        if (candidate instanceof TargetClassAware) {
            result = ((TargetClassAware) candidate).getTargetClass();
        }
        if (result == null) {
            result = (isCglibProxy(candidate) ? candidate.getClass().getSuperclass() : candidate.getClass());
        }
        return result;
    }

    private static boolean isCglibProxy(@Nullable Object object) {
        return (object instanceof SpringProxy &&
                object.getClass().getName().contains(ClassUtils.CGLIB_CLASS_SEPARATOR));
    }

    private static boolean isAopProxy(@Nullable Object object) {
        return (object instanceof SpringProxy && (Proxy.isProxyClass(object.getClass()) ||
                object.getClass().getName().contains(ClassUtils.CGLIB_CLASS_SEPARATOR)));
    }

    public static Method findBridgedMethod(Method targetMethod) {
        return BridgeMethodResolver.findBridgedMethod(targetMethod);
    }

    public static boolean isAopProxy(InvocationHandler invocationHandler) {
        return invocationHandler instanceof AopProxy;
    }

    public static Object[] getArgs(Method bridgedMethod, RunDTO runDTO) {
        return SpringParamConvertUtils.getArgs(bridgedMethod, runDTO);
    }

    public static String[] getBeanNamesForType(Class<?> type) {
        initSpringContext();
        for (BeanFactory beanFactory : beanFactories) {
            if (beanFactory instanceof DefaultListableBeanFactory) {
                DefaultListableBeanFactory factory = (DefaultListableBeanFactory) beanFactory;
                return factory.getBeanNamesForType(type);
            }
        }
        return new String[0];
    }

    public static BeanDefinition getBeanDefinition(String beanName) {
        initSpringContext();
        for (BeanFactory beanFactory : beanFactories) {
            if (beanFactory instanceof DefaultListableBeanFactory) {
                DefaultListableBeanFactory factory = (DefaultListableBeanFactory) beanFactory;
                return factory.getBeanDefinition(beanName);
            }
        }
        return null;
    }

}
