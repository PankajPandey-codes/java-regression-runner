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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateResolverTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void resolveStringReplacesToken() throws Exception {
        ScenarioContext context = context("{\"loanAccountNumber\":\"LN1\"}");

        Resolution<String> value = TemplateResolver.resolveString("/loan/{{createLoan.response.loanAccountNumber}}/approve", context);

        assertFalse(value.hasErrors());
        assertEquals("/loan/LN1/approve", value.value());
    }

    @Test
    void resolveNodePreservesWholeTokenType() throws Exception {
        ScenarioContext context = context("{\"identifier\":12345}");
        JsonNode payload = mapper.readTree("{\"loanId\":\"{{createLoan.response.identifier}}\"}");

        Resolution<JsonNode> value = TemplateResolver.resolveNode(payload, context);

        assertFalse(value.hasErrors());
        assertTrue(value.value().get("loanId").isInt());
        assertEquals(12345, value.value().get("loanId").asInt());
    }

    @Test
    void filteredWholeTokensPreserveNumericTypeAtEveryPayloadDepth() throws Exception {
        ScenarioContext context = context(
                "[{\"type\":\"OTHER\",\"active\":true,\"id\":8},"
                        + "{\"type\":\"TARGET\",\"active\":true,\"id\":7}]"
        );
        String reference = "{{createLoan.response[?(@.type == 'TARGET' && @.active == true)].id}}";
        JsonNode payload = mapper.readTree(
                "{\"outerId\":\"" + reference + "\",\"details\":{\"innerId\":\"" + reference + "\"}}"
        );

        Resolution<JsonNode> value = TemplateResolver.resolveNode(payload, context);

        assertFalse(value.hasErrors());
        assertTrue(value.value().path("outerId").isInt());
        assertEquals(7, value.value().path("outerId").asInt());
        assertTrue(value.value().path("details").path("innerId").isInt());
        assertEquals(7, value.value().path("details").path("innerId").asInt());
    }

    @Test
    void embeddedTokenStaysString() throws Exception {
        ScenarioContext context = context("{\"identifier\":12345}");
        JsonNode payload = mapper.readTree("{\"externalId\":\"loan-{{createLoan.response.identifier}}\"}");

        Resolution<JsonNode> value = TemplateResolver.resolveNode(payload, context);

        assertFalse(value.hasErrors());
        assertEquals("loan-12345", value.value().get("externalId").asText());
    }

    @Test
    void missingTokenReturnsNullValueAndErrors() throws Exception {
        ScenarioContext context = context("{\"identifier\":12345}");

        Resolution<String> value = TemplateResolver.resolveString("{{createLoan.response.missing}}", context);

        assertTrue(value.hasErrors());
        assertNull(value.value());
    }

    @Test
    void currentShorthandIsRejected() throws Exception {
        ScenarioContext context = context("{\"identifier\":12345}");

        Resolution<String> value = TemplateResolver.resolveString("{{current.response.identifier}}", context);

        assertTrue(value.hasErrors());
        assertEquals("malformed_reference", value.errors().get(0).get("kind"));
    }

    @Test
    void resolveSqlTemplateWithNoTokensSucceedsTrivially() throws Exception {
        ScenarioContext context = context("{\"identifier\":12345}");

        Resolution<TemplateResolver.SqlTemplate> value =
                TemplateResolver.resolveSqlTemplate("SELECT * FROM loan", context);

        assertFalse(value.hasErrors());
        assertEquals("SELECT * FROM loan", value.value().sql());
        assertTrue(value.value().params().isEmpty());
    }

    @Test
    void resolveSqlTemplateBindsScalarToSinglePlaceholder() throws Exception {
        ScenarioContext context = context("{\"identifier\":12345}");

        Resolution<TemplateResolver.SqlTemplate> value = TemplateResolver.resolveSqlTemplate(
                "SELECT * FROM loan WHERE id = {{createLoan.response.identifier}}", context);

        assertFalse(value.hasErrors());
        assertEquals("SELECT * FROM loan WHERE id = ?", value.value().sql());
        assertEquals(1, value.value().params().size());
        assertEquals(12345, value.value().params().get(0).asInt());
    }

    @Test
    void resolveSqlTemplateBindsTextScalar() throws Exception {
        ScenarioContext context = context("{\"loanAccountNumber\":\"LN1\"}");

        Resolution<TemplateResolver.SqlTemplate> value = TemplateResolver.resolveSqlTemplate(
                "SELECT * FROM loan WHERE account_no = {{createLoan.response.loanAccountNumber}}", context);

        assertFalse(value.hasErrors());
        assertEquals("SELECT * FROM loan WHERE account_no = ?", value.value().sql());
        assertEquals("LN1", value.value().params().get(0).asText());
    }

    @Test
    void resolveSqlTemplateBindsBooleanScalar() throws Exception {
        ScenarioContext context = context("{\"active\":true}");

        Resolution<TemplateResolver.SqlTemplate> value = TemplateResolver.resolveSqlTemplate(
                "SELECT * FROM loan WHERE active = {{createLoan.response.active}}", context);

        assertFalse(value.hasErrors());
        assertEquals("SELECT * FROM loan WHERE active = ?", value.value().sql());
        assertTrue(value.value().params().get(0).asBoolean());
    }

    @Test
    void resolveSqlTemplateExpandsArrayOfScalarsToMultiplePlaceholders() throws Exception {
        ScenarioContext context = context("{\"ids\":[1,2,3]}");

        Resolution<TemplateResolver.SqlTemplate> value = TemplateResolver.resolveSqlTemplate(
                "SELECT * FROM loan WHERE id IN ({{createLoan.response.ids}})", context);

        assertFalse(value.hasErrors());
        assertEquals("SELECT * FROM loan WHERE id IN (?,?,?)", value.value().sql());
        assertEquals(3, value.value().params().size());
        assertEquals(1, value.value().params().get(0).asInt());
        assertEquals(2, value.value().params().get(1).asInt());
        assertEquals(3, value.value().params().get(2).asInt());
    }

    @Test
    void resolveSqlTemplateRejectsEmptyArray() throws Exception {
        ScenarioContext context = context("{\"ids\":[]}");

        Resolution<TemplateResolver.SqlTemplate> value = TemplateResolver.resolveSqlTemplate(
                "SELECT * FROM loan WHERE id IN ({{createLoan.response.ids}})", context);

        assertTrue(value.hasErrors());
        assertNull(value.value());
        assertEquals("empty_array_sql_value", value.errors().get(0).get("kind"));
    }

    @Test
    void resolveSqlTemplateRejectsArrayContainingObject() throws Exception {
        ScenarioContext context = context("{\"ids\":[1,{\"nested\":true}]}");

        Resolution<TemplateResolver.SqlTemplate> value = TemplateResolver.resolveSqlTemplate(
                "SELECT * FROM loan WHERE id IN ({{createLoan.response.ids}})", context);

        assertTrue(value.hasErrors());
        assertNull(value.value());
        assertEquals("non_scalar_sql_value", value.errors().get(0).get("kind"));
    }

    @Test
    void resolveSqlTemplateRejectsArrayContainingArray() throws Exception {
        ScenarioContext context = context("{\"ids\":[1,[2,3]]}");

        Resolution<TemplateResolver.SqlTemplate> value = TemplateResolver.resolveSqlTemplate(
                "SELECT * FROM loan WHERE id IN ({{createLoan.response.ids}})", context);

        assertTrue(value.hasErrors());
        assertEquals("non_scalar_sql_value", value.errors().get(0).get("kind"));
    }

    @Test
    void resolveSqlTemplateRejectsArrayContainingNull() throws Exception {
        ScenarioContext context = context("{\"ids\":[1,null]}");

        Resolution<TemplateResolver.SqlTemplate> value = TemplateResolver.resolveSqlTemplate(
                "SELECT * FROM loan WHERE id IN ({{createLoan.response.ids}})", context);

        assertTrue(value.hasErrors());
        assertEquals("non_scalar_sql_value", value.errors().get(0).get("kind"));
    }

    @Test
    void resolveSqlTemplateRejectsNullValue() throws Exception {
        ScenarioContext context = context("{\"identifier\":null}");

        Resolution<TemplateResolver.SqlTemplate> value = TemplateResolver.resolveSqlTemplate(
                "SELECT * FROM loan WHERE id = {{createLoan.response.identifier}}", context);

        assertTrue(value.hasErrors());
        assertEquals("null_sql_value", value.errors().get(0).get("kind"));
    }

    @Test
    void resolveSqlTemplateRejectsObjectValue() throws Exception {
        ScenarioContext context = context("{\"loan\":{\"id\":1}}");

        Resolution<TemplateResolver.SqlTemplate> value = TemplateResolver.resolveSqlTemplate(
                "SELECT * FROM loan WHERE id = {{createLoan.response.loan}}", context);

        assertTrue(value.hasErrors());
        assertEquals("non_scalar_sql_value", value.errors().get(0).get("kind"));
    }

    @Test
    void resolveSqlTemplateRejectsQuotedToken() throws Exception {
        ScenarioContext context = context("{\"loanAccountNumber\":\"LN1\"}");

        Resolution<TemplateResolver.SqlTemplate> value = TemplateResolver.resolveSqlTemplate(
                "SELECT * FROM loan WHERE account_no = '{{createLoan.response.loanAccountNumber}}'", context);

        assertTrue(value.hasErrors());
        assertNull(value.value());
        assertEquals("quoted_sql_token", value.errors().get(0).get("kind"));
    }

    @Test
    void resolveSqlTemplateRejectsTokenEmbeddedInsideLikePattern() throws Exception {
        ScenarioContext context = context("{\"loanAccountNumber\":\"LN1\"}");

        Resolution<TemplateResolver.SqlTemplate> value = TemplateResolver.resolveSqlTemplate(
                "SELECT * FROM loan WHERE account_no LIKE '%{{createLoan.response.loanAccountNumber}}%'", context);

        assertTrue(value.hasErrors());
        assertNull(value.value());
        assertEquals("quoted_sql_token", value.errors().get(0).get("kind"));
    }

    @Test
    void resolveSqlTemplateRejectsDoubleQuotedToken() throws Exception {
        ScenarioContext context = context("{\"loanAccountNumber\":\"LN1\"}");

        Resolution<TemplateResolver.SqlTemplate> value = TemplateResolver.resolveSqlTemplate(
                "SELECT * FROM loan WHERE account_no = \"{{createLoan.response.loanAccountNumber}}\"", context);

        assertTrue(value.hasErrors());
        assertNull(value.value());
        assertEquals("quoted_sql_token", value.errors().get(0).get("kind"));
    }

    @Test
    void resolveSqlTemplateAcceptsTokenAfterClosedStringLiteralsInSelectList() throws Exception {
        ScenarioContext context = context("{\"identifier\":12345}");

        Resolution<TemplateResolver.SqlTemplate> value = TemplateResolver.resolveSqlTemplate(
                "select JSON_ARRAYAGG(JSON_OBJECT('loan_id',loan_id,'amount',amount)) as actualDBResult"
                        + " from fa_fee_amort_master where loan_id = {{createLoan.response.identifier}}",
                context);

        assertFalse(value.hasErrors());
        assertEquals(
                "select JSON_ARRAYAGG(JSON_OBJECT('loan_id',loan_id,'amount',amount)) as actualDBResult"
                        + " from fa_fee_amort_master where loan_id = ?",
                value.value().sql());
        assertEquals(1, value.value().params().size());
    }

    @Test
    void resolveSqlTemplateAcceptsTokenAfterMysqlDoubledQuoteEscape() throws Exception {
        ScenarioContext context = context("{\"identifier\":12345}");

        Resolution<TemplateResolver.SqlTemplate> value = TemplateResolver.resolveSqlTemplate(
                "select * from loan where note = 'it''s fine' and id = {{createLoan.response.identifier}}", context);

        assertFalse(value.hasErrors());
        assertEquals(1, value.value().params().size());
    }

    @Test
    void resolveSqlTemplateRejectsTokenAfterUnbalancedQuote() throws Exception {
        ScenarioContext context = context("{\"identifier\":12345}");

        Resolution<TemplateResolver.SqlTemplate> value = TemplateResolver.resolveSqlTemplate(
                "select * from loan where note = 'unterminated and id = {{createLoan.response.identifier}}", context);

        assertTrue(value.hasErrors());
        assertEquals("quoted_sql_token", value.errors().get(0).get("kind"));
    }

    @Test
    void resolveSqlTemplateQuotedTokenErrorReportsTheOffendingLiteralNotAFabricatedOne() throws Exception {
        ScenarioContext context = context("{\"loanAccountNumber\":\"LN1\"}");

        Resolution<TemplateResolver.SqlTemplate> value = TemplateResolver.resolveSqlTemplate(
                "SELECT * FROM loan WHERE account_no LIKE '%{{createLoan.response.loanAccountNumber}}%'", context);

        assertTrue(value.hasErrors());
        Map<String, Object> error = value.errors().get(0);
        assertEquals("quoted_sql_token", error.get("kind"));
        assertTrue(
                String.valueOf(error.get("actual")).contains("%"),
                "Must surface the surrounding literal (the wildcards are the actual problem), was: "
                        + error.get("actual"));
        assertTrue(
                String.valueOf(error.get("expected")).contains("CONCAT"),
                "Remedy must be valid SQL for a LIKE, was: " + error.get("expected"));
    }

    @Test
    void resolveSqlTemplateAcceptsTokenAfterEscapedQuoteInsideStringLiteral() throws Exception {
        ScenarioContext context = context("{\"identifier\":12345}");

        Resolution<TemplateResolver.SqlTemplate> value = TemplateResolver.resolveSqlTemplate(
                "select * from loan where note = 'it\\'s fine' and id = {{createLoan.response.identifier}}", context);

        assertFalse(value.hasErrors());
        assertEquals(1, value.value().params().size());
    }

    @Test
    void resolveSqlTemplateQuotedTokenRejectedEvenWhenReferenceIsMissing() throws Exception {
        ScenarioContext context = context("{\"identifier\":12345}");

        Resolution<TemplateResolver.SqlTemplate> value = TemplateResolver.resolveSqlTemplate(
                "SELECT * FROM loan WHERE id = '{{createLoan.response.missing}}'", context);

        assertTrue(value.hasErrors());
        assertEquals("quoted_sql_token", value.errors().get(0).get("kind"));
    }

    @Test
    void resolveSqlTemplateFailsAllOrNothingWhenOneTokenIsInvalid() throws Exception {
        ScenarioContext context = context("{\"identifier\":12345,\"loan\":{\"id\":1}}");

        Resolution<TemplateResolver.SqlTemplate> value = TemplateResolver.resolveSqlTemplate(
                "SELECT * FROM loan WHERE id = {{createLoan.response.identifier}} AND meta = {{createLoan.response.loan}}",
                context);

        assertTrue(value.hasErrors());
        assertNull(value.value());
        assertEquals("non_scalar_sql_value", value.errors().get(0).get("kind"));
    }

    @Test
    void resolveSqlTemplatePreservesLeftToRightParamOrder() throws Exception {
        ScenarioContext context = context("{\"identifier\":12345,\"loanAccountNumber\":\"LN1\"}");

        Resolution<TemplateResolver.SqlTemplate> value = TemplateResolver.resolveSqlTemplate(
                "SELECT * FROM loan WHERE account_no = {{createLoan.response.loanAccountNumber}} AND id = {{createLoan.response.identifier}}",
                context);

        assertFalse(value.hasErrors());
        assertEquals("SELECT * FROM loan WHERE account_no = ? AND id = ?", value.value().sql());
        assertEquals("LN1", value.value().params().get(0).asText());
        assertEquals(12345, value.value().params().get(1).asInt());
    }

    @Test
    void resolveSqlTemplateMissingReferenceBubblesExistingErrorKind() throws Exception {
        ScenarioContext context = context("{\"identifier\":12345}");

        Resolution<TemplateResolver.SqlTemplate> value = TemplateResolver.resolveSqlTemplate(
                "SELECT * FROM loan WHERE id = {{createLoan.response.missing}}", context);

        assertTrue(value.hasErrors());
        assertEquals("missing_reference", value.errors().get(0).get("kind"));
    }

    private ScenarioContext context(String responseJson) throws Exception {
        ScenarioContext context = new ScenarioContext();
        context.store("createLoan", 1, 200, 200, true, mapper.readTree(responseJson), null);
        return context;
    }
}
