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

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * loadedResources key 的路径识别测试。
 * <p>
 * 这是热重载后 "Invalid bound statement (not found)" 的关键环节：MyBatis/Spring 在不同加载途径下
 * 会写入形态完全不同的 key（反斜杠绝对路径、URL.getFile() 带前导斜杠的正斜杠路径、classpath 相对路径），
 * 只要有一种识别不了，重载时就找不到 XML、无法恢复 statement；只要有一种删不掉，就会不断累积重复 key
 * 并污染后续所有 mapper 的重载。多模块工程实测出现过 369→491 条 key 的累积。
 * </p>
 *
 * @author future0923
 */
class MyBatisSpringResourceManagerPathTest {

    /**
     * 启动时 mybatis-spring 用 FileSystemResource.toString() 写入的形态（反斜杠）。
     */
    @Test
    void shouldExtractWindowsBackslashPath() {
        String key = "file [D:\\project\\java\\T6\\t6-common\\comm-lib\\target\\classes\\mapper2\\BaseDictUserMapper.xml]";
        assertEquals("D:/project/java/T6/t6-common/comm-lib/target/classes/mapper2/BaseDictUserMapper.xml",
                MyBatisSpringResourceManager.extractXmlPath(key));
    }

    /**
     * XML 重载用 URL.getFile() 拼出的形态（前导斜杠 + 正斜杠），必须与上一种归一化为同一个路径，
     * 否则会被当成两个不同资源：既删不掉旧 key，也打不开新 key。
     */
    @Test
    void shouldTreatLeadingSlashFormAsSamePath() {
        String startupKey = "file [D:\\project\\a\\X.xml]";
        String reloadKey = "file [/D:/project/a/X.xml]";
        assertEquals(MyBatisSpringResourceManager.extractXmlPath(startupKey),
                MyBatisSpringResourceManager.extractXmlPath(reloadKey));
    }

    @Test
    void shouldExtractClassPathResourceForm() {
        assertEquals("mapper2/BaseDictUserMapper.xml",
                MyBatisSpringResourceManager.extractXmlPath("class path resource [mapper2/BaseDictUserMapper.xml]"));
    }

    /**
     * extractXmlPath 只统一分隔符与前导斜杠；scheme（file:/jar:）的解析由 canonicalXmlPath/openXmlStream 负责，
     * 这里保留原样，避免把 jar: 内嵌路径拆坏。
     */
    @Test
    void shouldExtractUrlAndJarForms() {
        assertEquals("file:/D:/a/X.xml", MyBatisSpringResourceManager.extractXmlPath("URL [file:/D:/a/X.xml]"));
        assertEquals("jar:file:/D:/a.jar!/mapper/X.xml",
                MyBatisSpringResourceManager.extractXmlPath("jar:file:/D:/a.jar!/mapper/X.xml"));
    }

    /**
     * namespace:/interface 标记不是文件，必须返回 null，否则重载时会去打开它们并报错。
     */
    @Test
    void shouldIgnoreNonFileMarkers() {
        assertNull(MyBatisSpringResourceManager.extractXmlPath("namespace:com.cobazaar.mapper.BaseDictUserMapper"));
        assertNull(MyBatisSpringResourceManager.extractXmlPath("interface com.cobazaar.mapper.BaseDictUserMapper"));
        assertNull(MyBatisSpringResourceManager.extractXmlPath(null));
        assertNull(MyBatisSpringResourceManager.extractXmlPath(""));
    }

    /**
     * 同一物理文件的两种 key 形态必须解析出相同的规范路径，去重逻辑依赖这一点。
     */
    @Test
    void shouldCanonicalizeSameFileToSamePath() throws Exception {
        File tempFile = File.createTempFile("mapper", ".xml");
        tempFile.deleteOnExit();
        Files.write(tempFile.toPath(), "<mapper/>".getBytes(StandardCharsets.UTF_8));

        String backslashKey = "file [" + tempFile.getAbsolutePath() + "]";
        String forwardKey = "file [/" + tempFile.getAbsolutePath().replace('\\', '/') + "]";

        String canonical1 = MyBatisSpringResourceManager.canonicalXmlPath(backslashKey, null);
        String canonical2 = MyBatisSpringResourceManager.canonicalXmlPath(forwardKey, null);
        assertNotNull(canonical1);
        assertEquals(canonical1, canonical2);
    }

