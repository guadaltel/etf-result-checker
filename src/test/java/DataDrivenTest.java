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

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import de.interactive_instruments.SUtils;
import de.interactive_instruments.etf.client.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.interactive_instruments.IFile;
import de.interactive_instruments.io.FilenameExtensionFilter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Jon Herrmann ( herrmann aT interactive-instruments doT de )
 */
class DataDrivenTest {

    private final static Logger logger = LoggerFactory.getLogger(DataDrivenTest.class);
    private final static FilenameExtensionFilter zipFileFilter = new FilenameExtensionFilter(".zip");

    abstract static class TestCase {
        private final String name;
        protected final JSONObject testCase;

        TestCase(final String name, final JSONObject testCase) {
            this.name = name;
            this.testCase = testCase;
        }

        final String getName() {
            return this.name;
        }

        abstract void waitForResultAndCompare();
        abstract void testRunFinished();

        private static String normalize(final String input) {
            return input.trim().replaceAll(
                " +", " ").replaceAll("\\n|\\r\\n?", System.lineSeparator());
        }



        protected void compare(final TestAssertionResult testTaskResult) {
            assertEquals(ResultStatus.fromString(this.testCase.getString("expectedResult")),
                testTaskResult.resultStatus(), "The assertion result status does not match expected status");
            if(this.testCase.has("maxDurationMs")) {
                assertTrue(this.testCase.getLong("maxDurationMs") >= testTaskResult.duration(),
                    "The assertion execution time exceeds the maximum expected duration");
            }
            final Collection<String> messages = testTaskResult.messages();
            if(this.testCase.has("expectedMessageCount")) {
                assertEquals(this.testCase.getInt("expectedMessageCount"), messages.size(),
                    "The number of error messages count does not match the expected number of error messages");
            }
            if(this.testCase.has("expectedMessages")) {
                final Set<String> expectedMessages = new HashSet<>();
                for (Object mO : this.testCase.getJSONArray("expectedMessages")) {
                    expectedMessages.add(normalize((String) mO));
                }

                final Set<String> unexpectedMessages = new HashSet<>();
                for (final String message : messages) {
                    final String m = normalize(message);
                    if(!expectedMessages.remove(m)) {
                        unexpectedMessages.add(m);
                    }
                }
                if(!expectedMessages.isEmpty()) {
                    for (final String expectedMessage : expectedMessages) {
                        logger.error("Expected message not found: {}",expectedMessage);
                    }
                    fail(expectedMessages.size()+ " expected message(s) were not found.");
                }
                if(!unexpectedMessages.isEmpty()) {
                    for (final String unexpectedMessage : unexpectedMessages) {
                        logger.error("Message not expected: {}",unexpectedMessage);
                    }
                    fail(unexpectedMessages.size()+ " message(s) were not expected.");
                }
            }
        }
    }

    private final static class WildCardTestCase extends TestCase {

        private List<TestAssertionResult> testTaskResults = new ArrayList<>();
        private int knownSize = 0;

        public WildCardTestCase(final JSONObject testCase) {
            super("*", testCase);
        }

        public void storeResult(final TestAssertionResult testTaskResult) {
            this.testTaskResults.add(testTaskResult);
        }

        public void testRunFinished() {
            synchronized (this) {
                notify();
            }
        }

        public void waitForResultAndCompare() {
            synchronized (this) {
                try {
                    if(this.testTaskResults == null || knownSize == testTaskResults.size()) {
                        wait();
                    }
                    if (this.testTaskResults == null) {
                        fail("No result found for test");
                    } else {
                        knownSize = testTaskResults.size();
                        for (final TestAssertionResult testTaskResult : testTaskResults) {
                            compare(testTaskResult);
                        }
                    }
                } catch (InterruptedException e) {
                    fail("Test interrupted");
                }
            }
        }
    }

    private final static class DefaultTestCase extends TestCase {
        private TestAssertionResult testTaskResult;

        public DefaultTestCase(final String name, final JSONObject testCase) {
            super(name, testCase);
        }

        public void resultAvailable(final TestAssertionResult testTaskResult) {
            this.testTaskResult = testTaskResult;
            synchronized (this) {
                notify();
            }
        }

        public void testRunFinished() {
            synchronized (this) {
                notify();
            }
        }

        public void waitForResultAndCompare() {
            synchronized (this) {
                try {
                    if(this.testTaskResult == null) {
                        wait();
                    }
                    if (this.testTaskResult == null) {
                        fail("No result found for test");
                    }
                    compare(testTaskResult);
                } catch (InterruptedException e) {
                    fail("Test interrupted");
                }
            }
        }
    }

    public static class PreparedTestSuite implements TestRunObserver {
        private final IFile directory;
        private final IFile dataFile;
        private final IFile runFile;
        private final IFile expectedFile;
        private final Map<String, TestCase> testCases = new HashMap<>();
        private WildCardTestCase wildCardTestCase;

