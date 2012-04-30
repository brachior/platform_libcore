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

package java.lang;

import java.lang.ref.WeakReference;

// The algorithm works like the double check locking pattern.
// The table of the hash map is stored in a volatile field
// which is written after any modification of the table entries
// Each entry is a pair class/value and the entry is stored in a weak ref
// like ephemerons.
// The get() tries to retrieve the value without synchronization but
// may see intermediary states. Perhaps another thread has already initilized
// the value but the current thread doesn't see the modification,
// in that case it will call lockedGet which works under a lock.
// The other intermediary state is that a thread can see that the entry exist
// but not initialized because initialValue() can call get() or remove(),
// like in the case above, we fallback to lockedGet.
// lockedGet() prunes links from the collision list that doesn't reference an entry anymore
// (because there are weak links) or resize the table if there is not enough space.
// In case of a resize, all empty weak links are removed.
// Before calling initialValue(), both resize() and prune() create a new Entry if necessary
// and lockedGet will store the result of the call to initialValue() just after to call it.
// remove(), like prune() unlinks weak links that doesn't reference an entry anymore.
// Entry that are not fully initialized (stale entry) are not removed because there still not exist.
//

/**
 * Lazily associate a computed value with (potentially) every type.
 * For example, if a dynamic language needs to construct a message dispatch
 * table for each class encountered at a message send call site,
 * it can use a {@code ClassValue} to cache information needed to
 * perform the message send quickly, for each class encountered.
 * @author John Rose, JSR 292 EG
 * @since 1.7
 */
public abstract class ClassValue<T> {
    private final Object lock = new Object();
    private volatile WeakLink[] weakLinks;
    private int size;  // indicate roughly the number of values, the real number may be lower
    
    private static class WeakLink extends WeakReference<Entry> {
        final WeakLink next;
        
        WeakLink(Entry entry, WeakLink next) {
            super(entry);
            this.next = next;
        }
    }
    
    // type & value are GCable at the same time
    private static class Entry {
        final Class<?> type;
        Object value;  // null means a stale value
        
        Entry(Class<?> type) {
            this.type = type;
        }
    }
    
    private static final Object NULL_VALUE = new Object();
    
    private static Object maskNull(Object value) {
        return (value == null)? NULL_VALUE: value;
    }
    private static Object unmaskNull(Object value) {
        return (value == NULL_VALUE)? null: value;
    }
    
    /**
     * Sole constructor.  (For invocation by subclass constructors, typically
     * implicit.)
     */
    protected ClassValue() {
        weakLinks = new WeakLink[16];
    }

    /**
     * Computes the given class's derived value for this {@code ClassValue}.
     * <p>
     * This method will be invoked within the first thread that accesses
     * the value with the {@link #get get} method.
     * <p>
     * Normally, this method is invoked at most once per class,
     * but it may be invoked again if there has been a call to
     * {@link #remove remove}.
     * <p>
     * If this method throws an exception, the corresponding call to {@code get}
     * will terminate abnormally with that exception, and no class value will be recorded.
     *
     * @param type the type whose class value must be computed
     * @return the newly computed value associated with this {@code ClassValue}, for the given class or interface
     * @see #get
     * @see #remove
     */
    protected abstract T computeValue(Class<?> type);

    /**
     * Returns the value for the given class.
     * If no value has yet been computed, it is obtained by
     * an invocation of the {@link #computeValue computeValue} method.
     * <p>
     * The actual installation of the value on the class
     * is performed atomically.
     * At that point, if several racing threads have
     * computed values, one is chosen, and returned to
     * all the racing threads.
     * <p>
     * The {@code type} parameter is typically a class, but it may be any type,
     * such as an interface, a primitive type (like {@code int.class}), or {@code void.class}.
     * <p>
     * In the absence of {@code remove} calls, a class value has a simple
     * state diagram:  uninitialized and initialized.
     * When {@code remove} calls are made,
     * the rules for value observation are more complex.
     * See the documentation for {@link #remove remove} for more information.
     *
     * @param type the type whose class value must be computed or retrieved
     * @return the current value associated with this {@code ClassValue}, for the given class or interface
     * @throws NullPointerException if the argument is null
     * @see #remove
     * @see #computeValue
     */
    @SuppressWarnings("unchecked")
    public T get(Class<?> type) {
        WeakLink[] weakLinks = this.weakLinks;   // volatile read
        int index = (type.hashCode() & 0x7fffffff) & (weakLinks.length - 1); 
        WeakLink link = weakLinks[index];
        for(;link != null; link = link.next) {
            Entry entry = link.get();
            if (entry != null && entry.type == type) {
                Object value = entry.value;
                if (value == null) {
                    // stale value, need to retry with the lock
                    break;
                }
                return (T)unmaskNull(value);
            }
        }
        
        return lockedGet(type);
    }
    
    @SuppressWarnings("unchecked")
    private T lockedGet(Class<?> type) {
        synchronized (lock) {
            WeakLink[] weakLinks = this.weakLinks;
            int length = weakLinks.length;
            
            Entry entry = (this.size == length >> 1)?
                 resize(type, weakLinks, length):
                 prune(type, weakLinks, length);
            
            Object value = entry.value;
            if (value != null) {
                return (T)unmaskNull(value);
            }

            T initialValue = computeValue(type);
            
            value = entry.value;
            if (value != null) {  // entry already initialized ?
                return (T)unmaskNull(value);
            }
            entry.value = maskNull(initialValue);
            this.weakLinks = this.weakLinks;        // volatile write
            return initialValue;
        }
    } 
    
