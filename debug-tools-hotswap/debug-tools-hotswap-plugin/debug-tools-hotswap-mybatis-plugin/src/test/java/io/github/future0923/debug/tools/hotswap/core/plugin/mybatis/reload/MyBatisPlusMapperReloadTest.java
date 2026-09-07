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

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import io.github.future0923.debug.tools.hotswap.core.util.ReflectionHelper;
import org.apache.ibatis.binding.MapperRegistry;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MyBatis-Plus 环境下 mapper 热重载回归测试。
 * <p>
 * 覆盖 bug：DebugTools 的 {@link MyBatisPlusMapperReload} 调用 MP 的 removeMapper + addMapper 后，
 * mappedStatements 中该 mapper 的全部 statement（含 XML 定义的）被 removeMapper 清空，而 addMapper
 * 内部 MapperAnnotationBuilder.parse() 因 loadedResources 中 "namespace:xxx" 标记未移除而跳过
 * loadXmlResource，XML statement 不会自动恢复，导致该 mapper 的 XML 方法全部报
 * Invalid bound statement (not found)。
 * </p>
 */
class MyBatisPlusMapperReloadTest {

    private static final String XML_RESOURCE = "mapper/ReloadPlusTestMapper.xml";

    private static final String NAMESPACE = "io.github.future0923.debug.tools.hotswap.core.plugin.mybatis.reload.MyBatisPlusMapperReloadTest$ReloadPlusTestMapper";

    /**
     * 测试启动时 XMLMapperBuilder 构造传入的 resource 字符串与 Spring 环境一致：
     * SqlSessionFactoryBean 传的是 {@code resource.toString()}（如 "class path resource [xxx]"），
     * 该字符串就是 loadedResources 中的 key。
     */
    private static final String KEY_FORMAT = "class path resource [" + XML_RESOURCE + "]";

    private MybatisConfiguration configuration;

