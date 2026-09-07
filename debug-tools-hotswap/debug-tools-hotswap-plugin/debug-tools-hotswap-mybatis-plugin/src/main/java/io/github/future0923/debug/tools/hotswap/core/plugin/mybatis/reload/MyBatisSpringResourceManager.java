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

import io.github.future0923.debug.tools.base.hutool.core.util.ArrayUtil;
import io.github.future0923.debug.tools.base.hutool.core.util.ReflectUtil;
import io.github.future0923.debug.tools.base.hutool.core.util.StrUtil;
import io.github.future0923.debug.tools.base.logging.Logger;
import io.github.future0923.debug.tools.hotswap.core.config.PluginConfiguration;
import io.github.future0923.debug.tools.hotswap.core.config.PluginManager;
import io.github.future0923.debug.tools.hotswap.core.plugin.mybatis.patch.IBatisPatcher;
import io.github.future0923.debug.tools.hotswap.core.plugin.mybatis.patch.MyBatisSpringPatcher;
import io.github.future0923.debug.tools.hotswap.core.util.ReflectionHelper;
import javassist.ClassPool;
import javassist.CtClass;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMap;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.session.Configuration;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.ClassPathMapperScanner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.File;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * MyBatis环境资源管理
 *
 * @author future0923
 */
public class MyBatisSpringResourceManager {

    private static final Logger logger = Logger.getLogger(MyBatisSpringResourceManager.class);

    /**
     * 全局重载互斥锁：XML 重载（watcher 线程）与 mapper/实体重载（命令执行线程）并发读写
     * configuration 的 mappedStatements / loadedResources，实测重启后首轮批量重载会撞出
     * ConcurrentModificationException（虽然被 fallback 自愈，但会让 statement 短暂丢失）。
     * 重入锁：mapper 重载内部再进入同锁（reloadMapperStatements）不会死锁。
     */
    public static final java.util.concurrent.locks.ReentrantLock RELOAD_LOCK = new java.util.concurrent.locks.ReentrantLock();

    /**
     * mybatis Configuration 集合
     */
    private static final Set<Configuration> configurationList = new HashSet<>();

    /**
     * MyBatis Spring MapperScanner
     */
    private static ClassPathMapperScanner mapperScanner;

    /**
     * Mapper.xml文件扫描路径
     */
    private static final Set<String> mapperLocations = new HashSet<>();

    /**
     * <ul>
     *  <li>当{@link Configuration}实例化的时候{@link IBatisPatcher#patchConfiguration}会注册进来。</li>
     *  <li>当{@link SqlSessionFactoryBean}实例化完成时会获取到{@link Configuration}对象注入到集合中，在{@link MyBatisSpringPatcher#patchSqlSessionFactoryBean(CtClass, ClassPool)}插桩。</li>
     * </ul>
     */
    public static void registerConfiguration(Configuration configuration) {
        if (configuration != null) {
            configurationList.add(configuration);
        }
    }

    /**
     * {@link MyBatisSpringPatcher#patchClassPathMapperScanner}注入对象
     */
    public static void loadScanner(ClassPathMapperScanner scanner) {
        if (null != mapperScanner) {
            return;
        }
        mapperScanner = scanner;

    }

    /**
     * {@link MyBatisSpringPatcher#patchMapperLocations(CtClass, ClassPool)}注入对象
     */
    public static void addMapperLocations(String[] mapperLocations) {
        if (mapperLocations == null) {
            return;
        }
        MyBatisSpringResourceManager.mapperLocations.addAll(Arrays.asList(mapperLocations));
    }

    /**
     * 获取 url 的真实地址，因为可能在 watchResources 和 extraClasspath 中
     */
    public static String getRelativePath(URL changedUrl) {
        PluginConfiguration pluginConfiguration = PluginManager.getInstance().getPluginConfiguration(MyBatisSpringResourceManager.class.getClassLoader());
        String changePath = changedUrl.getPath();
        // PluginManager 未初始化（如单元测试环境）时直接返回完整路径，调用方只用于生成 loadedResource key，
        // 不影响功能：XMLMapperBuilder.parse() 用该 key 做 isResourceLoaded 判断，不匹配则重新解析（正是重载语义）
        if (pluginConfiguration == null) {
            return changePath;
        }
        URL[] watchResources = pluginConfiguration.getWatchResources();
        if (watchResources != null) {
            for (URL watchResource : watchResources) {
                if (changePath.contains(watchResource.getPath())) {
                    return changePath.replace(watchResource.getPath(), "");
                }
            }
        }

        URL[] extraClasspath = pluginConfiguration.getExtraClasspath();
        if (extraClasspath != null) {
            for (URL extraUrl : extraClasspath) {
                if (changePath.contains(extraUrl.getPath())) {
                    return changePath.replace(extraUrl.getPath(), "");
                }
            }
        }

        return changePath;
    }

