/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Copyright (C) 2012 JSR 292 Port to Android
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package java.lang.invoke;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import sun.misc.Unsafe;

/**
 * A method type represents the arguments and return type accepted and
 * returned by a method handle, or the arguments and return type passed
 * and expected  by a method handle caller.  Method types must be properly
 * matched between a method handle and all its callers,
 * and the JVM's operations enforce this matching at, specifically
 * during calls to {@link MethodHandle#invokeExact MethodHandle.invokeExact}
 * and {@link MethodHandle#invoke MethodHandle.invoke}, and during execution
 * of {@code invokedynamic} instructions.
 * <p>
 * The structure is a return type accompanied by any number of parameter types.
 * The types (primitive, {@code void}, and reference) are represented by {@link Class} objects.
 * (For ease of exposition, we treat {@code void} as if it were a type.
 * In fact, it denotes the absence of a return type.)
 * <p>
 * All instances of {@code MethodType} are immutable.
 * Two instances are completely interchangeable if they compare equal.
 * Equality depends on pairwise correspondence of the return and parameter types and on nothing else.
 * <p>
 * This type can be created only by factory methods.
 * All factory methods may cache values, though caching is not guaranteed.
 * Some factory methods are static, while others are virtual methods which
 * modify precursor method types, e.g., by changing a selected parameter.
 * <p>
 * Factory methods which operate on groups of parameter types
 * are systematically presented in two versions, so that both Java arrays and
 * Java lists can be used to work with groups of parameter types.
 * The query methods {@code parameterArray} and {@code parameterList}
 * also provide a choice between arrays and lists.
 * <p>
 * {@code MethodType} objects are sometimes derived from bytecode instructions
 * such as {@code invokedynamic}, specifically from the type descriptor strings associated
 * with the instructions in a class file's constant pool.
 * <p>
 * Like classes and strings, method types can also be represented directly
 * in a class file's constant pool as constants.
 * A method type may be loaded by an {@code ldc} instruction which refers
 * to a suitable {@code CONSTANT_MethodType} constant pool entry.
 * The entry refers to a {@code CONSTANT_Utf8} spelling for the descriptor string.
 * For more details, see the <a href="package-summary.html#mtcon">package summary</a>.
 * <p>
 * When the JVM materializes a {@code MethodType} from a descriptor string,
 * all classes named in the descriptor must be accessible, and will be loaded.
 * (But the classes need not be initialized, as is the case with a {@code CONSTANT_Class}.)
 * This loading may occur at any time before the {@code MethodType} object is first derived.
 * @author John Rose, JSR 292 EG
 */
public final class MethodType implements java.io.Serializable {
    private static final long serialVersionUID = 292L;

    transient final Class<?>   returnType;
    transient final Class<?>[] parameterTypes;

    // used by serialization
    @SuppressWarnings("unused")
    private MethodType() {
        this.returnType = null;
        this.parameterTypes = null;
    }
    
    MethodType(Class<?> returnType, Class<?>[] parameterTypes) {
        this.returnType = returnType;
        this.parameterTypes = parameterTypes;
    }
    
    /**
     * Returns the parameter type at the specified index, within this method type.
     * @param num the index (zero-based) of the desired parameter type
     * @return the selected parameter type
     * @throws IndexOutOfBoundsException if {@code num} is not a valid index into {@code parameterArray()}
     */
    public Class<?> parameterType(int num) {
        return parameterTypes[num];
    }
    /**
     * Returns the number of parameter types in this method type.
     * @return the number of parameter types
     */
    public int parameterCount() {
        return parameterTypes.length;
    }
    /**
     * Returns the return type of this method type.
     * @return the return type
     */
    public Class<?> returnType() {
        return returnType;
    }

    /**
     * Presents the parameter types as a list (a convenience method).
     * The list will be immutable.
     * @return the parameter types (as an immutable list)
     */
    public List<Class<?>> parameterList() {
        return Collections.unmodifiableList(Arrays.asList(parameterTypes));
    }

    /**
     * Presents the parameter types as an array (a convenience method).
     * Changes to the array will not result in changes to the type.
     * @return the parameter types (as a fresh copy if necessary)
     */
    public Class<?>[] parameterArray() {
        return Arrays.copyOf(parameterTypes, parameterTypes.length);
    }

    private static void checkNullAndVoids(Class<?>[] parameterTypes) {
        for(int i=0; i<parameterTypes.length; i++) {
            checkNullAndVoid(parameterTypes[i]);
        }
    }
    
    private static void checkNullAndVoid(Class<?> parameterType) {
        if (parameterType == void.class) {
            throw new IllegalArgumentException("parameter type can't be void");
        }
    }

