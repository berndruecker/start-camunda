/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.camunda.start.processing;

import com.camunda.start.rest.dto.DownloadProjectDto;
import com.camunda.start.update.VersionUpdater;
import com.camunda.start.update.dto.StarterVersionDto;
import com.camunda.start.util.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.zeroturnaround.zip.ByteSource;
import org.zeroturnaround.zip.ZipEntrySource;
import org.zeroturnaround.zip.ZipUtil;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ProjectGenerator {

  protected static final String MAIN_PATH = "/src/main/";
  protected static final String JAVA_PATH = MAIN_PATH + "java/";
  protected static final String RESOURCES_PATH = MAIN_PATH + "resources/";

  protected static final String APPLICATION_CLASS_NAME = "Application.java";
  protected static final String APPLICATION_YAML_NAME = "application.yaml";
  protected static final String APPLICATION_POM_NAME = "pom.xml";

  protected static final String TEMPLATES_PATH = "/com/camunda/start/templates/";


  protected DownloadProjectDto inputData;
  protected Map<String, Object> templateContext;
  protected Map<String, StarterVersionDto> versions;

  @Autowired
  protected VersionUpdater versionUpdater;

  @Autowired
  protected TemplateProcessor templateProcessor;

  public byte[] generate(DownloadProjectDto inputData) {
    initialize(inputData);

    byte[] applicationClass = processByFileName(APPLICATION_CLASS_NAME);
    byte[] applicationYaml = processByFileName(APPLICATION_YAML_NAME);
    byte[] pomXml = processByFileName(APPLICATION_POM_NAME);

    String projectName = (String) templateContext.get("artifact");
    String packageName = dotToSlash((String) templateContext.get("group"));

    ZipEntrySource[] entries = new ZipEntrySource[] {
        new ByteSource(projectName + JAVA_PATH + packageName + "/" + APPLICATION_CLASS_NAME, applicationClass),
        new ByteSource(projectName + RESOURCES_PATH + APPLICATION_YAML_NAME, applicationYaml),
        new ByteSource(projectName + "/" + APPLICATION_POM_NAME, pomXml)
    };

    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    ZipUtil.pack(entries, baos);

    return baos.toByteArray();
  }

  public String generate(DownloadProjectDto inputData, String fileName) {
    initialize(inputData);

    return templateProcessor.process(templateContext, TEMPLATES_PATH + fileName + ".vm");
  }

  protected byte[] processByFileName(String filename) {
    return templateProcessor.process(templateContext,TEMPLATES_PATH + filename + ".vm")
        .getBytes();
  }

  public void initialize(DownloadProjectDto inputData) {
    this.inputData = inputData;

    versions = JsonUtil.asObject(readVersionsAsJson())
        .getStarterVersions()
        .stream()
        .collect(Collectors.toMap(StarterVersionDto::getStarterVersion,
            starterVersionDto -> starterVersionDto, (a, b) -> b, LinkedHashMap::new));

    addDefaultValues(inputData);

    templateContext = initTemplateContext(inputData);
  }

  protected String readVersionsAsJson() {
    try {
      return new String(Files.readAllBytes(Paths.get("versions.json")),
          Charset.defaultCharset());
    } catch (FileNotFoundException e) {
      versionUpdater.updateVersions();

      try {
        return new String(Files.readAllBytes(Paths.get("versions.json")),
            Charset.defaultCharset());
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }

    } catch (IOException e) {
      throw new RuntimeException(e);

    }
  }

  protected void addDefaultValues(DownloadProjectDto inputData) {
    if (isEmpty(inputData.getModules())) {
      inputData.setModules(Collections.singletonList("camunda-rest"));
    }
    if (isEmpty(inputData.getGroup())) {
      inputData.setGroup("com.example.workflow");
    }
    if (isEmpty(inputData.getDatabase())) {
      inputData.setDatabase("h2");
    }
    if (isEmpty(inputData.getArtifact())) {
      inputData.setArtifact("my-project");
    }
    if (isEmpty(inputData.getStarterVersion())) {
      String latestStarterVersion = versions.keySet()
          .iterator()
          .next();

      inputData.setStarterVersion(latestStarterVersion);
    }
    if (isEmpty(inputData.getJavaVersion())) {
      inputData.setJavaVersion("12");
    }
    if (isEmpty(inputData.getUsername())) {
      inputData.setUsername("demo");
    }
    if (isEmpty(inputData.getPassword())) {
      inputData.setPassword("demo");
    }
    if (isEmpty(inputData.getVersion())) {
      inputData.setVersion("1.0.0-SNAPSHOT");
    }
  }

  private boolean isEmpty(String string) {
    return string == null || string.isEmpty();
  }

  private boolean isEmpty(List<String> set) {
    return set == null || set.isEmpty();
  }

  protected Map<String, Object> initTemplateContext(DownloadProjectDto inputData) {
    Map<String, Object> context = new HashMap<>();
    context.put("packageName", inputData.getGroup());

    context.put("dbType", inputData.getDatabase());
    context.put("dbClassRef", getDbClassRef(inputData.getDatabase()));

    context.put("adminUsername", inputData.getUsername());
    context.put("adminPassword", inputData.getPassword());

    context.put("camundaVersion", resolveCamundaVersion(inputData.getStarterVersion()));
    context.put("springBootVersion", resolveSpringBootVersion(inputData.getStarterVersion()));
    context.put("javaVersion", inputData.getJavaVersion());

    context.put("group", inputData.getGroup());
    context.put("artifact", inputData.getArtifact());
    context.put("projectVersion", inputData.getVersion());

    context.put("dependencies", getDeps(inputData.getModules()));

    return context;
  }

  protected String resolveSpringBootVersion(String starterVersion) {
    return versions.get(starterVersion)
        .getSpringBootVersion();
  }

  protected String resolveCamundaVersion(String starterVersion) {
    return versions.get(starterVersion)
        .getCamundaVersion();
  }

  protected List<Dependency> getDeps(List<String> modules) {
    List<Dependency> dependencies = new ArrayList<>();

    modules.forEach(module -> {
      switch (module) {
        case "camunda-webapps":

          Dependency camundaWebapps = new Dependency()
              .setGroup("org.camunda.bpm.springboot")
              .setArtifact("camunda-bpm-spring-boot-starter-webapp")
              .setVersion(inputData.getStarterVersion());

          dependencies.add(camundaWebapps);
          break;
        case "camunda-rest":

          Dependency camundaRest = new Dependency()
              .setGroup("org.camunda.bpm.springboot")
              .setArtifact("camunda-bpm-spring-boot-starter-rest")
              .setVersion(inputData.getStarterVersion());

          dependencies.add(camundaRest);
          break;
        case "spring-boot-security":

          Dependency springSecurity = new Dependency()
              .setGroup("org.springframework.boot")
              .setArtifact("spring-boot-starter-security");

          dependencies.add(springSecurity);
          break;
        case "spring-boot-web":

          Dependency springWeb = new Dependency()
              .setGroup("org.springframework.boot")
              .setArtifact("spring-boot-starter-web");

          dependencies.add(springWeb);
          break;
        default:
          throw new RuntimeException("Unknown module!");
      }
    });

    addJdbcDependency(inputData.getDatabase(), dependencies);

    return dependencies;
  }

  protected void addJdbcDependency(String database, List<Dependency> dependencies) {
    Dependency jdbcDependency = null;

    switch (database) {
      case "postgresql":
        jdbcDependency = new Dependency()
            .setGroup("org.postgresql")
            .setArtifact("postgresql");
        break;
      case "mysql":
        jdbcDependency = new Dependency()
            .setGroup("mysql")
            .setArtifact("mysql-connector-java");
        break;
      case "h2":
        jdbcDependency = new Dependency()
            .setGroup("com.h2database")
            .setArtifact("h2");
        break;
      default:
        throw new RuntimeException("Unknown database!");
    }

    dependencies.add(jdbcDependency);
  }

  protected String getDbClassRef(String database) {
    switch (database.toLowerCase()) {
      case "postgresql":
        return "org.postgresql.jdbc2.optional.SimpleDataSource";
      case "mysql":
        return "com.mysql.cj.jdbc.MysqlDataSource";
      default:
        return "";
    }
  }

  protected String dotToSlash(String input) {
    return input.replace(".", "/");
  }

}
