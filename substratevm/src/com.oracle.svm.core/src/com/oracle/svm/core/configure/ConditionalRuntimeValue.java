/*
 * Copyright (c) 2024, 2024, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.configure;

import java.util.Arrays;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

public final class ConditionalRuntimeValue<T> {
    /*
     * Intentionally an array to save space in the image heap.
     */
    private final Class<?>[] elements;
    private boolean satisfied;
    volatile T value;

    public ConditionalRuntimeValue(Set<Class<?>> conditions, T value) {
        elements = conditions.toArray(Class[]::new);
        this.value = value;
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public T getValueUnconditionally() {
        return value;
    }

    public Set<Class<?>> getConditions() {
        return Arrays.stream(elements).collect(Collectors.toSet());
    }

    public T getValue(Predicate<Class<?>> conditionSatisfied) {
        if (satisfied) {
            return value;
        } else {
            for (Class<?> element : elements) {
                if (conditionSatisfied.test(element)) {
                    satisfied = true;
                    break;
                }
            }
            if (satisfied) {
                return value;
            }
        }
        return null;
    }
}
