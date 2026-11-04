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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonPathLiteTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void selectsUniqueMatchAtAnyArrayPosition() throws Exception {
        for (String json : List.of(
                "[{\"type\":\"TARGET\",\"active\":true,\"id\":7},{\"type\":\"OTHER\",\"active\":true,\"id\":8}]",
                "[{\"type\":\"OTHER\",\"active\":true,\"id\":8},{\"type\":\"TARGET\",\"active\":true,\"id\":7}]",
                "[{\"type\":\"OTHER\",\"active\":true,\"id\":8},{\"type\":\"TARGET\",\"active\":true,\"id\":7},{\"type\":\"LATER\",\"active\":true,\"id\":9}]"
        )) {
            Resolution<JsonNode> result = JsonPathLite.read(
                    mapper.readTree(json),
                    "[?(@.type == 'TARGET' && @.active == true)].id"
            );

            assertFalse(result.hasErrors());
            assertEquals(7, result.value().asInt());
        }
    }

    @Test
    void reportsNoMatchingArrayItem() throws Exception {
        Resolution<JsonNode> result = JsonPathLite.read(
                mapper.readTree("[{\"type\":\"OTHER\",\"active\":true,\"id\":8}]"),
                "[?(@.type == 'TARGET' && @.active == true)].id"
        );

        assertTrue(result.hasErrors());
        assertEquals("missing_reference", result.errors().get(0).get("kind"));
    }

    @Test
    void rejectsAmbiguousArrayMatch() throws Exception {
        Resolution<JsonNode> result = JsonPathLite.read(
                mapper.readTree("[{\"type\":\"TARGET\",\"active\":true,\"id\":7},{\"type\":\"TARGET\",\"active\":true,\"id\":8}]"),
                "[?(@.type == 'TARGET' && @.active == true)].id"
        );

        assertTrue(result.hasErrors());
        assertEquals("ambiguous_reference", result.errors().get(0).get("kind"));
    }

    @Test
    void reportsMalformedFilterSyntax() throws Exception {
        Resolution<JsonNode> result = JsonPathLite.read(
                mapper.readTree("[{\"type\":\"TARGET\",\"id\":7}]"),
                "[?(@.type != 'TARGET')].id"
        );

        assertTrue(result.hasErrors());
        assertEquals("malformed_reference", result.errors().get(0).get("kind"));
    }

    @Test
    void reportsMalformedUnicodeEscapeAsMalformedReference() throws Exception {
        String path = "[?(@.label == \"" + "\\" + "u-041\")].id";

        Resolution<JsonNode> result = JsonPathLite.read(
                mapper.readTree("[{\"label\":\"TARGET\",\"id\":7}]"),
                path
        );

        assertTrue(result.hasErrors());
        assertEquals("malformed_reference", result.errors().get(0).get("kind"));
    }

    @Test
    void reportsUnterminatedFilterExpression() throws Exception {
        Resolution<JsonNode> result = JsonPathLite.read(
                mapper.readTree("[{\"type\":\"TARGET\",\"id\":7}]"),
                "[?(@.type == 'TARGET'"
        );

        assertTrue(result.hasErrors());
        assertEquals("malformed_reference", result.errors().get(0).get("kind"));
    }

    @Test
    void reportsMalformedFilterIntentWithoutExactOpeningGrammar() throws Exception {
        for (String path : List.of(
                "[?@.type == 'TARGET')].id",
                "[? (@.type == 'TARGET')].id"
        )) {
            Resolution<JsonNode> result = JsonPathLite.read(
                    mapper.readTree("[{\"type\":\"TARGET\",\"id\":7}]"),
                    path
            );

            assertTrue(result.hasErrors(), path);
            assertEquals("malformed_reference", result.errors().get(0).get("kind"), path);
            assertEquals("Malformed filter path: " + path, result.errors().get(0).get("expected"), path);
        }
    }

    @Test
    void reportsMalformedNonJsonFilterLiteral() throws Exception {
        Resolution<JsonNode> result = JsonPathLite.read(
                mapper.readTree("[{\"rank\":1,\"id\":7}]"),
                "[?(@.rank == +1)].id"
        );

        assertTrue(result.hasErrors());
        assertEquals("malformed_reference", result.errors().get(0).get("kind"));
    }

    @Test
    void reportsMalformedUnescapedControlCharactersInFilterStrings() throws Exception {
        for (String path : List.of(
                "[?(@.label == \"line\nbreak\")].id",
                "[?(@.label == 'tab\tvalue')].id"
        )) {
            Resolution<JsonNode> result = JsonPathLite.read(
                    mapper.readTree("[{\"label\":\"value\",\"id\":7}]"),
                    path
            );

            assertTrue(result.hasErrors(), path);
            assertEquals("malformed_reference", result.errors().get(0).get("kind"), path);
        }
    }

    @Test
    void rejectsFilterAppliedToNonArray() throws Exception {
        Resolution<JsonNode> result = JsonPathLite.read(
                mapper.readTree("{\"type\":\"TARGET\",\"id\":7}"),
                "[?(@.type == 'TARGET')].id"
        );

        assertTrue(result.hasErrors());
        assertEquals("missing_reference", result.errors().get(0).get("kind"));
    }

    @Test
    void retainsFieldAndNumericIndexTraversal() throws Exception {
        Resolution<JsonNode> result = JsonPathLite.read(
                mapper.readTree("{\"data\":[{\"id\":9}]}"),
                "data[0].id"
        );

        assertFalse(result.hasErrors());
        assertEquals(9, result.value().asInt());
    }
}
