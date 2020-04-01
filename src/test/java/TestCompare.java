/**
 * Copyright 2010-2019 interactive instruments GmbH
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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Stream;

import de.interactive_instruments.etf.client.EtfEndpoint;
import de.interactive_instruments.etf.client.EtfValidatorClient;
import de.interactive_instruments.etf.client.HttpBasicAuthentication;
import de.interactive_instruments.etf.client.RemoteInvocationException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.interactive_instruments.IFile;

/**
 * Run createTests in the IDE
 *
 *
 * @author Jon Herrmann ( herrmann aT interactive-instruments doT de )
 */
@Execution(ExecutionMode.CONCURRENT)
public class TestCompare {

    private static EtfEndpoint etfEndpoint;

    private final static IFile ddtDirectory = new IFile("src/test/resources/ddt");
    private final static IFile outputDirectory = new IFile("build/tmp/ddt/results");
    private final static IFile tmpOutputDirectory = new IFile("build/tmp/ddt/tmp_outputs");
    private final static IFile configFile = new IFile("src/test/resources/ddt/endpoint.properties");

    private final static Logger logger = LoggerFactory.getLogger(TestCompare.class);

    @BeforeAll
    static void setUp() throws IOException {
        configFile.expectFileIsReadable();
        outputDirectory.ensureDir();
        tmpOutputDirectory.ensureDir();
        logger.info("Output directory is: {}", outputDirectory.getAbsolutePath());
        logger.info("Temporary directory is: {}", tmpOutputDirectory.getAbsolutePath());

        try (final InputStream input = new FileInputStream(configFile)) {
            final Properties configuration = new Properties();
            configuration.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            final String endpoint = Objects.requireNonNull(
                configuration.getProperty("endpoint"), "property endpoint not set");
            final EtfValidatorClient client = EtfValidatorClient.create().url(new URL(endpoint))
                // timeout for upload
                .timeout(Duration.ofMinutes(45));
            final String username = configuration.getProperty("username");
            if (username != null) {
                client.authenticator(HttpBasicAuthentication.create(username, configuration.getProperty("password")));
            }
            etfEndpoint = client.init();
        }
        assertTrue(etfEndpoint.available());
        logger.info("Test session id: {}", etfEndpoint.sessionId());
    }

    @AfterEach
    @BeforeEach
    void checkStatus() {
        assertTrue(etfEndpoint.available(), "Endpoint down");
    }

    @TestFactory
    Stream<DynamicNode> generate() {
        logger.info(
            "Generated tests are not displayed correctly. See issue https://github.com/gradle/gradle/issues/5975");
        return ddtDirectory.listDirs().stream()
            .map(testSuiteDirectory -> {
                final Collection<DataDrivenTest.TestCase> testCases;
                try {
                    final DataDrivenTest.PreparedTestSuite testSuite =
                        DataDrivenTest.createTestSuite(testSuiteDirectory);
                    testCases = testSuite.createTestRunAndTestCases(etfEndpoint);
                } catch (final IOException e) {
                    return DynamicTest.dynamicTest(
                        testSuiteDirectory.getName(), fail("Failed to create Test Suite ", e));
                } catch (RemoteInvocationException e) {
                    return DynamicTest.dynamicTest(
                        testSuiteDirectory.getName(), fail("Failed to start Test Suite ", e));
                }
                return DynamicContainer.dynamicContainer(testSuiteDirectory.getName(),
                    dynamicTestsFromTestCasesStream(testSuiteDirectory.getName(),testCases));
            });
    }

    private static Stream<DynamicTest> dynamicTestsFromTestCasesStream(final String testSuiteDirectory, final Collection<DataDrivenTest.TestCase> testCases) {
        return testCases.stream().map(
            tc -> DynamicTest.dynamicTest(tc.getName(), tc::waitForResultAndCompare)
        );
    }
}
