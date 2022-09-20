/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.operation.test.bml;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.Registration;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.operation.GenerateOperations;
import com.oracle.truffle.api.operation.Operation;
import com.oracle.truffle.api.operation.OperationConfig;
import com.oracle.truffle.api.operation.OperationNodes;
import com.oracle.truffle.api.operation.OperationRootNode;

@Registration(id = "bm", name = "bm")
class BenchmarkLanguage extends TruffleLanguage<Object> {

    private static final Map<String, Function<BenchmarkLanguage, CallTarget>> NAMES = new HashMap<>();

    @Override
    protected Object createContext(Env env) {
        return new Object();
    }

    public static void registerName(String name, BiConsumer<BenchmarkLanguage, BMOperationRootNodeGen.Builder> parser) {
        registerName2(name, l -> {
            OperationNodes nodes = BMOperationRootNodeGen.create(OperationConfig.DEFAULT, b -> parser.accept(l, b));
            return nodes.getNodes().get(nodes.getNodes().size() - 1).getCallTarget();
        });
    }

    public static void registerName2(String name, Function<BenchmarkLanguage, CallTarget> parser) {
        NAMES.put(name, parser);
    }

    @Override
    protected CallTarget parse(ParsingRequest request) throws Exception {
        String name = request.getSource().getCharacters().toString();
        if (!NAMES.containsKey(name)) {
            throw new AssertionError("source not registered: " + name);
        }

        return NAMES.get(name).apply(this);
    }
}

@GenerateOperations(//
                languageClass = BenchmarkLanguage.class, //
                boxingEliminationTypes = {int.class}, //
                decisionsFile = "decisions.json")
abstract class BMOperationRootNode extends RootNode implements OperationRootNode {
    protected BMOperationRootNode(TruffleLanguage<?> language, FrameDescriptor frameDescriptor) {
        super(language, frameDescriptor);
    }

    protected BMOperationRootNode(TruffleLanguage<?> language, FrameDescriptor.Builder frameDescriptor) {
        super(language, frameDescriptor.build());
    }

    @Operation
    static final class Add {
        @Specialization
        static int doInts(int left, int right) {
            return left + right;
        }
    }

    @Operation
    static final class Mod {
        @Specialization
        static int doInts(int left, int right) {
            return left % right;
        }
    }

    @Operation
    static final class AddQuickened {
        @Specialization
        static int doInts(int left, int right) {
            return left + right;
        }
    }

    @Operation
    static final class ModQuickened {
        @Specialization
        static int doInts(int left, int right) {
            return left % right;
        }
    }

    @Operation(disableBoxingElimination = true)
    static final class AddBoxed {
        @Specialization
        static int doInts(int left, int right) {
            return left + right;
        }
    }

    @Operation(disableBoxingElimination = true)
    static final class ModBoxed {
        @Specialization
        static int doInts(int left, int right) {
            return left % right;
        }
    }

    @Operation
    static final class Less {
        @Specialization
        static boolean doInts(int left, int right) {
            return left < right;
        }
    }
}