    private Entry prune(Class<?> type, WeakLink[] weakLinks, int length) {
        int index = (type.hashCode() & 0x7fffffff) & (length - 1); 
        
        int size = this.size;
        WeakLink newLink = null;
        for(WeakLink l = weakLinks[index]; l != null; l = l.next) {
            Entry entry = l.get();
            if (entry == null) {
                size--;
                continue;
            }
            if (entry.type == type) {
                return entry;  // another thread may have already initialized the value
                // the table may not be cleanup, but that's not a big deal
                // because the other thread should have clean the thing up
            }
            newLink = new WeakLink(entry, newLink);
        }
        
        // new uninitialized link
        Entry newEntry = new Entry(type);
        weakLinks[index] = new WeakLink(newEntry, newLink);
        this.size = size + 1;
        
        return newEntry;
    }
    
    private Entry resize(Class<?> type, WeakLink[] weakLinks, int length) {
        WeakLink[] newLinks = new WeakLink[length << 1];
        int newLength = newLinks.length;
        
        Entry newEntry = null;
        int size = 0;  // recompute the size
        for(int i=0; i<length; i++) {
            for(WeakLink l = weakLinks[i]; l != null; l = l.next) {
                Entry entry = l.get();
                if (entry == null) {
                    continue;
                }
                
                Class<?> entryType = entry.type;
                if (entryType == type) {
                    newEntry = entry;
                }
                
                int index = (entryType.hashCode() & 0x7fffffff) & (newLength - 1);    
                newLinks[index] = new WeakLink(entry, newLinks[index]);
                size++;
            }
        }
        
        if (newEntry == null) {
            int index = (type.hashCode() & 0x7fffffff) & (newLength - 1); 
            newEntry = new Entry(type);
            newLinks[index] = new WeakLink(newEntry, newLinks[index]);
            size++;
        }
        
        
        this.weakLinks = newLinks;
        this.size = size;
        
        return newEntry;
    }

    /**
     * Removes the associated value for the given class.
     * If this value is subsequently {@linkplain #get read} for the same class,
     * its value will be reinitialized by invoking its {@link #computeValue computeValue} method.
     * This may result in an additional invocation of the
     * {@code computeValue} method for the given class.
     * <p>
     * In order to explain the interaction between {@code get} and {@code remove} calls,
     * we must model the state transitions of a class value to take into account
     * the alternation between uninitialized and initialized states.
     * To do this, number these states sequentially from zero, and note that
     * uninitialized (or removed) states are numbered with even numbers,
     * while initialized (or re-initialized) states have odd numbers.
     * <p>
     * When a thread {@code T} removes a class value in state {@code 2N},
     * nothing happens, since the class value is already uninitialized.
     * Otherwise, the state is advanced atomically to {@code 2N+1}.
     * <p>
     * When a thread {@code T} queries a class value in state {@code 2N},
     * the thread first attempts to initialize the class value to state {@code 2N+1}
     * by invoking {@code computeValue} and installing the resulting value.
     * <p>
     * When {@code T} attempts to install the newly computed value,
     * if the state is still at {@code 2N}, the class value will be initialized
     * with the computed value, advancing it to state {@code 2N+1}.
     * <p>
     * Otherwise, whether the new state is even or odd,
     * {@code T} will discard the newly computed value
     * and retry the {@code get} operation.
     * <p>
     * Discarding and retrying is an important proviso,
     * since otherwise {@code T} could potentially install
     * a disastrously stale value.  For example:
     * <ul>
     * <li>{@code T} calls {@code CV.get(C)} and sees state {@code 2N}
     * <li>{@code T} quickly computes a time-dependent value {@code V0} and gets ready to install it
     * <li>{@code T} is hit by an unlucky paging or scheduling event, and goes to sleep for a long time
     * <li>...meanwhile, {@code T2} also calls {@code CV.get(C)} and sees state {@code 2N}
     * <li>{@code T2} quickly computes a similar time-dependent value {@code V1} and installs it on {@code CV.get(C)}
     * <li>{@code T2} (or a third thread) then calls {@code CV.remove(C)}, undoing {@code T2}'s work
     * <li> the previous actions of {@code T2} are repeated several times
     * <li> also, the relevant computed values change over time: {@code V1}, {@code V2}, ...
     * <li>...meanwhile, {@code T} wakes up and attempts to install {@code V0}; <em>this must fail</em>
     * </ul>
     * We can assume in the above scenario that {@code CV.computeValue} uses locks to properly
     * observe the time-dependent states as it computes {@code V1}, etc.
     * This does not remove the threat of a stale value, since there is a window of time
     * between the return of {@code computeValue} in {@code T} and the installation
     * of the the new value.  No user synchronization is possible during this time.
     *
     * @param type the type whose class value must be removed
     * @throws NullPointerException if the argument is null
     */
    public void remove(Class<?> type) {
        synchronized (lock) {
            WeakLink[] weakLinks = this.weakLinks;
            int index = (type.hashCode() & 0x7fffffff) & (weakLinks.length - 1); 
            int size = this.size;
            
            WeakLink newLink = null;
            for(WeakLink link = weakLinks[index]; link != null; link = link.next) {
                Entry entry = link.get(); 
                if (entry == null || (entry.type == type && entry.value != null)) {
                    size--;
                    continue;
                }
                newLink = new WeakLink(entry, newLink);
            }
            weakLinks[index] = newLink;
            this.size = size;
            this.weakLinks = this.weakLinks;      // volatile write
        }
    }
}