    // method type cache
    private static final Cache CACHE = new Cache();

    
    // JVM entry point
    // the parameterTypes should be a freshly allocated array
    // This method is also used by trusted method from this package
    static MethodType unsafeMethodType(Class<?> returnType, Class<?>... parameterTypes) {
        return CACHE.getMethodType(returnType, parameterTypes);
    }
    
    /**
     * Finds or creates an instance of the given method type.
     * @param rtype  the return type
     * @param ptypes the parameter types
     * @return a method type with the given components
     * @throws NullPointerException if {@code rtype} or {@code ptypes} or any element of {@code ptypes} is null
     * @throws IllegalArgumentException if any element of {@code ptypes} is {@code void.class}
     */
    public static MethodType methodType(Class<?> rtype, Class<?>[] ptypes) {
        rtype.getClass();
        checkNullAndVoids(ptypes);
        return CACHE.getMethodType(rtype, Arrays.copyOf(ptypes, ptypes.length));
    }

    /**
     * Finds or creates a method type with the given components.
     * Convenience method for {@link #methodType(java.lang.Class, java.lang.Class[]) methodType}.
     * @return a method type with the given components
     * @throws NullPointerException if {@code rtype} or {@code ptypes} or any element of {@code ptypes} is null
     * @throws IllegalArgumentException if any element of {@code ptypes} is {@code void.class}
     */
    public static MethodType methodType(Class<?> rtype, List<Class<?>> ptypes) {
        rtype.getClass();
        Class<?>[] parameterTypes = ptypes.toArray(new Class<?>[ptypes.size()]);
        checkNullAndVoids(parameterTypes);
        return CACHE.getMethodType(rtype, parameterTypes);
    }

    /**
     * Finds or creates a method type with the given components.
     * Convenience method for {@link #methodType(java.lang.Class, java.lang.Class[]) methodType}.
     * The leading parameter type is prepended to the remaining array.
     * @return a method type with the given components
     * @throws NullPointerException if {@code rtype} or {@code ptype0} or {@code ptypes} or any element of {@code ptypes} is null
     * @throws IllegalArgumentException if {@code ptype0} or {@code ptypes} or any element of {@code ptypes} is {@code void.class}
     */
    public static MethodType methodType(Class<?> rtype, Class<?> ptype0, Class<?>... ptypes) {
        rtype.getClass();
        checkNullAndVoid(ptype0);
        checkNullAndVoids(ptypes);
        Class<?>[] parameterTypes = new Class<?>[ptypes.length + 1];
        parameterTypes[0] = ptype0;
        System.arraycopy(ptypes, 0, parameterTypes, 1, ptypes.length);
        return CACHE.getMethodType(rtype, parameterTypes);
    }

    /**
     * Finds or creates a method type with the given components.
     * Convenience method for {@link #methodType(java.lang.Class, java.lang.Class[]) methodType}.
     * The resulting method has no parameter types.
     * @return a method type with the given return value
     * @throws NullPointerException if {@code rtype} is null
     */
    public static MethodType methodType(Class<?> rtype) {
        return CACHE.getMethodType(rtype, EMPTY_CLASS_ARRAY);
    }
    
    private static final Class<?>[] EMPTY_CLASS_ARRAY = new Class<?>[0];

    /**
     * Finds or creates a method type with the given components.
     * Convenience method for {@link #methodType(java.lang.Class, java.lang.Class[]) methodType}.
     * The resulting method has the single given parameter type.
     * @return a method type with the given return value and parameter type
     * @throws NullPointerException if {@code rtype} or {@code ptype0} is null
     * @throws IllegalArgumentException if {@code ptype0} is {@code void.class}
     */
    public static MethodType methodType(Class<?> rtype, Class<?> ptype0) {
        return CACHE.getMethodType(rtype, new Class<?>[]{ ptype0 });
    }

    /**
     * Finds or creates a method type with the given components.
     * Convenience method for {@link #methodType(java.lang.Class, java.lang.Class[]) methodType}.
     * The resulting method has the same parameter types as {@code ptypes},
     * and the specified return type.
     * @throws NullPointerException if {@code rtype} or {@code ptypes} is null
     */
    public static MethodType methodType(Class<?> rtype, MethodType ptypes) {
        return CACHE.getMethodType(rtype, ptypes.parameterTypes);
    }

