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
package io.github.future0923.debug.tools.extension.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.support.GenericApplicationContext;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringEnvUtilTest {

    @Test
    void emptyOrInactiveSpringContextIsNotReady() {
        assertFalse(SpringEnvUtil.isSpringContextReadyToCache(Collections.emptyList()));

        GenericApplicationContext context = new GenericApplicationContext();

        assertFalse(SpringEnvUtil.isSpringContextReadyToCache(Collections.singletonList(context)));
    }

    @Test
    void refreshedSpringContextIsReadyForInvocation() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();

        try {
            assertTrue(SpringEnvUtil.isSpringContextReadyToCache(Collections.singletonList(context)));
        } finally {
            context.close();
        }
    }

    @Test
    void bootstrapSpringContextIsNotReadyForInvocation() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.setId("bootstrap");
        context.refresh();

        try {
            assertFalse(SpringEnvUtil.isSpringContextReadyToCache(Collections.singletonList(context)));
            assertFalse((Boolean) SpringEnvUtil.evaluateSpringReadyStatus(Collections.singletonList(context)).get("ready"));
        } finally {
            context.close();
        }
    }

    @Test
    void missingBootReadinessEventDoesNotBlockRefreshedSpringContext() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(ApplicationAvailability.class, () -> new StaticApplicationAvailability(null));
        context.refresh();

        try {
            assertTrue(SpringEnvUtil.isSpringContextReadyToCache(Collections.singletonList(context)));
            assertTrue((Boolean) SpringEnvUtil.evaluateSpringReadyStatus(Collections.singletonList(context)).get("ready"));
        } finally {
            context.close();
        }
    }

    @Test
    void refusingTrafficDoesNotBlockRefreshedSpringContext() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(ApplicationAvailability.class, () -> new StaticApplicationAvailability(ReadinessState.REFUSING_TRAFFIC));
        context.refresh();

        try {
            assertTrue(SpringEnvUtil.isSpringContextReadyToCache(Collections.singletonList(context)));
            assertTrue((Boolean) SpringEnvUtil.evaluateSpringReadyStatus(Collections.singletonList(context)).get("ready"));
        } finally {
            context.close();
        }
    }

    @Test
    void acceptingTrafficMakesSpringContextReady() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(ApplicationAvailability.class, () -> new StaticApplicationAvailability(ReadinessState.ACCEPTING_TRAFFIC));
        context.refresh();

        try {
            assertTrue(SpringEnvUtil.isSpringContextReadyToCache(Collections.singletonList(context)));
            assertTrue((Boolean) SpringEnvUtil.evaluateSpringReadyStatus(Collections.singletonList(context)).get("ready"));
        } finally {
            context.close();
        }
    }

    private static class StaticApplicationAvailability implements ApplicationAvailability {

        private final ReadinessState readinessState;

        private StaticApplicationAvailability(ReadinessState readinessState) {
            this.readinessState = readinessState;
        }

        @Override
        public ReadinessState getReadinessState() {
            return readinessState == null ? ReadinessState.REFUSING_TRAFFIC : readinessState;
        }

        @Override
        public Object getLastChangeEvent(Class<?> stateType) {
            if (readinessState == null) {
                return null;
            }
            return new StaticAvailabilityChangeEvent(readinessState);
        }
    }

    public static class StaticAvailabilityChangeEvent {

        private final ReadinessState readinessState;

        private StaticAvailabilityChangeEvent(ReadinessState readinessState) {
            this.readinessState = readinessState;
        }

        public ReadinessState getState() {
            return readinessState;
        }
    }
}
