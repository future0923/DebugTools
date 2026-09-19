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

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClassLoaderResourceSyncUtilsTest {

    @Test
    void missingUrlsKeepsSourceOrderAndSkipsExistingTargetUrls() throws Exception {
        URL first = new URL("file:/target/classes/");
        URL second = new URL("file:/dependency.jar");
        URL existing = new URL("file:/idea_rt.jar");

        List<URL> missingUrls = ClassLoaderResourceSyncUtils.missingUrls(
                new URL[]{first, existing, second},
                new URL[]{existing}
        );

        assertEquals(first, missingUrls.get(0));
        assertEquals(second, missingUrls.get(1));
        assertEquals(2, missingUrls.size());
    }

    @Test
    void missingUrlsFiltersOutBootInfAndNestedJarUrls() throws Exception {
        URL regular = new URL("file:/target/classes/");
        URL bootInf = new URL("jar:file:/app.jar!/BOOT-INF/lib/demo.jar!/");
        URL nested = new URL("jar:file:/outer.jar!/inner.jar");

        List<URL> missingUrls = ClassLoaderResourceSyncUtils.missingUrls(
                new URL[]{regular, bootInf, nested},
                new URL[]{}
        );

        assertEquals(1, missingUrls.size());
        assertEquals(regular, missingUrls.get(0));
    }

    @Test
    void syncToSystemClassLoaderHandlesNullSafely() {
        // Should not throw NPE or mutate SystemClassLoader
        ClassLoaderResourceSyncUtils.syncToSystemClassLoader(null);
    }
}
