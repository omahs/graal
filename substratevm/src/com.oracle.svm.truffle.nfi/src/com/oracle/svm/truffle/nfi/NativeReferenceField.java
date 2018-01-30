/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package com.oracle.svm.truffle.nfi;

import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.RecomputeFieldValue.CustomFieldValueComputer;
import com.oracle.svm.core.util.VMError;
import jdk.vm.ci.meta.ResolvedJavaField;

/**
 * Fields that contain native pointers are annotated with {@link RecomputeFieldValue}, with this
 * class as {@link CustomFieldValueComputer}. Objects containing such fields can not be part of the
 * image heap, because the native pointers refer to allocations from the image builder, and are not
 * valid anymore at runtime.
 */
public final class NativeReferenceField implements CustomFieldValueComputer {

    @Override
    public Object compute(ResolvedJavaField original, ResolvedJavaField annotated, Object receiver) {
        throw VMError.unsupportedFeature(String.format("Native object (%s) stored in pre-initialized context.", original.getDeclaringClass().getName()));
    }
}
