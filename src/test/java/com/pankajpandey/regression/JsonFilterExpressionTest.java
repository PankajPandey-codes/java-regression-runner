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
import com.fasterxml.jackson.databind.node.DoubleNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonFilterExpressionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void matchesEveryGenericScalarCondition() throws Exception {
        JsonFilterExpression filter = JsonFilterExpression.parse(
                "@.type == 'TARGET' && @.active == true && @.rank == 2 && @.note == null"
        );

        assertTrue(filter.matches(mapper.readTree(
                "{\"type\":\"TARGET\",\"active\":true,\"rank\":2.0,\"note\":null}"
        )));
        assertFalse(filter.matches(mapper.readTree(
                "{\"type\":\"OTHER\",\"active\":true,\"rank\":2,\"note\":null}"
        )));
    }

    @Test
    void acceptsDoubleQuotedStringsAndEscapedQuotes() throws Exception {
        JsonFilterExpression filter = JsonFilterExpression.parse("@.label == \"TARGET \\\"A\\\"\"");

        assertTrue(filter.matches(mapper.readTree("{\"label\":\"TARGET \\\"A\\\"\"}")));
    }

    @Test
    void acceptsFourDigitUnicodeEscape() throws Exception {
        String escapedTarget = "@.label == \"" + "\\" + "u0054ARGET\"";
        JsonFilterExpression filter = JsonFilterExpression.parse(escapedTarget);

        assertTrue(filter.matches(mapper.readTree("{\"label\":\"TARGET\"}")));
    }

    @Test
    void acceptsJsonNumberSyntaxAndDelimiterSpecificStringEscapes() throws Exception {
        JsonFilterExpression number = JsonFilterExpression.parse("@.amount == -1.25e+2");
        JsonFilterExpression slash = JsonFilterExpression.parse("@.path == \"a\\/b\"");
        JsonFilterExpression apostrophe = JsonFilterExpression.parse("@.label == 'it\\'s ready'");

        assertTrue(number.matches(mapper.readTree("{\"amount\":-125.0}")));
        assertTrue(slash.matches(mapper.readTree("{\"path\":\"a/b\"}")));
        assertTrue(apostrophe.matches(mapper.readTree("{\"label\":\"it's ready\"}")));
    }

    @Test
    void treatsNonFiniteProgrammaticNumberAsNonMatchWithoutThrowing() throws Exception {
        JsonFilterExpression filter = JsonFilterExpression.parse("@.amount == 1e309");
        com.fasterxml.jackson.databind.node.ObjectNode candidate = mapper.createObjectNode();
        candidate.set("amount", DoubleNode.valueOf(Double.POSITIVE_INFINITY));

        assertFalse(assertDoesNotThrow(() -> filter.matches(candidate)));
    }

    @Test
    void requiresCandidateFieldsAndScalarValuesToMatch() throws Exception {
        JsonFilterExpression filter = JsonFilterExpression.parse("@.type == 'TARGET' && @.active == true");

        assertFalse(filter.matches(mapper.readTree("{\"type\":\"TARGET\"}")));
        assertFalse(filter.matches(mapper.readTree("[\"TARGET\",true]")));
        assertFalse(filter.matches(mapper.readTree("{\"type\":{\"value\":\"TARGET\"},\"active\":true}")));
    }

    @Test
    void rejectsMalformedOrUnsupportedExpressions() {
        assertThrows(IllegalArgumentException.class, () -> JsonFilterExpression.parse(""));
        assertThrows(IllegalArgumentException.class, () -> JsonFilterExpression.parse("@.type != 'TARGET'"));
        assertThrows(IllegalArgumentException.class, () -> JsonFilterExpression.parse("@.type == 'TARGET' || @.active == true"));
        assertThrows(IllegalArgumentException.class, () -> JsonFilterExpression.parse("@.type =="));
        assertThrows(IllegalArgumentException.class, () -> JsonFilterExpression.parse("@.nested.value == 'TARGET'"));
        assertThrows(IllegalArgumentException.class, () -> JsonFilterExpression.parse("@.type == []"));
    }

    @Test
    void rejectsMalformedUnicodeEscapes() {
        for (String escapedValue : java.util.List.of("u-041\"", "u0G41\"", "u123")) {
            String malformedUnicode = "@.label == \"" + "\\" + escapedValue;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> JsonFilterExpression.parse(malformedUnicode),
                    malformedUnicode
            );
        }
    }

    @Test
    void rejectsUnterminatedQuotedString() {
        assertThrows(IllegalArgumentException.class, () -> JsonFilterExpression.parse("@.label == 'TARGET"));
    }

    @Test
    void rejectsNonJsonNumberSyntaxAndWrongDelimiterEscapes() {
        for (String expression : java.util.List.of(
                "@.n == +1",
                "@.n == .5",
                "@.n == 1.",
                "@.n == 01",
                "@.s == \"a\\'b\"",
                "@.s == 'a\\\"b'",
                "@.s == \"line\nbreak\"",
                "@.s == 'tab\tvalue'"
        )) {
            assertThrows(IllegalArgumentException.class, () -> JsonFilterExpression.parse(expression), expression);
        }
    }
}
