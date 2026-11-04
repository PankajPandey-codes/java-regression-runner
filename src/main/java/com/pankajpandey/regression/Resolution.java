/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.pankajpandey.regression;

import java.util.List;
import java.util.Map;

record Resolution<T>(T value, List<Map<String, Object>> errors) {
    Resolution {
        errors = errors == null ? List.of() : List.copyOf(errors);
        value = errors.isEmpty() ? value : null;
    }

    static <T> Resolution<T> ok(T value) {
        return new Resolution<>(value, List.of());
    }

    static <T> Resolution<T> errors(List<Map<String, Object>> errors) {
        return new Resolution<>(null, errors);
    }

    boolean hasErrors() {
        return !errors.isEmpty();
    }
}
