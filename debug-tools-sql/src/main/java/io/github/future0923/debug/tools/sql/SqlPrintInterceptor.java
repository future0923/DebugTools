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
package io.github.future0923.debug.tools.sql;

import io.github.future0923.debug.tools.base.context.RunMethodContext;
import io.github.future0923.debug.tools.base.enums.PrintSqlType;
import io.github.future0923.debug.tools.base.hutool.core.collection.CollUtil;
import io.github.future0923.debug.tools.base.hutool.core.util.ArrayUtil;
import io.github.future0923.debug.tools.base.hutool.core.util.BooleanUtil;
import io.github.future0923.debug.tools.base.hutool.core.util.ClassLoaderUtil;
import io.github.future0923.debug.tools.base.hutool.core.util.ClassUtil;
import io.github.future0923.debug.tools.base.hutool.core.util.ObjectUtil;
import io.github.future0923.debug.tools.base.hutool.core.util.ReflectUtil;
import io.github.future0923.debug.tools.base.hutool.core.util.StrUtil;
import io.github.future0923.debug.tools.base.hutool.sql.SqlCompressor;
import io.github.future0923.debug.tools.base.hutool.sql.SqlFormatter;
import io.github.future0923.debug.tools.base.logging.Logger;
import io.github.future0923.debug.tools.base.trace.MethodTrace;
import io.github.future0923.debug.tools.base.tuple.Tuple2;
import io.github.future0923.debug.tools.base.utils.DebugToolsIgnoreSqlUtils;
import io.github.future0923.debug.tools.base.utils.DebugToolsJvmUtils;
import io.github.future0923.debug.tools.utils.SqlFileWriter;
import io.github.future0923.debug.tools.vm.JvmToolsUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 打印SQL字节码拦截器
 *
 * @author future0923
 */
public class SqlPrintInterceptor {

    private static final Logger logger = Logger.getLogger(SqlPrintInterceptor.class);

    private static final List<String> CONNECTION_AGENT_METHODS = Arrays.asList("prepareStatement", "createStatement");

    private static final List<String> CONNECTION_AGENT_METHODS_PROCEDURE = Arrays.asList("prepareCall");

    private static final List<String> PREPARED_STATEMENT_METHODS = Arrays.asList("execute", "executeUpdate", "executeQuery", "addBatch");

    private static final Pattern PROXY_CLASS_PATTERN = Pattern.compile("(jdk.proxy\\d+.\\$Proxy.*)|(com.sun.proxy.\\$Proxy.*)");

    private static final String MYBATIS_PROXY_CLASS_NAME = "org.apache.ibatis.binding.MapperProxy";

    private static final String MYBATIS_PLUS_PROXY_CLASS_NAME = "com.baomidou.mybatisplus.core.override.MybatisMapperProxy";

    private static final String JPA_PROXY_CLASS_NAME = "org.springframework.aop.framework.JdkDynamicAopProxy";

    public static PrintSqlType printSqlType = PrintSqlType.NO;
    private static Boolean autoSaveSql = false;
    private static Boolean compressSqlPreserveBlockComments = true;
    private static String applicationName = DebugToolsJvmUtils.getApplicationName();
    private static String projectName;
    private static String projectPathHash;

    public static void setPrintSqlType(String printSqlType) {
        SqlPrintInterceptor.printSqlType = PrintSqlType.of(printSqlType);
    }

    public static void setAutoSaveSql(Boolean autoSave) {
        autoSaveSql = autoSave;
    }

    public static void setCompressSqlPreserveBlockComments(Boolean compressSqlPreserveBlockCommentsFromAgent) {
        compressSqlPreserveBlockComments = compressSqlPreserveBlockCommentsFromAgent;
    }

    public static void setApplicationName(String name) {
        if (StrUtil.isNotBlank(name)) {
            applicationName = name;
        }
    }

    public static void setProjectContext(String name, String pathHash) {
        projectName = name;
        projectPathHash = pathHash;
    }

    public static Connection proxyConnection(final Connection connection) {
        Object c = Proxy.newProxyInstance(
                SqlPrintByteCodeEnhance.class.getClassLoader(),
                new Class[]{Connection.class},
                new ConnectionHandler(connection)
        );
        return (Connection) c;
    }


    private static Statement proxyStatement(final Statement statement) {
        Object c = Proxy.newProxyInstance(
                SqlPrintByteCodeEnhance.class.getClassLoader(),
                new Class[]{PreparedStatement.class, Statement.class},
                new StatementHandler(statement)
        );
        return (Statement) c;
    }

