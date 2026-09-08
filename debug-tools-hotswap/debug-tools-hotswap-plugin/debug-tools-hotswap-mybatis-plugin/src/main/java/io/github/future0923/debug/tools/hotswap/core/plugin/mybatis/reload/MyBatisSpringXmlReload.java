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
import io.github.future0923.debug.tools.hotswap.core.plugin.mybatis.MyBatisPlugin;
import io.github.future0923.debug.tools.hotswap.core.util.ReflectionHelper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.builder.xml.XMLMapperEntityResolver;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.session.Configuration;

import java.net.URL;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 重新载入mybatis spring的xml资源
 *
 * @author future0923
 */
@SuppressWarnings("unchecked")
public class MyBatisSpringXmlReload extends AbstractMyBatisResourceReload<URL> {

    private static final Logger logger = Logger.getLogger(MyBatisSpringXmlReload.class);

    private static final Set<String> RELOADING_XML = ConcurrentHashMap.newKeySet();

    private MyBatisSpringXmlReload() {

    }

    @Override
    protected void doReload(URL url) throws Exception {
        String loadedResource = buildLoadedResource(url);
        String path = url.getPath();
        if (!RELOADING_XML.add(path)) {
            if (ProjectConstants.DEBUG) {
                logger.info("{} is currently processing reload task.", path);
            }
            return;
        }
        try {
            ClassLoader classLoader = MyBatisPlugin.getUserClassLoader();
            String canonicalTarget = MyBatisSpringResourceManager.canonicalXmlPath(loadedResource, classLoader);
            for (Configuration configuration : MyBatisSpringResourceManager.getConfigurationList()) {
                Set<String> loadedResources = (Set<String>) ReflectionHelper.get(configuration, LOADED_RESOURCES_FIELD);
                // 必须按“物理文件”而不是字符串删除 key：启动时写入的是 file [D:\a\X.xml]（反斜杠），
                // 而这里用 URL.getFile() 拼出的是 file [/D:/a/X.xml]，两者字符串不等但指向同一文件。
                // 只按字符串删会删不到，反而在 parse 成功后 add 一条重复 key，越积越多（实测 369→491），
                // 这些重复 key 后续会让 mapper 重载误判“XML 找不到”而无法恢复 statement。
                boolean removed = removeSameFileKeys(loadedResources, canonicalTarget, classLoader);
                // 重解析前必须清理该 namespace 的 resultMap/parameterMap/sqlFragment/selectKey 残留，
                // 否则 MyBatis 的 StrictMap.put 遇到同名 key 直接抛异常，导致含 <resultMap>/<sql> 的 XML
                // 永远热更不成功（表现为改了 XML 里的 SQL 但运行时不生效）。
                clearResidueForXml(url, configuration);
                XMLMapperBuilder xmlMapperBuilder = new XMLMapperBuilder(
                        url.openConnection().getInputStream(),
                        configuration,
                        loadedResource,
                        configuration.getSqlFragments()
                );
                try {
                    this.removeSelectKey(xmlMapperBuilder, configuration);
                } catch (Error error) {
                    logger.error("mybatis 重置selectKey失败，url：{}", url);
                }
                try {
                    xmlMapperBuilder.parse();
                    logger.reload("reload MyBatis xml file {}", path);
                } catch (Exception e) {
                    // 解析失败时把 key 放回：否则下次重载连“启动时加载过哪个 XML”的线索都没了
                    if (loadedResource != null && !removed) {
                        loadedResources.add(loadedResource);
                    }
                    throw e;
                }
            }
        } catch (Exception e) {
            logger.error("refresh mybatis xml error", e);
        } finally {
            RELOADING_XML.remove(path);
        }

    }

    /**
     * 删除 loadedResources 中所有指向同一物理文件的 key，返回是否删掉了至少一个。
     */
    private boolean removeSameFileKeys(Set<String> loadedResources, String canonicalTarget, ClassLoader classLoader) {
        if (loadedResources == null || loadedResources.isEmpty() || canonicalTarget == null) {
            return false;
        }
        boolean removed = false;
        Iterator<String> iterator = loadedResources.iterator();
        while (iterator.hasNext()) {
            String key = iterator.next();
            String canonical = MyBatisSpringResourceManager.canonicalXmlPath(key, classLoader);
            if (canonical != null && canonical.equals(canonicalTarget)) {
                iterator.remove();
                removed = true;
            }
        }
        return removed;
    }

    /**
     * 按 XML 的 namespace 清理注册表残留，使重解析可以覆盖旧定义。
     */
    private void clearResidueForXml(URL url, Configuration configuration) {
        try (java.io.InputStream inputStream = url.openConnection().getInputStream()) {
            XPathParser parser = new XPathParser(inputStream, true, null, new XMLMapperEntityResolver());
            XNode xNode = parser.evalNode("/mapper");
            String namespace = xNode == null ? null : xNode.getStringAttribute(NAMESPACE);
            if (namespace != null && !namespace.isEmpty()) {
                MyBatisSpringResourceManager.clearNamespaceResidue(configuration, namespace);
            }
        } catch (Exception e) {
            logger.debug("clear residue for xml {} error: {}", url, e.getMessage());
        }
    }

    private String buildLoadedResource(URL url) {
        return FILE + " [" + MyBatisSpringResourceManager.getRelativePath(url) + "]";
    }

    private void removeSelectKey(XMLMapperBuilder xmlMapperBuilder, Configuration configuration) {
        XPathParser parser = (XPathParser) ReflectionHelper.get(xmlMapperBuilder, "parser");
        XNode xNode = parser.evalNode("/mapper");
        String namespace = xNode.getStringAttribute(NAMESPACE);
        Map<String, KeyGenerator> keyGenerators = (Map<String, KeyGenerator>) ReflectionHelper.get(configuration, "keyGenerators");
        if (keyGenerators != null) {
            Iterator<Map.Entry<String, KeyGenerator>> iterator = keyGenerators.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, KeyGenerator> next = iterator.next();
                String key = next.getKey();
                if (key.startsWith(namespace) && key.endsWith("!selectKey")) {
                    iterator.remove();
                }
            }
        }
    }

}
