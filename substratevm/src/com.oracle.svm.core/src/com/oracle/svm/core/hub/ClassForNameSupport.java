/*
 * Copyright (c) 2017, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.hub;

import static com.oracle.svm.core.MissingRegistrationUtils.throwMissingRegistrationErrors;

import java.util.Set;

import org.graalvm.collections.EconomicMap;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.impl.ConfigurationCondition;

import com.oracle.svm.core.configure.ConditionalRuntimeValue;
import com.oracle.svm.core.feature.AutomaticallyRegisteredImageSingleton;
import com.oracle.svm.core.reflect.MissingReflectionRegistrationUtils;
import com.oracle.svm.core.util.ImageHeapMap;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.util.ReflectionUtil;

@AutomaticallyRegisteredImageSingleton
public final class ClassForNameSupport {

    public static ClassForNameSupport singleton() {
        return ImageSingletons.lookup(ClassForNameSupport.class);
    }

    /** The map used to collect registered classes. */
    private final EconomicMap<String, ConditionalRuntimeValue<Object>> knownClasses = ImageHeapMap.create();

    private static final Object NEGATIVE_QUERY = new Object();

    @Platforms(Platform.HOSTED_ONLY.class)
    public void registerClass(Class<?> clazz) {
        registerClass(ConfigurationCondition.alwaysTrue(), clazz);
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public void registerClass(ConfigurationCondition condition, Class<?> clazz) {
        assert !clazz.isPrimitive() : "primitive classes cannot be looked up by name";
        if (PredefinedClassesSupport.isPredefined(clazz)) {
            return; // must be defined at runtime before it can be looked up
        }
        String name = clazz.getName();
        ConditionalRuntimeValue<Object> conditionalRuntimeValue = knownClasses.get(name);
        Object currentValue = conditionalRuntimeValue == null ? null : conditionalRuntimeValue.getValueUnconditionally();

        /*
         * If the class is already registered as negative, it means that it exists but is not
         * accessible through the builder class loader, and it was already registered by name (as
         * negative query) before this point. In that case, we update the map to contain the actual
         * class.
         */
        VMError.guarantee(currentValue == null ||
                        currentValue == clazz ||
                        currentValue instanceof Throwable ||
                        (currentValue == NEGATIVE_QUERY && ReflectionUtil.lookupClass(true, name) == null),
                        "Invalid Class.forName value for %s: %s", name, currentValue);

        Class<?> conditionClass = condition.getType();
        Set<Class<?>> resConditions;
        if (conditionalRuntimeValue != null) {
            Set<Class<?>> conditions = conditionalRuntimeValue.getConditions();
            conditions.add(conditionClass);
            resConditions = conditions;
        } else {
            resConditions = Set.of(conditionClass);
        }

        if (currentValue == NEGATIVE_QUERY) {
            knownClasses.put(name, new ConditionalRuntimeValue<>(resConditions, clazz));
        } else if (currentValue == null) {
            knownClasses.put(name, new ConditionalRuntimeValue<>(resConditions, clazz));
        } else if (currentValue instanceof Class<?>) {
            knownClasses.put(name, new ConditionalRuntimeValue<>(resConditions, clazz));
        } else {
            throw VMError.shouldNotReachHere("Testing");
        }
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public void registerExceptionForClass(ConfigurationCondition cnd, String className, Throwable t) {
        knownClasses.put(className, new ConditionalRuntimeValue<>(Set.of(cnd.getType()), t));
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public void registerNegativeQuery(ConfigurationCondition cnd, String className) {
        /*
         * If the class is not accessible by the builder class loader, but was already registered
         * through registerClass(Class<?>), we don't overwrite the actual class or exception.
         */
        knownClasses.putIfAbsent(className, new ConditionalRuntimeValue<>(Set.of(cnd.getType()), NEGATIVE_QUERY));
    }

    public Class<?> forNameOrNull(String className, ClassLoader classLoader) {
        try {
            return forName(className, classLoader, true);
        } catch (ClassNotFoundException e) {
            throw VMError.shouldNotReachHere("ClassForNameSupport#forNameOrNull should not throw", e);
        }
    }

    public Class<?> forName(String className, ClassLoader classLoader) throws ClassNotFoundException {
        return forName(className, classLoader, false);
    }

    private Class<?> forName(String className, ClassLoader classLoader, boolean returnNullOnException) throws ClassNotFoundException {
        if (className == null) {
            return null;
        }
        var conditional = knownClasses.get(className);
        Object result = conditional == null ? null : conditional.getValue(cls -> DynamicHub.fromClass(cls).isReached());
        if (result == NEGATIVE_QUERY || className.endsWith("[]")) {
            /* Querying array classes with their "TypeName[]" name always throws */
            result = new ClassNotFoundException(className);
        }
        if (result == null) {
            result = PredefinedClassesSupport.getLoadedForNameOrNull(className, classLoader);
        }
        // Note: for non-predefined classes, we (currently) don't need to check the provided loader
        // TODO rewrite stack traces (GR-42813)
        if (result instanceof Class<?>) {
            return (Class<?>) result;
        } else if (result instanceof Throwable) {
            if (returnNullOnException) {
                return null;
            }

            if (result instanceof Error) {
                throw (Error) result;
            } else if (result instanceof ClassNotFoundException) {
                throw (ClassNotFoundException) result;
            }
        } else if (result == null) {
            if (throwMissingRegistrationErrors()) {
                MissingReflectionRegistrationUtils.forClass(className);
            }

            if (returnNullOnException) {
                return null;
            } else {
                throw new ClassNotFoundException(className);
            }
        }
        throw VMError.shouldNotReachHere("Class.forName result should be Class, ClassNotFoundException or Error: " + result);
    }

    public int count() {
        return knownClasses.size();
    }
}
