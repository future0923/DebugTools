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
package io.github.future0923.debug.tools.hotswap.core.plugin.forest.reload;

import com.dtflys.forest.config.ForestConfiguration;
import com.dtflys.forest.scanner.ClassPathClientScanner;
import io.github.future0923.debug.tools.base.constants.ProjectConstants;
import io.github.future0923.debug.tools.base.logging.Logger;
import io.github.future0923.debug.tools.hotswap.core.plugin.forest.ForestPlugin;
import io.github.future0923.debug.tools.hotswap.core.plugin.forest.dto.ForestClientReloadDTO;
import io.github.future0923.debug.tools.hotswap.core.plugin.spring.scanner.ClassPathBeanDefinitionScannerAgent;
import io.github.future0923.debug.tools.hotswap.core.util.ReflectionHelper;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("unchecked")
public class ForestReload {

    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    public static Object getLock(String className) {
        return LOCKS.computeIfAbsent(className, k -> new Object());
    }

    private static final Logger logger = Logger.getLogger(ForestReload.class);

    private static final Set<String> RELOADING_CLASS = ConcurrentHashMap.newKeySet();

    private ForestReload() {

    }

    protected void reload(ForestClientReloadDTO dto) throws Exception {
        String className = dto.getClassName();
        // 同类中取重
        if (!RELOADING_CLASS.add(className)) {
            if (ProjectConstants.DEBUG) {
                logger.info("{} plus reload task is already running, skip.", className);
            }
            return;
        }
        // 不同类中串行
        Object lock = getLock(className);
        try {
            synchronized (lock) {
                ForestConfiguration forestConfiguration = (ForestConfiguration) ForestPlugin.getForestConfiguration();
                if (forestConfiguration == null) {
                    logger.error("forestConfiguration is null");
                    return;
                }

                if(forestConfiguration.getInstanceCache().containsKey(Class.forName(className))){
                    logger.debug("forestConfiguration instance cache contains key {},starting remove", className);
                    forestConfiguration.getInstanceCache().remove(Class.forName(className));
                }

                forestConfiguration.createInstance(Class.forName(className));
                defineBean(className, dto.getBytes(), dto.getPath());
                logger.reload("reload {} in {}", className);
            }
        } catch (Exception e) {
            logger.error("refresh forest client error", e);
        } finally {
            RELOADING_CLASS.remove(className);
        }
    }


    protected void forestBeanDefinition(ClassPathClientScanner scanner, BeanDefinitionHolder holder) {
        try {
            Set<BeanDefinitionHolder> holders = new HashSet<>();
            holders.add(holder);
            Class<?> classPathMapperScanner = Class.forName("com.dtflys.forest.scanner.ClassPathClientScanner");
            Method method = classPathMapperScanner.getDeclaredMethod("processBeanDefinitions", Set.class);

            boolean isAccess = method.isAccessible();
            method.setAccessible(true);
            method.invoke(scanner, holders);
            method.setAccessible(isAccess);
        } catch (Exception e) {
            logger.error("freshForest err", e);
        }
    }

    protected void defineBean(String className, byte[] bytes, String path) throws IOException {
        ClassPathClientScanner forestScanner = (ClassPathClientScanner) ForestPlugin.getScanner();

        if (forestScanner == null) {
            logger.error("forestScanner is null");
            return;
        }
        ClassPathBeanDefinitionScannerAgent scannerAgent = ClassPathBeanDefinitionScannerAgent.getInstance(forestScanner);
        BeanDefinition beanDefinition = scannerAgent.resolveBeanDefinition(bytes);
        if (beanDefinition == null) {
            logger.error("not found beanDefinition:{}", className);
            return;
        }
        scannerAgent.defineBean(beanDefinition, path);
        BeanNameGenerator beanNameGenerator = (BeanNameGenerator) ReflectionHelper.get(forestScanner, "beanNameGenerator");
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) ReflectionHelper.get(scannerAgent, "registry");
        String beanName = beanNameGenerator.generateBeanName(beanDefinition, registry);
        BeanDefinitionHolder definitionHolder = new BeanDefinitionHolder(beanDefinition, beanName);
        forestBeanDefinition(forestScanner, definitionHolder);
        logger.reload("register forest client {} in spring bean", className);
    }

}