    @BeforeEach
    void setUp() throws Exception {
        configuration = new MybatisConfiguration();
        // 清理其他测试可能残留的静态状态
        MyBatisSpringResourceManager.getConfigurationList().clear();
        MyBatisSpringResourceManager.registerConfiguration(configuration);
        MyBatisSpringResourceManager.addMapperLocations(new String[]{"classpath*:" + XML_RESOURCE});
        // 模拟启动：SqlSessionFactoryBean 解析 XML（会写入 loadedResources 的 key 并 addMapper）
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(XML_RESOURCE)) {
            XMLMapperBuilder xmlMapperBuilder = new XMLMapperBuilder(
                    is, configuration, KEY_FORMAT, configuration.getSqlFragments());
            xmlMapperBuilder.parse();
        }
        // MP 的 SQL 注入器在启动时会向 mapperRegistryCache 登记 mapper，
        // removeMapper 只有 cache 中存在记录才会执行清理，这里模拟该登记
        GlobalConfigUtils.getMapperRegistryCache(configuration).add("interface " + NAMESPACE);
    }

    @AfterEach
    void tearDown() throws Exception {
        Field field = MyBatisSpringResourceManager.class.getDeclaredField("mapperLocations");
        field.setAccessible(true);
        Set<String> mapperLocations = (Set<String>) field.get(null);
        mapperLocations.clear();
        MyBatisSpringResourceManager.getConfigurationList().clear();
    }

    /**
     * 复现 bug：removeMapper + addMapper 后 XML statement 丢失；
     * 验证修复：重新解析 XML 后 XML statement 恢复。
     */
    @Test
    void mapperReloadKeepsXmlStatements() throws Exception {
        // 启动后：注解 statement 与 XML statement 都在
        assertTrue(configuration.hasStatement(NAMESPACE + ".selectAnn"), "启动后注解 statement 应存在");
        assertTrue(configuration.hasStatement(NAMESPACE + ".selectXml"), "启动后 XML statement 应存在");

        // 复现 DebugTools 的 MP 重载逻辑：removeMapper 清空全部 statement，addMapper 只恢复注解 statement
        MapperRegistry mapperRegistry = configuration.getMapperRegistry();
        configuration.removeMapper(ReloadPlusTestMapper.class);
        assertFalse(mapperRegistry.hasMapper(ReloadPlusTestMapper.class), "removeMapper 后注册表应清空");

        // 修复：清理 removeMapper 未处理的 resultMaps/parameterMaps 残留（否则 addMapper 重解析注解时 StrictMap 冲突抛异常）
        MyBatisPlusMapperReload reload = newInstance(MyBatisPlusMapperReload.class);
        invokePrivate(reload, "clearReloadResidue", configuration, NAMESPACE);
        configuration.addMapper(ReloadPlusTestMapper.class);
        assertTrue(mapperRegistry.hasMapper(ReloadPlusTestMapper.class), "addMapper 后注册表应恢复");
        assertTrue(configuration.hasStatement(NAMESPACE + ".selectAnn"), "注解 statement 应被 addMapper 恢复");
        assertFalse(configuration.hasStatement(NAMESPACE + ".selectXml"), "复现bug：XML statement 在 removeMapper+addMapper 后丢失");

        // 修复：按 mapper-locations 重新解析 XML，恢复 XML statement
        invokePrivate(reload, "reloadXmlStatements", configuration, NAMESPACE, getClass().getClassLoader());

        assertTrue(configuration.hasStatement(NAMESPACE + ".selectXml"), "修复后 XML statement 应恢复");
        assertTrue(configuration.hasStatement(NAMESPACE + ".selectAnn"), "修复后注解 statement 应保留");
        assertTrue(mapperRegistry.hasMapper(ReloadPlusTestMapper.class), "修复后注册表应保留");
    }

    /**
     * 修复不应影响其他 mapper：重载 mapperA 时 mapperB 的 statement 保持原样
     */
    @Test
    void reloadOneMapperDoesNotAffectOthers() throws Exception {
        configuration.addMapper(ReloadPlusTestMapper.class);

        MyBatisPlusMapperReload reload = newInstance(MyBatisPlusMapperReload.class);
        invokePrivate(reload, "reloadXmlStatements", configuration, NAMESPACE, getClass().getClassLoader());

        assertTrue(configuration.hasStatement(NAMESPACE + ".selectXml"));
        assertTrue(configuration.hasStatement(NAMESPACE + ".selectAnn"));
    }

    /**
     * 用户实际场景：mapperLocations 收集值不匹配（如 mybatis-spring-boot 默认的
     * "classpath:mapper/*.xml, mapper2/*.xml" 模式，PathMatchingResourcePatternResolver 只解析
     * 第一个 classpath 根、匹配不到子模块的 XML），此时必须靠 loadedResources 兜底找回 XML。
     */
    @Test
    void mapperReloadRestoresXmlWhenMapperLocationsMismatch() throws Exception {
        // 清空 mapperLocations，模拟收集到的模式完全匹配不到 XML 的环境
        clearMapperLocations();
        assertTrue(MyBatisSpringResourceManager.getMapperLocations().isEmpty(), "前置：mapperLocations 应已清空");

        configuration.removeMapper(ReloadPlusTestMapper.class);
        MyBatisPlusMapperReload reload = newInstance(MyBatisPlusMapperReload.class);
        invokePrivate(reload, "clearReloadResidue", configuration, NAMESPACE);
        configuration.addMapper(ReloadPlusTestMapper.class);
        assertTrue(configuration.hasStatement(NAMESPACE + ".selectAnn"), "注解 statement 应被 addMapper 恢复");
        assertFalse(configuration.hasStatement(NAMESPACE + ".selectXml"), "复现bug：XML statement 丢失");

        invokePrivate(reload, "reloadXmlStatements", configuration, NAMESPACE, getClass().getClassLoader());

        assertTrue(configuration.hasStatement(NAMESPACE + ".selectXml"), "loadedResources 兜底应恢复 XML statement");
    }

    /**
     * 实测环境场景（多模块工程）：启动 key 被 XML 重载删掉、只剩一条 {@code file [/C:/...]} 形态的
     * 重复 key（URL.getFile() 产物，带前导斜杠）。修复前这种 key 根本打不开，日志会报
     * "loadedResource key not openable"，XML statement 无法恢复，只能重启。
     */
    @Test
    void mapperReloadRestoresXmlFromLeadingSlashKey() throws Exception {
        clearMapperLocations();
        Set<String> loadedResources = loadedResources();
        // 只保留带前导斜杠的绝对路径 key，复现运行时残留的唯一线索
        loadedResources.remove(KEY_FORMAT);
        File tempXml = File.createTempFile("ReloadPlusTestMapper", ".xml");
        tempXml.deleteOnExit();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(XML_RESOURCE)) {
            Files.copy(is, tempXml.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        String poisonedKey = "file [/" + tempXml.getAbsolutePath().replace('\\', '/') + "]";
        loadedResources.add(poisonedKey);

        configuration.removeMapper(ReloadPlusTestMapper.class);
        MyBatisPlusMapperReload reload = newInstance(MyBatisPlusMapperReload.class);
        invokePrivate(reload, "clearReloadResidue", configuration, NAMESPACE);
        configuration.addMapper(ReloadPlusTestMapper.class);
        assertFalse(configuration.hasStatement(NAMESPACE + ".selectXml"), "复现bug：XML statement 丢失");

        invokePrivate(reload, "reloadXmlStatements", configuration, NAMESPACE, getClass().getClassLoader());

        assertTrue(configuration.hasStatement(NAMESPACE + ".selectXml"), "前导斜杠形态的 key 必须能打开并恢复 XML statement");
    }

    /**
     * 渐进失效场景：用户反馈“经常这样”，而单次热更正常。多轮 removeMapper+addMapper+XML 找回
     * 交替时，上一轮重新写回的 loadedResources key 必须能被下一轮再次命中，否则从某轮起
     * XML statement 会永久停在“只剩 MP 注入 CRUD”的状态（实测 45→18）。
     */
    @Test
    void repeatedMapperReloadKeepsXmlStatements() throws Exception {
        MyBatisPlusMapperReload reload = newInstance(MyBatisPlusMapperReload.class);
        for (int round = 1; round <= 5; round++) {
            configuration.removeMapper(ReloadPlusTestMapper.class);
            invokePrivate(reload, "clearReloadResidue", configuration, NAMESPACE);
            assertTrue(configuration.getSqlFragments().isEmpty(),
                    "第 " + round + " 轮：清理后 sqlFragments 不应残留（StrictMap 短 key 会引发重解析冲突）");
            configuration.addMapper(ReloadPlusTestMapper.class);
            invokePrivate(reload, "reloadXmlStatements", configuration, NAMESPACE, getClass().getClassLoader());
            assertTrue(configuration.hasStatement(NAMESPACE + ".selectXml"),
                    "第 " + round + " 轮热重载后 XML statement 应仍存在（当前 loadedResources="
                            + loadedResources().size() + "）");
            assertTrue(configuration.hasStatement(NAMESPACE + ".selectAnn"), "第 " + round + " 轮后注解 statement 应存在");
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> loadedResources() throws Exception {
        return (Set<String>) ReflectionHelper.get(configuration, "loadedResources");
    }

    /**
     * 复现并锁定“改实体类/多模块连带重编译”场景（用户实际报的症状）：
     * {@code MyBatisPlusEntityReload} 会按 {@code mapperClass.getName() + "."} 前缀直接清空该 mapper 的
     * 全部 mappedStatements（含 XML 定义的），但只会重新注入 MP 的 CRUD 方法，从不重解析 XML；
     * 它也不走 removeMapper/addMapper，所以 mapper 重载轨迹看起来完全正常，XML 方法却全报
     * "Invalid bound statement (not found)"。实体重载必须在注入完 CRUD 后补一次 XML 重解析。
     */
    @Test
    void entityStyleWipeRestoresXmlStatements() throws Exception {
        configuration.addMapper(ReloadPlusTestMapper.class);
        assertTrue(configuration.hasStatement(NAMESPACE + ".selectXml"), "启动后 XML statement 应存在");

        // 复现实体重载的 wipe：只删 mappedStatements，不调 removeMapper
        Map<String, org.apache.ibatis.mapping.MappedStatement> mappedStatements =
                (Map<String, org.apache.ibatis.mapping.MappedStatement>) ReflectionHelper.get(configuration, "mappedStatements");
        Set<String> wiped = mappedStatements.keySet().stream()
                .filter(key -> key.startsWith(NAMESPACE + "."))
                .collect(java.util.stream.Collectors.toSet());
        wiped.forEach(mappedStatements::remove);
        assertEquals(13, wiped.size(), "实体重载的 wipe 会连 MP CRUD、注解与 XML statement 一起删掉");
        assertFalse(configuration.hasStatement(NAMESPACE + ".selectXml"), "复现 bug：实体重载后 XML statement 丢失");
        assertFalse(configuration.hasStatement(NAMESPACE + ".selectAnn"), "复现 bug：实体重载后注解 statement 也丢失");
        // 实体重载不碰注册表，所以 mapper 重载路径的轨迹不会记录任何东西
        assertTrue(configuration.getMapperRegistry().hasMapper(ReloadPlusTestMapper.class),
                "实体重载不应移除注册表（因此 mapper 重载修复无法兼顾该场景）");

        // 修复：完整重解析（removeMapper + 清残留 + addMapper + 重解析 XML）
        MyBatisPlusMapperReload.reloadMapperStatements(configuration, ReloadPlusTestMapper.class,
                getClass().getClassLoader());

        assertTrue(configuration.hasStatement(NAMESPACE + ".selectXml"), "修复后 XML statement 应恢复");
        assertTrue(configuration.hasStatement(NAMESPACE + ".selectAnn"), "修复后注解 statement 应恢复");
        // MP 具体注入了哪些 CRUD 方法随版本与实体字段而异（本用例启动时就没有 selectById），
        // 所以按“必须覆盖擦除前的全部 statement”断言，而不是硬编码某个方法名。
        assertTrue(mappedStatements.keySet().containsAll(wiped),
                "修复后应恢复擦除前的全部 " + wiped.size() + " 条 statement，实际缺失: "
                        + wiped.stream().filter(id -> !mappedStatements.containsKey(id))
                                .collect(java.util.stream.Collectors.toList()));
    }

    /**
     * 实体重载场景连续多轮也必须稳定（每轮都会重新写入 sqlFragments/resultMaps）。
     */
    @Test
    void entityStyleWipeSurvivesRepeatedRounds() throws Exception {
        configuration.addMapper(ReloadPlusTestMapper.class);
        for (int round = 1; round <= 3; round++) {
            Map<String, org.apache.ibatis.mapping.MappedStatement> mappedStatements =
                    (Map<String, org.apache.ibatis.mapping.MappedStatement>) ReflectionHelper.get(configuration, "mappedStatements");
            mappedStatements.keySet().stream()
                    .filter(key -> key.startsWith(NAMESPACE + "."))
                    .collect(java.util.stream.Collectors.toSet())
                    .forEach(mappedStatements::remove);
            MyBatisPlusMapperReload.reloadMapperStatements(configuration, ReloadPlusTestMapper.class,
                    getClass().getClassLoader());
            assertTrue(configuration.hasStatement(NAMESPACE + ".selectXml"), "第 " + round + " 轮实体重载后应恢复 XML statement");
            assertTrue(configuration.hasStatement(NAMESPACE + ".selectAnn"), "第 " + round + " 轮注解 statement 应恢复");
        }
    }

    private static void clearMapperLocations() throws Exception {
        Field field = MyBatisSpringResourceManager.class.getDeclaredField("mapperLocations");
        field.setAccessible(true);
        Set<String> mapperLocations = (Set<String>) field.get(null);
        mapperLocations.clear();
    }

    private static MyBatisPlusMapperReload newInstance(Class<MyBatisPlusMapperReload> clazz) throws Exception {
        Constructor<MyBatisPlusMapperReload> constructor = clazz.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    /**
     * 按参数可赋值性匹配方法（方法声明参数类型可能比实参类型更宽，如 Configuration vs MybatisConfiguration）
     */
    private static Object invokePrivate(Object target, String methodName, Object... args) throws Exception {
        Method matched = null;
        for (Method method : target.getClass().getDeclaredMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != args.length) {
                continue;
            }
            boolean assignable = true;
            Class<?>[] paramTypes = method.getParameterTypes();
            for (int i = 0; i < args.length; i++) {
                if (!paramTypes[i].isAssignableFrom(args[i].getClass())) {
                    assignable = false;
                    break;
                }
            }
            if (assignable) {
                matched = method;
                break;
            }
        }
        if (matched == null) {
            throw new NoSuchMethodException(methodName);
        }
        matched.setAccessible(true);
        return matched.invoke(target, args);
    }

    @TableName("reload_plus_test")
    static class ReloadPlusTestEntity {
    }

    interface ReloadPlusTestMapper extends BaseMapper<ReloadPlusTestEntity> {

        String selectXml();

        @org.apache.ibatis.annotations.Select("select 'ann'")
        String selectAnn();
    }

    

    

    

    

    

    

    /**
     * XML 重载的残留清理绝不能碰 mappedStatements。
     * <p>
     * XML 重载只会重新解析 XML 里定义的那部分 statement，若清理时把 MP 注入的 CRUD
     * （selectById/insert/updateById...）一起删掉，它们再也补不回来——实测 mapper 从 45 条掉到 27 条，
     * 且只有在真实多模块工程里才会暴露（本地单测没有 MP CRUD 注入）。
     * </p>
     */
    @Test
    void xmlReloadResidueClearMustNotTouchMappedStatements() {
        int before = MyBatisPlusMapperReload.countNamespaceStatements(configuration, NAMESPACE);
        assertTrue(before > 0, "前置条件：mapper 已注册 statement");
        MyBatisSpringResourceManager.clearNamespaceResidue(configuration, NAMESPACE);
        assertEquals(before, MyBatisPlusMapperReload.countNamespaceStatements(configuration, NAMESPACE),
                "clearNamespaceResidue（XML 重载路径）不得删除 mappedStatements");
        // 重载专用方法则必须能清掉，否则短 key 残留会让 addMapper 的 put 整条忽略
        MyBatisSpringResourceManager.clearNamespaceStatements(configuration, NAMESPACE);
        assertEquals(0, MyBatisPlusMapperReload.countNamespaceStatements(configuration, NAMESPACE),
                "clearNamespaceStatements 应清空该 namespace 的 statement");
    }

}
