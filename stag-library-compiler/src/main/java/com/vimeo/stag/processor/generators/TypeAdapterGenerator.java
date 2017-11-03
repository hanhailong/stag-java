/*
 * The MIT License (MIT)
 * <p/>
 * Copyright (c) 2016 Vimeo
 * <p/>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p/>
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * <p/>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.vimeo.stag.processor.generators;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.internal.bind.TreeTypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;
import com.squareup.javapoet.TypeVariableName;
import com.vimeo.stag.KnownTypeAdapters.ArrayTypeAdapter;
import com.vimeo.stag.processor.generators.model.AnnotatedClass;
import com.vimeo.stag.processor.generators.model.ClassInfo;
import com.vimeo.stag.processor.generators.model.SupportedTypesModel;
import com.vimeo.stag.processor.generators.model.accessor.FieldAccessor;
import com.vimeo.stag.processor.utils.ElementUtils;
import com.vimeo.stag.processor.utils.FileGenUtils;
import com.vimeo.stag.processor.utils.KnownTypeAdapterUtils;
import com.vimeo.stag.processor.utils.TypeUtils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;

public class TypeAdapterGenerator extends AdapterGenerator {

    private static final String TYPE_ADAPTER_FIELD_PREFIX = "mTypeAdapter";

    @NotNull private final ClassInfo mInfo;
    @NotNull private final SupportedTypesModel mSupportedTypesModel;

    public TypeAdapterGenerator(@NotNull SupportedTypesModel supportedTypesModel, @NotNull ClassInfo info) {
        mSupportedTypesModel = supportedTypesModel;
        mInfo = info;
    }

    /**
     * This is used to generate the type token code for the types that are unknown.
     */
    @Nullable
    private static String getTypeTokenCodeForGenericType(@NotNull TypeMirror fieldType,
                                                         @NotNull Map<TypeMirror, String> typeVarsMap) {

        String result;
        if (fieldType.getKind() == TypeKind.TYPEVAR) {
            result = "com.google.gson.reflect.TypeToken.get(" + typeVarsMap.get(fieldType) + ")";
        } else if (fieldType instanceof DeclaredType) {
                /*
                 * If it is of ParameterizedType, {@link com.vimeo.stag.utils.ParameterizedTypeUtil} is used to get the
                 * type token of the parameter type.
                 */
            DeclaredType declaredFieldType = (DeclaredType) fieldType;
            List<? extends TypeMirror> typeMirrors = ((DeclaredType) fieldType).getTypeArguments();
            result = "com.google.gson.reflect.TypeToken.getParameterized(" +
                     declaredFieldType.asElement().toString() + ".class";
                /*
                 * Iterate through all the types from the typeArguments and generate type token code accordingly
                 */
            for (TypeMirror parameterTypeMirror : typeMirrors) {
                if (parameterTypeMirror.getKind() == TypeKind.TYPEVAR) {
                    result += ", " + typeVarsMap.get(parameterTypeMirror);
                } else {
                    result += ",\n" + getTypeTokenCodeForGenericType(parameterTypeMirror, typeVarsMap) + ".getType()";
                }
            }
            result += ")";
        } else {
            result = "com.google.gson.reflect.TypeToken.get(" + fieldType.toString() + ")";
        }

        return result;
    }

    @NotNull
    private static TypeName getAdapterFieldTypeName(@NotNull TypeMirror type) {
        TypeName typeName = TypeVariableName.get(type);
        return ParameterizedTypeName.get(ClassName.get(TypeAdapter.class), typeName);
    }

    @NotNull
    private static MethodSpec getReadMethodSpec(@NotNull TypeName typeName,
                                                @NotNull Map<FieldAccessor, TypeMirror> elements,
                                                @NotNull AdapterFieldInfo adapterFieldInfo) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("read")
                .addParameter(JsonReader.class, "reader")
                .returns(typeName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addException(IOException.class);

        builder.addCode("\tcom.google.gson.stream.JsonToken peek = reader.peek();\n");
        builder.addCode("\tif (com.google.gson.stream.JsonToken.NULL == peek) {\n" +
                        "\t\treader.nextNull();\n" +
                        "\t\treturn null;\n" +
                        "\t}\n" +
                        "\tif (com.google.gson.stream.JsonToken.BEGIN_OBJECT != peek) {\n" +
                        "\t\treader.skipValue();\n" +
                        "\t\treturn null;\n" +
                        "\t}\n" +
                        "\treader.beginObject();\n" +
                        '\n' +
                        '\t' + typeName + " object = new " + typeName +
                        "();\n" +
                        "\twhile (reader.hasNext()) {\n" +
                        "\t\tString name = reader.nextName();\n" +
                        "\t\tswitch (name) {\n");

        final List<FieldAccessor> nonNullFields = new ArrayList<>();

        for (Map.Entry<FieldAccessor, TypeMirror> element : elements.entrySet()) {
            final FieldAccessor fieldAccessor = element.getKey();
            String name = fieldAccessor.getJsonName();

            final TypeMirror elementValue = element.getValue();

            builder.addCode("\t\t\tcase \"" + name + "\":\n");

            String[] alternateJsonNames = fieldAccessor.getAlternateJsonNames();
            if (alternateJsonNames != null && alternateJsonNames.length > 0) {
                for (String alternateJsonName : alternateJsonNames) {
                    builder.addCode("\t\t\tcase \"" + alternateJsonName + "\":\n");
                }
            }

            String variableType = element.getValue().toString();
            boolean isPrimitive = TypeUtils.isSupportedPrimitive(variableType);

            if (isPrimitive) {
                builder.addCode("\t\t\t\tobject." +
                        fieldAccessor.createSetterCode(adapterFieldInfo.getAdapterAccessor(elementValue, name) +
                                ".read(reader, object." + fieldAccessor.createGetterCode() + ")") + ";");

            } else {
                builder.addCode("\t\t\t\tobject." + fieldAccessor.createSetterCode(adapterFieldInfo.getAdapterAccessor(elementValue, name) +
                        ".read(reader)") + ";");
            }


            builder.addCode("\n\t\t\t\tbreak;\n");
            if (fieldAccessor.doesRequireNotNull()) {
                if (!TypeUtils.isSupportedPrimitive(elementValue.toString())) {
                    nonNullFields.add(fieldAccessor);
                }
            }
        }

        builder.addCode("\t\t\tdefault:\n" +
                        "\t\t\t\treader.skipValue();\n" +
                        "\t\t\t\tbreak;\n" +
                        "\t\t}\n" +
                        "\t}\n" +
                        '\n' +
                        "\treader.endObject();\n");

        for (FieldAccessor nonNullField : nonNullFields) {
            builder.addCode("\n\tif (object." + nonNullField.createGetterCode() + " == null) {");
            builder.addCode("\n\t\tthrow new java.io.IOException(\"" + nonNullField.createGetterCode() + " cannot be null\");");
            builder.addCode("\n\t}\n\n");
        }

        builder.addCode("\treturn object;\n");

        return builder.build();
    }

    @NotNull
    private static String getFieldAccessorForKnownJsonAdapterType(@NotNull ExecutableElement adapterType,
                                                                  @NotNull TypeSpec.Builder adapterBuilder,
                                                                  @NotNull MethodSpec.Builder constructorBuilder,
                                                                  @NotNull TypeMirror fieldType,
                                                                  @NotNull TypeUtils.JsonAdapterType jsonAdapterType,
                                                                  @NotNull AdapterFieldInfo adapterFieldInfo,
                                                                  boolean isNullSafe,
                                                                  @NotNull String keyFieldName) {
        String fieldAdapterAccessor = "new " + FileGenUtils.escapeStringForCodeBlock(adapterType.getEnclosingElement().toString());
        if (jsonAdapterType == TypeUtils.JsonAdapterType.TYPE_ADAPTER) {
            ArrayList<String> constructorParameters = new ArrayList<>();
            if (!adapterType.getParameters().isEmpty()) {
                for (VariableElement parameter : adapterType.getParameters()) {
                    if (parameter.asType().toString().equals(TypeUtils.className(Gson.class))) {
                        constructorParameters.add("gson");
                    } else if (TypeUtils.isAssignable(parameter.asType(), ElementUtils.getTypeFromQualifiedName(TypeAdapterFactory.class.getName()))) {
                        constructorParameters.add("new " + parameter.asType() + "()");
                    } else {
                        throw new IllegalStateException("Not supported " + parameter.asType() + "parameter for @JsonAdapter value");
                    }
                }
            }


            String constructorParameterStr = "(";
            for (int i = 0; i < constructorParameters.size(); i++) {
                constructorParameterStr += constructorParameters.get(i);
                if (i != constructorParameters.size() - 1) {
                    constructorParameterStr += ",";
                }
            }
            constructorParameterStr += ")";
            fieldAdapterAccessor += constructorParameterStr;
        } else if (jsonAdapterType == TypeUtils.JsonAdapterType.TYPE_ADAPTER_FACTORY) {
            TypeName typeTokenField = ParameterizedTypeName.get(ClassName.get(TypeToken.class), TypeVariableName.get(fieldType));
            fieldAdapterAccessor += "().create(gson, new " + typeTokenField + "(){})";
        } else if (jsonAdapterType == TypeUtils.JsonAdapterType.JSON_SERIALIZER
                   || jsonAdapterType == TypeUtils.JsonAdapterType.JSON_DESERIALIZER
                   || jsonAdapterType == TypeUtils.JsonAdapterType.JSON_SERIALIZER_DESERIALIZER) {
            String serializer = null, deserializer = null;

            if (jsonAdapterType == TypeUtils.JsonAdapterType.JSON_SERIALIZER_DESERIALIZER) {
                String varName = keyFieldName + "SerializerDeserializer";
                String initializer = adapterType.getEnclosingElement().toString() + " " + varName + " = " +
                                     "new " + adapterType;
                constructorBuilder.addStatement(initializer);
                serializer = deserializer = varName;
            } else if (jsonAdapterType == TypeUtils.JsonAdapterType.JSON_SERIALIZER) {
                serializer = "new " + adapterType;
            } else if (jsonAdapterType == TypeUtils.JsonAdapterType.JSON_DESERIALIZER) {
                deserializer = "new " + adapterType;
            }
            TypeName typeTokenField = ParameterizedTypeName.get(ClassName.get(TypeToken.class), TypeVariableName.get(fieldType));
            fieldAdapterAccessor = "new " + TypeVariableName.get(TreeTypeAdapter.class) + "(" + serializer + ", " + deserializer + ", gson, new " + typeTokenField + "(){}, null)";
        } else {
            throw new IllegalArgumentException(
                    "@JsonAdapter value must be TypeAdapter, TypeAdapterFactory, "
                    + "JsonSerializer or JsonDeserializer reference.");
        }
        //Add this to a member variable
        String fieldName = TYPE_ADAPTER_FIELD_PREFIX + adapterFieldInfo.size();
        String originalFieldName = FileGenUtils.unescapeEscapedString(fieldName);
        TypeName typeName = getAdapterFieldTypeName(fieldType);
        adapterBuilder.addField(typeName, originalFieldName, Modifier.PRIVATE, Modifier.FINAL);
        String statement = fieldName + " = " + getCleanedFieldInitializer(fieldAdapterAccessor);
        if (isNullSafe) {
            statement += ".nullSafe()";
        }
        constructorBuilder.addStatement(statement);

        return fieldName;
    }

    /**
     * Returns the adapter code for the known types.
     */
    private String getAdapterAccessor(@NotNull TypeMirror fieldType, @NotNull Builder adapterBuilder,
                                      @NotNull MethodSpec.Builder constructorBuilder,
                                      @NotNull Map<TypeMirror, String> typeVarsMap,
                                      @NotNull AdapterFieldInfo adapterFieldInfo) {

        String knownTypeAdapter = KnownTypeAdapterUtils.getKnownTypeAdapterForType(fieldType);

        if (null != knownTypeAdapter) {
            return knownTypeAdapter;
        } else if (TypeUtils.isNativeArray(fieldType)) {
                /*
                 * If the fieldType is of type native arrays such as String[] or int[]
                 */
            TypeMirror arrayInnerType = TypeUtils.getArrayInnerType(fieldType);
            if (TypeUtils.isSupportedPrimitive(arrayInnerType.toString())) {
                return KnownTypeAdapterUtils.getNativePrimitiveArrayTypeAdapter(fieldType);
            } else {
                String adapterAccessor = getAdapterAccessor(arrayInnerType, adapterBuilder,
                        constructorBuilder, typeVarsMap,
                        adapterFieldInfo);
                String nativeArrayInstantiator =
                        KnownTypeAdapterUtils.getNativeArrayInstantiator(arrayInnerType);
                String adapterCode = "new " + TypeUtils.className(ArrayTypeAdapter.class) + "<" +
                        arrayInnerType.toString() + ">" +
                        "(" + adapterAccessor + ", " + nativeArrayInstantiator + ")";
                return adapterCode;
            }
        } else {
            return getAdapterForUnknownGenericType(fieldType, adapterBuilder, constructorBuilder,
                    typeVarsMap, adapterFieldInfo);
        }
    }

    private static String getCleanedFieldInitializer(String code) {
        return code.replace("mStagFactory", "stagFactory").replace("mGson", "gson");
    }

    /**
     * Returns the adapter code for the unknown types.
     */
    private static String getAdapterForUnknownGenericType(@NotNull TypeMirror fieldType,
                                                          @NotNull Builder adapterBuilder,
                                                          @NotNull MethodSpec.Builder constructorBuilder,
                                                          @NotNull Map<TypeMirror, String> typeVarsMap,
                                                          @NotNull AdapterFieldInfo adapterFieldInfo) {

        String fieldName = adapterFieldInfo.getFieldName(fieldType);
        if (null == fieldName) {
            fieldName = TYPE_ADAPTER_FIELD_PREFIX + adapterFieldInfo.size();
            adapterFieldInfo.addField(fieldType, fieldName);
            String originalFieldName = FileGenUtils.unescapeEscapedString(fieldName);
            TypeName typeName = getAdapterFieldTypeName(fieldType);
            adapterBuilder.addField(typeName, originalFieldName, Modifier.PRIVATE, Modifier.FINAL);
            constructorBuilder.addStatement(
                    fieldName + " = (TypeAdapter<" + fieldType + ">) gson.getAdapter(" +
                    getTypeTokenCodeForGenericType(fieldType, typeVarsMap) + ")");
        }
        return fieldName;
    }

    @NotNull
    private AdapterFieldInfo addAdapterFields(@NotNull Builder adapterBuilder,
                                              @NotNull MethodSpec.Builder constructorBuilder,
                                              @NotNull Map<FieldAccessor, TypeMirror> memberVariables,
                                              @NotNull Map<TypeMirror, String> typeVarsMap) {

        AdapterFieldInfo result = new AdapterFieldInfo(memberVariables.size());
        for (Map.Entry<FieldAccessor, TypeMirror> entry : memberVariables.entrySet()) {
            FieldAccessor fieldAccessor = entry.getKey();
            TypeMirror fieldType = entry.getValue();

            String adapterAccessor = null;
            TypeMirror optionalJsonAdapter = fieldAccessor.getJsonAdapterType();
            if (optionalJsonAdapter != null) {
                ExecutableElement constructor = ElementUtils.getFirstConstructor(optionalJsonAdapter);
                if (constructor != null) {
                    TypeUtils.JsonAdapterType jsonAdapterType1 = TypeUtils.getJsonAdapterType(optionalJsonAdapter);
                    String fieldAdapterAccessor = getFieldAccessorForKnownJsonAdapterType(constructor, adapterBuilder, constructorBuilder, fieldType,
                            jsonAdapterType1, result, fieldAccessor.isJsonAdapterNullSafe(), fieldAccessor.getJsonName());
                    result.addFieldToAccessor(fieldAccessor.getJsonName(), fieldAdapterAccessor);
                } else {
                    throw new IllegalStateException("Unsupported @JsonAdapter value: " + optionalJsonAdapter);
                }
            } else if (KnownTypeAdapterUtils.hasNativePrimitiveTypeAdapter(fieldType)) {
                adapterAccessor = KnownTypeAdapterUtils.getNativePrimitiveTypeAdapter(fieldType);
            } else if (TypeUtils.containsTypeVarParams(fieldType)) {
                adapterAccessor = getAdapterForUnknownGenericType(fieldType, adapterBuilder, constructorBuilder,
                        typeVarsMap, result);
            } else {
                adapterAccessor = getAdapterAccessor(fieldType, adapterBuilder, constructorBuilder,
                        typeVarsMap, result);

                if (null != adapterAccessor && adapterAccessor.startsWith("new ")) {
                    String fieldName = result.getFieldName(fieldType);
                    //Add this to a member variable
                    if(null == fieldName) {
                        fieldName = TYPE_ADAPTER_FIELD_PREFIX + result.size();
                        result.addField(fieldType, fieldName);
                        String originalFieldName = FileGenUtils.unescapeEscapedString(fieldName);
                        TypeName typeName = getAdapterFieldTypeName(fieldType);
                        adapterBuilder.addField(typeName, originalFieldName, Modifier.PRIVATE, Modifier.FINAL);
                        String statement = fieldName + " = " + getCleanedFieldInitializer(adapterAccessor);
                        constructorBuilder.addStatement(statement);
                    }
                    adapterAccessor = fieldName;
                }
            }

            if (null != adapterAccessor) {
                result.addTypeToAdapterAccessor(fieldType, adapterAccessor);
            }
        }
        return result;
    }

    @NotNull
    private static MethodSpec getWriteMethodSpec(@NotNull TypeName typeName,
                                                 @NotNull Map<FieldAccessor, TypeMirror> memberVariables,
                                                 @NotNull AdapterFieldInfo adapterFieldInfo) {
        final MethodSpec.Builder builder = MethodSpec.methodBuilder("write")
                .addParameter(JsonWriter.class, "writer")
                .addParameter(typeName, "object")
                .returns(void.class)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addException(IOException.class);

        builder.addStatement("writer.beginObject()");
        builder.beginControlFlow("if (object == null)");
        builder.addStatement("writer.endObject()");
        builder.addStatement("return");
        builder.endControlFlow();

        for (Map.Entry<FieldAccessor, TypeMirror> element : memberVariables.entrySet()) {
            FieldAccessor fieldAccessor = element.getKey();
            final String getterCode = fieldAccessor.createGetterCode();

            String name = fieldAccessor.getJsonName();
            String variableType = element.getValue().toString();

            boolean isPrimitive = TypeUtils.isSupportedPrimitive(variableType);

            builder.addCode("\n");
            if (!isPrimitive) {
                builder.beginControlFlow("if (object." + getterCode + " != null) ");
            }

            builder.addStatement("writer.name(\"" + name + "\")");
            if (!isPrimitive) {
                builder.addStatement(
                        adapterFieldInfo.getAdapterAccessor(element.getValue(), name) + ".write(writer, object." +
                                getterCode + ")");
                /*
                * If the element is annotated with NonNull annotation, throw {@link IOException} if it is null.
                */
                if (fieldAccessor.doesRequireNotNull()) {
                    builder.endControlFlow();
                    builder.beginControlFlow("else if (object." + getterCode + " == null)");
                    builder.addStatement("throw new java.io.IOException(\"" + getterCode +
                            " cannot be null\")");
                }

                builder.endControlFlow();
            } else {
                builder.addStatement("writer.value(object." + getterCode + ")");
            }
        }

        builder.addCode("\n");
        builder.addStatement("writer.endObject()");
        return builder.build();
    }

    /**
     * Generates the TypeSpec for the TypeAdapter
     * that this class generates.
     *
     * @return a valid TypeSpec that can be written
     * to a file or added to another class.
     */
    @Override
    @NotNull
    public TypeSpec createTypeAdapterSpec(@NotNull StagGenerator stagGenerator) {
        TypeMirror typeMirror = mInfo.getType();
        TypeName typeVariableName = TypeVariableName.get(typeMirror);

        List<? extends TypeMirror> typeArguments = mInfo.getTypeArguments();

        MethodSpec.Builder constructorBuilder = MethodSpec.constructorBuilder()
                .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                                       .addMember("value", "\"unchecked\"")
                                       .addMember("value", "\"rawtypes\"")
                                       .build())
                .addModifiers(Modifier.PUBLIC)
                .addParameter(Gson.class, "gson");

        String className = FileGenUtils.unescapeEscapedString(mInfo.getTypeAdapterClassName());
        TypeSpec.Builder adapterBuilder = TypeSpec.classBuilder(className)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .superclass(ParameterizedTypeName.get(ClassName.get(TypeAdapter.class), typeVariableName));

        Map<TypeMirror, String> typeVarsMap = new HashMap<>();

        int idx = 0;
        if (null != typeArguments) {
            for (TypeMirror innerTypeMirror : typeArguments) {
                if (innerTypeMirror.getKind() == TypeKind.TYPEVAR) {
                    TypeVariable typeVariable = (TypeVariable) innerTypeMirror;
                    String simpleName = typeVariable.asElement().getSimpleName().toString();
                    adapterBuilder.addTypeVariable(TypeVariableName.get(simpleName, TypeVariableName.get(typeVariable.getUpperBound())));
                    //If the classInfo has unknown types, pass type... as param in constructor.
                    String paramName = "type[" + String.valueOf(idx) + "]";
                    typeVarsMap.put(typeVariable, paramName);
                    idx++;
                }
            }

            if (idx > 0) {
                constructorBuilder.addParameter(Type[].class, "type");
                constructorBuilder.varargs(true);
            }
        }

        AnnotatedClass annotatedClass = mSupportedTypesModel.getSupportedType(typeMirror);
        if (null == annotatedClass) {
            throw new IllegalStateException("The AnnotatedClass class can't be null in TypeAdapterGenerator : " + typeMirror.toString());
        }
        Map<FieldAccessor, TypeMirror> memberVariables = annotatedClass.getMemberVariables();

        AdapterFieldInfo adapterFieldInfo =
                addAdapterFields(adapterBuilder, constructorBuilder, memberVariables, typeVarsMap);

        MethodSpec writeMethod = getWriteMethodSpec(typeVariableName, memberVariables, adapterFieldInfo);
        MethodSpec readMethod = getReadMethodSpec(typeVariableName, memberVariables, adapterFieldInfo);

        adapterBuilder.addField(Gson.class, "mGson", Modifier.FINAL, Modifier.PRIVATE);
        constructorBuilder.addStatement("this.mGson = gson");

        adapterBuilder.addMethod(constructorBuilder.build());
        adapterBuilder.addMethod(writeMethod);
        adapterBuilder.addMethod(readMethod);

        return adapterBuilder.build();
    }

    private static class AdapterFieldInfo {

        //Type.toString -> Accessor Map
        @NotNull
        private final Map<String, String> mAdapterAccessor;

        //FieldName -> Accessor Map
        @NotNull
        private final Map<String, String> mFieldAdapterAccessor;

        //Type.toString -> Accessor Map
        @NotNull
        private final Map<String, String> mAdapterFields;

        AdapterFieldInfo(int capacity) {
            mAdapterFields = new HashMap<>(capacity);
            mAdapterAccessor = new HashMap<>(capacity);
            mFieldAdapterAccessor = new HashMap<>(capacity);
        }

        String getAdapterAccessor(@NotNull TypeMirror typeMirror, @NotNull String fieldName) {
            String adapterAccessor = mFieldAdapterAccessor.get(fieldName);
            if (adapterAccessor == null) {
                adapterAccessor = mAdapterAccessor.get(typeMirror.toString());
            }
            return adapterAccessor;
        }

        String getFieldName(@NotNull TypeMirror fieldType) {
            return mAdapterFields.get(fieldType.toString());
        }

        int size() {
            return mAdapterFields.size() + mFieldAdapterAccessor.size();
        }

        void addField(@NotNull TypeMirror fieldType, @NotNull String fieldName) {
            mAdapterFields.put(fieldType.toString(), fieldName);
        }

        void addTypeToAdapterAccessor(@NotNull TypeMirror typeMirror, String accessorCode) {
            mAdapterAccessor.put(typeMirror.toString(), accessorCode);
        }

        void addFieldToAccessor(@NotNull String fieldName, @NotNull String accessorCode) {
            mFieldAdapterAccessor.put(fieldName, accessorCode);
        }
    }
}