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
import com.google.gson.reflect.TypeToken;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import com.vimeo.stag.processor.generators.model.AnnotatedClass;
import com.vimeo.stag.processor.generators.model.ClassInfo;
import com.vimeo.stag.processor.generators.model.SupportedTypesModel;
import com.vimeo.stag.processor.utils.FileGenUtils;
import com.vimeo.stag.processor.utils.Preconditions;
import com.vimeo.stag.processor.utils.TypeUtils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.lang.model.element.Modifier;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

public class StagGenerator {

    @NotNull private static final String CLASS_STAG = "Stag";
    @NotNull private static final String CLASS_TYPE_ADAPTER_FACTORY = "Factory";
    @NotNull private static final Map<String, GenericClassInfo> KNOWN_MAP_GENERIC_CLASSES = new HashMap<>();
    @NotNull private static final Map<String, GenericClassInfo> KNOWN_COLLECTION_GENERIC_CLASSES = new HashMap<>();

    static {
        KNOWN_MAP_GENERIC_CLASSES.put(Map.class.getName(), new GenericClassInfo(false));
        KNOWN_MAP_GENERIC_CLASSES.put(HashMap.class.getName(), new GenericClassInfo(false));
        KNOWN_MAP_GENERIC_CLASSES.put(LinkedHashMap.class.getName(), new GenericClassInfo(false));
        KNOWN_MAP_GENERIC_CLASSES.put(ConcurrentHashMap.class.getName(), new GenericClassInfo(false));
        KNOWN_COLLECTION_GENERIC_CLASSES.put(Collection.class.getName(), new GenericClassInfo(false));
        KNOWN_COLLECTION_GENERIC_CLASSES.put(List.class.getName(), new GenericClassInfo(false));
        KNOWN_COLLECTION_GENERIC_CLASSES.put(ArrayList.class.getName(), new GenericClassInfo(false));
    }

    @NotNull private final List<ClassInfo> mKnownClasses;
    @NotNull private final Map<String, GenericClassInfo> mGenericClassInfo = new HashMap<>();

    public StagGenerator(@NotNull Set<TypeMirror> knownTypes,
                         @NotNull SupportedTypesModel supportedTypesModel) {
        mKnownClasses = new ArrayList<>(knownTypes.size());

        Map<String, ClassInfo> knownFieldNames = new HashMap<>(knownTypes.size());
        Map<String, List<ClassInfo>> clashingClassNames = new HashMap<>(knownTypes.size());
        Set<ClassInfo> genericClasses = new HashSet<>();
        for (TypeMirror knownType : knownTypes) {
            if (!TypeUtils.isAbstract(knownType)) {
                ClassInfo classInfo = new ClassInfo(knownType);
                List<? extends TypeMirror> typeArguments = classInfo.getTypeArguments();
                if (null == typeArguments || typeArguments.isEmpty()) {
                    String adapterFactoryMethodName = classInfo.getTypeAdapterClassName();
                    ClassInfo clashingClass = knownFieldNames.get(adapterFactoryMethodName);
                    if (null != clashingClass) {
                        List<ClassInfo> classInfoList = clashingClassNames.get(adapterFactoryMethodName);
                        if (null == classInfoList) {
                            classInfoList = new ArrayList<>();
                            classInfoList.add(clashingClass);
                            clashingClassNames.put(adapterFactoryMethodName, classInfoList);
                        }
                        classInfoList.add(classInfo);
                    } else {
                        knownFieldNames.put(adapterFactoryMethodName, classInfo);
                    }
                } else {
                    genericClasses.add(classInfo);
                }
                mKnownClasses.add(classInfo);
            }
        }

        for (ClassInfo knownGenericType : genericClasses) {
            List<? extends TypeMirror> typeArguments = knownGenericType.getTypeArguments();
            AnnotatedClass annotatedClass = supportedTypesModel.getSupportedType(knownGenericType.getType());
            if (null == annotatedClass) {
                throw new IllegalStateException("The AnnotatedClass class can't be null in StagGenerator : " + knownGenericType.toString());
            }

            Preconditions.checkNotNull(typeArguments);
            mGenericClassInfo.put(knownGenericType.getType().toString(),
                                  new GenericClassInfo(true));
        }
    }

    public static String getGeneratedFactoryClassAndPackage(String generatedPackageName) {
        return generatedPackageName + "." + CLASS_STAG + "." + CLASS_TYPE_ADAPTER_FACTORY;
    }

    @NotNull
    private static String removeSpecialCharacters(TypeMirror typeMirror) {
        String typeString = typeMirror.toString();
        /*
         * This is done to avoid generating duplicate method names, where the inner class type
         * has same name (in different packages). In that case we are using the complete package name
         * of the class to avoid class. We'll come up with a better solution for this case.
         */
        if (TypeUtils.isSupportedNative(typeMirror.toString())) {
            typeString = typeString.substring(typeString.lastIndexOf(".") + 1);
        }
        typeString = typeString.replace("<", "").replace(">", "").replace("[", "").replace("]", "");
        typeString = typeString.replace(",", "").replace(".", "");
        return typeString;
    }

