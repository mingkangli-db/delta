/*
 * Copyright (2024) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.delta.kernel.internal;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import io.delta.kernel.types.StructType;

import io.delta.kernel.internal.actions.Metadata;
import io.delta.kernel.internal.actions.Protocol;
import io.delta.kernel.internal.util.ColumnMapping;
import io.delta.kernel.internal.util.Tuple2;
import static io.delta.kernel.internal.DeltaErrors.*;

/**
 * Contains utility methods related to the Delta table feature support in protocol.
 */
public class TableFeatures {

    private static final Set<String> SUPPORTED_WRITER_FEATURES =
            Collections.unmodifiableSet(new HashSet<String>() {{
                    add("appendOnly");
                    add("inCommitTimestamp-preview");
                }});

    ////////////////////
    // Helper Methods //
    ////////////////////

    public static void validateReadSupportedTable(
            Protocol protocol, Metadata metadata, String tablePath) {
        switch (protocol.getMinReaderVersion()) {
            case 1:
                break;
            case 2:
                ColumnMapping.throwOnUnsupportedColumnMappingMode(metadata);
                break;
            case 3:
                List<String> readerFeatures = protocol.getReaderFeatures();
                for (String readerFeature : readerFeatures) {
                    switch (readerFeature) {
                        case "columnMapping":
                            ColumnMapping.throwOnUnsupportedColumnMappingMode(metadata);
                            break;
                        case "deletionVectors": // fall through
                        case "timestampNtz": // fall through
                        case "vacuumProtocolCheck": // fall through
                        case "v2Checkpoint":
                            break;
                        default:
                            throw DeltaErrors.unsupportedReaderFeature(tablePath, readerFeature);
                    }
                }
                break;
            default:
                throw DeltaErrors.unsupportedReaderProtocol(
                    tablePath, protocol.getMinReaderVersion());
        }
    }

    /**
     * Utility method to validate whether the given table is supported for writing from Kernel.
     * Currently, the support is as follows:
     * <ul>
     *     <li>protocol writer version 1.</li>
     *     <li>protocol writer version 2 only with appendOnly feature enabled.</li>
     *     <li>protocol writer version 7 with {@code appendOnly}, {@code inCommitTimestamp-preview}
     *     feature enabled.</li>
     * </ul>
     *
     * @param protocol    Table protocol
     * @param metadata    Table metadata
     * @param tableSchema Table schema
     */
    public static void validateWriteSupportedTable(
            Protocol protocol,
            Metadata metadata,
            StructType tableSchema,
            String tablePath) {
        int minWriterVersion = protocol.getMinWriterVersion();
        switch (minWriterVersion) {
            case 1:
                break;
            case 2:
                // Append-only and column invariants are the writer features added in version 2
                // Append-only is supported, but not the invariants
                validateNoInvariants(tableSchema);
                break;
            case 3:
                // Check constraints are added in version 3
                throw unsupportedWriterProtocol(tablePath, minWriterVersion);
            case 4:
                // CDF and generated columns are writer features added in version 4
                throw unsupportedWriterProtocol(tablePath, minWriterVersion);
            case 5:
                // Column mapping is the only one writer feature added in version 5
                throw unsupportedWriterProtocol(tablePath, minWriterVersion);
            case 6:
                // Identity is the only one writer feature added in version 6
                throw unsupportedWriterProtocol(tablePath, minWriterVersion);
            case 7:
                for (String writerFeature : protocol.getWriterFeatures()) {
                    switch (writerFeature) {
                        // Only supported writer features as of today in Kernel
                        case "appendOnly":
                            break;
                        case "inCommitTimestamp-preview":
                            break;
                        default:
                            throw unsupportedWriterFeature(tablePath, writerFeature);
                    }
                }
                break;
            default:
                throw unsupportedWriterProtocol(tablePath, minWriterVersion);
        }
    }

    /**
     * Given the automatically enabled features from Delta table metadata, returns the minimum
     * required reader and writer version that satisfies all enabled table features in the metadata.
     *
     * @param enabledFeatures the automatically enabled features from the Delta table metadata
     * @return the minimum required reader and writer version that satisfies all enabled table
     */
    public static Tuple2<Integer, Integer> minProtocolVersionFromAutomaticallyEnabledFeatures(
            Set<String> enabledFeatures) {

        int readerVersion = 0;
        int writerVersion = 0;

        for (String feature : enabledFeatures) {
            readerVersion = Math.max(readerVersion, getMinReaderVersion(feature));
            writerVersion = Math.max(writerVersion, getMinWriterVersion(feature));
        }

        return new Tuple2<>(readerVersion, writerVersion);
    }

    /**
     * Extract the writer features that should be enabled automatically based on the metadata which
     * are not already enabled. For example, the {@code inCommitTimestamp-preview} feature should be
     * enabled when the delta property name (delta.enableInCommitTimestamps-preview) is set to true
     * in the metadata if it is not already enabled.
     *
     * @param metadata the metadata of the table
     * @return the writer features that should be enabled automatically
     */
    public static Set<String> extractAutomaticallyEnabledWriterFeatures(
            Metadata metadata, Protocol protocol) {
        return TableFeatures.SUPPORTED_WRITER_FEATURES.stream()
                .filter(f -> metadataRequiresWriterFeatureToBeEnabled(metadata, f))
                .filter(f -> protocol.getWriterFeatures() == null ||
                        !protocol.getWriterFeatures().contains(f))
                .collect(Collectors.toSet());
    }

    /**
     * Get the minimum reader version required for a feature.
     *
     * @param feature the feature
     * @return the minimum reader version required for the feature
     */
    private static int getMinReaderVersion(String feature) {
        switch (feature) {
            case "inCommitTimestamp-preview":
                return 3;
            default:
                return 1;
        }
    }

    /**
     * Get the minimum writer version required for a feature.
     *
     * @param feature the feature
     * @return the minimum writer version required for the feature
     */
    private static int getMinWriterVersion(String feature) {
        switch (feature) {
            case "inCommitTimestamp-preview":
                return 7;
            default:
                return 2;
        }
    }

    /**
     * Determine whether a writer feature must be supported and enabled to satisfy the metadata
     * requirements.
     *
     * @param metadata the table metadata
     * @param feature the writer feature to check
     * @return whether the writer feature must be enabled
     */
    private static boolean metadataRequiresWriterFeatureToBeEnabled(
            Metadata metadata, String feature) {
        switch (feature) {
            case "inCommitTimestamp-preview":
                return TableConfig.isICTEnabled(metadata);
            default:
                return false;
        }
    }

    private static void validateNoInvariants(StructType tableSchema) {
        boolean hasInvariants = tableSchema.fields().stream().anyMatch(
                field -> field.getMetadata().contains("delta.invariants"));
        if (hasInvariants) {
            throw columnInvariantsNotSupported();
        }
    }
}