    /**
     * Finds or creates a method type whose components are {@code Object} with an optional trailing {@code Object[]} array.
     * Convenience method for {@link #methodType(java.lang.Class, java.lang.Class[]) methodType}.
     * All parameters and the return type will be {@code Object},
     * except the final array parameter if any, which will be {@code Object[]}.
     * @param objectArgCount number of parameters (excluding the final array parameter if any)
     * @param finalArray whether there will be a trailing array parameter, of type {@code Object[]}
     * @return a generally applicable method type, for all calls of the given fixed argument count and a collected array of further arguments
     * @throws IllegalArgumentException if {@code objectArgCount} is negative or greater than 255 (or 254, if {@code finalArray} is true)
     * @see #genericMethodType(int)
     */
    public static MethodType genericMethodType(int objectArgCount, boolean finalArray) {
        int length = objectArgCount + ((finalArray)? 1: 0);
        if (length < 0 || length > 255) {
            throw new IllegalArgumentException(String.valueOf(objectArgCount));
        }
        Class<?>[] parameterTypes = new Class<?>[length];
        for(int i=0; i<objectArgCount; i++) {
            parameterTypes[i] = Object.class;
        }
        if (finalArray) {
            parameterTypes[objectArgCount] = Object[].class;
        }
        return CACHE.getMethodType(Object.class, parameterTypes);
    }

    /**
     * Finds or creates a method type whose components are all {@code Object}.
     * Convenience method for {@link #methodType(java.lang.Class, java.lang.Class[]) methodType}.
     * All parameters and the return type will be Object.
     * @param objectArgCount number of parameters
     * @return a generally applicable method type, for all calls of the given argument count
     * @throws IllegalArgumentException if {@code objectArgCount} is negative or greater than 255
     * @see #genericMethodType(int, boolean)
     */
    public static MethodType genericMethodType(int objectArgCount) {
        return genericMethodType(objectArgCount, false);
    }
    
    /**
     * Converts all types, both reference and primitive, to {@code Object}.
     * Convenience method for {@link #genericMethodType(int) genericMethodType}.
     * The expression {@code type.wrap().erase()} produces the same value
     * as {@code type.generic()}.
     * @return a version of the original type with all types replaced
     */
    public MethodType generic() {
        return genericMethodType(parameterTypes.length);
    }

    /**
     * Finds or creates a method type with a single different parameter type.
     * Convenience method for {@link #methodType(java.lang.Class, java.lang.Class[]) methodType}.
     * @param num    the index (zero-based) of the parameter type to change
     * @param nptype a new parameter type to replace the old one with
     * @return the same type, except with the selected parameter changed
     * @throws IndexOutOfBoundsException if {@code num} is not a valid index into {@code parameterArray()}
     * @throws IllegalArgumentException if {@code nptype} is {@code void.class}
     * @throws NullPointerException if {@code nptype} is null
     */
    public MethodType changeParameterType(int num, Class<?> nptype) {
        Class<?>[] parameterTypes = this.parameterTypes;
        // it will throw an AIOOB, which is just fine
        if (parameterTypes[num] == nptype) {
            return this;
        }
        checkNullAndVoid(nptype);
        parameterTypes = Arrays.copyOf(parameterTypes, parameterTypes.length);
        parameterTypes[num] = nptype;
        return CACHE.getMethodType(returnType, parameterTypes);
    }

    /**
     * Finds or creates a method type with additional parameter types.
     * Convenience method for {@link #methodType(java.lang.Class, java.lang.Class[]) methodType}.
     * @param num    the position (zero-based) of the inserted parameter type(s)
     * @param ptypesToInsert zero or more new parameter types to insert into the parameter list
     * @return the same type, except with the selected parameter(s) inserted
     * @throws IndexOutOfBoundsException if {@code num} is negative or greater than {@code parameterCount()}
     * @throws IllegalArgumentException if any element of {@code ptypesToInsert} is {@code void.class}
     *                                  or if the resulting method type would have more than 255 parameter slots
     * @throws NullPointerException if {@code ptypesToInsert} or any of its elements is null
     */
    public MethodType insertParameterTypes(int num, Class<?>... ptypesToInsert) {
        Class<?>[] parameterTypes = this.parameterTypes;
        int length = parameterTypes.length;
        if (num < 0 || num > length)
            throw new IndexOutOfBoundsException(String.valueOf(num));
        checkNullAndVoids(ptypesToInsert);
        
        int ptypesToInsertLength = ptypesToInsert.length;
        Class<?>[] array = new Class<?>[length + ptypesToInsertLength];
        for(int i = 0; i<num; i++) {
            array[i] = parameterTypes[i];
        }
        for(int i = 0; i<ptypesToInsertLength; i++) {
            array[num + i] = ptypesToInsert[i];
        }
        for(int i = num; i<length; i++) {
            array[ptypesToInsertLength + i] = parameterTypes[i];
        }
        return CACHE.getMethodType(returnType, array);
    }

