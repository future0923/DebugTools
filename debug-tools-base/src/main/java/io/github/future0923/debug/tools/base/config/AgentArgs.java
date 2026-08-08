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
package io.github.future0923.debug.tools.base.config;

import io.github.future0923.debug.tools.base.enums.PrintSqlType;
import io.github.future0923.debug.tools.base.hutool.core.util.StrUtil;
import io.github.future0923.debug.tools.base.logging.Logger;
import io.github.future0923.debug.tools.base.utils.DebugToolsProperties;
import io.github.future0923.debug.tools.base.utils.DebugToolsStringUtils;
import lombok.Data;

import java.lang.reflect.Field;
import java.net.URL;
import java.util.Properties;

/**
 * Agent可传入的参数
 *
 * @author future0923
 */
@Data
public class AgentArgs {

    private static final Logger logger = Logger.getLogger(AgentArgs.class);
    /*
     * 热重载/热部署时禁用的插件名集合分割符号
     */
    public static final String DISABLED_PLUGINS_SPLIT_CHAR = "#";

    /**
     * 日志等级
     */
    private Logger.Level logLevel;

    /**
     * 是否启动Server. true | false
     */
    private String server;

    /**
     * 监听的TCP端口 (server=true时才生效)
     */
    private String tcpPort;

    /**
     * 监听的HTTP端口 (server=true时才生效)
     */
    private String httpPort;

    /**
     * 附着的应用名称
     */
    private String applicationName;

    /**
     * IDEA 项目名称，用于隔离本地 SQL 历史。
     */
    private String projectName;

    /**
     * IDEA 项目路径 hash，用于区分同名项目。
     */
    private String projectPathHash;

    /**
     * 是否打印执行的SQL语句 {@link PrintSqlType}
     */
    private String printSql;

    /**
     * 是否追踪SQL
     */
    private String traceSql;

    /**
     * 是否开启热重载/热部署. true | false
     */
    private String hotswap;

    /**
     * 热重载/热部署时禁用的插件名集合
     */
    private String disabledPlugins;

    /**
     * 外部配置文件路径
     */
    private String propertiesFilePath;

    /**
     * 本地是否开启自动附着. true | false
     */
    private String autoAttach;

    /**
     * 是否自动保存SQL日志 true|false
     */
    private String autoSaveSql;

    /**
     * 压缩打印 SQL 时，是否保留 块注释
     */
    private String compressSqlPreserveBlockComments;

    /**
     * SQL日志保留天数
     */
    private Integer sqlRetentionDays;

    /**
     * 热重载时忽略哪些静态配置路径
     */
    private String ignoreStaticFieldPath;

    /**
     * 忽略sql文件配置路径
     */
    private String ignoreSqlConfigPath;

    /**
     * 将agent上的string参数转为AgentArgs对象
     *
     * @param agentArgs key1=value1,key2=value2
     * @return AgentArgs
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static AgentArgs parse(String agentArgs) {
        AgentArgs config = new AgentArgs();
        if (DebugToolsStringUtils.isNotBlank(agentArgs)) {
            String[] argsArray = agentArgs.split(",");
            for (String arg : argsArray) {
                String[] keyValue = arg.split("=");
                if (keyValue.length == 2) {
                    try {
                        Field field = AgentArgs.class.getDeclaredField(keyValue[0]);
                        field.setAccessible(true);
                        if (field.getType() == Integer.class) {
                            field.set(config, Integer.valueOf(keyValue[1]));
                        } else if (field.getType().isEnum()) {
                            Class<? extends Enum> enumType = (Class<? extends Enum>) field.getType();
                            field.set(config, Enum.valueOf(enumType, keyValue[1]));
                        } else {
                            field.set(config, keyValue[1]);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        config.loadConfigurationFile();
        return config;
    }

    /**
     * 将AgentArgs对象转为agent参数字符串
     *
     * @param config AgentArgs对象
     * @return agent参数字符串 key1=value1,key2=value2
     */
    public static String format(AgentArgs config) {
        StringBuilder argsBuilder = new StringBuilder();
        Field[] fields = config.getClass().getDeclaredFields();
        for (Field field : fields) {
            if ("logger".equals(field.getName()) || "DISABLED_PLUGINS_SPLIT_CHAR".equals(field.getName())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(config);
                if (value != null) {
                    String valueStr = String.valueOf(value);
                    if (StrUtil.isBlank(valueStr)) {
                        continue;
                    }
                    if (argsBuilder.length() > 0) {
                        argsBuilder.append(",");
                    }
                    argsBuilder.append(field.getName()).append("=").append(valueStr);
                }
            } catch (Exception ignored) {
            }
        }
        return argsBuilder.toString();
    }

    /**
     * 将当前对象转为agent参数字符串
     *
     * @return agent参数字符串 key1=value1,key2=value2
     */
    public String format() {
        return format(this);
    }

    /**
     * 载入外部文件，相同时优先级低于agent参数
     */
    private void loadConfigurationFile() {
        try {
            String externalPropertiesFile = propertiesFilePath;
            if (DebugToolsStringUtils.isNotBlank(externalPropertiesFile)) {
                URL configurationURL = DebugToolsStringUtils.resourceNameToURL(externalPropertiesFile);
                Properties properties = new DebugToolsProperties();
                properties.load(configurationURL.openStream());
                if (DebugToolsStringUtils.isBlank(hotswap)) {
                    hotswap = properties.getProperty("hotswap", "true");
                }
                if (DebugToolsStringUtils.isBlank(server)) {
                    server = properties.getProperty("server", "true");
                }
                if (DebugToolsStringUtils.isBlank(printSql)) {
                    printSql = properties.getProperty("printSql", PrintSqlType.NO.getType());
                }
                if (DebugToolsStringUtils.isBlank(applicationName)) {
                    applicationName = properties.getProperty("applicationName");
                }
                if (DebugToolsStringUtils.isBlank(tcpPort)) {
                    tcpPort = properties.getProperty("tcpPort");
                }
                if (DebugToolsStringUtils.isBlank(httpPort)) {
                    httpPort = properties.getProperty("httpPort");
                }
            }
        } catch (Exception e) {
            logger.error("Error while loading external properties file " + propertiesFilePath, e);
        }
    }
}
