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
import io.github.future0923.debug.tools.hotswap.core.plugin.mybatis.dto.MyBatisPlusEntityReloadDTO;
import io.github.future0923.debug.tools.hotswap.core.plugin.mybatis.utils.MyBatisUtils;
import io.github.future0923.debug.tools.hotswap.core.util.ReflectionHelper;
import org.apache.ibatis.binding.MapperRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.Reflector;
import org.apache.ibatis.session.Configuration;
import org.mybatis.spring.mapper.ClassPathMapperScanner;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * 重载 MyBatisPlus Entity 资源
 *
 * @author future0923
 */
@SuppressWarnings({"unchecked"})
public class MyBatisPlusEntityReload extends AbstractMyBatisResourceReload<MyBatisPlusEntityReloadDTO> {

    private static final Logger logger = Logger.getLogger(MyBatisPlusEntityReload.class);

    private static final Set<String> RELOADING_CLASS = ConcurrentHashMap.newKeySet();

    private MyBatisPlusEntityReload() {
    }

    @Override
    protected void doReload(MyBatisPlusEntityReloadDTO dto) throws Exception {
        Class<?> clazz = dto.getClazz();
        String className = clazz.getName();
        ClassLoader classLoader = dto.getUserClassLoader();
        // 同类中取重
        if (!RELOADING_CLASS.add(className)) {
            if (ProjectConstants.DEBUG) {
                logger.info("{} plus reload task is already running, skip.", className);
            }
            return;
        }
        try {
            logger.debug("reload class: {}", className);
            ClassPathMapperScanner mapperScanner = MyBatisSpringResourceManager.getMapperScanner();
            if (mapperScanner == null) {
                logger.debug("mapperScanner is null");
                return;
            }
            Set<Configuration> configurationList = MyBatisSpringResourceManager.getConfigurationList();
            if (configurationList.isEmpty()) {
                logger.debug("mybatis configuration is empty");
                return;
            }
            // 不同类中串行
            Object lock = MyBatisUtils.getLock(className);
            synchronized (lock) {
                for (Configuration configuration : configurationList) {
                    Class<? extends Configuration> configurationClass = configuration.getClass();
                    if (configurationClass.getName().equals("com.baomidou.mybatisplus.core.MybatisConfiguration")) {
                        MapperRegistry mapperRegistry = configuration.getMapperRegistry();
                        Collection<Class<?>> mappers = mapperRegistry.getMappers();
                        List<Class<?>> mapperClassList = new LinkedList<>();
                        for (Class<?> mapper : mappers) {
                            Type[] genericInterfaces = mapper.getGenericInterfaces();
                            for (Type genericInterface : genericInterfaces) {
                                if (genericInterface instanceof ParameterizedType) {
                                    ParameterizedType parameterizedType = (ParameterizedType) genericInterface;
                                    Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
                                    if (actualTypeArguments.length > 0) {
                                        Type modelType = Arrays.stream(actualTypeArguments).filter(type -> type.getTypeName().equals(clazz.getTypeName())).findAny().orElse(null);
                                        if (modelType != null) {
                                            mapperClassList.add(mapper);
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                        for (Class<?> mapperClass : mapperClassList) {
                            Map<String, MappedStatement> mappedStatements = (Map<String, MappedStatement>) ReflectionHelper.get(configuration, "mappedStatements");
                            // 清空 Mapper 方法 mappedStatement 缓存信息
                            final String typeKey = mapperClass.getName() + ".";
                            Set<String> mapperSet = mappedStatements.keySet()
                                    .stream()
                                    .filter(mappedStatement -> mappedStatement.startsWith(typeKey))
                                    .collect(Collectors.toSet());
                            for (String key : mapperSet) {
                                mappedStatements.remove(key);
                            }
                            //构建MapperBuilderAssistant
                            String xmlResource = mapperClass.getName().replace(".", "/") + ".java (best guess)";
                            MapperBuilderAssistant builderAssistant = new MapperBuilderAssistant(configuration, xmlResource);
                            builderAssistant.setCurrentNamespace(mapperClass.getName());

                            //移除实体类对应的字段缓存，否则在初始化TableInfo的时候，不重新初始化字段集合
                            Map<Class<?>, List<Field>> classFieldCache = (Map<Class<?>, List<Field>>) ReflectionHelper.get(null, classLoader.loadClass("com.baomidou.mybatisplus.core.toolkit.ReflectionKit"), "CLASS_FIELD_CACHE");
                            classFieldCache.remove(clazz);

                            //移除mapper缓存，否则不执行循环注入自定义方法  if (!mapperRegistryCache.contains(className)) {
                            Set<String> mapperRegistryCache = (Set<String>) ReflectionHelper.invoke(null, classLoader.loadClass("com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils"), "getMapperRegistryCache", new Class[]{Configuration.class}, builderAssistant.getConfiguration());
                            mapperRegistryCache.remove(mapperClass.toString());

                            //移除实体类对应的表缓存，否则不重新初始化TableInfo
                            ReflectionHelper.invoke(null, classLoader.loadClass("com.baomidou.mybatisplus.core.metadata.TableInfoHelper"), "remove", new Class[]{Class.class}, clazz);

                            //移除实体类对应的映射器缓存
                            DefaultReflectorFactory reflectorFactory = (DefaultReflectorFactory) configuration.getReflectorFactory();
                            ConcurrentMap<Class<?>, Reflector> reflectorMap = (ConcurrentMap<Class<?>, Reflector>) ReflectionHelper.get(reflectorFactory, reflectorFactory.getClass(), "reflectorMap");
                            reflectorMap.remove(clazz);

                            //注入自定义方法
                            Object iSqlInjector = ReflectionHelper.invoke(null, classLoader.loadClass("com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils"), "getSqlInjector", new Class[]{Configuration.class}, configuration);
                            ReflectionHelper.invoke(iSqlInjector, iSqlInjector.getClass(), "inspectInject", new Class[]{MapperBuilderAssistant.class, Class.class}, builderAssistant, mapperClass);

                            // 上面已按 mapperClass.getName()+"." 前缀删掉了该 mapper 的全部 mappedStatements
                            // （含注解与 XML 定义的），而 inspectInject 只会重新注入 MP 的 CRUD 方法：
                            // 接口上的 @Select 等注解 statement 与 XML statement 都不会回来，调用时报
                            // "Invalid bound statement (not found)"，只能重启。这是多模块工程里
                            // “改一行代码就要重启”的主要成因（任何 entity 变动都会连带波及它的全部 mapper）。
                            // 这里复用 mapper 重载同一套完整重解析逻辑补齐（内部会先 removeMapper
                            // 再 addMapper，因为实体重载没摘除注册表，直接 addMapper 会抛 "is known"）。
                            MyBatisPlusMapperReload.reloadMapperStatements(configuration, mapperClass, classLoader);
                        }
                        logger.reload("reload entity class {}", className);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("refresh mybatis entity error", e);
        } finally {
            RELOADING_CLASS.remove(className);
        }
    }
}