    /**
     * Finds or creates a method type with additional parameter types.
     * Convenience method for {@link #methodType(java.lang.Class, java.lang.Class[]) methodType}.
     * @param num    the position (zero-based) of the inserted parameter type(s)
     * @param ptypesToInsert zero or more new parameter types to insert into the parameter list
     * @return the same type, except with the selected parameter(s) inserted
     * @throws IndexOutOfBoundsException if {@code num} is negative or greater than {@code parameterCount()}
     * @throws IllegalArgumentException if any element of {@code ptypesToInsert} is {@code void.class}
     *                                  or if the resulting method type would have more than 255 parameter slots
     * @throws NullPointerException if {@code ptypesToInsert} or any of its elements is null
     */
    public MethodType insertParameterTypes(int num, List<Class<?>> ptypesToInsert) {
        return insertParameterTypes(num, ptypesToInsert.toArray(new Class<?>[ptypesToInsert.size()]));
    }
    
    /**
     * Finds or creates a method type with additional parameter types.
     * Convenience method for {@link #methodType(java.lang.Class, java.lang.Class[]) methodType}.
     * @param ptypesToInsert zero or more new parameter types to insert after the end of the parameter list
     * @return the same type, except with the selected parameter(s) appended
     * @throws IllegalArgumentException if any element of {@code ptypesToInsert} is {@code void.class}
     *                                  or if the resulting method type would have more than 255 parameter slots
     * @throws NullPointerException if {@code ptypesToInsert} or any of its elements is null
     */
    public MethodType appendParameterTypes(Class<?>... ptypesToInsert) {
        checkNullAndVoids(ptypesToInsert);
        
        Class<?>[] parameterTypes = this.parameterTypes;
        int length = parameterTypes.length;
        int ptypesToInsertLength = ptypesToInsert.length;
        parameterTypes = Arrays.copyOf(parameterTypes, length + ptypesToInsertLength);
        for(int i = 0; i<ptypesToInsertLength; i++) {
            parameterTypes[length + i] = ptypesToInsert[i];
        }
        return CACHE.getMethodType(returnType, parameterTypes);
    }

    

    /**
     * Finds or creates a method type with additional parameter types.
     * Convenience method for {@link #methodType(java.lang.Class, java.lang.Class[]) methodType}.
     * @param ptypesToInsert zero or more new parameter types to insert after the end of the parameter list
     * @return the same type, except with the selected parameter(s) appended
     * @throws IllegalArgumentException if any element of {@code ptypesToInsert} is {@code void.class}
     *                                  or if the resulting method type would have more than 255 parameter slots
     * @throws NullPointerException if {@code ptypesToInsert} or any of its elements is null
     */
    public MethodType appendParameterTypes(List<Class<?>> ptypesToInsert) {
        return appendParameterTypes(ptypesToInsert.toArray(new Class<?>[ptypesToInsert.size()]));
    }

    /**
     * Finds or creates a method type with some parameter types omitted.
     * Convenience method for {@link #methodType(java.lang.Class, java.lang.Class[]) methodType}.
     * @param start  the index (zero-based) of the first parameter type to remove
     * @param end    the index (greater than {@code start}) of the first parameter type after not to remove
     * @return the same type, except with the selected parameter(s) removed
     * @throws IndexOutOfBoundsException if {@code start} is negative or greater than {@code parameterCount()}
     *                                  or if {@code end} is negative or greater than {@code parameterCount()}
     *                                  or if {@code start} is greater than {@code end}
     */
    public MethodType dropParameterTypes(int start, int end) {
        int length = parameterTypes.length;
        if (start<0 || start > end || end > length)
            throw new IndexOutOfBoundsException("start="+start+" end="+end);
        
        int delta = end -start;
        if (length == delta) {  // short-cut
            return methodType(returnType);
        }
        Class<?>[] array = new Class<?>[length - delta];
        for(int i=0; i<start; i++) {
            array[i] = parameterTypes[i];
        }
        for(int i=end; i<length; i++) {
            array[i - delta] = parameterTypes[i];
        }
        return CACHE.getMethodType(returnType, array);
    }

    /**
     * Finds or creates a method type with a different return type.
     * Convenience method for {@link #methodType(java.lang.Class, java.lang.Class[]) methodType}.
     * @param nrtype a return parameter type to replace the old one with
     * @return the same type, except with the return type change
     * @throws NullPointerException if {@code nrtype} is null
     */
    public MethodType changeReturnType(Class<?> nrtype) {
        if (returnType == nrtype) {
            return this;
        }
        return CACHE.getMethodType(nrtype, parameterTypes);
    }

