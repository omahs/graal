package com.oracle.truffle.dsl.processor.operations;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.oracle.truffle.dsl.processor.ProcessorContext;
import com.oracle.truffle.dsl.processor.java.ElementUtils;
import com.oracle.truffle.dsl.processor.java.compiler.CompilerFactory;
import com.oracle.truffle.dsl.processor.parser.AbstractParser;
import com.oracle.truffle.tools.utils.json.JSONArray;
import com.oracle.truffle.tools.utils.json.JSONObject;
import com.oracle.truffle.tools.utils.json.JSONTokener;

public class OperationsParser extends AbstractParser<OperationsData> {

    @Override
    protected OperationsData parse(Element element, List<AnnotationMirror> mirror) {

        TypeElement typeElement = (TypeElement) element;
        AnnotationMirror generateOperationsMirror = ElementUtils.findAnnotationMirror(mirror, types.GenerateOperations);

        OperationsData data = new OperationsData(ProcessorContext.getInstance(), typeElement, generateOperationsMirror);
        {
            ExecutableElement parseMethod = ElementUtils.findExecutableElement(typeElement, "parse");
            if (parseMethod == null) {
                data.addError(typeElement,
                                "Parse method not found. You must provide a method named 'parse' with following signature: void parse({Language}, {Context}, %sBuilder)",
                                typeElement.getSimpleName());
                return data;
            }

            if (parseMethod.getParameters().size() != 3) {
                data.addError(parseMethod, "Parse method must have exactly three arguments: the language, source and the builder");
                return data;
            }

            TypeMirror languageType = parseMethod.getParameters().get(0).asType();
            TypeMirror contextType = parseMethod.getParameters().get(1).asType();

            data.setParseContext(languageType, contextType, parseMethod);
        }

        List<TypeElement> operationTypes = new ArrayList<>(ElementFilter.typesIn(typeElement.getEnclosedElements()));
        List<AnnotationMirror> opProxies = ElementUtils.getRepeatedAnnotation(typeElement.getAnnotationMirrors(), types.OperationProxy);

        for (AnnotationMirror mir : opProxies) {
            DeclaredType tgtType = (DeclaredType) ElementUtils.getAnnotationValue(mir, "value").getValue();

            SingleOperationData opData = new SingleOperationParser(data, (TypeElement) tgtType.asElement()).parse(null, null);

            if (opData != null) {
                data.addOperationData(opData);
            } else {
                data.addError("Could not generate operation: " + tgtType.asElement().getSimpleName());
            }
        }

        boolean hasSome = false;
        for (TypeElement inner : operationTypes) {
            if (ElementUtils.findAnnotationMirror(inner, types.Operation) == null) {
                continue;
            }

            hasSome = true;

            SingleOperationData opData = new SingleOperationParser(data).parse(inner, false);

            if (opData != null) {
                data.addOperationData(opData);
            } else {
                data.addError("Could not generate operation: " + inner.getSimpleName());
            }

            opData.redirectMessagesOnGeneratedElements(data);
        }

        if (!hasSome) {
            data.addWarning("No operations found");
            return data;
        }

        data.setDecisionsFilePath(getMainDecisionsFilePath(typeElement, generateOperationsMirror));

        boolean isTracing = false;
        if (!isTracing) {
            OperationDecisions decisions = parseDecisions(typeElement, generateOperationsMirror, data);
            data.setDecisions(decisions);
        }

        data.setTracing(isTracing);

        if (data.hasErrors()) {
            return data;
        }

        data.initializeContext();

        return data;
    }

    private String getMainDecisionsFilePath(TypeElement element, AnnotationMirror generateOperationsMirror) {
        String file = (String) ElementUtils.getAnnotationValue(generateOperationsMirror, "decisionsFile").getValue();
        if (file == null || file.isEmpty()) {
            return null;
        }

        return getDecisionsFile(element, file).getAbsolutePath();
    }

    private File getDecisionsFile(TypeElement element, String path) {
        File file = CompilerFactory.getCompiler(element).getEnclosingFile(processingEnv, element);
        String parent = file.getParent();
        File target = new File(parent, path);

        return target;
    }

    @SuppressWarnings("unchecked")
    private OperationDecisions parseDecisions(TypeElement element, AnnotationMirror generateOperationsMirror, OperationsData data) {
        String file = (String) ElementUtils.getAnnotationValue(generateOperationsMirror, "decisionsFile").getValue();
        OperationDecisions mainDecisions = parseDecisions(element, file, data, true);
        if (mainDecisions == null) {
            return null;
        }

        List<String> overrideFiles = (List<String>) ElementUtils.getAnnotationValue(generateOperationsMirror, "decisionOverrideFiles").getValue();

        for (String overrideFile : overrideFiles) {
            OperationDecisions overrideDecision = parseDecisions(element, overrideFile, data, false);
            if (overrideDecision != null) {
                mainDecisions.merge(overrideDecision);
            }
        }

        return mainDecisions;
    }

    private OperationDecisions parseDecisions(TypeElement element, String path, OperationsData data, boolean isMain) {
        File target = getDecisionsFile(element, path);
        try {
            FileInputStream fi = new FileInputStream(target);
            JSONArray o = new JSONArray(new JSONTokener(fi));
            return OperationDecisions.deserialize(o);
        } catch (FileNotFoundException ex) {
            data.addError("Decisions file '%s' not found. Build & run with tracing to generate it.", target.toString());
        }
        return null;
    }

    @Override
    public DeclaredType getAnnotationType() {
        return types.GenerateOperations;
    }

}
