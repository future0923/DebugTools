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
package io.github.future0923.debug.tools.hotswap.core.plugin.freemarker.command;

import io.github.future0923.debug.tools.base.hutool.core.util.ReflectUtil;
import io.github.future0923.debug.tools.hotswap.core.command.MergeableCommand;

import java.lang.reflect.Method;

/**
 * @author future0923
 */
public class FreemarkerClearCacheCommand extends MergeableCommand {

    private final Object objectWrapper;

    public FreemarkerClearCacheCommand(Object objectWrapper) {
        this.objectWrapper = objectWrapper;
    }

    @Override
    public void executeCommand() {
        Method clearCacheMethod = null;
        try {
            clearCacheMethod = ReflectUtil.getMethodByName(objectWrapper.getClass(), "clearClassIntrospecitonCache");
        } catch (Exception ignored) {

        }
        if (clearCacheMethod == null) {
            clearCacheMethod = ReflectUtil.getMethodByName(objectWrapper.getClass(), "clearClassIntrospectionCache");
        }
        ReflectUtil.invoke(objectWrapper, clearCacheMethod);
    }
}
