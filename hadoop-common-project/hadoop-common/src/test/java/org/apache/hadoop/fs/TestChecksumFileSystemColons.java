/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.test.GenericTestUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for HADOOP-17845: ChecksumFileSystem must construct .crc sidecar
 * paths safely for filenames that contain colons.
 *
 * A colon is legal in a filename on Linux/macOS but is a URI scheme
 * delimiter, so naively constructing {@code new Path(parentStr + "/." +
 * name + ".crc")} or {@code new Path(singleString)} would cause
 * {@code Path} to parse the string as a URI and throw
 * {@code URISyntaxException: Relative path in absolute URI}.
 *
 * The fix is to always use the two-argument constructor
 * {@code new Path(parent, childName)}, whose {@code childName} component
 * is never URI-parsed.
 */
public class TestChecksumFileSystemColons {

  private static final String TEST_ROOT =
      GenericTestUtils.getTempPath("TestChecksumFileSystemColons");

  private LocalFileSystem localFs;

  @BeforeEach
  public void setUp() throws Exception {
    localFs = FileSystem.getLocal(new Configuration());
    localFs.setVerifyChecksum(true);
  }

  @AfterEach
  public void tearDown() throws IOException {
    localFs.delete(new Path(TEST_ROOT), true);
  }

  /**
   * getChecksumFile must not throw when the filename contains a colon.
   * The returned path must equal /dir/.bar:baz.crc .
   */
  @Test
  public void testChecksumFileForFilenameWithColon() throws Exception {
    // Construct the colon-bearing path via URI to bypass Path(String)'s
    // URI-parsing of single-string input (a separate Path-layer issue).
    Path dataFile = new Path(new java.net.URI(
        "file", null, "/foo/bar:baz", null));
    Path crc = assertDoesNotThrow(
        () -> localFs.getChecksumFile(dataFile),
        "getChecksumFile must not throw for a colon-bearing filename");
    assertEquals("/foo/.bar:baz.crc", crc.toUri().getPath(),
        "Checksum sidecar path is wrong");
  }

  /**
   * getChecksumFile must handle BP-ID-style names that embed IPv6 addresses,
   * e.g. "BP-12345-fd00:dead:beef::10-1234567890".
   */
  @Test
  public void testChecksumFileForBPIdLikeName() throws Exception {
    String bpId = "BP-12345-fd00:dead:beef::10-1234567890";
    Path dataFile = new Path(new java.net.URI(
        "file", null, "/nn-storage/" + bpId, null));
    Path crc = assertDoesNotThrow(
        () -> localFs.getChecksumFile(dataFile),
        "getChecksumFile must not throw for a BP-ID filename with IPv6 colons");
    assertEquals("/nn-storage/." + bpId + ".crc",
        crc.toUri().getPath(),
        "Checksum sidecar path is wrong for BP-ID filename");
  }

  /**
   * isChecksumFile must correctly classify paths whose names contain colons.
   * The classification depends only on the name component, not the full path.
   */
  @Test
  public void testIsChecksumFileWithColons() throws Exception {
    // Path's String/parent+String constructors URI-parse the child, which
    // chokes on colons (a separate, deeper Path-layer issue); construct
    // via a URI directly so the test exercises only isChecksumFile's
    // name-only logic.
    Path crcWithColon = new Path(new java.net.URI(
        "file", null, "/foo/.bar:baz.crc", null));
    assertTrue(ChecksumFileSystem.isChecksumFile(crcWithColon),
        "Path whose name starts with '.' and ends with '.crc' must be recognised");

    Path dataWithColon = new Path(new java.net.URI(
        "file", null, "/foo/bar:baz", null));
    assertTrue(!ChecksumFileSystem.isChecksumFile(dataWithColon),
        "Data file with colon in name must not be classified as a checksum file");

    String bpId = "BP-12345-fd00:dead:beef::10-1234567890";
    Path bpCrc = new Path(new java.net.URI(
        "file", null, "/nn/." + bpId + ".crc", null));
    assertTrue(ChecksumFileSystem.isChecksumFile(bpCrc),
        "BP-ID .crc sidecar must be recognised as a checksum file");
  }

  /**
   * End-to-end round-trip: write a small file whose name contains a colon
   * through LocalFileSystem (which extends ChecksumFileSystem), verify that
   * the .crc sidecar is created on disk and can be read back.
   *
   * This test is skipped silently on file systems that forbid colons in
   * filenames (Windows NTFS), because the colon restriction is OS-enforced
   * at the native layer before Hadoop path logic is reached.
   */
  @Test
  public void testRoundTripWithLocalFileSystem() throws Exception {
    // Bail out gracefully on file systems that disallow colons (e.g. NTFS).
    File testRootFile = new File(TEST_ROOT);
    File probe = new File(testRootFile, "colon:probe");
    if (!testRootFile.mkdirs() && !testRootFile.isDirectory()) {
      return; // cannot create test dir – skip
    }
    try {
      if (!probe.createNewFile()) {
        return; // colons not supported – skip
      }
      probe.delete();
    } catch (IOException e) {
      return; // colons not supported on this OS/FS – skip
    }

    // Proceed: colon filenames are supported.
    Path dir = new Path(TEST_ROOT);
    // Construct the colon-bearing filename via URI to bypass
    // Path(String)'s URI-parsing of the child component (a separate
    // Path-layer issue distinct from the ChecksumFileSystem fix).
    java.net.URI parentUri = dir.toUri();
    java.net.URI dataUri = new java.net.URI(parentUri.getScheme(), null,
        parentUri.getPath() + "/data:file.txt", null);
    Path dataFile = new Path(dataUri);

    // Write through ChecksumFileSystem so a .crc sidecar is generated.
    try (FSDataOutputStream out = localFs.create(dataFile, true)) {
      out.write("hello colon world".getBytes(StandardCharsets.UTF_8));
    }

    // Verify sidecar exists.
    Path crcFile = localFs.getChecksumFile(dataFile);
    FileSystem rawFs = localFs.getRawFileSystem();
    assertTrue(rawFs.exists(crcFile),
        "CRC sidecar must exist after writing through ChecksumFileSystem");

    // Read back and verify checksum passes.
    localFs.setVerifyChecksum(true);
    try (FSDataInputStream in = localFs.open(dataFile)) {
      byte[] buf = new byte[64];
      int n = in.read(buf);
      String content = new String(buf, 0, n, StandardCharsets.UTF_8);
      assertEquals("hello colon world", content,
          "Round-trip content must match original");
    }
  }
}
