/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.maven.internal;

import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import okhttp3.mockwebserver.*;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openrewrite.ExecutionContext;
import org.openrewrite.HttpSenderExecutionContextView;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Issue;
import org.openrewrite.ipc.http.HttpSender;
import org.openrewrite.ipc.http.HttpUrlConnectionSender;
import org.openrewrite.maven.MavenDownloadingException;
import org.openrewrite.maven.MavenExecutionContextView;
import org.openrewrite.maven.MavenParser;
import org.openrewrite.maven.tree.GroupArtifact;
import org.openrewrite.maven.tree.GroupArtifactVersion;
import org.openrewrite.maven.tree.MavenMetadata;
import org.openrewrite.maven.tree.MavenRepository;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SuppressWarnings({"HttpUrlsUsage"})
class MavenPomDownloaderTest {
    private final ExecutionContext ctx = HttpSenderExecutionContextView.view(new InMemoryExecutionContext())
      .setHttpSender(new HttpUrlConnectionSender(Duration.ofMillis(100), Duration.ofMillis(100)));

    private void mockServer(Integer responseCode, Consumer<MockWebServer> block) {
        try (MockWebServer mockRepo = new MockWebServer()) {
            mockRepo.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest recordedRequest) {
                    return new MockResponse().setResponseCode(responseCode).setBody("");
                }
            });
            mockRepo.start();
            block.accept(mockRepo);
            assertThat(mockRepo.getRequestCount())
              .as("The mock repository received no requests. The test is not using it.")
              .isGreaterThan(0);
            mockRepo.shutdown();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Disabled("Flaky on CI")
    @Test
    void normalizeOssSnapshots() {
        var downloader = new MavenPomDownloader(emptyMap(), ctx);
        MavenRepository oss = downloader.normalizeRepository(
          MavenRepository.builder().id("oss").uri("https://oss.sonatype.org/content/repositories/snapshots").build(),
          null);

        assertThat(oss).isNotNull();
        assertThat(oss.getUri()).isEqualTo("https://oss.sonatype.org/content/repositories/snapshots");
    }

    @ParameterizedTest
    @Issue("https://github.com/openrewrite/rewrite/issues/3141")
    @ValueSource(strings = {"http://0.0.0.0", "https://0.0.0.0", "0.0.0.0:443"})
    void skipBlockedRepository(String url) {
        var downloader = new MavenPomDownloader(emptyMap(), ctx);
        MavenRepository oss = downloader.normalizeRepository(
          MavenRepository.builder().id("myRepo").uri(url).build(),
          null, null);

        assertThat(oss).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 400})
    void normalizeAcceptErrorStatuses(Integer status) {
        var downloader = new MavenPomDownloader(emptyMap(), ctx);
        mockServer(status, mockRepo -> {
            var originalRepo = MavenRepository.builder()
              .id("id")
              .uri("http://%s:%d/maven".formatted(mockRepo.getHostName(), mockRepo.getPort()))
              .build();
            var normalizedRepo = downloader.normalizeRepository(originalRepo, null);
            assertThat(normalizedRepo).isEqualTo(originalRepo);
        });
    }

    @Test
    void retryConnectException() throws Throwable {
        var downloader = new MavenPomDownloader(emptyMap(), ctx);
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("body"));
            String body = new String(downloader.sendRequest(new HttpSender.Request(server.url("/test").url(), "request".getBytes(), HttpSender.Method.GET, Map.of())));
            assertThat(body).isEqualTo("body");
            assertThat(server.getRequestCount()).isEqualTo(2);
            server.shutdown();
        }
    }

    @Test
    void normalizeRejectConnectException() {
        var downloader = new MavenPomDownloader(emptyMap(), ctx);
        var normalizedRepository = downloader.normalizeRepository(
          MavenRepository.builder().id("id").uri("https//localhost").build(),
          null
        );
        assertThat(normalizedRepository).isEqualTo(null);
    }

    @Test
    void invalidArtifact() {
        var downloader = new MavenPomDownloader(emptyMap(), ctx);
        var gav = new GroupArtifactVersion("fred", "fred", "1.0.0");
        mockServer(500,
          repo1 -> mockServer(400, repo2 -> {
              var repositories = List.of(
                MavenRepository.builder()
                  .id("id")
                  .uri("http://%s:%d/maven".formatted(repo1.getHostName(), repo1.getPort()))
                  .build(),
                MavenRepository.builder()
                  .id("id2")
                  .uri("http://%s:%d/maven".formatted(repo2.getHostName(), repo2.getPort()))
                  .build()
              );

              assertThatThrownBy(() -> downloader.download(gav, null, null, repositories))
                .isInstanceOf(MavenDownloadingException.class)
                .hasMessageContaining("http://%s:%d/maven".formatted(repo1.getHostName(), repo1.getPort()))
                .hasMessageContaining("http://%s:%d/maven".formatted(repo2.getHostName(), repo2.getPort()));
          })
        );
    }

    @Test
    @Issue("https://github.com/openrewrite/rewrite/issues/3152")
    void useSnapshotTimestampVersion() {
        var downloader = new MavenPomDownloader(emptyMap(), ctx);
        var gav = new GroupArtifactVersion("fred", "fred", "2020.0.2-20210127.131051-2");
        try (MockWebServer mockRepo = new MockWebServer()) {
            mockRepo.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest recordedRequest) {
                    return !recordedRequest.getPath().endsWith("fred/fred/2020.0.2-SNAPSHOT/fred-2020.0.2-20210127.131051-2.pom") ?
                      new MockResponse().setResponseCode(404).setBody("") :
                      new MockResponse().setResponseCode(200).setBody(
                        //language=xml
                        """
                          <project>
                              <groupId>org.springframework.cloud</groupId>
                              <artifactId>spring-cloud-dataflow-build</artifactId>
                              <version>2.10.0-SNAPSHOT</version>
                          </project>
                          """);
                }
            });
            mockRepo.start();
            var repositories = List.of(MavenRepository.builder()
              .id("id")
              .uri("http://%s:%d/maven".formatted(mockRepo.getHostName(), mockRepo.getPort()))
              .username("user")
              .password("pass")
              .build());

            assertDoesNotThrow(() -> downloader.download(gav, null, null, repositories));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void usesAnonymousRequestIfRepositoryRejectsCredentials() {
        var downloader = new MavenPomDownloader(emptyMap(), ctx);
        var gav = new GroupArtifactVersion("fred", "fred", "1.0.0");
        try (MockWebServer mockRepo = new MockWebServer()) {
            mockRepo.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest recordedRequest) {
                    return recordedRequest.getHeaders().get("Authorization") != null ?
                      new MockResponse().setResponseCode(401).setBody("") :
                      new MockResponse().setResponseCode(200).setBody(
                        //language=xml
                        """
                          <project>
                              <groupId>org.springframework.cloud</groupId>
                              <artifactId>spring-cloud-dataflow-build</artifactId>
                              <version>2.10.0-SNAPSHOT</version>
                          </project>
                          """);
                }
            });
            mockRepo.start();
            var repositories = List.of(MavenRepository.builder()
              .id("id")
              .uri("http://%s:%d/maven".formatted(mockRepo.getHostName(), mockRepo.getPort()))
              .username("user")
              .password("pass")
              .build());

            assertDoesNotThrow(() -> downloader.download(gav, null, null, repositories));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void usesAuthenticationIfRepositoryHasCredentials() {
        var downloader = new MavenPomDownloader(emptyMap(), ctx);
        var gav = new GroupArtifactVersion("fred", "fred", "1.0.0");
        try (MockWebServer mockRepo = new MockWebServer()) {
            mockRepo.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest recordedRequest) {
                    return recordedRequest.getHeaders().get("Authorization") != null ?
                      new MockResponse().setResponseCode(200).setBody(
                        //language=xml
                        """
                          <project>
                              <groupId>org.springframework.cloud</groupId>
                              <artifactId>spring-cloud-dataflow-build</artifactId>
                              <version>2.10.0-SNAPSHOT</version>
                          </project>
                          """) :
                      new MockResponse().setResponseCode(401).setBody("");
                }
            });
            mockRepo.start();
            var repositories = List.of(MavenRepository.builder()
              .id("id")
              .uri("http://%s:%d/maven".formatted(mockRepo.getHostName(), mockRepo.getPort()))
              .username("user")
              .password("pass")
              .build());

            assertDoesNotThrow(() -> downloader.download(gav, null, null, repositories));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("When username or password are environment properties that cannot be resolved, they should not be used")
    @Issue("https://github.com/openrewrite/rewrite/issues/3142")
    void doesNotUseAuthenticationIfCredentialsCannotBeResolved() {
        var downloader = new MavenPomDownloader(emptyMap(), ctx);
        var gav = new GroupArtifactVersion("fred", "fred", "1.0.0");
        try (MockWebServer mockRepo = new MockWebServer()) {
            mockRepo.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest recordedRequest) {
                    return recordedRequest.getHeaders().get("Authorization") == null ?
                      new MockResponse().setResponseCode(200).setBody(
                        //language=xml
                        """
                          <project>
                              <groupId>org.springframework.cloud</groupId>
                              <artifactId>spring-cloud-dataflow-build</artifactId>
                              <version>2.10.0-SNAPSHOT</version>
                          </project>
                          """) :
                      new MockResponse().setResponseCode(401).setBody("");
                }
            });
            mockRepo.start();
            var repositories = List.of(MavenRepository.builder()
              .id("id")
              .uri("http://%s:%d/maven".formatted(mockRepo.getHostName(), mockRepo.getPort()))
              .username("${env.ARTIFACTORY_USERNAME}")
              .password("${env.ARTIFACTORY_USERNAME}")
              .build());

            assertDoesNotThrow(() -> downloader.download(gav, null, null, repositories));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @Disabled
    void dontFetchSnapshotsFromReleaseRepos() {
        try (MockWebServer snapshotRepo = new MockWebServer();
             MockWebServer releaseRepo = new MockWebServer()) {
            snapshotRepo.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    MockResponse response = new MockResponse().setResponseCode(200);
                    if (request.getPath() != null && request.getPath().contains("maven-metadata")) {
                        response.setBody(
                          //language=xml
                          """
                                <metadata modelVersion="1.1.0">
                                  <groupId>org.springframework.cloud</groupId>
                                  <artifactId>spring-cloud-dataflow-build</artifactId>
                                  <version>2.10.0-SNAPSHOT</version>
                                  <versioning>
                                    <snapshot>
                                      <timestamp>20220201.001946</timestamp>
                                      <buildNumber>85</buildNumber>
                                    </snapshot>
                                    <lastUpdated>20220201001950</lastUpdated>
                                    <snapshotVersions>
                                      <snapshotVersion>
                                        <extension>pom</extension>
                                        <value>2.10.0-20220201.001946-85</value>
                                        <updated>20220201001946</updated>
                                      </snapshotVersion>
                                    </snapshotVersions>
                                  </versioning>
                                </metadata>
                            """
                        );
                    } else if (request.getPath() != null && request.getPath().endsWith(".pom")) {
                        response.setBody(
                          //language=xml
                          """
                                <project>
                                    <groupId>org.springframework.cloud</groupId>
                                    <artifactId>spring-cloud-dataflow-build</artifactId>
                                    <version>2.10.0-SNAPSHOT</version>
                                </project>
                            """
                        );
                    }
                    return response;
                }
            });

            var metadataPaths = new AtomicInteger(0);
            releaseRepo.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    MockResponse response = new MockResponse().setResponseCode(404);
                    if (request.getPath() != null && request.getPath().contains("maven-metadata")) {
                        metadataPaths.incrementAndGet();
                    }
                    return response;
                }
            });

            releaseRepo.start();
            snapshotRepo.start();

            MavenParser.builder().build().parse(ctx,
              //language=xml
              """
                    <project>
                        <modelVersion>4.0.0</modelVersion>
                    
                        <groupId>org.openrewrite.test</groupId>
                        <artifactId>foo</artifactId>
                        <version>0.1.0-SNAPSHOT</version>
                        
                        <repositories>
                          <repository>
                            <id>snapshot</id>
                            <snapshots>
                              <enabled>true</enabled>
                            </snapshots>
                            <url>http://%s:%d</url>
                          </repository>
                          <repository>
                            <id>release</id>
                            <snapshots>
                              <enabled>false</enabled>
                            </snapshots>
                            <url>http://%s:%d</url>
                          </repository>
                        </repositories>
                        
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework.cloud</groupId>
                                <artifactId>spring-cloud-dataflow-build</artifactId>
                                <version>2.10.0-SNAPSHOT</version>
                            </dependency>
                        </dependencies>
                    </project>
                """.formatted(snapshotRepo.getHostName(), snapshotRepo.getPort(), releaseRepo.getHostName(), releaseRepo.getPort())
            );

            assertThat(snapshotRepo.getRequestCount()).isGreaterThan(1);
            assertThat(metadataPaths.get()).isEqualTo(0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void deriveMetaDataFromFileRepository(@TempDir Path repoPath) throws IOException, MavenDownloadingException {
        Path fred = repoPath.resolve("fred/fred");

        for (String version : Arrays.asList("1.0.0", "1.1.0", "2.0.0")) {
            Path versionPath = fred.resolve(version);
            Files.createDirectories(versionPath);
            Files.writeString(versionPath.resolve("fred-" + version + ".pom"), "");
        }

        MavenRepository repository = MavenRepository.builder()
          .id("file-based")
          .uri(repoPath.toUri().toString())
          .knownToExist(true)
          .deriveMetadataIfMissing(true)
          .build();
        MavenMetadata metaData = new MavenPomDownloader(emptyMap(), new InMemoryExecutionContext())
          .downloadMetadata(new GroupArtifact("fred", "fred"), null, List.of(repository));
        assertThat(metaData.getVersioning().getVersions()).hasSize(3).containsAll(Arrays.asList("1.0.0", "1.1.0", "2.0.0"));
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void mergeMetadata() throws IOException {
        @Language("xml") String metadata1 = """
              <metadata>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot</artifactId>
                  <versioning>
                      <versions>
                          <version>2.3.3</version>
                          <version>2.4.1</version>
                          <version>2.4.2</version>
                      </versions>
                      <snapshot>
                          <timestamp>20220927.033510</timestamp>
                          <buildNumber>223</buildNumber>
                      </snapshot>
                      <snapshotVersions>
                          <snapshotVersion>
                              <extension>pom.asc</extension>
                              <value>0.1.0-20220927.033510-223</value>
                              <updated>20220927033510</updated>
                          </snapshotVersion>
                      </snapshotVersions>
                  </versioning>
              </metadata>
          """;

        @Language("xml") String metadata2 = """
              <metadata modelVersion="1.1.0">
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot</artifactId>
                  <versioning>
                      <versions>
                          <version>2.3.2</version>
                          <version>2.3.3</version>
                      </versions>
                      <snapshot>
                          <timestamp>20210115.042754</timestamp>
                          <buildNumber>180</buildNumber>
                      </snapshot>
                      <snapshotVersions>
                          <snapshotVersion>
                              <extension>pom.asc</extension>
                              <value>0.1.0-20210115.042754-180</value>
                              <updated>20210115042754</updated>
                          </snapshotVersion>
                      </snapshotVersions>
                  </versioning>
              </metadata>
          """;

        var m1 = MavenMetadata.parse(metadata1.getBytes());
        var m2 = MavenMetadata.parse(metadata2.getBytes());

        var merged = new MavenPomDownloader(emptyMap(), new InMemoryExecutionContext()).mergeMetadata(m1, m2);

        assertThat(merged.getVersioning().getSnapshot().getTimestamp()).isEqualTo("20220927.033510");
        assertThat(merged.getVersioning().getSnapshot().getBuildNumber()).isEqualTo("223");
        assertThat(merged.getVersioning().getVersions()).hasSize(4).contains("2.3.2", "2.3.3", "2.4.1", "2.4.2");
        assertThat(merged.getVersioning().getSnapshotVersions()).hasSize(2);
        assertThat(merged.getVersioning().getSnapshotVersions().get(0).getExtension()).isNotNull();
        assertThat(merged.getVersioning().getSnapshotVersions().get(0).getValue()).isNotNull();
        assertThat(merged.getVersioning().getSnapshotVersions().get(0).getUpdated()).isNotNull();
    }

    @Test
    void skipsLocalInvalidArtifactsMissingJar(@TempDir Path localRepository) throws IOException {
        Path localArtifact = localRepository.resolve("com/bad/bad-artifact");
        assertThat(localArtifact.toFile().mkdirs()).isTrue();
        Files.createDirectories(localArtifact.resolve("1"));

        Path localPom = localRepository.resolve("com/bad/bad-artifact/1/bad-artifact-1.pom");
        Files.writeString(localPom,
          //language=xml
          """
             <project>
               <groupId>com.bad</groupId>
               <artifactId>bad-artifact</artifactId>
               <version>1</version>
             </project>
            """
        );

        MavenRepository mavenLocal = MavenRepository.builder()
          .id("local")
          .uri(localRepository.toUri().toString())
          .snapshots(false)
          .knownToExist(true)
          .build();

        // Does not return invalid dependency.
        assertThrows(MavenDownloadingException.class, () ->
          new MavenPomDownloader(emptyMap(), new InMemoryExecutionContext())
            .download(new GroupArtifactVersion("com.bad", "bad-artifact", "1"), null, null, List.of(mavenLocal)));
    }

    @Test
    void skipsLocalInvalidArtifactsEmptyJar(@TempDir Path localRepository) throws IOException {
        Path localArtifact = localRepository.resolve("com/bad/bad-artifact");
        assertThat(localArtifact.toFile().mkdirs()).isTrue();
        Files.createDirectories(localArtifact.resolve("1"));

        Path localPom = localRepository.resolve("com/bad/bad-artifact/1/bad-artifact-1.pom");
        Files.writeString(localPom,
          //language=xml
          """
             <project>
               <groupId>com.bad</groupId>
               <artifactId>bad-artifact</artifactId>
               <version>1</version>
             </project>
            """
        );
        Path localJar = localRepository.resolve("com/bad/bad-artifact/1/bad-artifact-1.jar");
        Files.writeString(localJar, "");

        MavenRepository mavenLocal = MavenRepository.builder()
          .id("local")
          .uri(localRepository.toUri().toString())
          .snapshots(false)
          .knownToExist(true)
          .build();

        // Does not return invalid dependency.
        assertThrows(MavenDownloadingException.class, () ->
          new MavenPomDownloader(emptyMap(), new InMemoryExecutionContext())
            .download(new GroupArtifactVersion("com.bad", "bad-artifact", "1"), null, null, List.of(mavenLocal)));
    }

    @Test
    void doNotRenameRepoForCustomMavenLocal(@TempDir Path tempDir) throws MavenDownloadingException, IOException {
        GroupArtifactVersion gav = createArtifact(tempDir);
        MavenExecutionContextView.view(ctx).setLocalRepository(MavenRepository.MAVEN_LOCAL_DEFAULT.withUri(tempDir.toUri().toString()));
        var downloader = new MavenPomDownloader(emptyMap(), ctx);

        var result = downloader.download(gav, null, null, List.of());
        assertThat(result.getRepository().getUri()).startsWith(tempDir.toUri().toString());
    }

    private static GroupArtifactVersion createArtifact(Path repository) throws IOException {
        Path target = repository.resolve(Paths.get("org", "openrewrite", "rewrite", "1.0.0"));
        Path pom = target.resolve("rewrite-1.0.0.pom");
        Path jar = target.resolve("rewrite-1.0.0.jar");
        Files.createDirectories(target);
        Files.createFile(pom);
        Files.createFile(jar);

        Files.write(pom,
          //language=xml
          """
            <project>
                <groupId>org.openrewrite</groupId>
                <artifactId>rewrite</artifactId>
                <version>1.0.0</version>
            </project>
            """.getBytes());
        Files.write(jar, "I'm a jar".getBytes()); // empty jars get ignored
        return new GroupArtifactVersion("org.openrewrite", "rewrite", "1.0.0");
    }

}