        public PreparedTestSuite(final IFile directory) throws IOException {
            this.directory = directory;
            this.directory.expectDirIsReadable();
            final IFile[] zipFiles = directory.listIFiles(zipFileFilter);
            if(zipFiles.length==0) {
                dataFile = null;
            }else if(zipFiles.length==1) {
                dataFile = zipFiles[0];
                dataFile.expectFileIsReadable();
            }else{
                throw new IOException("Only one ZIP file is supported. Multiple ZIP files found in directory "+
                    this.directory.getAbsolutePath());
            }
            runFile = directory.secureExpandPathDown("run.json");
            runFile.expectFileIsReadable();
            expectedFile = directory.secureExpandPathDown("expected.json");
            if(expectedFile.exists()) {
                expectedFile.expectFileIsReadable();
            }
        }

        private static JSONObject parseJsonFile(final IFile file) throws IOException {
            try (final InputStream runFileIs = new FileInputStream(file)) {
                final JSONTokener tokener = new JSONTokener(new InputStreamReader(runFileIs, StandardCharsets.UTF_8));
                return new JSONObject(tokener);
            }
        }

        public Collection<TestCase> createTestRunAndTestCases(final EtfEndpoint etfEndpoint) throws RemoteInvocationException, IOException {
            final boolean generateTemplate = !expectedFile.exists();
            if(expectedFile.exists()) {
                final JSONObject testCasesJson = parseJsonFile(expectedFile);
                for (final String testCaseName : testCasesJson.keySet()) {
                    final JSONObject tc = testCasesJson.getJSONObject(testCaseName);
                    if ("*".equals(testCaseName)) {
                        this.wildCardTestCase = new WildCardTestCase(tc);
                        testCases.putIfAbsent("*", this.wildCardTestCase);
                    } else {
                        testCases.put(testCaseName, new DefaultTestCase(testCaseName, tc));
                    }
                }
            }else{
                logger.info("The expected.json file does not exist, generating a template for you based on the results of this run.");
            }

            final JSONObject runObj = parseJsonFile(runFile);


                    /*
        logger.info("Available Executable Test Suites: ");
                for (final ExecutableTestSuite ets : etfEndpoint.executableTestSuites()) {
            logger.info(" - {}", ets);
        }
         */

            final TestRunExecutable executable;

            final HelpAvailable trtHelp = () -> {
                logger.info("Available Test Run Templates: ");
                for (final TestRunTemplate i : etfEndpoint.testRunTemplates()) {
                    logger.info(" - {}", i);
                }
            };

            final HelpAvailable etsHelp = () -> {
                logger.info("Available Executable Test Suites: ");
                for (final ExecutableTestSuite i : etfEndpoint.executableTestSuites()) {
                    logger.info(" - {}", i);
                }
            };

            final HelpAvailable tagHelp = () -> {
                logger.info("Available Tags: ");
                for (final Tag i : etfEndpoint.tags()) {
                    logger.info(" - {}", i);
                }
            };

            final Collection<ExecMapping> mappings = new ArrayList<>() {{

                // Test Run Template ID
               add(new ExecMapping("Test Run Template", "testRunTemplateId",
                   properties -> etfEndpoint.testRunTemplates().itemById(properties.iterator().next()).get(),
                   trtHelp
               ));

                // Test Run Template Name
                add(new ExecMapping("Test Run Template", "testRunTemplateName",
                    properties -> etfEndpoint.testRunTemplates().itemByLabel(properties.iterator().next()).get(),
                    trtHelp
                ));

                // Executable Test Suite Name
                add(new ExecMapping("Executable Test Suite", "executableTestSuiteName",
                    properties -> etfEndpoint.executableTestSuites().itemByLabel(properties.iterator().next()).get(),
                    etsHelp
                ));

                // Executable Test Suite IDs
                add(new ExecMapping("Executable Test Suite", "executableTestSuiteIds",
                    properties -> etfEndpoint.executableTestSuites().itemsById(properties),
                    etsHelp
                ));

                // tagName
                add(new ExecMapping("Executable Test Suite", "tagName",
                    properties -> {
                        final Tag tag = etfEndpoint.tags().itemByLabel(properties.iterator().next()).get();
                        return etfEndpoint.executableTestSuites().itemsByTag(tag);
                    },
                    tagHelp
                ));

            }};

            TestRunExecutable candidate=null;
            for(final ExecMapping mapping : mappings) {
                final TestRunExecutable e = executable(runObj, mapping);
                if(e!=null) {
                    candidate=e;
                }
            }
            if(candidate!=null) {
                executable=candidate;
            }else{
                throw new IllegalStateException("Property testRunTemplateId, testRunTemplateName, "
                    + "tagName, executableTestSuiteIds or executableTestSuiteId not found");
            }


            final RunParameters runParameters;
            if(runObj.has("arguments")) {
                final Map<String, Object> argumentsRaw = runObj.getJSONObject("arguments").toMap();
                final Map<String, String> arguments = argumentsRaw.entrySet().stream().collect(Collectors
                    .toMap(Map.Entry::getKey, stringObjectEntry ->
                        (String) stringObjectEntry.getValue().toString(), (a, b) -> b));
                runParameters = executable.parameters().setFrom(arguments);
            }else{
                runParameters = null;
            }

            final AdHocTestObject testObject;
            if(dataFile!=null) {
                testObject = etfEndpoint.newAdHocTestObject().fromDataSet(this.dataFile.toPath());
            }else if(runObj.has("endpoint")){
            	testObject = etfEndpoint.newAdHocTestObject().fromService(new URL(runObj.getString("endpoint")));
            } else {
            	testObject = etfEndpoint.newAdHocTestObject().fromDataSet(new URL(runObj.getString("url")));
            }
            if(generateTemplate) {
                final TestRun tr = executable.execute(testObject, runParameters);
                try {
                    generateTemplate(tr.result());
                } catch (final ExecutionException e) {
                    logger.error("Can not generate a Template from a failed Test Run", e);
                }
                return Collections.emptyList();
            }else{
                executable.execute(testObject, this, runParameters);
                return testCases.values();
            }
        }

