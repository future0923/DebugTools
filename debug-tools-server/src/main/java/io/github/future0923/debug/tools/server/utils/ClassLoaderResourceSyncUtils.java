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
package io.github.future0923.debug.tools.server.utils;

import io.github.future0923.debug.tools.base.logging.Logger;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Synchronizes child URLClassLoader entries to the system classloader on JDK 8.
 */
public class ClassLoaderResourceSyncUtils {

    private static final Logger logger = Logger.getLogger(ClassLoaderResourceSyncUtils.class);

    private ClassLoaderResourceSyncUtils() {
    }

    public static void syncToSystemClassLoader(ClassLoader sourceClassLoader) {
        if (sourceClassLoader == null) {
            return;
        }
        // Spring Boot 的 LaunchedURLClassLoader 等分层类加载器严禁注入 SystemClassLoader，
        // 否则将破坏类加载隔离，导致 Spring AOP / 动态代理类与已加载 Bean 产生 IncompatibleClassChangeError。
        if (isSpringBootClassLoader(sourceClassLoader)) {
            return;
        }
        ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
        if (!(sourceClassLoader instanceof URLClassLoader) || !(systemClassLoader instanceof URLClassLoader)) {
            return;
        }
        URLClassLoader source = (URLClassLoader) sourceClassLoader;
        URLClassLoader target = (URLClassLoader) systemClassLoader;
        List<URL> missingUrls = missingUrls(source.getURLs(), target.getURLs());
        if (missingUrls.isEmpty()) {
            return;
        }
        synchronized (target) {
            try {
                Method addUrl = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
                addUrl.setAccessible(true);
                for (URL url : missingUrls(target, source.getURLs())) {
                    addUrl.invoke(target, url);
                }
            } catch (Exception e) {
                logger.warning("sync classloader resources to system classloader failed", e);
            }
        }
    }

    private static boolean isSpringBootClassLoader(ClassLoader classLoader) {
        String name = classLoader.getClass().getName();
        return name.startsWith("org.springframework.boot.loader");
    }

    static List<URL> missingUrls(URL[] sourceUrls, URL[] targetUrls) {
        Set<URL> targetUrlSet = new HashSet<>(Arrays.asList(targetUrls));
        List<URL> missingUrls = new ArrayList<>();
        for (URL sourceUrl : sourceUrls) {
            if (isExcludedUrl(sourceUrl)) {
                continue;
            }
            if (!targetUrlSet.contains(sourceUrl)) {
                missingUrls.add(sourceUrl);
            }
        }
        return missingUrls;
    }

    private static boolean isExcludedUrl(URL url) {
        if (url == null) {
            return true;
        }
        String path = url.toString();
        return path.contains("BOOT-INF/") || path.contains("!/");
    }

    private static List<URL> missingUrls(URLClassLoader target, URL[] sourceUrls) {
        return missingUrls(sourceUrls, target.getURLs());
    }
}
