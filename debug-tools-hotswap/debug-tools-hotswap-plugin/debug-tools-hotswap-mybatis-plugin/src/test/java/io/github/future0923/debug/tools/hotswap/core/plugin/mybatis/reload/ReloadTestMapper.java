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

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * MyBatisSpringMapperReloadTest 使用的测试 mapper：注解 + XML 混用，
 * 模拟真实项目中 mapper 同时有注解 statement 和 XML statement 的场景
 */
@Mapper
public interface ReloadTestMapper {

    /**
     * 由 mapper/ReloadTestMapper.xml 提供 statement
     */
    String selectXml();

    /**
     * 由 @Select 注解提供 statement
     */
    @Select("select 'ann'")
    String selectAnn();
}
