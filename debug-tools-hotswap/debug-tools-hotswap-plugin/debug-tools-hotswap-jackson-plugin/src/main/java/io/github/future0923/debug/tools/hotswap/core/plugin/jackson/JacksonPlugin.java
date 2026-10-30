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
package io.github.future0923.debug.tools.hotswap.core.plugin.jackson;

import io.github.future0923.debug.tools.base.logging.Logger;
import io.github.future0923.debug.tools.hotswap.core.annotation.Init;
import io.github.future0923.debug.tools.hotswap.core.annotation.LoadEvent;
import io.github.future0923.debug.tools.hotswap.core.annotation.OnClassLoadEvent;
import io.github.future0923.debug.tools.hotswap.core.annotation.Plugin;
import io.github.future0923.debug.tools.hotswap.core.command.Command;
import io.github.future0923.debug.tools.hotswap.core.command.Scheduler;
import io.github.future0923.debug.tools.hotswap.core.plugin.jackson.command.JacksonReloadCommand;
import io.github.future0923.debug.tools.hotswap.core.util.PluginManagerInvoker;
import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.CtNewMethod;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * @author future0923
 */
@Plugin(
        name = "Jackson",
        description = "Reload Jackson cache after class definition/change.",
        testedVersions = {"All between 2.13.4"}
)
public class JacksonPlugin {

    private static final Logger logger = Logger.getLogger(JacksonPlugin.class);

    /**
     * 生成清除的方法，热重载时候调用
     */
    public static final String CLEAR_CACHE_METHOD = "$$ha$clearCache";

    /**
     * jackson相关的对象
     */
    private final Set<Object> jacksonObj = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private final Command command = new JacksonReloadCommand(jacksonObj);

    @Init
    public Scheduler scheduler;

    @OnClassLoadEvent(classNameRegexp = "(com.fasterxml.jackson.databind.ObjectMapper)|(tools.jackson.databind.ObjectMapper)", events = LoadEvent.DEFINE, skipSynthetic = false)
    public static void patchObjectMapper(CtClass ctClass) throws CannotCompileException {
        instantiateJackJson(ctClass);
        CtMethod clearCacheMethod = CtNewMethod.make("public void " + CLEAR_CACHE_METHOD + "() {_rootDeserializers.clear();}", ctClass);
        ctClass.addMethod(clearCacheMethod);
        logger.info("patch jackson ObjectMapper success");
    }

    @OnClassLoadEvent(classNameRegexp = "(com.fasterxml.jackson.databind.ser.SerializerCache)|(tools.jackson.databind.ser.SerializerCache)", events = LoadEvent.DEFINE, skipSynthetic = false)
    public static void patchSerializerCache(CtClass ctClass) throws CannotCompileException {
        instantiateJackJson(ctClass);
        CtMethod clearCacheMethod = CtNewMethod.make("public void " + CLEAR_CACHE_METHOD + "() {flush();}", ctClass);
        ctClass.addMethod(clearCacheMethod);
        logger.info("patch jackson SerializerCache success");
    }

    @OnClassLoadEvent(classNameRegexp = "(com.fasterxml.jackson.databind.deser.DeserializerCache)|(tools.jackson.databind.deser.DeserializerCache)", events = LoadEvent.DEFINE, skipSynthetic = false)
    public static void patchDeserializerCache(CtClass ctClass) throws CannotCompileException {
        instantiateJackJson(ctClass);
        CtMethod clearCacheMethod = CtNewMethod.make("public void " + CLEAR_CACHE_METHOD + "() {flushCachedDeserializers();}", ctClass);
        ctClass.addMethod(clearCacheMethod);
        logger.info("patch jackson DeserializerCache success");
    }

    @OnClassLoadEvent(classNameRegexp = "com.fasterxml.jackson.databind.ser.impl.ReadOnlyClassToSerializerMap", events = LoadEvent.DEFINE, skipSynthetic = false)
    public static void patchTowReadOnlyClassToSerializerMap(CtClass ctClass) throws CannotCompileException {
        instantiateJackJson(ctClass);
        CtMethod clearCacheMethod = CtNewMethod.make("public void " + CLEAR_CACHE_METHOD + "() {_buckets = new com.fasterxml.jackson.databind.ser.impl.ReadOnlyClassToSerializerMap.Bucket[_size];}", ctClass);
        ctClass.addMethod(clearCacheMethod);
        logger.info("patch jackson ReadOnlyClassToSerializerMap success");
    }

    @OnClassLoadEvent(classNameRegexp = "tools.jackson.databind.ser.impl.ReadOnlyClassToSerializerMap", events = LoadEvent.DEFINE, skipSynthetic = false)
    public static void patchThreePlusReadOnlyClassToSerializerMap(CtClass ctClass) throws CannotCompileException {
        instantiateJackJson(ctClass);
        CtMethod clearCacheMethod = CtNewMethod.make("public void " + CLEAR_CACHE_METHOD + "() {_buckets = new tools.jackson.databind.ser.impl.ReadOnlyClassToSerializerMap.Bucket[_size];}", ctClass);
        ctClass.addMethod(clearCacheMethod);
        logger.info("patch jackson ReadOnlyClassToSerializerMap success");
    }

    @OnClassLoadEvent(classNameRegexp = "(com.fasterxml.jackson.databind.type.TypeFactory)|(tools.jackson.databind.type.TypeFactory)", events = LoadEvent.DEFINE, skipSynthetic = false)
    public static void patchTypeFactory(CtClass ctClass) throws CannotCompileException {
        instantiateJackJson(ctClass);
        CtMethod clearCacheMethod = CtNewMethod.make("public void " + CLEAR_CACHE_METHOD + "() { _typeCache.clear();}", ctClass);
        ctClass.addMethod(clearCacheMethod);
        logger.info("patch jackson TypeFactory success");
    }

    @OnClassLoadEvent(classNameRegexp = "(com.fasterxml.jackson.databind.util.LRUMap)|(tools.jackson.databind.util.SimpleLookupCache)", events = LoadEvent.DEFINE, skipSynthetic = false)
    public static void patchLRUMap(CtClass ctClass) throws CannotCompileException {
        instantiateJackJson(ctClass);
        CtMethod clearCacheMethod = CtNewMethod.make("public void " + CLEAR_CACHE_METHOD + "() { clear();}", ctClass);
        ctClass.addMethod(clearCacheMethod);
        logger.info("patch jackson LRUMap success");
    }

    @OnClassLoadEvent(classNameRegexp = ".*", events = LoadEvent.REDEFINE)
    public void redefineClass() {
        scheduler.scheduleCommand(command, 500);
    }

    public void registerJacksonObj(Object objectMapper) {
        jacksonObj.add(objectMapper);
    }

    public static void instantiateJackJson(CtClass ctClass) throws CannotCompileException {
        StringBuilder src = new StringBuilder("{");
        src.append(PluginManagerInvoker.buildInitializePlugin(JacksonPlugin.class));
        src.append(PluginManagerInvoker.buildCallPluginMethod(JacksonPlugin.class, "registerJacksonObj", "this", "java.lang.Object"));
        src.append("}");
        for (CtConstructor constructor : ctClass.getDeclaredConstructors()) {
            constructor.insertAfter(src.toString());
        }
    }
}
