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

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UrlResolverTest {

    @Test
    void buildTokenUrlUsesExplicitOverrideWhenPresent() {
        String url = UrlResolver.buildTokenUrl("https", "dev.example.com", "/identity/v1/token", "http://localhost:2021/identity/v1/token");
        assertEquals("http://localhost:2021/identity/v1/token", url);
    }

    @Test
    void buildApiUrlUsesServiceOverrideForPortfolio() {
        String url = UrlResolver.buildApiUrl(
                "https",
                "dev.example.com",
                Map.of("portfolio", "http://localhost:5200"),
                "/api/portfolio/v1/payments/settlements"
        );
        assertEquals("http://localhost:5200/api/portfolio/v1/payments/settlements", url);
    }

    @Test
    void buildApiUrlFallsBackToSharedServerWhenNoOverrideExists() {
        String url = UrlResolver.buildApiUrl(
                "https",
                "dev.example.com",
                Map.of("customer", "http://localhost:5400"),
                "/api/portfolio/v1/payments/settlements"
        );
        assertEquals("https://dev.example.com/api/portfolio/v1/payments/settlements", url);
    }

    @Test
    void buildApiUrlNormalizesMissingLeadingSlash() {
        String url = UrlResolver.buildApiUrl(
                "https",
                "dev.example.com",
                Map.of("portfolio", "http://localhost:5200"),
                "api/portfolio/v1/loanaccount/edit/7882"
        );
        assertEquals("http://localhost:5200/api/portfolio/v1/loanaccount/edit/7882", url);
    }

    @Test
    void buildApiUrlLeavesAbsoluteUrlsUntouched() {
        String url = UrlResolver.buildApiUrl(
                "https",
                "dev.example.com",
                Map.of("portfolio", "http://localhost:5200"),
                "http://localhost:5300/api/feeamortization/v1/feeamortization/amortize/5758"
        );
        assertEquals("http://localhost:5300/api/feeamortization/v1/feeamortization/amortize/5758", url);
    }

    @Test
    void parseServiceBaseUrlsUnderstandsMapStyleConfig() {
        Map<String, String> urls = Config.parseServiceBaseUrls(
                "portfolio=http://localhost:5200, feeamortization=http://localhost:5300, notifications=http://localhost:5700"
        );

        assertEquals("http://localhost:5200", urls.get("portfolio"));
        assertEquals("http://localhost:5300", urls.get("feeamortization"));
        assertEquals("http://localhost:5700", urls.get("notifications"));
    }
}
