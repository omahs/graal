/*
 * Copyright (c) 2016, 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.graal.code;

import static com.oracle.svm.core.util.VMError.shouldNotReachHere;

import java.nio.ByteBuffer;

import jdk.graal.compiler.code.DataSection.Data;
import jdk.graal.compiler.code.DataSection.Patches;
import jdk.graal.compiler.core.common.type.CompressibleConstant;
import jdk.graal.compiler.core.common.type.TypedConstant;
import jdk.graal.compiler.lir.asm.DataBuilder;

import com.oracle.svm.core.FrameAccess;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.heap.ReferenceAccess;
import com.oracle.svm.core.meta.SubstrateObjectConstant;

import jdk.vm.ci.common.JVMCIError;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.SerializableConstant;
import jdk.vm.ci.meta.VMConstant;

public class SubstrateDataBuilder extends DataBuilder {
    @Override
    public Data createDataItem(Constant constant) {
        int size;
        if (constant instanceof VMConstant vmConstant) {
            assert constant instanceof JavaConstant && constant instanceof CompressibleConstant && constant instanceof TypedConstant : constant;
            return new ObjectData(vmConstant);
        } else if (JavaConstant.isNull(constant)) {
            if (SubstrateObjectConstant.isCompressed((JavaConstant) constant)) {
                size = ConfigurationValues.getObjectLayout().getReferenceSize();
            } else {
                size = FrameAccess.uncompressedReferenceSize();
            }
            return createZeroData(size, size);
        } else if (constant instanceof SerializableConstant) {
            SerializableConstant s = (SerializableConstant) constant;
            return createSerializableData(s);
        } else {
            throw new JVMCIError(String.valueOf(constant));
        }
    }

    public static class ObjectData extends Data {
        private final VMConstant constant;

        protected ObjectData(VMConstant constant) {
            super(ConfigurationValues.getObjectLayout().getReferenceSize(), ConfigurationValues.getObjectLayout().getReferenceSize());
            assert ((CompressibleConstant) constant).isCompressed() == ReferenceAccess.singleton()
                            .haveCompressedReferences() : "Constant object references in compiled code must be compressed (base-relative)";
            this.constant = constant;
        }

        public JavaConstant getConstant() {
            return (JavaConstant) constant;
        }

        @Override
        protected void emit(ByteBuffer buffer, Patches patches) {
            emit(buffer, patches, getSize(), constant);
        }

        public static void emit(ByteBuffer buffer, Patches patches, int size, VMConstant constant) {
            int position = buffer.position();
            if (size == Integer.BYTES) {
                buffer.putInt(0);
            } else if (size == Long.BYTES) {
                buffer.putLong(0L);
            } else {
                shouldNotReachHere("Unsupported object constant reference size: " + size);
            }
            patches.registerPatch(position, constant);
        }
    }

    @Override
    public int getMaxSupportedAlignment() {
        // See RuntimeCodeInstaller.prepareCodeMemory
        // Code and data are allocated in one go
        return SubstrateOptions.codeAlignment();
    }
}
