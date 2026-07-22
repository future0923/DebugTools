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
package io.github.future0923.debug.tools.base.utils;

import io.github.future0923.debug.tools.base.constants.ProjectConstants;
import io.github.future0923.debug.tools.base.hutool.core.io.FileUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Enumeration;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class DebugToolsFileUtils {

    private static final String FOLDER_SEPARATOR = "/";

    private static final String WINDOWS_FOLDER_SEPARATOR = "\\";

    private static final String TOP_PATH = "..";

    private static final String CURRENT_PATH = ".";

    public static File getLibResourceJar(ClassLoader classLoader, String name) {
        String fullName = "lib/" + name + ".jar";
        File debugToolsCoreJarFile;
        try {
            URL coreJarUrl = classLoader.getResource(fullName);
            if (coreJarUrl == null) {
                throw new IllegalArgumentException("can not getResources " + fullName + " from classloader: "
                        + classLoader);
            }
            // 实际写入拼接版本号
            debugToolsCoreJarFile = DebugToolsFileUtils.getTmpLibFile(coreJarUrl.openStream(), name + "-" + ProjectConstants.VERSION, ".jar");
        } catch (Exception e) {
            throw new IllegalArgumentException("can not getResources " + fullName + " from classloader: "
                    + classLoader);
        }
        return debugToolsCoreJarFile;
    }

    /**
     * 判断文件是否存在，如果path为null，则返回false
     *
     * @param path 文件路径
     * @return 如果存在返回true
     */
    public static boolean exist(String path) {
        return (null != path) && exist(new File(path));
    }

    /**
     * 判断文件是否存在，如果file为null，则返回false
     *
     * @param file 文件
     * @return 如果存在返回true
     */
    public static boolean exist(File file) {
        return (null != file) && file.exists();
    }

    /**
     * @return tmp file absolute path
     */
    public static String copyChildFile(File file, String child) throws IOException {
        if (file.getPath().endsWith(".jar")) {
            try (JarFile jarFile = new JarFile(file)) {
                // 获取Jar包中所有的文件
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String entryName = entry.getName();

                    // 如果该文件是需要提取的资源文件
                    if (DebugToolsFileUtils.pathEquals(entryName, child)) {
                        return getTmpLibFile(jarFile.getInputStream(entry), extName(child, true)).getAbsolutePath();
                    }
                }
            }
            return "";
        }
        if (file.isDirectory()) {
            file = new File(file.getAbsolutePath(), child);
        }
        return getTmpLibFile(Files.newInputStream(file.toPath()), extName(child, true)).getAbsolutePath();
    }

    public static File getTmpLibFile(InputStream inputStream, String suffix) throws IOException {
        return getTmpLibFile(inputStream, "DebugToolsJniLibrary", suffix);
    }

    public static File getTmpLibFile(InputStream inputStream, String prefix, String suffix) throws IOException {
        File tempDir = createTempDir();

        // 1. 构造默认文件名
        String fileName = prefix + (suffix != null ? suffix : "");
        File tmpLibFile = new File(tempDir, fileName);

        // 不存在当前版本的依赖或mac更换了jdk版本,创建
        if ((!tmpLibFile.exists()) || (DebugToolsOSUtils.isMac() && DebugToolsJvmUtils.changeJdk(Boolean.TRUE))) {
            try (FileOutputStream tmpLibOutputStream = new FileOutputStream(tmpLibFile);
                 InputStream inputStreamNew = inputStream) {
                DebugToolsIOUtils.copy(inputStreamNew, tmpLibOutputStream);
            }
        }

        return tmpLibFile;
    }

    private static File createTempDir() {
        File tempDir = DebugToolsLibUtils.getDebugToolsLibDir();
        if (!tempDir.exists()) {
            if (!tempDir.mkdir()) {
                throw new IllegalStateException("Failed to create directory within " + tempDir);
            }
        }
        return tempDir;
    }

    public static boolean pathEquals(String path1, String path2) {
        return cleanPath(path1).equals(cleanPath(path2));
    }

    /**
     * 修正路径
     */
    public static String cleanPath(String path) {
        if (path.isEmpty()) {
            return path;
        }

        String normalizedPath = path.replace(WINDOWS_FOLDER_SEPARATOR, FOLDER_SEPARATOR);
        String pathToUse = normalizedPath;

        // Shortcut if there is no work to do
        if (pathToUse.indexOf('.') == -1) {
            return pathToUse;
        }

        // Strip prefix from path to analyze, to not treat it as part of the
        // first path element. This is necessary to correctly parse paths like
        // "file:core/../core/io/Resource.class", where the ".." should just
        // strip the first "core" directory while keeping the "file:" prefix.
        int prefixIndex = pathToUse.indexOf(':');
        String prefix = "";
        if (prefixIndex != -1) {
            prefix = pathToUse.substring(0, prefixIndex + 1);
            if (prefix.contains(FOLDER_SEPARATOR)) {
                prefix = "";
            } else {
                pathToUse = pathToUse.substring(prefixIndex + 1);
            }
        }
        if (pathToUse.startsWith(FOLDER_SEPARATOR)) {
            prefix = prefix + FOLDER_SEPARATOR;
            pathToUse = pathToUse.substring(1);
        }

        String[] pathArray = pathToUse.split(FOLDER_SEPARATOR);
        // we never require more elements than pathArray and in the common case the same number
        Deque<String> pathElements = new ArrayDeque<>(pathArray.length);
        int tops = 0;

        for (int i = pathArray.length - 1; i >= 0; i--) {
            String element = pathArray[i];
            if (CURRENT_PATH.equals(element)) {
                // Points to current directory - drop it.
            } else if (TOP_PATH.equals(element)) {
                // Registering top path found.
                tops++;
            } else {
                if (tops > 0) {
                    // Merging path element with element corresponding to top path.
                    tops--;
                } else {
                    // Normal path element found.
                    pathElements.addFirst(element);
                }
            }
        }

        // All path elements stayed the same - shortcut
        if (pathArray.length == pathElements.size()) {
            return normalizedPath;
        }
        // Remaining top paths need to be retained.
        for (int i = 0; i < tops; i++) {
            pathElements.addFirst(TOP_PATH);
        }
        // If nothing else left, at least explicitly point to current path.
        if (pathElements.size() == 1 && pathElements.getLast().isEmpty() && !prefix.endsWith(FOLDER_SEPARATOR)) {
            pathElements.addFirst(CURRENT_PATH);
        }

        final String joined = join(FOLDER_SEPARATOR, pathElements);
        // avoid string concatenation with empty prefix
        return prefix.isEmpty() ? joined : prefix + joined;
    }

    public static String getFileAsString(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String temp;
            while ((temp = br.readLine()) != null) {
                sb.append(temp);
            }
            return sb.toString();
        }
    }

    @SafeVarargs
    public static <T> String join(final T... elements) {
        return join(elements, null);
    }

    public static String join(final Object[] array, final String delimiter) {
        if (array == null) {
            return null;
        }
        return join(array, delimiter, 0, array.length);
    }

    public static String join(final Object[] array, final String delimiter, final int startIndex, final int endIndex) {
        if (array == null) {
            return null;
        }
        if (endIndex - startIndex <= 0) {
            return "";
        }
        final StringJoiner joiner = new StringJoiner(toStringOrEmpty(delimiter));
        for (int i = startIndex; i < endIndex; i++) {
            joiner.add(toStringOrEmpty(array[i]));
        }
        return joiner.toString();
    }

    private static String toStringOrEmpty(final Object obj) {
        return Objects.toString(obj, "");
    }

    /**
     * 创建文件及其父目录，如果这个文件存在，直接返回这个文件<br>
     * 此方法不对File对象类型做判断，如果File不存在，无法判断其类型
     *
     * @param path 相对ClassPath的目录或者绝对路径目录，使用POSIX风格
     * @return 文件，若路径为null，返回null
     */
    public static File touch(String path) {
        if (path == null) {
            return null;
        }
        return touch(new File(path));
    }

    /**
     * 获取标准的绝对路径
     *
     * @param file 文件
     * @return 绝对路径
     */
    public static String getAbsolutePath(File file) {
        if (file == null) {
            return null;
        }

        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    public static File touch(File file) {
        if (null == file) {
            return null;
        }
        if (!file.exists()) {
            mkParentDirs(file);
            try {
                //noinspection ResultOfMethodCallIgnored
                file.createNewFile();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return file;
    }

    public static File mkParentDirs(File file) {
        if (null == file) {
            return null;
        }
        return mkdir(getParent(file, 1));
    }

    /**
     * 创建文件夹，如果存在直接返回此文件夹<br>
     * 此方法不对File对象类型做判断，如果File不存在，无法判断其类型
     *
     * @param dirPath 文件夹路径，使用POSIX格式，无论哪个平台
     * @return 创建的目录
     */
    public static File mkdir(String dirPath) {
        if (dirPath == null) {
            return null;
        }
        final File dir = file(dirPath);
        return mkdir(dir);
    }

    /**
     * 创建文件夹，会递归自动创建其不存在的父文件夹，如果存在直接返回此文件夹<br>
     * 此方法不对File对象类型做判断，如果File不存在，无法判断其类型<br>
     *
     * @param dir 目录
     * @return 创建的目录
     */
    public static File mkdir(File dir) {
        if (dir == null) {
            return null;
        }
        if (!dir.exists()) {
            mkdirsSafely(dir, 5, 1);
        }
        return dir;
    }

    /**
     * 创建File对象
     *
     * @return File
     */
    public static File file(String path) {
        if (null == path) {
            return null;
        }
        return new File(path);
    }

    /**
     * 安全地级联创建目录 (确保并发环境下能创建成功)
     *
     * <pre>
     *     并发环境下，假设 test 目录不存在，如果线程A mkdirs "test/A" 目录，线程B mkdirs "test/B"目录，
     *     其中一个线程可能会失败，进而导致以下代码抛出 FileNotFoundException 异常
     *
     *     file.getParentFile().mkdirs(); // 父目录正在被另一个线程创建中，返回 false
     *     file.createNewFile(); // 抛出 IO 异常，因为该线程无法感知到父目录已被创建
     * </pre>
     *
     * @param dir         待创建的目录
     * @param tryCount    最大尝试次数
     * @param sleepMillis 线程等待的毫秒数
     * @return true表示创建成功，false表示创建失败
     * @author z8g
     * @since 5.7.21
     */
    public static boolean mkdirsSafely(File dir, int tryCount, long sleepMillis) {
        if (dir == null) {
            return false;
        }
        if (dir.isDirectory()) {
            return true;
        }
        for (int i = 1; i <= tryCount; i++) { // 高并发场景下，可以看到 i 处于 1 ~ 3 之间
            // 如果文件已存在，也会返回 false，所以该值不能作为是否能创建的依据，因此不对其进行处理
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            if (dir.exists()) {
                return true;
            }
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        return dir.exists();
    }


    /**
     * 获取指定层级的父路径
     *
     * <pre>
     * getParent(file("d:/aaa/bbb/cc/ddd", 0)) -》 "d:/aaa/bbb/cc/ddd"
     * getParent(file("d:/aaa/bbb/cc/ddd", 2)) -》 "d:/aaa/bbb"
     * getParent(file("d:/aaa/bbb/cc/ddd", 4)) -》 "d:/"
     * getParent(file("d:/aaa/bbb/cc/ddd", 5)) -》 null
     * </pre>
     *
     * @param file  目录或文件
     * @param level 层级
     * @return 路径File，如果不存在返回null
     * @since 4.1.2
     */
    public static File getParent(File file, int level) {
        if (level < 1 || null == file) {
            return file;
        }

        File parentFile;
        try {
            parentFile = file.getCanonicalFile().getParentFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (1 == level) {
            return parentFile;
        }
        return getParent(parentFile, level - 1);
    }

    private static final CharSequence[] SPECIAL_SUFFIX = {"tar.bz2", "tar.Z", "tar.gz", "tar.xz"};

    private static final char UNIX_SEPARATOR = '/';
    private static final char WINDOWS_SEPARATOR = '\\';

    /**
     * 获取文件扩展名
     *
     * @param fileName 文件路径名
     * @param hasDot   返回带不带点
     * @return 扩展名
     */
    public static String extName(String fileName, boolean hasDot) {
        if (fileName == null) {
            return null;
        }
        final int index = fileName.lastIndexOf(".");
        if (index == -1) {
            return "";
        } else {
            int dotOffset = hasDot ? 0 : 1;
            final int secondToLastIndex = fileName.substring(0, index).lastIndexOf(".");
            final String substr = fileName.substring(secondToLastIndex == -1 ? index : secondToLastIndex + dotOffset);
            if (DebugToolsStringUtils.containsAny(substr, SPECIAL_SUFFIX)) {
                return substr;
            }

            final String ext = fileName.substring(index + dotOffset);
            // 扩展名中不能包含路径相关的符号
            return DebugToolsStringUtils.containsAny(ext, UNIX_SEPARATOR, WINDOWS_SEPARATOR) ? "" : ext;
        }
    }

    /**
     * 写数据到文件中<br>
     * 文件路径如果是相对路径，则相对ClassPath
     *
     * @param data 数据
     * @param path 相对ClassPath的目录或者绝对路径目录
     * @return 目标文件
     */
    public static File writeBytes(byte[] data, String path) {
        return writeBytes(data, touch(path));
    }

    /**
     * 写数据到文件中
     *
     * @param dest 目标文件
     * @param data 数据
     * @return 目标文件
     */
    public static File writeBytes(byte[] data, File dest) {
        return writeBytes(data, dest, 0, data.length, false);
    }

    /**
     * 写入数据到文件
     *
     * @param data     数据
     * @param file     目标文件
     * @param off      数据开始位置
     * @param len      数据长度
     * @param isAppend 是否追加模式
     * @return 目标文件
     */
    public static File writeBytes(byte[] data, File file, int off, int len, boolean isAppend) {
        return write(file, data, off, len, isAppend);
    }

    /**
     * 写入数据到文件
     *
     * @param data     数据
     * @param off      数据开始位置
     * @param len      数据长度
     * @param isAppend 是否追加模式
     * @return 目标文件
     */
    public static File write(File file, byte[] data, int off, int len, boolean isAppend) {
        try (FileOutputStream out = new FileOutputStream(touch(file), isAppend)) {
            out.write(data, off, len);
            out.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return file;
    }

    /**
     * 获取自动附加文件
     */
    public static File getAutoAttachFile() {
        return FileUtil.touch(FileUtil.getUserHomePath() + "/" + ProjectConstants.AUTO_ATTACH_FLAG_FILE);
    }

    /**
     * 获取指定 JVM 的自动附加就绪文件，避免多个进程共用全局标记互相干扰。
     *
     * @param pid JVM 进程 ID
     * @return 自动附加就绪文件
     */
    public static File getAutoAttachFile(String pid) {
        return FileUtil.touch(
                FileUtil.getUserHomePath() + "/" + ProjectConstants.AUTO_ATTACH_PID_FLAG_FILE_PREFIX + pid + ".txt"
        );
    }

}
