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
import io.github.future0923.debug.tools.hotswap.core.plugin.mybatis.dto.MyBatisPlusMapperReloadDTO;
import io.github.future0923.debug.tools.hotswap.core.plugin.mybatis.utils.MyBatisUtils;
import io.github.future0923.debug.tools.hotswap.core.util.ReflectionHelper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.session.Configuration;
import org.mybatis.spring.mapper.ClassPathMapperScanner;
import org.springframework.core.io.Resource;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 重载 MyBatisPlus Mapper 资源
 *
 * @author future0923
 */
public class MyBatisPlusMapperReload extends AbstractMyBatisResourceReload<MyBatisPlusMapperReloadDTO> {

    private static final Logger logger = Logger.getLogger(MyBatisPlusMapperReload.class);

    private static final Set<String> RELOADING_CLASS = ConcurrentHashMap.newKeySet();

    private MyBatisPlusMapperReload() {
    }

    @Override
    protected void doReload(MyBatisPlusMapperReloadDTO dto) throws Exception {
        Class<?> clazz = dto.getClazz();
        String className = clazz.getName();
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
                        ReflectionHelper.invoke(configuration, configurationClass, "removeMapper", new Class[]{Class.class}, clazz);
                        // MP 的 removeMapper 只清理 mappedStatements/knownMappers/loadedResources，
                        // resultMaps/parameterMaps 中该 mapper 的条目会残留。若不清理，addMapper 重解析注解方法时
                        // StrictMap.put 会抛 "Result Maps collection already contains value for ..."，
                        // 整个重载失败且注册表处于半破坏状态，只能重启恢复。
                        clearReloadResidue(configuration, className);
                        ReflectionHelper.invoke(configuration, configurationClass, "addMapper", new Class[]{Class.class}, clazz);
                        // MP 的 removeMapper 会把 mappedStatements 中该 mapper 的全部 statement（含 XML 定义的）清空，
                        // 而 addMapper 内部 MapperAnnotationBuilder.parse() 因 loadedResources 中 "namespace:xxx" 标记
                        // 未移除而跳过 loadXmlResource，XML statement 不会自动恢复。这里必须按 mapper-locations 找到
                        // 对应的 XML 重新解析，否则该 mapper 的 XML 方法全部报 Invalid bound statement (not found)。
                        reloadXmlStatements(configuration, className, dto.getUserClassLoader());
                        defineBean(className, dto.getBytes(), dto.getPath());
                        logger.reload("reload {} in {}", className, configuration);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("refresh mybatis plus mapper error", e);
        } finally {
            RELOADING_CLASS.remove(className);
        }
    }

    /**
     * 清理 removeMapper 未处理的注册表残留。
     * <p>
     * 实现已下沉到 {@link MyBatisSpringResourceManager#clearNamespaceResidue}，与 XML 重载共用：
     * MP 的 removeMapper 只清 mappedStatements，不清 resultMaps/parameterMaps/sqlFragments/keyGenerators，
     * 残留会让 addMapper 与重解析 XML 时 StrictMap.put 抛冲突异常；MP 的 StrictMap 还会写入不带
     * namespace 的短 key（useGeneratedShortKey），需按 value id 前缀一并清除。
     * </p>
     */
    /**
     * 重新解析 mapper 接口，恢复它的全部 statement（MP CRUD + 注解 + XML）。
     * <p>
     * 供 mapper 重载与实体重载共用。实体重载（{@code MyBatisPlusEntityReload}）会按
     * {@code mapperClass.getName() + "."} 前缀直接清空 mappedStatements，但只会重新注入 MP 的 CRUD
     * 方法，既不会恢复接口上的 {@code @Select} 等注解 statement，也不会重解析 XML；而且它不会从
     * knownMappers 里摘除该接口，所以不能直接调 addMapper（会抛 "is known" 异常），必须先 removeMapper。
     * </p>
     */
    public static void reloadMapperStatements(Configuration configuration, Class<?> mapperClass, ClassLoader classLoader) {
        String className = mapperClass.getName();
        Class<? extends Configuration> configurationClass = configuration.getClass();
        // 与 AbstractMyBatisResourceReload.reload() 共用全局互斥锁，避免与 watcher 线程的 XML 重载
        // 并发操作同一 configuration（实体重载/mapper 重载也会被锁在外面排队）。
        java.util.concurrent.locks.ReentrantLock lock = MyBatisSpringResourceManager.RELOAD_LOCK;
        lock.lock();
        try {
            reloadMapperStatementsInternal(configuration, mapperClass, classLoader, className, configurationClass);
        } finally {
            lock.unlock();
        }
    }

    private static void reloadMapperStatementsInternal(Configuration configuration, Class<?> mapperClass, ClassLoader classLoader,
                                                       String className, Class<? extends Configuration> configurationClass) {
        // MP 的 inspectInject 以 mapperRegistryCache 里的 mapperClass.toString() 作为幂等标记。
        // 实体重载在调本方法前已经手动 inspectInject 过一次并写回了该标记，若不摘掉，
        // 下面的 addMapper 会跳过 CRUD 注入，而 removeMapper 已把旧 CRUD statement 删了
        // → selectById/insert 等反而丢失（实测如此）。必须在重解析前清除。
        ensureMapperRegistryCache(configuration, classLoader, mapperClass);
        ReflectionHelper.invoke(configuration, configurationClass, "removeMapper", new Class[]{Class.class}, mapperClass);
        clearReloadResidue(configuration, className);
        // 必须显式摘掉 loadedResources 里的接口解析标记：MapperAnnotationBuilder.parse() 的总开关就是
        // isResourceLoaded(type.toString())，只要 "interface <类名>" 还在，整个“遍历接口方法重建注解
        // statement”的循环会被跳过，addMapper 不抛异常也不注册任何东西（实测：反射调 MP 的
        // removeMapper 后该标记仍存在，而同样的序列改成直接调用则能恢复全部 statement）。
        // addMapper 自会重新写回这些 key，不会造成重复解析。
        removeMapperParseMarkers(configuration, mapperClass);
        // addMapper 是 MyBatis Configuration 的公开 API，直接调用而不是反射：
        // 反射包装会把 InvocationTargetException 转成 IllegalStateException，丢失原始报错信息。
        configuration.addMapper(mapperClass);
        reloadXmlStatements(configuration, className, classLoader);
    }

    /**
     * 移除 loadedResources 中阻塞接口重解析的 {@code interface <类名>} 标记（removeMapper 正常路径下已删，此处兜底）。
     * <p>注意不能连 {@code namespace:<类名>} 一起删：那会让 parse() 里的 loadXmlResource() 重跑一遍，
     * 与后面的 reloadXmlStatements 重复解析同一 XML，反而撞出 resultMap 重复。</p>
     */
    @SuppressWarnings("unchecked")
    private static void removeMapperParseMarkers(Configuration configuration, Class<?> mapperClass) {
        try {
            Set<String> loadedResources = (Set<String>) ReflectionHelper.get(configuration, "loadedResources");
            if (loadedResources == null) {
                return;
            }
            loadedResources.remove(mapperClass.toString());
        } catch (Exception e) {
            logger.debug("remove mapper parse markers for {} error: {}", mapperClass, e.getMessage());
        }
    }

    /**
     * 保证 mapperRegistryCache 里有该 mapper 的标记，使 MP 的 removeMapper 真能生效。
     * <p>
     * 这里必须是“补上”而不是“摘掉”。MP 3.5.1 的 {@code MybatisConfiguration.removeMapper(Class)} 开头就是
     * {@code if (mapperRegistryCache.contains(type.toString())) { ...真正注销... }}，标记不在则整个方法空转；
     * 紧接着的 {@code addMapper} 在 {@code MybatisMapperRegistry} 里遇到已注册时是直接 {@code return}
     * （MP 有意不抛异常），于是 {@code parse()} 根本不会被调用 —— 表现为“重载没报错、日志说成功，
     * 但一条 statement 都没恢复”，只能重启。实体重载正是先 remove 了标记再调本方法，因此必须补回。
     * removeMapper 自己会在注销后把标记删掉，后续 addMapper 因此会重新注入 CRUD，无需额外处理。
     * </p>
     */
    private static void ensureMapperRegistryCache(Configuration configuration, ClassLoader classLoader, Class<?> mapperClass) {
        try {
            Class<?> globalConfigUtils = classLoader == null
                    ? GlobalConfigUtilsHolder.CLASS
                    : classLoader.loadClass("com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils");
            if (globalConfigUtils == null) {
                return;
            }
            @SuppressWarnings("unchecked")
            Set<String> mapperRegistryCache = (Set<String>) ReflectionHelper.invoke(null, globalConfigUtils,
                    "getMapperRegistryCache", new Class[]{Configuration.class}, configuration);
            if (mapperRegistryCache != null) {
                mapperRegistryCache.add(mapperClass.toString());
            }
        } catch (Throwable t) {
            logger.debug("ensure mapper registry cache for {} error: {}", mapperClass, t.getMessage());
        }
    }

    /**
     * 无 classLoader 时的兜底：从本类可见的类路径里找 GlobalConfigUtils（测试环境用）。
     */
    private static final class GlobalConfigUtilsHolder {
        private static final Class<?> CLASS = resolve();

        private static Class<?> resolve() {
            try {
                return Class.forName("com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils");
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /**
     * 重载专用残留清理：除了 resultMap/parameterMap/sqlFragment/keyGenerator，
     * 还必须清掉 mappedStatements 的短 key——本方法的所有调用点后面紧跟 addMapper，
     * 会把 XML、注解、MP CRUD 全量重新注册，所以提前删干净是安全的（也是必要的）。
     */
    static void clearReloadResidue(Configuration configuration, String className) {
        MyBatisSpringResourceManager.clearNamespaceResidue(configuration, className);
        MyBatisSpringResourceManager.clearNamespaceStatements(configuration, className);
    }

    /**
     * 重新解析该 mapper 对应的 XML，恢复被 removeMapper 清空的 XML statement。
     * <p>
     * 优先从 {@code configuration.loadedResources}（启动时 SqlSessionFactoryBean 真实加载过的 XML 清单）找回：
     * loadedResources 的 key 是 XMLMapperBuilder 构造时传入的 resource 字符串（如
     * "class path resource [mapper2/BaseDictUserMapper.xml]"），可靠且与任何 mapper-locations 配置解耦。
     * 不依赖 mapperLocations 模式匹配的原因：多模块工程中收集到的 mapperLocations 可能是 mybatis-spring-boot
     * 的默认值（如 "classpath:mapper/*.xml, mapper2/*.xml"），而 PathMatchingResourcePatternResolver 对
     * "classpath:" 前缀和无前缀模式只解析<b>第一个</b> classpath 根下的目录（determineRootDir 后 resourceLoader
     * .getResource 单根语义），子模块（如 comm-lib）target/classes 下的 XML 永远匹配不到，导致 XML statement
     * 无法恢复、该 mapper 的 XML 方法全部 Invalid bound statement。
     * </p>
     *
     * @param configuration MyBatis Configuration
     * @param className     mapper 接口全限定名（即 XML 的 namespace）
     * @param classLoader   应用类加载器，用于解析 mapper-locations 通配符
     */
    static void reloadXmlStatements(Configuration configuration, String className, ClassLoader classLoader) {
        Set<String> loadedResources = (Set<String>) ReflectionHelper.get(configuration, LOADED_RESOURCES_FIELD);
        if (loadedResources != null && !loadedResources.isEmpty()) {
            if (reloadXmlFromLoadedResources(configuration, className, classLoader, loadedResources)) {
                return;
            }
        }
        Set<String> locations = MyBatisSpringResourceManager.getMapperLocations();
        if (locations == null || locations.isEmpty()) {
            logger.debug("mapperLocations is empty, skip xml reload for {}", className);
            return;
        }
        for (String location : locations) {
            try {
                Resource[] resources = MyBatisSpringResourceManager.getPathMatchingResourcePatternResolver(classLoader, location);
                if (resources == null || resources.length == 0) {
                    continue;
                }
                for (Resource resource : resources) {
                    String namespace = readNamespace(resource);
                    if (className.equals(namespace)) {
                        String loadedResource = FILE + " [" + MyBatisSpringResourceManager.getRelativePath(resource.getURL()) + "]";
                        if (loadedResources != null) {
                            loadedResources.remove(loadedResource);
                        }
                        try (java.io.InputStream inputStream = resource.getInputStream()) {
                            XMLMapperBuilder xmlMapperBuilder = new XMLMapperBuilder(
                                    inputStream, configuration, loadedResource, configuration.getSqlFragments());
                            xmlMapperBuilder.parse();
                        }
                        logger.reload("reload xml {} for mapper {}", loadedResource, className);
                        return;
                    }
                }
            } catch (Exception e) {
                // 单个 location 解析失败不影响其他 location；XML 缺失时仅告警，不阻断 mapper 重载主流程
                logger.error("reload xml for mapper {} error, location: {}", className, location, e);
            }
        }
    }

    /**
     * 统计该 mapper 在 mappedStatements 中的 statement 数。
     * <p>MyBatis 的 StrictMap 会为歧义短 key 放入 List 占位，遇到非 MappedStatement 元素要跳过。</p>
     */
    static int countNamespaceStatements(Configuration configuration, String className) {
        int count = 0;
        String prefix = className + ".";
        // 快照迭代：重载线程间并发写 mappedStatements 时，直接遍历活集合会抛
        // ConcurrentModificationException（重启后首轮批量 XML 重载时实测）。
        for (Object element : new java.util.ArrayList<>(configuration.getMappedStatements())) {
            if (!(element instanceof org.apache.ibatis.mapping.MappedStatement)) {
                continue;
            }
            if (((org.apache.ibatis.mapping.MappedStatement) element).getId().startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 从 {@code configuration.loadedResources} 中枚举 XML，找到 namespace 与该 mapper 匹配的资源并重新解析。
     * <p>
     * loadedResources 的 key 由 XMLMapperBuilder 构造时传入的 resource 字符串决定，常见格式：
     * <ul>
     *   <li>{@code class path resource [mapper2/BaseDictUserMapper.xml]} —— Spring ClassPathResource.toString()</li>
     *   <li>{@code file [D:/.../mapper2/BaseDictUserMapper.xml]} —— Spring FileSystemResource</li>
     *   <li>{@code URL [jar:file:/...!/mapper2/BaseDictUserMapper.xml]} —— 打包 jar 内的 XML</li>
     *   <li>裸相对路径 {@code mapper/xxx.xml}</li>
     * </ul>
     * 命中后：先把旧 key 从 loadedResources 移除（否则 XMLMapperBuilder.parse() 内部 isResourceLoaded 检查会跳过），
     * 再用原 key 重新构造 XMLMapperBuilder 解析，让 statement 以原 key 重新注册。
     * </p>
     */
    static boolean reloadXmlFromLoadedResources(Configuration configuration, String className, ClassLoader classLoader,
                                                 Set<String> loadedResources) {
        for (String loadedResource : new ArrayList<>(loadedResources)) {
            String xmlPath = extractXmlPath(loadedResource);
            if (xmlPath == null) {
                continue;
            }
            try (java.io.InputStream inputStream = openXmlStream(xmlPath, classLoader)) {
                if (inputStream == null) {
                    continue;
                }
                if (!className.equals(readNamespace(inputStream))) {
                    continue;
                }
                loadedResources.remove(loadedResource);
                int before = countNamespaceStatements(configuration, className);
                try (java.io.InputStream parseStream = openXmlStream(xmlPath, classLoader)) {
                    XMLMapperBuilder xmlMapperBuilder = new XMLMapperBuilder(
                            parseStream, configuration, loadedResource, configuration.getSqlFragments());
                    xmlMapperBuilder.parse();
                } catch (Exception e) {
                    // 解析失败时必须把 key 放回：否则下次重载连“启动时加载过哪个 XML”的线索都没了，
                    // 变成不可恢复的级联失败（只能重启）。
                    loadedResources.add(loadedResource);
                    throw e;
                }
                // 必须验证 parse 真的写回了 statement：“没报错”不等于“生效”——XMLMapperBuilder.parse()
                // 在 isResourceLoaded 为真时会直接空转，日志会误报成功而 statement 依旧丢失。
                int after = countNamespaceStatements(configuration, className);
                if (after <= before) {
                    loadedResources.add(loadedResource);
                    return false;
                }
                logger.reload("reload xml {} for mapper {}", loadedResource, className);
                return true;
            } catch (Exception e) {
                // 单个 key 打开/解析失败说明它可能不是 XML（如 namespace:/interface 标记），继续下一个
                logger.debug("try reload xml from loadedResource {} error: {}", loadedResource, e.getMessage());
            }
        }
        return false;
    }

    /**
     * 从 loadedResources 的 key 中提取可重新打开的 XML 路径；非 XML 资源返回 null。
     * <p>实现已下沉到 {@link MyBatisSpringResourceManager#extractXmlPath}，与 XML 重载共用同一套路径识别规则。</p>
     */
    static String extractXmlPath(String loadedResource) {
        return MyBatisSpringResourceManager.extractXmlPath(loadedResource);
    }

    /**
     * 按 XML 路径打开流（实现见 {@link MyBatisSpringResourceManager#openXmlStream}）。
     */
    static java.io.InputStream openXmlStream(String xmlPath, ClassLoader classLoader) throws Exception {
        return MyBatisSpringResourceManager.openXmlStream(xmlPath, classLoader);
    }

    /**
     * 读取 XML 的 namespace 属性，用于匹配 mapper 接口。解析失败返回 null（不匹配）。
     */
    static String readNamespace(Resource resource) {
        try (java.io.InputStream inputStream = resource.getInputStream()) {
            return readNamespace(inputStream);
        } catch (Exception e) {
            logger.debug("read namespace from {} error: {}", resource, e.getMessage());
            return null;
        }
    }

    /**
     * 从输入流读取 XML 的 namespace 属性。
     */
    static String readNamespace(java.io.InputStream inputStream) {
        try {
            // 必须传 XMLMapperEntityResolver：XPathParser 默认构造在 JDK9+ 上因 accessExternalDTD 限制
            // 无法解析 http 外部 DTD（mybatis-3-mapper.dtd），导致 namespace 读取失败
            XPathParser parser = new XPathParser(inputStream, true, null, new org.apache.ibatis.builder.xml.XMLMapperEntityResolver());
            XNode xNode = parser.evalNode("/mapper");
            return xNode == null ? null : xNode.getStringAttribute(NAMESPACE);
        } catch (Exception e) {
            logger.debug("read namespace error: {}", e.getMessage());
            return null;
        }
    }
}