    /**
     * 前导斜杠形态必须能真正打开：实测运行时就是卡在这里，日志出现
     * "loadedResource key not openable: file [/D:/...]"，导致 XML statement 无法恢复。
     */
    @Test
    void shouldOpenLeadingSlashForm() throws Exception {
        File tempFile = File.createTempFile("mapper", ".xml");
        tempFile.deleteOnExit();
        Files.write(tempFile.toPath(), "<mapper/>".getBytes(StandardCharsets.UTF_8));

        String leadingSlashKey = "file [/" + tempFile.getAbsolutePath().replace('\\', '/') + "]";
        String xmlPath = MyBatisSpringResourceManager.extractXmlPath(leadingSlashKey);
        assertNotNull(xmlPath);
        try (InputStream inputStream = MyBatisSpringResourceManager.openXmlStream(xmlPath,
                getClass().getClassLoader())) {
            assertNotNull(inputStream, "带前导斜杠的 Windows 路径必须可以打开");
        }
    }

    @Test
    void shouldOpenPlainAbsoluteAndClasspathForms() throws Exception {
        File tempFile = File.createTempFile("mapper", ".xml");
        tempFile.deleteOnExit();
        Files.write(tempFile.toPath(), "<mapper/>".getBytes(StandardCharsets.UTF_8));

        assertNotNull(MyBatisSpringResourceManager.openXmlStream(tempFile.getAbsolutePath(), null));
        // classpath 相对路径
        try (InputStream inputStream = MyBatisSpringResourceManager.openXmlStream(
                "mapper/ReloadPlusTestMapper.xml", getClass().getClassLoader())) {
            assertNotNull(inputStream);
        }
        // 打不开时返回 null 而不是抛异常，调用方据此继续尝试下一个 key
        assertNull(MyBatisSpringResourceManager.openXmlStream("no/such/mapper.xml", getClass().getClassLoader()));
    }

    /**
     * 相对路径 key 经 classloader 解析后，应与同一文件的绝对路径 key 得到相同规范路径。
     */
    @Test
    void shouldCanonicalizeClasspathRelativeAgainstAbsolute() throws Exception {
        String canonical = MyBatisSpringResourceManager.canonicalXmlPath(
                "class path resource [mapper/ReloadPlusTestMapper.xml]", getClass().getClassLoader());
        assertNotNull(canonical);
        assertTrue(new File(canonical).exists(), "应解析到 target/test-classes 下的真实文件: " + canonical);
    }

    /**
     * 去重比较用的是规范路径，不是 extractXmlPath 结果：必须保证 file:/ 形态与反斜杠绝对路径
     * 指向同一文件时得到相同规范路径，否则 XML 重载会删不掉旧 key。
     */
    @Test
    void shouldCanonicalizeFileUrlAgainstBackslashPath() throws Exception {
        File tempFile = File.createTempFile("mapper", ".xml");
        tempFile.deleteOnExit();
        Files.write(tempFile.toPath(), "<mapper/>".getBytes(StandardCharsets.UTF_8));

        String backslashKey = "file [" + tempFile.getAbsolutePath() + "]";
        String fileUrlKey = "URL [" + tempFile.toURI().toURL() + "]";

        String canonical1 = MyBatisSpringResourceManager.canonicalXmlPath(backslashKey, null);
        String canonical2 = MyBatisSpringResourceManager.canonicalXmlPath(fileUrlKey, null);
        assertNotNull(canonical1);
        assertEquals(canonical1, canonical2);
    }

}
