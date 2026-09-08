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
package io.github.future0923.debug.tools.hotswap.core.plugin.mybatis.reload;

import io.github.future0923.debug.tools.base.logging.Logger;
import io.github.future0923.debug.tools.hotswap.core.plugin.spring.scanner.ClassPathBeanDefinitionScannerAgent;
import io.github.future0923.debug.tools.hotswap.core.util.ReflectionHelper;
import org.mybatis.spring.mapper.ClassPathMapperScanner;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * @author future0923
 */
public abstract class AbstractMyBatisResourceReload<T> implements MyBatisResourceReload {

    private static final Logger logger = Logger.getLogger(AbstractMyBatisResourceReload.class);

@Override
    @SuppressWarnings("unchecked")
    public void reload(Object object) throws Exception {
        // 全局重载互斥：XML 重载（watcher 线程）与 mapper/实体重载（命令执行线程）会并发读写
        // configuration 的 mappedStatements/loadedResources，实测出现 ConcurrentModificationException
        // （重启后首轮批量 XML 重载与 mapper 重载撞车）。重入锁：mapper 重载内部再调
        // reloadMapperStatements（同锁）不会死锁。
        java.util.concurrent.locks.ReentrantLock lock = MyBatisSpringResourceManager.RELOAD_LOCK;
        lock.lock();
        try {
            doReload((T) object);
        } finally {
            lock.unlock();
        }
    }

    protected abstract void doReload(T object) throws Exception;

    /**
     * 这块是mybatis接口的生成代理类的原理
     */
    protected void mybatisBeanDefinition(ClassPathMapperScanner mapperScanner, BeanDefinitionHolder holder){
        try{
            Set<BeanDefinitionHolder> holders = new HashSet<>();
            holders.add(holder);
            Class<?> classPathMapperScanner = Class.forName("org.mybatis.spring.mapper.ClassPathMapperScanner");
            Method method = classPathMapperScanner.getDeclaredMethod("processBeanDefinitions", Set.class);
            boolean isAccess = method.isAccessible();
            method.setAccessible(true);
            method.invoke(mapperScanner, holders);
            method.setAccessible(isAccess);
        }catch (Exception e) {
            logger.error("freshMyBatis err",e);
        }
    }

    protected void defineBean(String className, byte[] bytes, String path) throws IOException {
        ClassPathMapperScanner mapperScanner = MyBatisSpringResourceManager.getMapperScanner();
        if (mapperScanner == null) {
            logger.debug("mapperScanner is null");
            return;
        }
        ClassPathBeanDefinitionScannerAgent scannerAgent = ClassPathBeanDefinitionScannerAgent.getInstance(mapperScanner);
        BeanDefinition beanDefinition = scannerAgent.resolveBeanDefinition(bytes);
        if (beanDefinition == null) {
            logger.error("not found beanDefinition:{}", className);
            return;
        }
        scannerAgent.defineBean(beanDefinition, path);
        BeanNameGenerator beanNameGenerator = (BeanNameGenerator) ReflectionHelper.get(mapperScanner, "beanNameGenerator");
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) ReflectionHelper.get(scannerAgent, "registry");
        String beanName = beanNameGenerator.generateBeanName(beanDefinition, registry);
        BeanDefinitionHolder definitionHolder = new BeanDefinitionHolder(beanDefinition, beanName);
        mybatisBeanDefinition(mapperScanner, definitionHolder);
        logger.reload("register mapper {} in spring bean", className);
    }
}
