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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeAssertionEvaluatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void strictDecimalTreatsEquivalentScalesAsEqual() throws Exception {
        ScenarioContext context = new ScenarioContext();
        context.store("loan", 1, 200, 200, true, mapper.readTree("{\"bookedInterestUnpaid\":14.5800}"), null);
        var expectedStep = mapper.readTree("{\"assertions\":[{\"name\":\"ianp\",\"actualPath\":\"interestAccruedNotDueNotPaid\",\"expected\":\"{{loan.response.bookedInterestUnpaid}}\",\"type\":\"strictDecimal\"}]}");

        List<Map<String, Object>> mismatches = RuntimeAssertionEvaluator.evaluate(expectedStep, mapper.readTree("{\"interestAccruedNotDueNotPaid\":14.58}"), context);

        assertTrue(mismatches.isEmpty());

        RuntimeAssertionEvaluator.Evaluation evaluation = RuntimeAssertionEvaluator.evaluateWithTrace(
                expectedStep,
                mapper.readTree("{\"interestAccruedNotDueNotPaid\":14.58}"),
                context
        );
        assertEquals(1, evaluation.assertionResults().size());
        assertEquals("PASS", evaluation.assertionResults().get(0).get("status"));
        assertEquals("14.58", evaluation.assertionResults().get(0).get("resolvedActual"));
        assertEquals("14.58", evaluation.assertionResults().get(0).get("resolvedExpected"));
    }

    @Test
    void strictDecimalFailsDifferentValue() throws Exception {
        ScenarioContext context = new ScenarioContext();
        context.store("loan", 1, 200, 200, true, mapper.readTree("{\"bookedInterestUnpaid\":14.58}"), null);
        var expectedStep = mapper.readTree("{\"assertions\":[{\"name\":\"ianp\",\"actualPath\":\"interestAccruedNotDueNotPaid\",\"expected\":\"{{loan.response.bookedInterestUnpaid}}\",\"type\":\"strictDecimal\"}]}");

        List<Map<String, Object>> mismatches = RuntimeAssertionEvaluator.evaluate(expectedStep, mapper.readTree("{\"interestAccruedNotDueNotPaid\":14.59}"), context);

        assertEquals(1, mismatches.size());
        assertEquals("assertion_mismatch", mismatches.get(0).get("kind"));

        RuntimeAssertionEvaluator.Evaluation evaluation = RuntimeAssertionEvaluator.evaluateWithTrace(
                expectedStep,
                mapper.readTree("{\"interestAccruedNotDueNotPaid\":14.59}"),
                context
        );
        assertEquals(1, evaluation.assertionResults().size());
        assertEquals("FAIL", evaluation.assertionResults().get(0).get("status"));
        assertEquals("14.59", evaluation.assertionResults().get(0).get("resolvedActual"));
        assertEquals("14.58", evaluation.assertionResults().get(0).get("resolvedExpected"));
        assertEquals("assertion_mismatch", evaluation.assertionResults().get(0).get("failureKind"));
    }

    @Test
    void supportsSameResponsePathComparison() throws Exception {
        ScenarioContext context = new ScenarioContext();
        var expectedStep = mapper.readTree("{\"assertions\":[{\"name\":\"same\",\"actualPath\":\"a\",\"expectedPath\":\"b\",\"type\":\"string\"}]}");

        List<Map<String, Object>> mismatches = RuntimeAssertionEvaluator.evaluate(expectedStep, mapper.readTree("{\"a\":\"x\",\"b\":\"x\"}"), context);

        assertTrue(mismatches.isEmpty());
    }

    @Test
    void greaterThanOrEqualUsesReadableOperatorAndNumericType() throws Exception {
        ScenarioContext context = new ScenarioContext();
        var expectedStep = mapper.readTree("{\"assertions\":[{\"name\":\"non-negative\",\"actualPath\":\"amount\",\"operator\":\"greaterThanOrEqual\",\"expectedValue\":0,\"type\":\"strictDecimal\"}]}");

        RuntimeAssertionEvaluator.Evaluation evaluation = RuntimeAssertionEvaluator.evaluateWithTrace(expectedStep, mapper.readTree("{\"amount\":0}"), context);

        assertTrue(evaluation.mismatches().isEmpty());
        assertEquals(1, evaluation.assertionResults().size());
        assertEquals("greaterThanOrEqual", evaluation.assertionResults().get(0).get("operator"));
        assertEquals("strictDecimal", evaluation.assertionResults().get(0).get("type"));
        assertEquals("0", evaluation.assertionResults().get(0).get("resolvedActual"));
        assertEquals("0", evaluation.assertionResults().get(0).get("resolvedExpected"));
    }

    @Test
    void greaterThanOrEqualFailsWhenActualIsBelowExpected() throws Exception {
        ScenarioContext context = new ScenarioContext();
        var expectedStep = mapper.readTree("{\"assertions\":[{\"name\":\"non-negative\",\"actualPath\":\"amount\",\"operator\":\"greaterThanOrEqual\",\"expectedValue\":0,\"type\":\"strictDecimal\"}]}");

        RuntimeAssertionEvaluator.Evaluation evaluation = RuntimeAssertionEvaluator.evaluateWithTrace(expectedStep, mapper.readTree("{\"amount\":-1}"), context);

        assertEquals(1, evaluation.mismatches().size());
        assertEquals("assertion_mismatch", evaluation.mismatches().get(0).get("kind"));
        assertEquals("FAIL", evaluation.assertionResults().get(0).get("status"));
        assertEquals("greaterThanOrEqual", evaluation.assertionResults().get(0).get("operator"));
        assertEquals("-1", evaluation.assertionResults().get(0).get("resolvedActual"));
        assertEquals("0", evaluation.assertionResults().get(0).get("resolvedExpected"));
    }

    @Test
    void numericComparisonOperatorsCoverPassAndFailBranches() throws Exception {
        ScenarioContext context = new ScenarioContext();
        var expectedStep = mapper.readTree("{\"assertions\":["
                + "{\"name\":\"lt-pass\",\"actualPath\":\"ltPass\",\"operator\":\"lessThan\",\"expectedValue\":5,\"type\":\"integer\"},"
                + "{\"name\":\"lt-fail\",\"actualPath\":\"ltFail\",\"operator\":\"lessThan\",\"expectedValue\":5,\"type\":\"integer\"},"
                + "{\"name\":\"lte-pass\",\"actualPath\":\"ltePass\",\"operator\":\"lessThanOrEqual\",\"expectedValue\":5,\"type\":\"integer\"},"
                + "{\"name\":\"lte-fail\",\"actualPath\":\"lteFail\",\"operator\":\"lessThanOrEqual\",\"expectedValue\":5,\"type\":\"integer\"},"
                + "{\"name\":\"gt-pass\",\"actualPath\":\"gtPass\",\"operator\":\"greaterThan\",\"expectedValue\":5,\"type\":\"integer\"},"
                + "{\"name\":\"gt-fail\",\"actualPath\":\"gtFail\",\"operator\":\"greaterThan\",\"expectedValue\":5,\"type\":\"integer\"}"
                + "]}");

        RuntimeAssertionEvaluator.Evaluation evaluation = RuntimeAssertionEvaluator.evaluateWithTrace(
                expectedStep,
                mapper.readTree("{\"ltPass\":4,\"ltFail\":5,\"ltePass\":5,\"lteFail\":6,\"gtPass\":6,\"gtFail\":5}"),
                context
        );

        assertEquals(3, evaluation.mismatches().size());
        assertEquals(6, evaluation.assertionResults().size());
        assertEquals("PASS", evaluation.assertionResults().get(0).get("status"));
        assertEquals("lessThan", evaluation.assertionResults().get(0).get("operator"));
        assertEquals("FAIL", evaluation.assertionResults().get(1).get("status"));
        assertEquals("lessThan", evaluation.assertionResults().get(1).get("operator"));
        assertEquals("PASS", evaluation.assertionResults().get(2).get("status"));
        assertEquals("lessThanOrEqual", evaluation.assertionResults().get(2).get("operator"));
        assertEquals("FAIL", evaluation.assertionResults().get(3).get("status"));
        assertEquals("lessThanOrEqual", evaluation.assertionResults().get(3).get("operator"));
        assertEquals("PASS", evaluation.assertionResults().get(4).get("status"));
        assertEquals("greaterThan", evaluation.assertionResults().get(4).get("operator"));
        assertEquals("FAIL", evaluation.assertionResults().get(5).get("status"));
        assertEquals("greaterThan", evaluation.assertionResults().get(5).get("operator"));
    }

    @Test
    void actualPathsSumUsesExplicitAggregationAndReportsExpression() throws Exception {
        ScenarioContext context = new ScenarioContext();
        var expectedStep = mapper.readTree("{\"assertions\":[{\"name\":\"sum\",\"actualPaths\":[\"paid\",\"unpaid\"],\"actualAggregation\":\"sum\",\"operator\":\"equals\",\"expectedPath\":\"booked\",\"type\":\"strictDecimal\"}]}");

        RuntimeAssertionEvaluator.Evaluation evaluation = RuntimeAssertionEvaluator.evaluateWithTrace(expectedStep, mapper.readTree("{\"paid\":\"3.25\",\"unpaid\":\"1.75\",\"booked\":\"5.00\"}"), context);

        assertTrue(evaluation.mismatches().isEmpty());
        assertEquals("actualPaths", evaluation.assertionResults().get(0).get("actualSource"));
        assertEquals("sum(paid, unpaid)", evaluation.assertionResults().get(0).get("actualPath"));
        assertEquals("5.00", evaluation.assertionResults().get(0).get("resolvedActual"));
        assertEquals("5.00", evaluation.assertionResults().get(0).get("resolvedExpected"));
    }

    @Test
    void actualPathsRequiresSumAggregation() throws Exception {
        ScenarioContext context = new ScenarioContext();
        var expectedStep = mapper.readTree("{\"assertions\":[{\"name\":\"sum\",\"actualPaths\":[\"paid\",\"unpaid\"],\"operator\":\"equals\",\"expectedPath\":\"booked\",\"type\":\"strictDecimal\"}]}");

        RuntimeAssertionEvaluator.Evaluation evaluation = RuntimeAssertionEvaluator.evaluateWithTrace(expectedStep, mapper.readTree("{\"paid\":1,\"unpaid\":2,\"booked\":3}"), context);

        assertEquals(1, evaluation.mismatches().size());
        assertEquals("assertion_config_error", evaluation.mismatches().get(0).get("kind"));
        assertEquals("FAIL", evaluation.assertionResults().get(0).get("status"));
    }

    @Test
    void actualSourcesAreMutuallyExclusive() throws Exception {
        ScenarioContext context = new ScenarioContext();
        var expectedStep = mapper.readTree("{\"assertions\":[{\"name\":\"bad\",\"actualPath\":\"paid\",\"actualPaths\":[\"paid\",\"unpaid\"],\"actualAggregation\":\"sum\",\"operator\":\"equals\",\"expectedPath\":\"booked\",\"type\":\"strictDecimal\"}]}");

        RuntimeAssertionEvaluator.Evaluation evaluation = RuntimeAssertionEvaluator.evaluateWithTrace(expectedStep, mapper.readTree("{\"paid\":1,\"unpaid\":2,\"booked\":3}"), context);

        assertEquals(1, evaluation.mismatches().size());
        assertEquals("assertion_config_error", evaluation.mismatches().get(0).get("kind"));
        assertEquals("2", evaluation.mismatches().get(0).get("actual"));
    }

    @Test
    void notEmptyIsUnaryAndRejectsExpectedSource() throws Exception {
        ScenarioContext context = new ScenarioContext();
        var passingStep = mapper.readTree("{\"assertions\":[{\"name\":\"name-present\",\"actualPath\":\"borrowerName\",\"operator\":\"notEmpty\"}]}");
        var invalidStep = mapper.readTree("{\"assertions\":[{\"name\":\"name-present\",\"actualPath\":\"borrowerName\",\"operator\":\"notEmpty\",\"expectedValue\":\"x\"}]}");

        RuntimeAssertionEvaluator.Evaluation passing = RuntimeAssertionEvaluator.evaluateWithTrace(passingStep, mapper.readTree("{\"borrowerName\":\"Pankaj\"}"), context);
        RuntimeAssertionEvaluator.Evaluation invalid = RuntimeAssertionEvaluator.evaluateWithTrace(invalidStep, mapper.readTree("{\"borrowerName\":\"Pankaj\"}"), context);

        assertTrue(passing.mismatches().isEmpty());
        assertEquals("notEmpty", passing.assertionResults().get(0).get("operator"));
        assertEquals(1, invalid.mismatches().size());
        assertEquals("assertion_config_error", invalid.mismatches().get(0).get("kind"));
    }

    @Test
    void notNullPassesEmptyArrayAndFailsJsonNull() throws Exception {
        ScenarioContext context = new ScenarioContext();
        var expectedStep = mapper.readTree("{\"assertions\":["
                + "{\"name\":\"items-present\",\"actualPath\":\"items\",\"operator\":\"notNull\"},"
                + "{\"name\":\"optional-present\",\"actualPath\":\"optional\",\"operator\":\"notNull\"}"
                + "]}");

        RuntimeAssertionEvaluator.Evaluation evaluation = RuntimeAssertionEvaluator.evaluateWithTrace(expectedStep, mapper.readTree("{\"items\":[],\"optional\":null}"), context);

        assertEquals(1, evaluation.mismatches().size());
        assertEquals("PASS", evaluation.assertionResults().get(0).get("status"));
        assertTrue((Boolean) evaluation.assertionResults().get(0).get("passed"));
        assertEquals("notNull", evaluation.assertionResults().get(0).get("operator"));
        assertEquals("FAIL", evaluation.assertionResults().get(1).get("status"));
        assertFalse((Boolean) evaluation.assertionResults().get(1).get("passed"));
        assertEquals("assertion_mismatch", evaluation.assertionResults().get(1).get("failureKind"));
    }

    @Test
    void notEmptyFailsBlankStringsAndEmptyArrays() throws Exception {
        ScenarioContext context = new ScenarioContext();
        var expectedStep = mapper.readTree("{\"assertions\":[{\"name\":\"name-present\",\"actualPath\":\"borrowerName\",\"operator\":\"notEmpty\"},{\"name\":\"installments-present\",\"actualPath\":\"mergedRepaymentPeriodInstallments\",\"operator\":\"notEmpty\"}]}");

        RuntimeAssertionEvaluator.Evaluation evaluation = RuntimeAssertionEvaluator.evaluateWithTrace(expectedStep, mapper.readTree("{\"borrowerName\":\" \",\"mergedRepaymentPeriodInstallments\":[]}"), context);

        assertEquals(2, evaluation.mismatches().size());
        assertFalse((Boolean) evaluation.assertionResults().get(0).get("passed"));
        assertFalse((Boolean) evaluation.assertionResults().get(1).get("passed"));
    }

    @Test
    void numericOperatorsRequireNumericType() throws Exception {
        ScenarioContext context = new ScenarioContext();
        var expectedStep = mapper.readTree("{\"assertions\":[{\"name\":\"bad-type\",\"actualPath\":\"amount\",\"operator\":\"greaterThanOrEqual\",\"expectedValue\":0,\"type\":\"string\"}]}");

        RuntimeAssertionEvaluator.Evaluation evaluation = RuntimeAssertionEvaluator.evaluateWithTrace(expectedStep, mapper.readTree("{\"amount\":\"10\"}"), context);

        assertEquals(1, evaluation.mismatches().size());
        assertEquals("assertion_config_error", evaluation.mismatches().get(0).get("kind"));
    }

    @Test
    void notEqualsUsesConfiguredValueType() throws Exception {
        ScenarioContext context = new ScenarioContext();
        var expectedStep = mapper.readTree("{\"assertions\":[{\"name\":\"changed\",\"actualPath\":\"amount\",\"operator\":\"notEquals\",\"expectedValue\":\"10.00\",\"type\":\"strictDecimal\"}]}");

        RuntimeAssertionEvaluator.Evaluation evaluation = RuntimeAssertionEvaluator.evaluateWithTrace(expectedStep, mapper.readTree("{\"amount\":\"11.00\"}"), context);

        assertTrue(evaluation.mismatches().isEmpty());
        assertEquals("notEquals", evaluation.assertionResults().get(0).get("operator"));
        assertEquals("11.00", evaluation.assertionResults().get(0).get("resolvedActual"));
        assertEquals("10.00", evaluation.assertionResults().get(0).get("resolvedExpected"));
    }

    @Test
    void missingActualPathFails() throws Exception {
        ScenarioContext context = new ScenarioContext();
        var expectedStep = mapper.readTree("{\"assertions\":[{\"name\":\"missing\",\"actualPath\":\"missing\",\"expectedValue\":\"x\",\"type\":\"string\"}]}");

        List<Map<String, Object>> mismatches = RuntimeAssertionEvaluator.evaluate(expectedStep, mapper.readTree("{\"a\":\"x\"}"), context);

        assertEquals(1, mismatches.size());
        assertEquals("missing_reference", mismatches.get(0).get("kind"));

        RuntimeAssertionEvaluator.Evaluation evaluation = RuntimeAssertionEvaluator.evaluateWithTrace(expectedStep, mapper.readTree("{\"a\":\"x\"}"), context);
        assertEquals(1, evaluation.assertionResults().size());
        assertEquals("FAIL", evaluation.assertionResults().get(0).get("status"));
        assertEquals("", evaluation.assertionResults().get(0).get("resolvedActual"));
        assertEquals("x", evaluation.assertionResults().get(0).get("resolvedExpected"));
        assertEquals("missing_reference", evaluation.assertionResults().get(0).get("failureKind"));
    }

    @Test
    void unsupportedOperatorProducesAssertionTrace() throws Exception {
        ScenarioContext context = new ScenarioContext();
        var expectedStep = mapper.readTree("{\"assertions\":[{\"name\":\"greater-than\",\"actualPath\":\"a\",\"expectedValue\":1,\"operator\":\"gt\",\"type\":\"integer\"}]}");

        RuntimeAssertionEvaluator.Evaluation evaluation = RuntimeAssertionEvaluator.evaluateWithTrace(expectedStep, mapper.readTree("{\"a\":2}"), context);

        assertEquals(1, evaluation.mismatches().size());
        assertEquals("assertion_config_error", evaluation.mismatches().get(0).get("kind"));
        assertEquals(1, evaluation.assertionResults().size());
        assertEquals("FAIL", evaluation.assertionResults().get(0).get("status"));
        assertEquals("gt", evaluation.assertionResults().get(0).get("operator"));
        assertEquals("assertion_config_error", evaluation.assertionResults().get(0).get("failureKind"));
    }
}
