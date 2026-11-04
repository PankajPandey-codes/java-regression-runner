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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioContextTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void resolvesByStepIdAndStepAlias() throws Exception {
        ScenarioContext context = new ScenarioContext();
        context.store("createLoan", 1, 200, 200, true, mapper.readTree("{\"loanAccountNumber\":\"LN1\",\"data\":[{\"id\":7}]}"), null);

        assertEquals("LN1", context.resolveReference("createLoan.response.loanAccountNumber").value().asText());
        assertEquals(7, context.resolveReference("step1.response.data[0].id").value().asInt());
    }

    @Test
    void resolvesReferenceableSourceStepEvenWhenStepFailedItsOwnComparison() throws Exception {
        ScenarioContext context = new ScenarioContext();
        context.store("loanGet", 1, 200, 200, false, true, mapper.readTree("{\"id\":1,\"bookedInterestUnpaid\":5583.34}"), null);

        Resolution<com.fasterxml.jackson.databind.JsonNode> value = context.resolveReference("loanGet.response.bookedInterestUnpaid");

        assertFalse(value.hasErrors());
        assertEquals("5583.34", value.value().asText());
    }

    @Test
    void rejectsUnreferenceableSourceStepEvenWhenResponseExists() throws Exception {
        ScenarioContext context = new ScenarioContext();
        context.store("loanGet", 1, 200, 500, false, false, mapper.readTree("{\"id\":1,\"bookedInterestUnpaid\":5583.34}"), null);

        Resolution<com.fasterxml.jackson.databind.JsonNode> value = context.resolveReference("loanGet.response.bookedInterestUnpaid");

        assertTrue(value.hasErrors());
        assertEquals("dependency_failed", value.errors().get(0).get("kind"));
        assertEquals("Source step is not referenceable: loanGet", value.errors().get(0).get("expected"));
    }

    @Test
    void rejectsStepWithNoResponse() throws Exception {
        // Block only when no response was captured — infrastructure failure or no HTTP call made.
        ScenarioContext context = new ScenarioContext();
        context.store("failedStep", 1, 200, 500, false, null, null);

        Resolution<com.fasterxml.jackson.databind.JsonNode> value = context.resolveReference("failedStep.response.id");

        assertTrue(value.hasErrors());
        assertEquals("dependency_failed", value.errors().get(0).get("kind"));
    }

    @Test
    void reportsMissingPath() throws Exception {
        ScenarioContext context = new ScenarioContext();
        context.store("source", 1, 200, 200, true, mapper.readTree("{\"id\":1}"), null);

        Resolution<com.fasterxml.jackson.databind.JsonNode> value = context.resolveReference("source.response.missing");

        assertTrue(value.hasErrors());
        assertEquals("missing_reference", value.errors().get(0).get("kind"));
        assertEquals("source.response.missing", value.errors().get(0).get("path"));
        assertEquals("Missing path: missing", value.errors().get(0).get("expected"));
        assertEquals("null", value.errors().get(0).get("actual"));
    }

    @Test
    void retainsLegacyDiagnosticForMissingArrayIndex() throws Exception {
        ScenarioContext context = new ScenarioContext();
        context.store("source", 1, 200, 200, true, mapper.readTree("{\"data\":[{\"id\":1}]}"), null);

        Resolution<com.fasterxml.jackson.databind.JsonNode> value = context.resolveReference("source.response.data[2].id");

        assertTrue(value.hasErrors());
        assertEquals("missing_reference", value.errors().get(0).get("kind"));
        assertEquals("source.response.data[2].id", value.errors().get(0).get("path"));
        assertEquals("Missing path: data[2].id", value.errors().get(0).get("expected"));
        assertEquals("null", value.errors().get(0).get("actual"));
    }

    @Test
    void retainsLegacyDiagnosticForMalformedNumericIndex() throws Exception {
        ScenarioContext context = new ScenarioContext();
        context.store("source", 1, 200, 200, true, mapper.readTree("{\"data\":[{\"id\":1}]}"), null);

        Resolution<com.fasterxml.jackson.databind.JsonNode> value = context.resolveReference("source.response.data[bad].id");

        assertTrue(value.hasErrors());
        assertEquals("missing_reference", value.errors().get(0).get("kind"));
        assertEquals("source.response.data[bad].id", value.errors().get(0).get("path"));
        assertEquals("Missing path: data[bad].id", value.errors().get(0).get("expected"));
        assertEquals("null", value.errors().get(0).get("actual"));
    }

    @Test
    void resolvesFilteredArrayReferenceByStepId() throws Exception {
        ScenarioContext context = new ScenarioContext();
        context.store("source", 1, 200, 200, true, mapper.readTree(
                "[{\"type\":\"OTHER\",\"active\":true,\"id\":8},"
                        + "{\"type\":\"TARGET\",\"active\":true,\"id\":7}]"
        ), null);

        Resolution<com.fasterxml.jackson.databind.JsonNode> value = context.resolveReference(
                "source.response[?(@.type == 'TARGET' && @.active == true)].id"
        );

        assertFalse(value.hasErrors());
        assertEquals(7, value.value().asInt());
    }

    @Test
    void preservesAmbiguousFilteredReferenceError() throws Exception {
        ScenarioContext context = new ScenarioContext();
        context.store("source", 1, 200, 200, true, mapper.readTree(
                "[{\"type\":\"TARGET\",\"active\":true,\"id\":7},"
                        + "{\"type\":\"TARGET\",\"active\":true,\"id\":8}]"
        ), null);

        Resolution<com.fasterxml.jackson.databind.JsonNode> value = context.resolveReference(
                "source.response[?(@.type == 'TARGET' && @.active == true)].id"
        );

        assertTrue(value.hasErrors());
        assertEquals("ambiguous_reference", value.errors().get(0).get("kind"));
        assertEquals(
                "source.response[?(@.type == 'TARGET' && @.active == true)].id",
                value.errors().get(0).get("path")
        );
        assertEquals("Filter matched multiple array items", value.errors().get(0).get("expected"));
        assertEquals("null", value.errors().get(0).get("actual"));
    }

    @Test
    void preservesMalformedFilteredReferenceError() throws Exception {
        ScenarioContext context = new ScenarioContext();
        context.store("source", 1, 200, 200, true, mapper.readTree("[{\"rank\":1,\"id\":7}]"), null);

        String reference = "source.response[?(@.rank == +1)].id";
        Resolution<com.fasterxml.jackson.databind.JsonNode> value = context.resolveReference(reference);

        assertTrue(value.hasErrors());
        assertEquals("malformed_reference", value.errors().get(0).get("kind"));
        assertEquals(reference, value.errors().get(0).get("path"));
        assertEquals("null", value.errors().get(0).get("actual"));
    }

    @Test
    void classifiesAnyFilterIntentAsMalformedReference() throws Exception {
        ScenarioContext context = new ScenarioContext();
        context.store("source", 1, 200, 200, true, mapper.readTree("[{\"type\":\"TARGET\",\"id\":7}]"), null);

        for (String reference : java.util.List.of(
                "source.response[?@.type == 'TARGET')].id",
                "source.response[? (@.type == 'TARGET')].id"
        )) {
            Resolution<com.fasterxml.jackson.databind.JsonNode> value = context.resolveReference(reference);

            assertTrue(value.hasErrors(), reference);
            assertEquals("malformed_reference", value.errors().get(0).get("kind"), reference);
            assertEquals(reference, value.errors().get(0).get("path"), reference);
        }
    }

    @Test
    void recognizesReservedStepIds() {
        assertTrue(ScenarioContext.isReservedStepId("current"));
        assertTrue(ScenarioContext.isReservedStepId("step1"));
        assertTrue(ScenarioContext.isReservedStepId("response"));
        assertFalse(ScenarioContext.isReservedStepId("loanAfterAccrual"));
    }

    @Test
    void rejectsRootIndexOnMultiElementDbSourceArray() throws Exception {
        ScenarioContext context = new ScenarioContext();
        context.store("dbStep", 1, 200, 200, true, true,
                mapper.readTree("[{\"fee_id\":1,\"amount\":10},{\"fee_id\":2,\"amount\":20}]"), null, true);

        Resolution<com.fasterxml.jackson.databind.JsonNode> value = context.resolveReference("dbStep.response[0].amount");

        assertTrue(value.hasErrors());
        assertEquals("ambiguous_db_source", value.errors().get(0).get("kind"));
        assertEquals("dbStep.response[0].amount", value.errors().get(0).get("path"));
    }

    @Test
    void permitsRootIndexOnSingleElementDbSourceArray() throws Exception {
        ScenarioContext context = new ScenarioContext();
        context.store("dbStep", 1, 200, 200, true, true,
                mapper.readTree("[{\"fee_id\":1,\"amount\":10}]"), null, true);

        Resolution<com.fasterxml.jackson.databind.JsonNode> value = context.resolveReference("dbStep.response[0].amount");

        assertFalse(value.hasErrors());
        assertEquals(10, value.value().asInt());
    }

    @Test
    void permitsRootFilterOnMultiElementDbSourceArray() throws Exception {
        ScenarioContext context = new ScenarioContext();
        context.store("dbStep", 1, 200, 200, true, true,
                mapper.readTree("[{\"fee_id\":1,\"amount\":10},{\"fee_id\":2,\"amount\":20}]"), null, true);

        Resolution<com.fasterxml.jackson.databind.JsonNode> value = context.resolveReference(
                "dbStep.response[?(@.fee_id == 2)].amount"
        );

        assertFalse(value.hasErrors());
        assertEquals(20, value.value().asInt());
    }

    @Test
    void permitsIndexAfterFilterOnDbSourceArray() throws Exception {
        ScenarioContext context = new ScenarioContext();
        context.store("dbStep", 1, 200, 200, true, true,
                mapper.readTree(
                        "[{\"fee_id\":1,\"splits\":[{\"amount\":1},{\"amount\":2},{\"amount\":3}]},"
                                + "{\"fee_id\":2,\"splits\":[{\"amount\":9}]}]"
                ), null, true);

        Resolution<com.fasterxml.jackson.databind.JsonNode> value = context.resolveReference(
                "dbStep.response[?(@.fee_id == 1)].splits[2].amount"
        );

        assertFalse(value.hasErrors());
        assertEquals(3, value.value().asInt());
    }

    @Test
    void permitsRootIndexOnMultiElementHttpSourceArray() throws Exception {
        ScenarioContext context = new ScenarioContext();
        context.store("httpStep", 1, 200, 200, true,
                mapper.readTree("[{\"id\":1},{\"id\":2}]"), null);

        Resolution<com.fasterxml.jackson.databind.JsonNode> value = context.resolveReference("httpStep.response[0].id");

        assertFalse(value.hasErrors());
        assertEquals(1, value.value().asInt());
    }
}
