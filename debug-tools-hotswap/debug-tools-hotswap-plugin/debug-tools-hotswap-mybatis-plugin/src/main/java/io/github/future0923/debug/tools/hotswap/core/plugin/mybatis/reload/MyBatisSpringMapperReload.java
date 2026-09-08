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

import io.github.future0923.debug.tools.base.constants.ProjectConstants;
import io.github.future0923.debug.tools.base.logging.Logger;
import io.github.future0923.debug.tools.hotswap.core.plugin.mybatis.dto.MyBatisSpringMapperReloadDTO;
import io.github.future0923.debug.tools.hotswap.core.plugin.mybatis.utils.MyBatisUtils;
import io.github.future0923.debug.tools.hotswap.core.util.ReflectionHelper;
import org.apache.ibatis.binding.MapperProxyFactory;
import org.apache.ibatis.binding.MapperRegistry;
import org.apache.ibatis.builder.annotation.MapperAnnotationBuilder;
import org.apache.ibatis.session.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 重新载入 mybatis spring 的 mapper 资源
 *
 * @author future0923
 */
@SuppressWarnings("unchecked")
public class MyBatisSpringMapperReload extends AbstractMyBatisResourceReload<MyBatisSpringMapperReloadDTO> {

    private static final Logger logger = Logger.getLogger(MyBatisSpringMapperReload.class);

    private static final Set<String> RELOADING_CLASS = ConcurrentHashMap.newKeySet();

    private MyBatisSpringMapperReload() {

    }

    @Override
    protected void doReload(MyBatisSpringMapperReloadDTO dto) throws Exception {
        String className = dto.getClassName();
        // 同类中取重
        if (!RELOADING_CLASS.add(className)) {
            if (ProjectConstants.DEBUG) {
                logger.info("{} plus reload task is already running, skip.", className);
            }
            return;
        }
        String loadedResource = buildLoadedResource(className);
        // 不同类中串行
        Object lock = MyBatisUtils.getLock(className);
        try {
            synchronized (lock) {
                for (Configuration configuration : MyBatisSpringResourceManager.getConfigurationList()) {
                    Set<String> loadedResources = (Set<String>) ReflectionHelper.get(configuration, LOADED_RESOURCES_FIELD);
                    loadedResources.remove(loadedResource);
                    MapperRegistry mapperRegistry = (MapperRegistry) ReflectionHelper.get(configuration, "mapperRegistry");
                    Map<Class<?>, MapperProxyFactory<?>> knownMappers = (Map<Class<?>, MapperProxyFactory<?>>) ReflectionHelper.get(mapperRegistry, "knownMappers");
                    // 记录本次要移除的mapper，后续需要恢复，避免从注册表永久丢失
                    List<Class<?>> removedMappers = new ArrayList<>();
                    knownMappers.keySet().removeIf(mapperClass -> {
                        if (loadedResource.contains(mapperClass.getName())) {
                            removedMappers.add(mapperClass);
                            return true;
                        }
                        return false;
                    });
                    try {
                        Class<?> mapperClass = Class.forName(className);
                        new MapperAnnotationBuilder(configuration, mapperClass).parse();
                        defineBean(className, dto.getBytes(), dto.getPath());
                        // defineBean 只对重新生成的 mapper bean 有效：对于启动时就已实例化的单例 bean，
                        // MapperFactoryBean.checkDaoConfig 不会再执行，MapperRegistry 里被上面 removeIf 移除的
                        // 条目不会自动恢复。这里必须显式重新注册，否则后续 getMapper 会报
                        // "Type ... is not known to the MapperRegistry"。
                        for (Class<?> removed : removedMappers) {
                            if (!knownMappers.containsKey(removed)) {
                                mapperRegistry.addMapper(removed);
                            }
                        }
                        logger.reload("reload {} in {}", className, configuration);
                    } catch (Exception e) {
                        // 解析/注册任何一步失败都要恢复注册表，避免 mapper 丢失后所有方法调用失败
                        for (Class<?> removed : removedMappers) {
                            if (!knownMappers.containsKey(removed)) {
                                knownMappers.put(removed, new MapperProxyFactory<>(removed));
                            }
                        }
                        throw e;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("refresh mybatis mapper error", e);
        } finally {
            RELOADING_CLASS.remove(className);
        }
    }

    private String buildLoadedResource(String className) {
        return INTERFACE + " " + className;
    }
}
