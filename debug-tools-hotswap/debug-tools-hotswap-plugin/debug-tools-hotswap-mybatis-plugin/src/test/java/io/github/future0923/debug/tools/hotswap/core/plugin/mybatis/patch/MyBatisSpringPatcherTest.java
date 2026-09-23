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
package io.github.future0923.debug.tools.hotswap.core.plugin.mybatis.patch;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MyBatisSpringPatcherTest {

    private static final String SPRING_SCANNER_CLASS =
            "org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider";

    private static final String MYBATIS_SCANNER_CLASS =
            "org.mybatis.spring.mapper.ClassPathMapperScanner";

    @Test
    void doesNotPatchSpringScannerWhenMyBatisSpringIsAbsent() throws Exception {
        ClassPool classPool = createClassPool(false);
        CtClass scannerClass = loadSpringScanner(classPool);
        byte[] originalCode = findCandidateComponentsCode(scannerClass);

        MyBatisSpringPatcher.patchClassPathScanningCandidateComponentProvider(scannerClass, classPool);

        assertArrayEquals(originalCode, findCandidateComponentsCode(scannerClass));
    }

    @Test
    void patchesSpringScannerWhenMyBatisSpringIsPresent() throws Exception {
        ClassPool classPool = createClassPool(true);
        CtClass scannerClass = loadSpringScanner(classPool);
        byte[] originalCode = findCandidateComponentsCode(scannerClass);

        MyBatisSpringPatcher.patchClassPathScanningCandidateComponentProvider(scannerClass, classPool);

        assertFalse(Arrays.equals(originalCode, findCandidateComponentsCode(scannerClass)));
    }

    private static ClassPool createClassPool(boolean myBatisPresent) {
        ClassPool classPool = new ClassPool() {
            @Override
            public CtClass getOrNull(String className) {
                if (!myBatisPresent && MYBATIS_SCANNER_CLASS.equals(className)) {
                    return null;
                }
                return super.getOrNull(className);
            }
        };
        classPool.appendSystemPath();
        classPool.appendClassPath(new LoaderClassPath(MyBatisSpringPatcherTest.class.getClassLoader()));
        return classPool;
    }

    private static CtClass loadSpringScanner(ClassPool classPool) throws IOException {
        String resourceName = SPRING_SCANNER_CLASS.replace('.', '/') + ".class";
        InputStream inputStream = MyBatisSpringPatcherTest.class.getClassLoader().getResourceAsStream(resourceName);
        assertNotNull(inputStream);
        try (InputStream stream = inputStream) {
            return classPool.makeClass(stream);
        }
    }

    private static byte[] findCandidateComponentsCode(CtClass scannerClass) throws Exception {
        CtMethod method = scannerClass.getDeclaredMethod(
                "findCandidateComponents",
                new CtClass[]{scannerClass.getClassPool().get(String.class.getName())}
        );
        return method.getMethodInfo().getCodeAttribute().getCode().clone();
    }
}