    /**
     * Reports if this type contains a primitive argument or return value.
     * The return type {@code void} counts as a primitive.
     * @return true if any of the types are primitives
     */
    public boolean hasPrimitives() {
        Class<?> returnType = this.returnType;
        if (returnType == void.class || returnType.isPrimitive()) {
            return true;
        }
        Class<?>[] parameterTypes = this.parameterTypes;
        for(int i=0; i<parameterTypes.length; i++) {
            if (parameterTypes[i].isPrimitive()) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Converts all primitive types to their corresponding wrapper types.
     * Convenience method for {@link #methodType(java.lang.Class, java.lang.Class[]) methodType}.
     * All reference types (including wrapper types) will remain unchanged.
     * A {@code void} return type is changed to the type {@code java.lang.Void}.
     * The expression {@code type.wrap().erase()} produces the same value
     * as {@code type.generic()}.
     * @return a version of the original type with all primitive types replaced
     */
    public MethodType wrap() {
        if (!hasPrimitives()) {
            return this;
        }
        Class<?> returnType = wrap(this.returnType);
        Class<?>[] parameterTypes = Arrays.copyOf(this.parameterTypes, this.parameterTypes.length);
        for(int i=0; i<parameterTypes.length; i++) {
            parameterTypes[i] = wrap(parameterTypes[i]);
        }
        return CACHE.getMethodType(returnType, parameterTypes);
    }
    
    private static Class<?> wrap(Class<?> clazz) {
        if (clazz == void.class) {
            return Void.class;
        }
        if (!clazz.isPrimitive()) {
            return clazz;
        }
        String name = clazz.getName();
        switch(name.charAt(0) * name.charAt(1)) {
        case 'b'*'o': // boolean
            return Boolean.class;
        case 'b'*'y': // byte
            return Byte.class;
        case 's'*'h': // short
            return Short.class;
        case 'c'*'h': // char
            return Character.class;
        case 'i'*'n': // int
            return Integer.class;
        case 'l'*'o': // long
            return Long.class;
        case 'f'*'l': // float
            return Float.class;
        case 'd'*'o': // double
            return Double.class;
        default:
            throw new InternalError();
        }
    }

    /**
     * Reports if this type contains a wrapper argument or return value.
     * Wrappers are types which box primitive values, such as {@link Integer}.
     * The reference type {@code java.lang.Void} counts as a wrapper,
     * if it occurs as a return type.
     * @return true if any of the types are wrappers
     */
    public boolean hasWrappers() {
        if (isWrapper(returnType)) {
            return true;
        }
        Class<?>[] parameterTypes = this.parameterTypes;
        for(int i=0; i<parameterTypes.length; i++) {
            if (isWrapper(parameterTypes[i])) {
                return true;
            }
        }
        return false;
    }
    
    private static boolean isWrapper(Class<?> clazz) {
        return clazz != unwrap(clazz);
    }
    
    private static Class<?> unwrap(Class<?> clazz) {
        String name = clazz.getName();
        if (name.length() < 14) {
            return clazz;
        }
        switch(name.charAt(10)) {
        case 'V': // Void
            if (clazz == Void.class)
                return void.class;
            return clazz;
        case 'B': // Boolean & Byte
            if (clazz == Boolean.class)
              return boolean.class;
            if (clazz == Byte.class)
                return byte.class;
            return clazz;
        case 'S': // Short
            if (clazz == Short.class)
                return short.class;
            return clazz;
        case 'C': // Character
            if (clazz == Character.class)
                return char.class;
            return clazz;
        case 'I': // Integer
            if (clazz == Integer.class)
                return int.class;
            return clazz;
        case 'L': // Long
            if (clazz == Long.class)
                return long.class;
            return clazz;
        case 'F': // Float
            if (clazz == Float.class)
                return float.class;
            return clazz;
        case 'D': // Double
            if (clazz == Double.class)
                return double.class;
            return clazz;
        default:
            throw new InternalError();
        }
    }
    
    /**
     * Converts all wrapper types to their corresponding primitive types.
     * Convenience method for {@link #methodType(java.lang.Class, java.lang.Class[]) methodType}.
     * All primitive types (including {@code void}) will remain unchanged.
     * A return type of {@code java.lang.Void} is changed to {@code void}.
     * @return a version of the original type with all wrapper types replaced
     */
    public MethodType unwrap() {
        if (!hasWrappers()) {
            return this;
        }
        Class<?> returnType = unwrap(this.returnType);
        Class<?>[] parameterTypes = Arrays.copyOf(this.parameterTypes, this.parameterTypes.length);
        for(int i=0; i<parameterTypes.length; i++) {
            parameterTypes[i] = unwrap(parameterTypes[i]);
        }
        return CACHE.getMethodType(returnType, parameterTypes);
    }

    private boolean isErased() {
        Class<?> returnType = this.returnType;
        if (!returnType.isPrimitive() && returnType != void.class && returnType != Object.class) {
            return false;
        }
        Class<?>[] parameterTypes = this.parameterTypes;
        for(int i=0; i<parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            if (!parameterType.isPrimitive() && parameterType != Object.class) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Erases all reference types to {@code Object}.
     * Convenience method for {@link #methodType(java.lang.Class, java.lang.Class[]) methodType}.
     * All primitive types (including {@code void}) will remain unchanged.
     * @return a version of the original type with all reference types replaced
     */
    public MethodType erase() {
        if (isErased()) {
            return this;
        }
        
        Class<?> returnType = erase(this.returnType);
        Class<?>[] parameterTypes = Arrays.copyOf(this.parameterTypes, this.parameterTypes.length);
        for(int i=0; i<parameterTypes.length; i++) {
            parameterTypes[i] = erase(parameterTypes[i]);
        }
        return CACHE.getMethodType(returnType, parameterTypes);
    }
    
    private static Class<?> erase(Class<?> clazz) {
        if (clazz == void.class || clazz.isPrimitive()) {
            return clazz;
        }
        return Object.class;
    }
    
    /**
     * Compares the specified object with this type for equality.
     * That is, it returns <tt>true</tt> if and only if the specified object
     * is also a method type with exactly the same parameters and return type.
     * @param x object to compare
     * @see Object#equals(Object)
     */
    @Override
    public boolean equals(Object x) {
        if (this == x) {
            return true;
        }
        if (!(x instanceof MethodType)) {
            return false;
        }
        MethodType methodType = (MethodType)x;
        return eq(returnType, parameterTypes, methodType.returnType, methodType.parameterTypes);
    }

    /**
     * Returns the hash code value for this method type.
     * It is defined to be the same as the hashcode of a List
     * whose elements are the return type followed by the
     * parameter types.
     * @return the hash code value for this method type
     * @see Object#hashCode()
     * @see #equals(Object)
     * @see List#hashCode()
     */
    @Override
    public int hashCode() {
        return hash(returnType, parameterTypes);
    }

    /**
     * Returns a string representation of the method type,
     * of the form {@code "(PT0,PT1...)RT"}.
     * The string representation of a method type is a
     * parenthesis enclosed, comma separated list of type names,
     * followed immediately by the return type.
     * <p>
     * Each type is represented by its
     * {@link java.lang.Class#getSimpleName simple name}.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        Class<?>[] parameterTypes = this.parameterTypes;
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i != 0) {
                sb.append(',');
            }
            sb.append(parameterTypes[i].getSimpleName());
        }
        return sb.append(')').append(returnType.getSimpleName()).toString();
    }

    
    /**
     * Finds or creates an instance of a method type, given the spelling of its bytecode descriptor.
     * Convenience method for {@link #methodType(java.lang.Class, java.lang.Class[]) methodType}.
     * Any class or interface name embedded in the descriptor string
     * will be resolved by calling {@link ClassLoader#loadClass(java.lang.String)}
     * on the given loader (or if it is null, on the system class loader).
     * <p>
     * Note that it is possible to encounter method types which cannot be
     * constructed by this method, because their component types are
     * not all reachable from a common class loader.
     * <p>
     * This method is included for the benfit of applications that must
     * generate bytecodes that process method handles and {@code invokedynamic}.
     * @param descriptor a bytecode-level type descriptor string "(T...)T"
     * @param loader the class loader in which to look up the types
     * @return a method type matching the bytecode-level type descriptor
     * @throws NullPointerException if the string is null
     * @throws IllegalArgumentException if the string is not well-formed
     * @throws TypeNotPresentException if a named type cannot be found
     */
    public static MethodType fromMethodDescriptorString(String descriptor, ClassLoader loader)
        throws IllegalArgumentException, TypeNotPresentException {
        
        int length = descriptor.length();
        if (length == 0 || descriptor.charAt(0) != '(') {
            throw new IllegalArgumentException(descriptor);
        }
        ArrayList<Class<?>> list = new ArrayList<>();
        int i = 1;
        boolean stopAtNext = false;
        int dimension = 0;
        loop: for(;i<length;) {
            Class<?> type;
            switch(descriptor.charAt(i)) {
            case '[':
                dimension++;
                i++;
                continue loop;
            case 'V':
                type = void.class;
                break;
            case 'Z':
                type = boolean.class;
                break;
            case 'B':
                type = byte.class;
                break;
            case 'S':
                type = short.class;
                break;
            case 'C':
                type = char.class;
                break;
            case 'I':
                type = int.class;
                break;
            case 'J':
                type = long.class;
                break;
            case 'F':
                type = float.class;
                break;
            case 'D':
                type = double.class;
                break;
            case 'L': {
                int index = descriptor.indexOf(';', i + 1);
                if (index == -1) {
                    throw new IllegalArgumentException(descriptor);
                }
                String name = descriptor.substring(i + 1, index).replace('/', '.');
                try {
                    type = Class.forName(name, false, loader);
                } catch (ClassNotFoundException e) {
                    throw new TypeNotPresentException(name, e);
                }
                i = index;
                break;
            }

            case ')':
                stopAtNext = true;
                i++;
                continue;

            default:  // unknown tag
                break loop;  // throw the exception
            }
            
            if (stopAtNext) {
                return methodType(type, list);
            }
            if (dimension != 0) {
                type = asArrayType(type, dimension);
            }
            list.add(type);
            i++;
        }
        throw new IllegalArgumentException(descriptor);
    }

    static Class<?> asArrayType(Class<?> type, int dimension) {
        for(int i=0; i<dimension; i++) {
            type = Array.newInstance(type, 0).getClass();
        }
        return type;
    }
    
    
    
    /**
     * Produces a bytecode descriptor representation of the method type.
     * <p>
     * Note that this is not a strict inverse of {@link #fromMethodDescriptorString fromMethodDescriptorString}.
     * Two distinct classes which share a common name but have different class loaders
     * will appear identical when viewed within descriptor strings.
     * <p>
     * This method is included for the benfit of applications that must
     * generate bytecodes that process method handles and {@code invokedynamic}.
     * {@link #fromMethodDescriptorString(java.lang.String, java.lang.ClassLoader) fromMethodDescriptorString},
     * because the latter requires a suitable class loader argument.
     * @return the bytecode type descriptor representation
     */
    public String toMethodDescriptorString() {
        StringBuilder builder = new StringBuilder();
        builder.append('(');
        Class<?>[] parameterTypes = this.parameterTypes;
        for(int i=0; i<parameterTypes.length; i++) {
            descriptor(builder, parameterTypes[i]);
        }
        return descriptor(builder.append(')'), returnType).toString();
    }

    private static StringBuilder descriptor(StringBuilder builder, Class<?> parameterType) {
        while(parameterType.isArray()) {
            builder.append('[');
            parameterType = parameterType.getComponentType();
        }
        if (parameterType == void.class) {
            return builder.append('V');
        }
        String name = parameterType.getName();
        if (parameterType.isPrimitive()) {
            return builder.append(primitiveDescriptor(name));
        }
        return builder.append('L').append(name.replace('.','/')).append(';');
    }
    
    private static char primitiveDescriptor(String primitiveName) {
        switch(primitiveName.charAt(0) * primitiveName.charAt(1)) {
        case 'b'*'o': // boolean
            return 'Z';
        case 'b'*'y': // byte
            return 'B';
        case 's'*'h': // short
            return 'S';
        case 'c'*'h': // char
            return 'C';
        case 'i'*'n': // int
            return 'I';
        case 'l'*'o': // long
            return 'J';
        case 'f'*'l': // float
            return 'F';
        case 'd'*'o': // double
            return 'D';
        default:
            throw new InternalError();
        }
    }
    
    private void writeObject(ObjectOutputStream output) throws java.io.IOException {
        output.writeObject(returnType);
        output.writeObject(parameterArray());  // send a copy if ObjectOutputStream is rogue
    }

    private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
        Class<?> returnType     = (Class<?>)input.readObject();
        Class<?>[] parameterTypes = (Class<?>[])input.readObject();

        returnType.getClass();
        checkNullAndVoids(parameterTypes);

        parameterTypes = Arrays.copyOf(parameterTypes, parameterTypes.length);  // defensive copy
        // set final fields
        DeSerializationHelper.UNSAFE.putObject(this, DeSerializationHelper.RETURNTYPE_OFFSET, returnType);
        DeSerializationHelper.UNSAFE.putObject(this, DeSerializationHelper.PARAMETERTYPES_OFFSET, parameterTypes);
    }

    private static class DeSerializationHelper {
        private DeSerializationHelper() {
            // empty
        }
        
        static final Unsafe UNSAFE = Unsafe.getUnsafe();
        static final long RETURNTYPE_OFFSET;
        static final long PARAMETERTYPES_OFFSET;
        static {
            try {
                RETURNTYPE_OFFSET = UNSAFE.objectFieldOffset(MethodType.class.getDeclaredField("rtype"));
                PARAMETERTYPES_OFFSET = UNSAFE.objectFieldOffset(MethodType.class.getDeclaredField("ptypes"));
            } catch (NoSuchFieldException e) {
                throw (InternalError)new InternalError().initCause(e);
            }
        } 
    }
    
    private Object readResolve() {
        return CACHE.getMethodType(returnType, parameterTypes);
    }
    
    static boolean eq(Class<?> returnType, Class<?>[] parameterTypes, Class<?> returnType2, Class<?>[] parameterTypes2) {
        if (returnType != returnType2) {
            return false;
        }
        int length = parameterTypes.length;
        if (length != parameterTypes2.length) {
            return false;
        }
        for(int i=0; i<length; i++) {
            if (parameterTypes[i] != parameterTypes2[i]) {
                return false;
            }
        }
        return true;
    }
    
    static int hash(Class<?> returnType, Class<?>[] parameterTypes) {
        int hash = returnType.hashCode();
        for(int i=0; i<parameterTypes.length; i++) {
            hash = 31 * hash + parameterTypes.hashCode();
        }
        return hash;
    }
    
    // see java.lang.ClassValue for a more detailed comment about this data structure
    // here, we store the hash in the link because comparing two method types
    // can be fairly slow
    private static class Cache {
        private final Object lock = new Object();
        private volatile WeakLink[] weakLinks;
        private int size;  // indicate roughly the number of method types, the real number may be lower
        
        private static class WeakLink extends WeakReference<MethodType> {
            final WeakLink next;
            final int hash;
            
            WeakLink(MethodType methodType, int hash, WeakLink next) {
                super(methodType);
                this.hash = hash;
                this.next = next;
            }
        }
        
        Cache() {
            weakLinks = new WeakLink[16];
        }

        public MethodType getMethodType(Class<?> returnType, Class<?>[] parameterTypes) {
            WeakLink[] weakLinks = this.weakLinks;   // volatile read
            int hash = hash(returnType, parameterTypes);
            int index = hash & (weakLinks.length - 1); 
            WeakLink link = weakLinks[index];
            for(;link != null; link = link.next) {
                MethodType entry = link.get();
                if (entry != null &&
                        link.hash == hash && 
                        eq(entry.returnType, entry.parameterTypes, returnType, parameterTypes)) {
                    return entry;
                }
            }
            
            return lockedGetMethodType(hash, returnType, parameterTypes);
        }
        
        private MethodType lockedGetMethodType(int hash, Class<?> returnType, Class<?>[] parameterTypes) {
            synchronized (lock) {
                WeakLink[] weakLinks = this.weakLinks;
                int length = weakLinks.length;
                
                MethodType entry = (this.size == length >> 1)?
                     resize(hash, returnType, parameterTypes, weakLinks, length):
                     prune(hash, returnType, parameterTypes, weakLinks, length);
                
                this.weakLinks = this.weakLinks;        // volatile write
                return entry;
            }
        } 
        
        private MethodType prune(int hash, Class<?> returnType, Class<?>[] parameterTypes, WeakLink[] weakLinks, int length) {
            int index = hash & (length - 1); 
            
            int size = this.size;
            WeakLink newLink = null;
            for(WeakLink l = weakLinks[index]; l != null; l = l.next) {
                MethodType entry = l.get();
                if (entry == null) {
                    size--;
                    continue;
                }
                int linkHash = l.hash;
                if (linkHash == hash && eq(entry.returnType, entry.parameterTypes, returnType, parameterTypes)) {
                    return entry;  // another thread may have already initialized the value
                    // the table may not be cleanup, but that's not a big deal
                    // because the other thread should have clean the thing up
                }
                newLink = new WeakLink(entry, linkHash, newLink);
            }
            
            // new uninitialized link
            MethodType newEntry = new MethodType(returnType, parameterTypes);
            weakLinks[index] = new WeakLink(newEntry, hash, newLink);
            this.size = size + 1;
            
            return newEntry;
        }
        
        private MethodType resize(int hash, Class<?> returnType, Class<?>[] parameterTypes, WeakLink[] weakLinks, int length) {
            WeakLink[] newLinks = new WeakLink[length << 1];
            int newLength = newLinks.length;
            
            MethodType newEntry = null;
            int size = 0;  // recompute the size
            for(int i=0; i<length; i++) {
                for(WeakLink l = weakLinks[i]; l != null; l = l.next) {
                    MethodType entry = l.get();
                    if (entry == null) {
                        continue;
                    }
                    
                    int linkHash = l.hash;
                    if (linkHash == hash &&
                            eq(entry.returnType, entry.parameterTypes, returnType, parameterTypes)) {
                        newEntry = entry;
                    }
                    
                    int index = linkHash & (newLength - 1);    
                    newLinks[index] = new WeakLink(entry, linkHash, newLinks[index]);
                    size++;
                }
            }
            
            if (newEntry == null) {
                int index = hash & (newLength - 1); 
                newEntry = new MethodType(returnType, parameterTypes);
                newLinks[index] = new WeakLink(newEntry, hash, newLinks[index]);
                size++;
            }
            
            this.weakLinks = newLinks;  // volatile write
            this.size = size;
            
            return newEntry;
        }
    }
}