    @NotNull
    private static String generateMethodName(@NotNull TypeMirror typeMirror) {
        String result = "";
        String outerClassType = TypeUtils.getSimpleOuterClassType(typeMirror);
        if (TypeUtils.isConcreteType(typeMirror)) {
            if (TypeUtils.isNativeArray(typeMirror)) {
                result = removeSpecialCharacters(typeMirror);
                return result + FileGenUtils.CODE_BLOCK_ESCAPED_SEPARATOR + "PrimitiveArray" +
                       FileGenUtils.CODE_BLOCK_ESCAPED_SEPARATOR;
            } else if (typeMirror instanceof DeclaredType) {
                List<? extends TypeMirror> typeArguments = ((DeclaredType) typeMirror).getTypeArguments();
                if (typeArguments.isEmpty()) {
                    result = removeSpecialCharacters(typeMirror) + FileGenUtils.CODE_BLOCK_ESCAPED_SEPARATOR;
                } else {
                    result += outerClassType + FileGenUtils.CODE_BLOCK_ESCAPED_SEPARATOR;
                    for (TypeMirror innerType : typeArguments) {
                        result += generateMethodName(innerType);
                    }
                }
            }
        }

        return result;
    }


    @Nullable
    GenericClassInfo getGenericClassInfo(@NotNull TypeMirror typeMirror) {
        return mGenericClassInfo.get(typeMirror.toString());
    }

    /**
     * Generates the public API in the form of the {@code Stag.Factory} type adapter factory
     * for the annotated classes. Creates the spec for the class.
     *
     * @return A non null TypeSpec for the factory class.
     */
    @NotNull
    public TypeSpec createStagSpec() {
        TypeSpec.Builder stagBuilder =
                TypeSpec.classBuilder(CLASS_STAG).addModifiers(Modifier.PUBLIC, Modifier.FINAL);
        stagBuilder.addType(getAdapterFactorySpec());

        return stagBuilder.build();
    }

    @NotNull
    private TypeSpec getAdapterFactorySpec() {
        TypeVariableName genericTypeName = TypeVariableName.get("T");

        TypeSpec.Builder adapterFactoryBuilder = TypeSpec.classBuilder(CLASS_TYPE_ADAPTER_FACTORY)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addSuperinterface(TypeAdapterFactory.class);

        MethodSpec.Builder createMethodBuilder = MethodSpec.methodBuilder("create")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                                       .addMember("value", "\"unchecked\"")
                                       .addMember("value", "\"rawtypes\"")
                                       .build())
                .addTypeVariable(genericTypeName)
                .returns(ParameterizedTypeName.get(ClassName.get(TypeAdapter.class), genericTypeName))
                .addParameter(Gson.class, "gson")
                .addParameter(ParameterizedTypeName.get(ClassName.get(TypeToken.class), genericTypeName),
                              "type")
                .addStatement("Class<? super T> clazz = type.getRawType()");

        /*
         * Iterate through all the registered known classes, and map the classes to its corresponding type adapters.
         */
        for (ClassInfo classInfo : mKnownClasses) {
            String qualifiedTypeAdapterName = classInfo.getTypeAdapterQualifiedClassName();
            List<? extends TypeMirror> typeArguments = classInfo.getTypeArguments();
            if (null == typeArguments || typeArguments.isEmpty()) {
                /*
                 *  This is used to generate the code if the class does not have any type arguments, or it is not parameterized.
                 */
                createMethodBuilder.beginControlFlow(
                        "if (clazz == " + classInfo.getClassAndPackage() + ".class)");
                createMethodBuilder.addStatement(
                        "return (TypeAdapter<T>)(new " + qualifiedTypeAdapterName + "(gson))");
                createMethodBuilder.endControlFlow();
                createMethodBuilder.addCode("\n");
            } else {

                /*
                 *  This is used to generate the code if the class has type arguments, or it is parameterized.
                 */
                createMethodBuilder.beginControlFlow(
                        "if (clazz == " + classInfo.getClassAndPackage() + ".class)");
                createMethodBuilder.addStatement("java.lang.reflect.Type parameters = type.getType()");
                createMethodBuilder.beginControlFlow(
                        "if (parameters instanceof java.lang.reflect.ParameterizedType)");
                createMethodBuilder.addStatement(
                        "java.lang.reflect.ParameterizedType parameterizedType = (java.lang.reflect.ParameterizedType) parameters");
                createMethodBuilder.addStatement(
                        "java.lang.reflect.Type[] parametersType = parameterizedType.getActualTypeArguments()");
                String statement = "return (TypeAdapter<T>) new " + qualifiedTypeAdapterName + "(gson";

                for (int idx = 0; idx < typeArguments.size(); idx++) {
                    statement += ", parametersType[" + idx + "]";
                }

                statement += ")";
                createMethodBuilder.addStatement(statement);
                createMethodBuilder.endControlFlow();
                createMethodBuilder.beginControlFlow("else");
                createMethodBuilder.addStatement("TypeToken objectToken = TypeToken.get(Object.class)");
                statement = "return (TypeAdapter<T>) new " + qualifiedTypeAdapterName + "(gson";
                for (int idx = 0; idx < typeArguments.size(); idx++) {
                    statement += ", objectToken.getType()";
                }
                statement += ")";
                createMethodBuilder.addStatement(statement);
                createMethodBuilder.endControlFlow();
                createMethodBuilder.endControlFlow();
                createMethodBuilder.addCode("\n");
            }
        }

        createMethodBuilder.addStatement("return null");
        adapterFactoryBuilder.addMethod(createMethodBuilder.build());

        return adapterFactoryBuilder.build();
    }


    static class GenericClassInfo {

        final boolean mHasUnknownVarTypeFields;

        GenericClassInfo(boolean hasUnknownVarTypeFields) {
            mHasUnknownVarTypeFields = hasUnknownVarTypeFields;
        }
    }
}