        private void generateTemplate(final TestRunResult testRun) throws IOException {
            expectedFile.createNewFile();
            try(final BufferedWriter fileWriter =  new BufferedWriter(new FileWriter(expectedFile,
                StandardCharsets.UTF_8))) {
                fileWriter.write('{');
                boolean oneWritten=false;
                for (final TestResult result : testRun) {
                    if(result instanceof TestAssertionResult) {
                        if(oneWritten) {
                            fileWriter.write(',');
                        }
                        oneWritten=true;
                        final TestAssertionResult assertionResult = (TestAssertionResult) result;
                        final JSONObject resultJson = new JSONObject();
                        final JSONObject details = new JSONObject();
                        details.put("expectedResult", assertionResult.resultStatus());
                        final Collection<String> messages = assertionResult.messages();
                        if(messages.isEmpty()) {
                            details.put("expectedMessageCount", 0);
                        }else{
                            details.put("expectedMessages", new JSONArray(assertionResult.messages()));
                        }
                        details.put("description", "Generated from Test Run");
                        resultJson.put(assertionResult.label(), details);
                        final StringWriter objWriter = new StringWriter();
                        resultJson.write(objWriter);
                        final String resultStr = objWriter.toString();
                        fileWriter.write(' ');
                        fileWriter.write(resultStr.substring(1, resultStr.length()-1));
                        fileWriter.newLine();
                    }
                }
                fileWriter.write('}');
            }
        }

        @FunctionalInterface
        private interface Exec {
            TestRunExecutable call(final Collection<String> properties) throws RemoteInvocationException;
        }

        @FunctionalInterface
        private interface HelpAvailable {
            void logHelp() throws RemoteInvocationException;
        }

        private static class ExecMapping {
            final String typeName;
            final String propertyName;
            final Exec exec;
            final HelpAvailable available;

            ExecMapping(final String typeName, final String propertyName, final Exec exec,
                final HelpAvailable available) {
                this.typeName = typeName;
                this.propertyName = propertyName;
                this.exec = exec;
                this.available = available;
            }
        }

        private TestRunExecutable executable(final JSONObject runObj, final ExecMapping mapping)
            throws RemoteInvocationException {
            try {
                if(runObj.has(mapping.propertyName)) {
                    if(mapping.propertyName.endsWith("s")) {
                        final List<String> etsIds = new ArrayList<>();
                        for (final Object executableTestSuiteIdObj : runObj.getJSONArray(mapping.propertyName)) {
                            final String executableTestSuiteId = (String) executableTestSuiteIdObj;
                            etsIds.add(executableTestSuiteId);
                        }
                        logger.info("Using {} {}", mapping.typeName+"s", SUtils.concatStr(",",etsIds));
                        return mapping.exec.call(etsIds);
                    }else{
                        final String etsName = runObj.getString(mapping.propertyName);
                        logger.info("Using Executable Test Suite {}", etsName);
                        return mapping.exec.call(Collections.singleton(etsName));
                    }
                }
            }catch(NoSuchElementException e) {
                logger.error("At least one {} could not be found", mapping.typeName);
                mapping.available.logHelp();
                throw new IllegalArgumentException("At least one "+mapping.typeName+" could not be found. ", e);
            }
            return null;
        }

        @Override
        public void testRunFinished(final TestRunResult testRun) {
            for (final TestResult result : testRun) {
                if(result instanceof TestAssertionResult) {
                    final TestCase tc = testCases.get(result.label());
                    if(tc!=null) {
                        ((DefaultTestCase)tc).resultAvailable((TestAssertionResult) result);
                    }else if(wildCardTestCase!=null) {
                        this.wildCardTestCase.storeResult((TestAssertionResult) result);
                    }
                }
            }
            this.testCases.values().forEach(TestCase::testRunFinished);
        }

        @Override
        public void exceptionOccurred(final Exception exception) {
            fail(exception);
        }
    }

    public static PreparedTestSuite createTestSuite(final IFile testSuiteDirectory) throws IOException {
        return new PreparedTestSuite(testSuiteDirectory);
    }
}
