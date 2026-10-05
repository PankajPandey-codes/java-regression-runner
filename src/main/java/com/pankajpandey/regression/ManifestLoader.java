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

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ManifestLoader {
    public static List<ManifestRow> load(Path manifestPath) throws IOException {
        List<ManifestRow> rows = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(manifestPath, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setTrim(true)
                     .setIgnoreEmptyLines(true)
                     .build()
                     .parse(reader)) {

            for (CSVRecord rec : parser) {
                rows.add(new ManifestRow(
                        rec.getRecordNumber() + 1,
                        get(rec, "apiName"),
                        get(rec, "apiInputsFile"),
                        get(rec, "toBeValidated"),
                        get(rec, "fileType"),
                        get(rec, "payloadJSON"),
                        get(rec, "expectedJSON"),
                        get(rec, "mysql"),
                        get(rec, "Module"),
                        get(rec, "consentPerson"),
                        get(rec, "BusinessFunction")
                ));
            }
        }
        return rows;
    }

    private static String get(CSVRecord rec, String key) {
        return rec.isMapped(key) ? rec.get(key).trim() : "";
    }
}
