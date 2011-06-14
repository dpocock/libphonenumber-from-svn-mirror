/*
 * Copyright (C) 2011 Google Inc.
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

package com.google.i18n.phonenumbers;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A utility that generates the binary serialization of the area code/location mappings from
 * human-readable text files. It also generates a configuration file which contains information on
 * data files available for use.
 *
 * <p> The text files must be located in sub-directories of the provided input path. For each input
 * file inputPath/lang/countryCallingCode.txt the corresponding binary file is generated as
 * outputPath/countryCallingCode_lang. The binary files are stored into the resulting JAR.
 *
 * @author Philippe Liard
 */
public class GenerateAreaCodeData {
  // The path to the input directory containing the languages directories.
  private final File inputPath;
  // The path to the output JAR file.
  private final File outputJarPath;
  // Whether the data is generated for testing.
  private final boolean forTesting;

  private static final Logger LOGGER = Logger.getLogger(GenerateAreaCodeData.class.getName());

  private JarOutputStream createJar() throws FileNotFoundException, IOException {
    Manifest manifest = new java.util.jar.Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    JarOutputStream target = new JarOutputStream(new FileOutputStream(outputJarPath));
    return target;
  }

  private void addFileToJar(File file, JarOutputStream jar) throws IOException {
    JarEntry entry = new JarEntry(
        GenerateAreaCodeData.class.getPackage().getName().replace('.', '/') +
        (forTesting ? "/geocoding_testing_data/" : "/geocoding_data/") +
        file.getPath());
    entry.setTime(file.lastModified());
    jar.putNextEntry(entry);
    BufferedInputStream bufferedInputStream = null;

    try {
      bufferedInputStream = new BufferedInputStream(new FileInputStream(file));
      byte[] buffer = new byte[4096];

      for (int read = 0; (read = bufferedInputStream.read(buffer)) > 0; ) {
        jar.write(buffer, 0, read);
      }
    } finally {
      jar.closeEntry();
      closeFile(bufferedInputStream);
    }
  }

  public GenerateAreaCodeData(File inputPath, File outputJarPath, boolean forTesting)
      throws IOException {
    if (!inputPath.isDirectory()) {
      throw new IOException("The provided input path does not exist: " +
                             inputPath.getAbsolutePath());
    }
    File parentDirectory = outputJarPath.getParentFile();
    if (parentDirectory.exists()) {
      if (!parentDirectory.isDirectory()) {
        throw new IOException("Expected directory: " + parentDirectory.getAbsolutePath());
      }
    } else {
      if (!parentDirectory.mkdirs()) {
        throw new IOException("Could not create directory " + parentDirectory.getAbsolutePath());
      }
    }
    this.inputPath = inputPath;
    this.outputJarPath = outputJarPath;
    this.forTesting = forTesting;
  }

  /**
   * Closes the provided file and log any potential IOException.
   */
  private static void closeFile(Closeable closeable) {
    if (closeable == null) {
      return;
    }
    try {
      closeable.close();
    } catch (IOException e) {
      LOGGER.log(Level.WARNING, e.getMessage());
    }
  }

  /**
   * Converts the text data read from the provided input stream to the Java binary serialization
   * format. The resulting data is written to the provided output stream.
   *
   * @VisibleForTesting
   */
  static void convertData(InputStream input, OutputStream output) throws IOException {
    SortedMap<Integer, String> areaCodeMapTemp = new TreeMap<Integer, String>();
    BufferedReader bufferedReader =
        new BufferedReader(new InputStreamReader(
            new BufferedInputStream(input), Charset.forName("UTF-8")));
    for (String line; (line = bufferedReader.readLine()) != null; ) {
      line = line.trim();
      if (line.length() == 0 || line.startsWith("#")) {
        continue;
      }
      int indexOfPipe = line.indexOf('|');
      if (indexOfPipe == -1) {
        LOGGER.log(Level.WARNING, "Malformatted data: expected '|'");
        continue;
      }
      String areaCode = line.substring(0, indexOfPipe);
      if (indexOfPipe == line.length() - 1) {
        LOGGER.log(Level.WARNING, "Missing location for area code " + areaCode);
        continue;
      }
      String location = line.substring(indexOfPipe + 1);
      areaCodeMapTemp.put(Integer.parseInt(areaCode), location);
    }
    // Build the corresponding area code map and serialize it to the binary format.
    AreaCodeMap areaCodeMap = new AreaCodeMap();
    areaCodeMap.readAreaCodeMap(areaCodeMapTemp);
    ObjectOutputStream objectOutputStream = new ObjectOutputStream(output);
    areaCodeMap.writeExternal(objectOutputStream);
    objectOutputStream.flush();
  }

  private class Pair<A, B> {
    public final A first;
    public final B second;

    public Pair(A first, B second) {
      this.first = first;
      this.second = second;
    }
  }

