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

package org.apache.iceberg.flink.sink;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.ManifestWriter;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.io.WriteResult;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;

class FlinkManifestUtil {
  private static final int FORMAT_V2 = 2;
  private static final Long DUMMY_SNAPSHOT_ID = 0L;

  private FlinkManifestUtil() {
  }

  static ManifestFile writeDataFiles(OutputFile outputFile, PartitionSpec spec, List<DataFile> dataFiles)
      throws IOException {
    ManifestWriter<DataFile> writer = ManifestFiles.write(FORMAT_V2, spec, outputFile, DUMMY_SNAPSHOT_ID);

    try (ManifestWriter<DataFile> closeableWriter = writer) {
      closeableWriter.addAll(dataFiles);
    }

    return writer.toManifestFile();
  }

  static ManifestFile writeDeleteFiles(OutputFile outputFile, PartitionSpec spec, List<DeleteFile> deleteFiles)
      throws IOException {
    ManifestWriter<DeleteFile> writer = ManifestFiles.writeDeleteManifest(FORMAT_V2, spec, outputFile,
        DUMMY_SNAPSHOT_ID);

    try (ManifestWriter<DeleteFile> closeableWriter = writer) {
      closeableWriter.addAll(deleteFiles);
    }
    return writer.toManifestFile();
  }

  static ManifestFile referenceDataFiles(OutputFile outputFile,
                                         long snapshotId,
                                         long sequenceNumber,
                                         PartitionSpec spec,
                                         List<DataFile> dataFiles) throws IOException {
    ManifestWriter<DataFile> writer = ManifestFiles.write(FORMAT_V2, spec, outputFile, DUMMY_SNAPSHOT_ID);

    try (ManifestWriter<DataFile> closeableWriter = writer) {
      dataFiles.forEach(datafile -> closeableWriter.existing(datafile, snapshotId, sequenceNumber));
    }

    return writer.toManifestFile();
  }

  static ManifestFile referenceDeleteFiles(OutputFile outputFile,
                                           long snapshotId,
                                           long sequenceNumber,
                                           PartitionSpec spec,
                                           List<DeleteFile> deleteFiles) throws IOException {
    ManifestWriter<DeleteFile> writer = ManifestFiles.writeDeleteManifest(FORMAT_V2, spec, outputFile,
        DUMMY_SNAPSHOT_ID);

    try (ManifestWriter<DeleteFile> closeableWriter = writer) {
      deleteFiles.forEach(deleteFile -> closeableWriter.existing(deleteFile, snapshotId, sequenceNumber));
    }

    return writer.toManifestFile();
  }

  static List<DataFile> readDataFiles(ManifestFile manifestFile, FileIO io) throws IOException {
    try (CloseableIterable<DataFile> dataFiles = ManifestFiles.read(manifestFile, io)) {
      return Lists.newArrayList(dataFiles);
    }
  }

  static List<DeleteFile> readDeleteFiles(ManifestFile manifestFile, FileIO io) throws IOException {
    try (CloseableIterable<DeleteFile> deleteFiles = ManifestFiles.readDeleteManifest(manifestFile, io, null)) {
      return Lists.newArrayList(deleteFiles);
    }

  }

  static ManifestOutputFileFactory createOutputFileFactory(Table table, String flinkJobId, int subTaskId,
                                                           long attemptNumber) {
    TableOperations ops = ((HasTableOperations) table).operations();
    return new ManifestOutputFileFactory(ops, table.io(), table.properties(), flinkJobId, subTaskId, attemptNumber);
  }

  static DeltaManifests writeCompletedFiles(WriteResult result,
                                            Supplier<OutputFile> outputFileSupplier,
                                            PartitionSpec spec) throws IOException {

    ManifestFile dataManifest = null;
    ManifestFile deleteManifest = null;

    // Write the completed data files into a newly created data manifest file.
    if (result.dataFiles() != null && result.dataFiles().length > 0) {
      dataManifest = writeDataFiles(outputFileSupplier.get(), spec, Lists.newArrayList(result.dataFiles()));
    }

    // Write the completed delete files into a newly created delete manifest file.
    if (result.deleteFiles() != null && result.deleteFiles().length > 0) {
      deleteManifest = writeDeleteFiles(outputFileSupplier.get(), spec, Lists.newArrayList(result.deleteFiles()));
    }

    return new DeltaManifests(dataManifest, deleteManifest, result.referencedDataFiles());
  }

  static DeltaManifests writeExistingFiles(long snapshotId,
                                           long sequenceNumber,
                                           WriteResult result,
                                           Supplier<OutputFile> outputFileSupplier,
                                           PartitionSpec spec) throws IOException {

    ManifestFile dataManifest = null;
    ManifestFile deleteManifest = null;

    // Write the exists data files into a newly created data manifest file.
    if (result.dataFiles() != null && result.dataFiles().length > 0) {
      dataManifest = referenceDataFiles(outputFileSupplier.get(), snapshotId, sequenceNumber, spec,
          Lists.newArrayList(result.dataFiles()));
    }

    // Write the exists delete files into a newly created delete manifest file.
    if (result.deleteFiles() != null && result.deleteFiles().length > 0) {
      deleteManifest = referenceDeleteFiles(outputFileSupplier.get(), snapshotId, sequenceNumber, spec,
          Lists.newArrayList(result.deleteFiles()));
    }

    return new DeltaManifests(dataManifest, deleteManifest, result.referencedDataFiles());
  }

  static WriteResult readCompletedFiles(DeltaManifests deltaManifests, FileIO io) throws IOException {
    WriteResult.Builder builder = WriteResult.builder();

    // Read the completed data files from persisted data manifest file.
    if (deltaManifests.dataManifest() != null) {
      builder.addDataFiles(readDataFiles(deltaManifests.dataManifest(), io));
    }

    // Read the completed delete files from persisted delete manifests file.
    if (deltaManifests.deleteManifest() != null) {
      builder.addDeleteFiles(readDeleteFiles(deltaManifests.deleteManifest(), io));
    }

    return builder.addReferencedDataFiles(deltaManifests.referencedDataFiles())
        .build();
  }
}