    /**
     * 获取 Mapper.xml 文件扫描路径（由 {@link MyBatisSpringPatcher#patchMapperLocations} 注入）
     */
    public static Set<String> getMapperLocations() {
        return mapperLocations;
    }

    public static ClassPathMapperScanner getMapperScanner() {
        return mapperScanner;
    }

    public static Set<Configuration> getConfigurationList() {
        return configurationList;
    }

    public static boolean isInMapperLocations(ClassLoader appClassLoader, String absolutePath) {
        if (mapperLocations.isEmpty()) {
            logger.debug("mapperLocations未配置，所有mapper xml文件都会加载");
            return true;
        }

        String hotDeployWatchResourcesPath = null;
        PluginConfiguration pluginConfiguration = PluginManager.getInstance().getPluginConfiguration(appClassLoader);
        if (pluginConfiguration != null) {
            URL[] resourcesPath = pluginConfiguration.getWatchResources();
            if (ArrayUtil.isNotEmpty(resourcesPath)) {
                String watchResourcesPath = resourcesPath[0].getPath();
                if (!watchResourcesPath.endsWith(File.separator)) {
                    watchResourcesPath += File.separator;
                }
                if (absolutePath.startsWith(watchResourcesPath)) {
                    hotDeployWatchResourcesPath = watchResourcesPath;
                }
            }
        }
        for (String mapperLocation : mapperLocations) {
            try {
                Resource[] resources = getPathMatchingResourcePatternResolver(appClassLoader, mapperLocation);
                for (Resource resource : resources) {
                    if (StrUtil.isNotBlank(hotDeployWatchResourcesPath)) {
                        if (resource instanceof ClassPathResource) {
                            if (((ClassPathResource) resource).getPath().endsWith(StrUtil.removePrefix(absolutePath, hotDeployWatchResourcesPath))) {
                                return true;
                            }
                        } else if (resource.getFile().getAbsolutePath().endsWith(StrUtil.removePrefix(absolutePath, hotDeployWatchResourcesPath))) {
                            return true;
                        }
                    } else {
                        // 使用File进行比较，避免windows上absolutePath路径格式不一致导致判断错误
                        if (resource.getFile().equals(new File(absolutePath))) {
                            return true;
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("获取mapperLocations失败", e);
            }
        }
        return false;
    }

    /**
     * 用于解析Mapper.xml文件的位置
     */
    public static Resource[] getPathMatchingResourcePatternResolver(ClassLoader classLoader, String mapperLocation) throws Exception {
        Class<?> resolver = classLoader.loadClass("org.springframework.core.io.support.PathMatchingResourcePatternResolver");
        Object resolverObj = resolver.getDeclaredConstructor().newInstance();
        return ReflectUtil.invoke(resolverObj, "getResources", mapperLocation);
    }

    /**
     * 从 loadedResources 的 key 中提取 XML 路径并规范化；非 XML 资源（namespace:/interface 标记）返回 null。
     * <p>
     * MyBatis/Spring 在不同加载途径下会写入不同形态的 key，必须全部识别：
     * <ul>
     *   <li>{@code file [D:\a\b\X.xml]} —— mybatis-spring 启动时 ClassPathResource/FileSystemResource.toString()</li>
     *   <li>{@code file [/D:/a/b/X.xml]} —— {@code URL.getFile()} 形态（带前导斜杠、正斜杠），
     *       与上一条指向同一文件但字符串不相等，不规范化就会误判为不同资源</li>
     *   <li>{@code class path resource [mapper/X.xml]} —— ClassPathResource.toString()</li>
     *   <li>{@code URL [file:/a/b]} / {@code jar:file:/x.jar!/mapper/X.xml} / 裸相对路径</li>
     * </ul>
     */
    public static String extractXmlPath(String loadedResource) {
        if (loadedResource == null || loadedResource.isEmpty()
                || loadedResource.startsWith("namespace:")
                || loadedResource.startsWith("interface ")) {
            return null;
        }
        int start = loadedResource.indexOf('[');
        String inner = start >= 0 && loadedResource.endsWith("]")
                ? loadedResource.substring(start + 1, loadedResource.length() - 1)
                : loadedResource;
        inner = inner.trim();
        return inner.isEmpty() ? null : normalizeXmlPath(inner);
    }

    /**
     * 统一路径形态：分隔符转正斜杠，并去掉 Windows file URL 产生的前导斜杠（/D:/x → D:/x）。
     */
    public static String normalizeXmlPath(String path) {
        String p = path.replace('\\', '/');
        // /D:/xxx 这种带前导斜杠的盘符路径（java.net.URL#getFile 对 file:/D:/x 的返回值）
        if (p.length() > 3 && p.charAt(0) == '/' && p.charAt(2) == ':' && isDriveLetter(p.charAt(1))) {
            p = p.substring(1);
        }
        return p;
    }

    private static boolean isDriveLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * 解析为规范绝对路径，用于判断两个 key 是否指向同一个物理文件。
     * <p>
     * 仅比较字符串会漏判：{@code file [D:\a\X.xml]} 与 {@code file [/D:/a/X.xml]} 字符串不同但是同一文件；
     * {@code class path resource [mapper/X.xml]} 是相对路径，需经 classloader 解析成绝对路径才能与前者对齐。
     * 无法解析时返回 null（调用方按“不确定”处理，不做删除）。
     * </p>
     */
    public static String canonicalXmlPath(String loadedResource, ClassLoader classLoader) {
        String xmlPath = extractXmlPath(loadedResource);
        if (xmlPath == null) {
            return null;
        }
        try {
            if (xmlPath.startsWith("jar:")) {
                // jar 内资源无法用 File 表达，直接用原始 URL 字符串作为标识
                return xmlPath;
            }
            if (xmlPath.startsWith("file:")) {
                return new File(new URL(xmlPath).getFile()).getCanonicalPath();
            }
            File file = new File(xmlPath);
            if (file.isAbsolute()) {
                return file.getCanonicalPath();
            }
            URL url = classLoader == null ? null : classLoader.getResource(xmlPath);
            if (url != null && "file".equals(url.getProtocol())) {
                return new File(url.getFile()).getCanonicalPath();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 按 XML 路径打开流：URL 前缀（file:/jar:/http）直接 new URL；绝对路径（含 /D:/ 形式）走文件流；
     * 其余按 classpath 相对路径从应用类加载器加载。打不开返回 null。
     */
    public static java.io.InputStream openXmlStream(String xmlPath, ClassLoader classLoader) throws Exception {
        String path = normalizeXmlPath(xmlPath);
        if (path.startsWith("http:") || path.startsWith("https:")
                || path.startsWith("jar:") || path.startsWith("file:")) {
            return new URL(path).openStream();
        }
        if (path.length() > 2 && path.charAt(1) == ':') {
            File file = new File(path);
            if (file.isFile()) {
                return new java.io.FileInputStream(file);
            }
        }
        if (classLoader == null) {
            return null;
        }
        java.io.InputStream stream = classLoader.getResourceAsStream(path);
        if (stream != null) {
            return stream;
        }
        // 兼容 key 里残留的前导斜杠（如 "/mapper/xx.xml"）
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        return classLoader.getResourceAsStream(normalized);
    }

    /**
     * 清理某个 namespace 在注册表中的残留（resultMaps/parameterMaps/sqlFragments/keyGenerators）。
     * <p>
     * 重新解析 XML 前必须调用：MyBatis 的 StrictMap.put 遇到同名 key 直接抛异常，而 removeMapper
     * 只清 mappedStatements，不清这几张表，导致重解析必然失败（表现为重载后 XML 方法全部
     * "Invalid bound statement (not found)"）。
     * </p>
     * <p>
     * 注意 MP 的 StrictMap.put 对含 "." 的 key 会额外写入不带 namespace 的短 key（useGeneratedShortKey），
     * 因此除了按 key 前缀删除，还要按 value 的 id 前缀匹配把短 key 一并清掉。
     * </p>
     *
     * @param namespace mapper 接口全限定名（即 XML 的 namespace）
     */
    public static void clearNamespaceResidue(Configuration configuration, String namespace) {
        for (String fieldName : new String[]{"resultMaps", "parameterMaps", "sqlFragments", "keyGenerators"}) {
            clearMapResidue(configuration, namespace, fieldName);
        }
    }

    /**
     * 额外清理该 namespace 的 mappedStatements（含派生短 key）。
     * <p>
     * 只允许 mapper / 实体重载路径调用：那条路径紧接着 addMapper，会把 XML、注解、MP 注入的 CRUD
     * 全部重新注册。XML 重载不能调用——它只会重新解析 XML 里定义的那部分 statement，
     * 提前删掉的 MP CRUD（selectById/insert/updateById…）再也补不回来
     * （实测会让 mapper 从 45 条掉到 27 条）。
     * </p>
     */
    public static void clearNamespaceStatements(Configuration configuration, String namespace) {
        clearMapResidue(configuration, namespace, "mappedStatements");
    }

    /**
     * 清理指定注册表中属于该 namespace 的条目（全限定 key + 由它派生的短 key）。
     * <p>
     * removeMapper / 实体重载的 wipe 只按“全限定 key 前缀”删，但 StrictMap.put 会为每个条目
     * 额外登记一个不带 namespace 的短 key（selectAnn、selectById…）。短 key 残留时，
     * 重新解析的 put 会认为“已存在”而整条忽略，表现为“重载没报错、日志说成功，
     * 但 hasStatement 仍为 false → Invalid bound statement”，只能重启。
     * </p>
     */
    private static void clearMapResidue(Configuration configuration, String namespace, String fieldName) {
        String prefix = namespace + ".";
            try {
                Map<String, ?> map = (Map<String, ?>) ReflectionHelper.get(configuration, fieldName);
                if (map == null) {
                    return;
                }
                // 先快照全限定 key，再推导同名短 key：StrictMap.put 会为全限定 key 额外写入一个短 key，
                // 只按前缀删会留下短 key，重解析时报“already contains value”。
                // 不能用 map.get(短key) 判断归属：StrictMap 对歧义短 key 会抛
                // "contains multiple values"，异常会冒泡整个 removeIf 导致该 map 一条都删不掉
                // （sqlFragments 实测就是这个现象：清理完全无效，下一轮重载必挂）。
                Set<String> staleFullKeys = new java.util.HashSet<>();
                for (String key : new java.util.ArrayList<>(map.keySet())) {
                    if (key.startsWith(prefix)) {
                        staleFullKeys.add(key);
                    }
                }
                Set<String> staleKeys = new java.util.HashSet<>(staleFullKeys);
                for (String key : new java.util.ArrayList<>(map.keySet())) {
                    if (key.contains(".")) {
                        continue;
                    }
                    if (staleFullKeys.contains(prefix + key)) {
                        staleKeys.add(key);
                    } else if (safeMatchesShortKey(map, key, prefix)) {
                        staleKeys.add(key);
                    }
                }
                map.keySet().removeAll(staleKeys);
            } catch (Exception e) {
                // 字段不存在（不同 MyBatis/MP 版本）时忽略，不影响其他注册表的清理
            logger.debug("clear {} for {} error: {}", fieldName, namespace, e.getMessage());
        }
    }

    /**
     * 按 value 的 id 前缀判断短 key 是否属于该 namespace；单个 key 判断失败不能影响整体清理。
     */
    private static boolean safeMatchesShortKey(Map<String, ?> map, String key, String prefix) {
        try {
            return matchesShortKey(map, key, prefix);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 短 key 的 value 与全限定 key 指向同一对象，按 value 的 id 前缀判断是否属于该 namespace。
     */
    private static boolean matchesShortKey(Map<String, ?> map, String key, String prefix) {
        if (key.contains(".")) {
            return false;
        }
        Object value = map.get(key);
        if (value instanceof ResultMap) {
            return ((ResultMap) value).getId().startsWith(prefix);
        }
        if (value instanceof ParameterMap) {
            return ((ParameterMap) value).getId().startsWith(prefix);
        }
        if (value instanceof MappedStatement) {
            return ((MappedStatement) value).getId().startsWith(prefix);
        }
        return false;
    }
}