  /**
   * Creates the input country code text file/output binary file (named countryCode_language)
   * mappings.
   */
  private List<Pair<File, File>> createInputOutputFileMappings() {
    List<Pair<File, File>> mappings = new ArrayList<Pair<File, File>>();
    File[] languageDirectories = inputPath.listFiles();

    for (File languageDirectory : languageDirectories) {
      if (!languageDirectory.isDirectory() || languageDirectory.isHidden()) {
        continue;
      }
      File[] countryCodeFiles = languageDirectory.listFiles();

      for (File countryCodeFile : countryCodeFiles) {
        if (countryCodeFile.isHidden()) {
          continue;
        }
        String countryCodeFileName = countryCodeFile.getName();
        int indexOfDot = countryCodeFileName.indexOf('.');
        if (indexOfDot == -1) {
          LOGGER.log(Level.WARNING,
                     String.format("unexpected file name %s, expected pattern .*\\.txt",
                                   countryCodeFileName));
          continue;
        }
        String countryCode = countryCodeFileName.substring(0, indexOfDot);
        if (!countryCode.matches("\\d+")) {
          LOGGER.log(Level.WARNING, "ignoring unexpected file " + countryCodeFileName);
          continue;
        }
        mappings.add(new Pair<File, File>(
            countryCodeFile,
            new File(String.format("%s_%s", countryCode, languageDirectory.getName()))));
      }
    }
    return mappings;
  }

  /**
   * Adds a country code/language mapping to the provided map. The country code and language are
   * generated from the provided file name previously used to output the area code/location mappings
   * for the given country.
   *
   * @VisibleForTesting
   */
  static void addConfigurationMapping(SortedMap<Integer, Set<String>> availableDataFiles,
                                      File outputAreaCodeMappingsFile) {
    String outputAreaCodeMappingsFileName = outputAreaCodeMappingsFile.getName();
    int indexOfUnderscore = outputAreaCodeMappingsFileName.indexOf('_');
    int countryCode = Integer.parseInt(
        outputAreaCodeMappingsFileName.substring(0, indexOfUnderscore));
    String language = outputAreaCodeMappingsFileName.substring(indexOfUnderscore + 1);

    Set<String> languageSet = availableDataFiles.get(countryCode);
    if (languageSet == null) {
      languageSet = new HashSet<String>();
      availableDataFiles.put(countryCode, languageSet);
    }
    languageSet.add(language);
  }

  /**
   * Outputs the binary configuration file mapping country codes to language strings.
   *
   * @VisibleForTesting
   */
  static void outputBinaryConfiguration(SortedMap<Integer, Set<String>> availableDataFiles,
                                        OutputStream outputStream) throws IOException {
    MappingFileProvider mappingFileProvider = new MappingFileProvider();
    mappingFileProvider.readFileConfigs(availableDataFiles);
    ObjectOutputStream objectOutputStream = new ObjectOutputStream(outputStream);
    mappingFileProvider.writeExternal(objectOutputStream);
    objectOutputStream.flush();
  }

  /**
   * Runs the area code data generator.
   *
   * @throws IOException
   * @throws FileNotFoundException
   */
  public void run() throws FileNotFoundException, IOException {
    JarOutputStream jar = createJar();
    List<Pair<File, File>> inputOutputMappings = createInputOutputFileMappings();
    SortedMap<Integer, Set<String>> availableDataFiles = new TreeMap<Integer, Set<String>>();

    for (Pair<File, File> inputOutputMapping : inputOutputMappings) {
      FileInputStream fileInputStream = null;
      FileOutputStream fileOutputStream = null;

      try {
        File textFile = inputOutputMapping.first;
        File binaryFile = inputOutputMapping.second;
        fileInputStream = new FileInputStream(textFile);
        fileOutputStream = new FileOutputStream(binaryFile);
        convertData(fileInputStream, fileOutputStream);
        addConfigurationMapping(availableDataFiles, inputOutputMapping.second);
        addFileToJar(binaryFile, jar);
      } catch (IOException e) {
        LOGGER.log(Level.SEVERE, e.getMessage());
        continue;
      } finally {
        closeFile(fileInputStream);
        closeFile(fileOutputStream);
      }
    }
    // Output the binary configuration file mapping country codes to languages.
    FileOutputStream fileOutputStream = null;

    try {
      File configFile = new File("config");
      fileOutputStream = new FileOutputStream(configFile);
      outputBinaryConfiguration(availableDataFiles, fileOutputStream);
      addFileToJar(configFile, jar);
    } finally {
      closeFile(fileOutputStream);
      closeFile(jar);
    }
  }

  public static void main(String[] args) {
    if (args.length != 3) {
      LOGGER.log(Level.SEVERE,
                 "usage: GenerateAreaCodeData /path/to/input/directory /path/to/output.jar" +
                 " forTesting");
      System.exit(1);
    }
    try {
      GenerateAreaCodeData generateAreaCodeData =
          new GenerateAreaCodeData(new File(args[0]), new File(args[1]),
                                   Boolean.parseBoolean(args[2]));
      generateAreaCodeData.run();
    } catch (IOException e) {
      LOGGER.log(Level.SEVERE, e.getMessage());
      System.exit(1);
    }
  }
}
