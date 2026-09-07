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

import io.github.future0923.debug.tools.hotswap.core.plugin.mybatis.dto.MyBatisSpringMapperReloadDTO;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.CtNewMethod;
import org.apache.ibatis.binding.MapperProxyFactory;
import org.apache.ibatis.binding.MapperRegistry;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MyBatisSpringMapperReload 回归测试：
 * <ul>
 *   <li>mapper 热重载后必须仍然注册在 MapperRegistry.knownMappers 中（旧实现依赖 defineBean 恢复，
 *       但对已实例化的单例 bean 是空操作，mapper 会从注册表永久丢失，后续调用报 not known to the MapperRegistry）</li>
 *   <li>接口结构变更（新增方法）时重载流程不能被指纹比对破坏</li>
 * </ul>
 */
class MyBatisSpringMapperReloadTest {

    private Configuration configuration;

    @BeforeEach
    void setUp() throws Exception {
        clearConfigurations();
        configuration = new Configuration();
        MyBatisSpringResourceManager.registerConfiguration(configuration);
        try (InputStream in = getClass().getResourceAsStream("/mapper/ReloadTestMapper.xml")) {
            new XMLMapperBuilder(in, configuration, "mapper/ReloadTestMapper.xml", configuration.getSqlFragments()).parse();
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        clearConfigurations();
    }

    private void clearConfigurations() throws Exception {
        Field field = MyBatisSpringResourceManager.class.getDeclaredField("configurationList");
        field.setAccessible(true);
        ((Set<?>) field.get(null)).clear();
    }

    @SuppressWarnings("unchecked")
    private Map<Class<?>, MapperProxyFactory<?>> knownMappers() throws Exception {
        MapperRegistry registry = (MapperRegistry) field(configuration, "mapperRegistry");
        return (Map<Class<?>, MapperProxyFactory<?>>) field(registry, "knownMappers");
    }

    private Object field(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    private int mappedStatementCount() throws Exception {
        return ((Map<?, ?>) field(configuration, "mappedStatements")).size();
    }

    private byte[] readClassBytes(Class<?> clazz) throws Exception {
        try (InputStream in = clazz.getClassLoader().getResourceAsStream(clazz.getName().replace('.', '/') + ".class")) {
            assert in != null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            return out.toByteArray();
        }
    }

    private void reload(String className, byte[] bytes) throws Exception {
        java.lang.reflect.Constructor<MyBatisSpringMapperReload> constructor =
                MyBatisSpringMapperReload.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        MyBatisResourceReload reload = constructor.newInstance();
        MyBatisSpringMapperReloadDTO dto = new MyBatisSpringMapperReloadDTO(className, bytes, "target/test-classes/" + className.replace('.', '/') + ".class");
        reload.reload(dto);
    }

    @Test
    void mapperReloadKeepsRegistryEntryAndStatements() throws Exception {
        String ns = ReloadTestMapper.class.getName();
        assertTrue(knownMappers().containsKey(ReloadTestMapper.class));
        int countBefore = mappedStatementCount();
        assertTrue(configuration.hasStatement(ns + ".selectXml", false));
        assertTrue(configuration.hasStatement(ns + ".selectAnn", false));

        reload(ReloadTestMapper.class.getName(), readClassBytes(ReloadTestMapper.class));

        // 核心回归点：重载后 mapper 必须仍在注册表中，statement 数量不变
        assertTrue(knownMappers().containsKey(ReloadTestMapper.class),
                "mapper 热重载后仍应注册在 MapperRegistry 中");
        assertEquals(countBefore, mappedStatementCount(), "statement 数量不应变化");
        assertTrue(configuration.hasStatement(ns + ".selectXml", false));
        assertTrue(configuration.hasStatement(ns + ".selectAnn", false));
    }

    @Test
    void structuralChangeDoesNotBreakReload() throws Exception {
        String ns = ReloadTestMapper.class.getName();
        // 用 javassist 给接口追加一个方法，模拟"新增方法"的结构变更（JVM 标准热替换无法应用）
        CtClass ctClass = new ClassPool(true).makeClass(new ByteArrayInputStream(readClassBytes(ReloadTestMapper.class)));
        CtMethod newMethod = CtNewMethod.make("public java.lang.String selectNewMethod(java.lang.Long id) { return null; }", ctClass);
        ctClass.addMethod(newMethod);
        byte[] v2Bytes = ctClass.toBytecode();

        reload(ReloadTestMapper.class.getName(), v2Bytes);

        // 结构变更无法被JVM热替换时，重载流程本身不能破坏现有状态
        assertTrue(knownMappers().containsKey(ReloadTestMapper.class));
        assertTrue(configuration.hasStatement(ns + ".selectXml", false));
        assertTrue(configuration.hasStatement(ns + ".selectAnn", false));
    }
}