    private static Statement proxyCallableStatement(final Statement statement) {
        Object c = Proxy.newProxyInstance(
                SqlPrintByteCodeEnhance.class.getClassLoader(),
                new Class[]{PreparedStatement.class, Statement.class, CallableStatement.class},
                new StatementHandler(statement)
        );
        return (Statement) c;
    }

    /**
     * connection 代理处理
     */
    private static class ConnectionHandler implements InvocationHandler {

        private final Connection connection;

        private ConnectionHandler(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = method.invoke(connection, args);
            if (CONNECTION_AGENT_METHODS.contains(method.getName())) {
                return proxyStatement((Statement) result);
            }
            if (CONNECTION_AGENT_METHODS_PROCEDURE.contains(method.getName())) {
                return proxyCallableStatement((Statement) result);
            }
            return result;
        }
    }

    /**
     * PreparedStatement 代理处理
     */
    private static class StatementHandler implements InvocationHandler {

        private final Statement statement;

        private final List<Object> parameters = new ArrayList<>();

        public StatementHandler(Statement statement) {
            this.statement = statement;

        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!PrintSqlType.isPrint(printSqlType.getType())) {
                return method.invoke(statement, args);
            }
            long startTime = System.currentTimeMillis();
            Object result = null;
            Exception exception = null;
            try {
                result = method.invoke(statement, args);
            } catch (Exception e) {
                logger.error("Failed to invoke statement method, method: " + method.getName());
                exception = e;
            }
            long endTime = System.currentTimeMillis();
            if (method.getName().startsWith("setNull")) {
                // 显式记录 NULL 值
                int index = (Integer) args[0];
                while (parameters.size() < index) parameters.add(null);
                parameters.set(index - 1, "NULL"); // 标记为 SQL NULL
            } else if (method.getName().startsWith("set") && args != null && args.length >= 2) {
                int index = (Integer) args[0];
                while (parameters.size() < index) parameters.add(null);
                parameters.set(index - 1, args[1]);
            }
            if (PREPARED_STATEMENT_METHODS.stream().anyMatch(s -> s.equals(method.getName()))) {
                Set<Pattern> sqlPrintPackages = DebugToolsIgnoreSqlUtils.getSqlPrintPackages();
                Set<Pattern> sqlPrintIgnorePackages = DebugToolsIgnoreSqlUtils.getSqlPrintIgnorePackages();
                if (CollUtil.isNotEmpty(sqlPrintPackages)) {
                    boolean isSqlPrint = false;
                    StackTraceElement[] stackTrace = getThreadStackTrace();
                    for (StackTraceElement stackTraceElement : stackTrace) {
                        if (isSqlPrint) {
                            break;
                        }
                        String packageName = getPackageNameAndProxy(stackTraceElement.getClassName());
                        if (StrUtil.isBlank(packageName)) {
                            continue;
                        }
                        for (Pattern pattern : sqlPrintPackages) {
                            if (pattern.matcher(packageName).find()) {
                                isSqlPrint = true;
                                break;
                            }
                        }
                    }
                    if (isSqlPrint) {
                        printSql(endTime - startTime, statement, parameters.toArray(new Object[0]), method, args);
                    }
                } else if (CollUtil.isNotEmpty(sqlPrintIgnorePackages)) {
                    boolean isSqlPrint = true;
                    StackTraceElement[] stackTrace = getThreadStackTrace();
                    for (StackTraceElement stackTraceElement : stackTrace) {
                        if (!isSqlPrint) {
                            break;
                        }
                        String packageName = getPackageNameAndProxy(stackTraceElement.getClassName());
                        for (Pattern pattern : sqlPrintIgnorePackages) {
                            if (pattern.matcher(packageName).find()) {
                                isSqlPrint = false;
                                break;
                            }
                        }
                    }
                    if (isSqlPrint) {
                        printSql(endTime - startTime, statement, parameters.toArray(new Object[0]), method, args);
                    }
                } else {
                    printSql(endTime - startTime, statement, parameters.toArray(new Object[0]), method, args);
                }
                parameters.clear();
            }
            if (exception != null) {
                throw exception;
            }
            return result;
        }
    }

    private static void printSql(long consume, Statement sta, Object[] parameters, Method method, Object[] args) {
        String className = sta.getClass().getName();
        DataSourceDriverClassEnum dbType = DataSourceDriverClassEnum.of(className);
        if (dbType == null) {
            logger.error("The current database driver is not yet supported. Driver class: {}", className);
            return;
        }
        try {
            String resultSql = ObjectUtil.isNull(args) ? dbType.getFormat().format(sta, parameters) : args[0].toString();
            resultSql = resultSql.endsWith(";") ? resultSql : resultSql + ";";

            if (BooleanUtil.isTrue(MethodTrace.getTraceSqlStatus())) {
                MethodTrace.enterSql(resultSql);
                MethodTrace.exit(consume);
            }

            if (StrUtil.isNotBlank(resultSql)) {
                Set<Pattern> sqlPrintStatement = DebugToolsIgnoreSqlUtils.getSqlPrintStatement();
                Set<Pattern> sqlPrintIgnoreStatement = DebugToolsIgnoreSqlUtils.getSqlPrintIgnoreStatement();
                if (CollUtil.isNotEmpty(sqlPrintStatement)) {
                    boolean isSqlPrint = false;
                    for (Pattern pattern : sqlPrintStatement) {
                        if (pattern.matcher(resultSql).find()) {
                            isSqlPrint = true;
                            break;
                        }
                    }
                    if (!isSqlPrint) {
                        return;
                    }
                } else if (CollUtil.isNotEmpty(sqlPrintIgnoreStatement)) {
                    boolean isSqlPrint = true;
                    for (Pattern pattern : sqlPrintIgnoreStatement) {
                        if (pattern.matcher(resultSql).find()) {
                            isSqlPrint = false;
                            break;
                        }
                    }
                    if (!isSqlPrint) {
                        return;
                    }
                }
            }

            if (PrintSqlType.PRETTY.equals(printSqlType) || PrintSqlType.YES.equals(printSqlType)) {
                resultSql = SqlFormatter.format(resultSql);
            }
            if (PrintSqlType.COMPRESS.equals(printSqlType)) {
                resultSql = SqlCompressor.compressSql(resultSql, compressSqlPreserveBlockComments);
            }
            logger.infoForce("Execute consume Time: {} ms; Execute SQL: \n\u001B[31m{}\u001B[0m", consume, resultSql);

            // 根据配置写入SQL记录到文件
            if (BooleanUtil.isTrue(autoSaveSql)) {
                try {
                    SqlFileWriter.writeSqlRecord(resultSql, consume, dbType.getType(), applicationName, projectName, projectPathHash);
                } catch (Exception e) {
                    logger.error("Failed to write SQL record to file", e);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to print SQL", e);
        }
    }

    private static String getPackageNameAndProxy(String className) {
        if (className.startsWith("io.github.future0923.debug.tools.sql.SqlPrintInterceptor")) {
            return StrUtil.EMPTY;
        }
        if (className.startsWith("io.github.future0923.debug.tools.server")) {
            return StrUtil.EMPTY;
        }
        if (className.startsWith("io.github.future0923.debug.tools.common")) {
            return StrUtil.EMPTY;
        }
        if (PROXY_CLASS_PATTERN.matcher(className).find()) {
            Object[] instances = JvmToolsUtils.getInstances(ClassLoaderUtil.loadClass(className));
            if (ArrayUtil.isEmpty(instances)) {
                return StrUtil.EMPTY;
            }
            InvocationHandler invocationHandler = Proxy.getInvocationHandler(instances[0]);
            if (MYBATIS_PROXY_CLASS_NAME.equals(invocationHandler.getClass().getName())
                    || MYBATIS_PLUS_PROXY_CLASS_NAME.equals(invocationHandler.getClass().getName())) {
                return ClassUtil.getPackageName(((Class<?>) ReflectUtil.getFieldValue(invocationHandler, "mapperInterface")).getName());
            } else if (JPA_PROXY_CLASS_NAME.equals(invocationHandler.getClass().getName())) {
                Class<?>[] proxiedInterfaces = (Class<?>[]) ReflectUtil.getFieldValue(invocationHandler, "proxiedInterfaces");
                if (ArrayUtil.isNotEmpty(proxiedInterfaces)) {
                    return ClassUtil.getPackageName((proxiedInterfaces[0].getName()));
                } else {
                    return StrUtil.EMPTY;
                }
            } else {
                return StrUtil.EMPTY;
            }
        } else {
            return ClassUtil.getPackageName(className);
        }
    }

    private static StackTraceElement[] getThreadStackTrace() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        Tuple2<String, String> runMethod = RunMethodContext.getRunMethod();
        if (runMethod != null) {
            return ArrayUtil.append(stackTrace, new StackTraceElement(runMethod.f0, runMethod.f1, "", -1));
        }
        return ArrayUtil.reverse(stackTrace);
    }
}
