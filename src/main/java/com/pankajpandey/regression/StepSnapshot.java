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

import com.fasterxml.jackson.databind.JsonNode;

record StepSnapshot(String stepId, int stepNumber, int expectedCode, int actualCode, boolean passed, boolean referenceable, JsonNode response, JsonNode payload, boolean dbSource) {
    StepSnapshot {
        stepId = stepId == null ? "" : stepId.trim();
        referenceable = referenceable && response != null && !response.isNull();
        response = response == null ? null : response.deepCopy();
        payload = payload == null ? null : payload.deepCopy();
    }
}
