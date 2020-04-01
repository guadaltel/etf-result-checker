# ETF Result Checker

[![European Union Public Licence 1.2](https://img.shields.io/badge/license-EUPL%201.2-blue.svg)](https://joinup.ec.europa.eu/software/page/eupl)

ETF Result Checker is a tool for testing Executable Test Suites by comparing the expected 
results of a test run with the actual results.

## Prerequisites

- Java 11

## Structure

The test model is rather simple and structures the tests into two hierarchical levels:

- Test Suite
- Test

The _Test Suite_ name is taken from the directory name that is created inside the _ddt_ folder. The _Tests_ 
are listed in the _expected.json_ file. and describe which results are expected for a specific 
_ETF Assertion_.

In addition to the expected.json file, the folder must contain at least one file that specifies which 
tests are started and which parameters should be used.

## Test Run configuration - run.json file

- testRunTemplateId / testRunTemplateName / tagName / executableTestSuiteIds / executableTestSuiteId
- url (mandatory if the directory does not contain a ZIP file)
- arguments (optional)

Example:

```json
    {
    "executableTestSuiteId": "EIDcb....",
    "arguments": {
        "test_to_execute": "*",
        "another_argument": "..."
  }
```

## Data set - a file with the .zip extension

The file will be used and uploaded to the ETF test instance. If the directory does not contain a file, 
the property `url` must be set in the run.json file.

## Expected Result configuration - expected.json file

The file will be generated from the Test Run results if it does not exist.

Structure:

```json
{
    "Name of the Assertion to test": {
        "expectedResult": "FAILED",
        "description": "Generated from Test Run",
        "expectedMessages": ["Error X occuerd due ..."]
    },
    "Name of another Assertion": {
        "expectedResult": "PASSED",
        "description": "Generated from Test Run",
    },
    "*": {
        "expectedResult": "NOT_APPLICABLE",
        "description": "All other Tests are not applicable",
    }
}
```

Properties:

- expectedResult : ETF Status code
- description : Description of the test
- expectedMessages (optional) : non-sequential test whether all messages are present
- expectedMessageCount (optional) : alternative test of the number of error messages
- maxDurationMs (optional) : upper bound of the test execution time

The optional wildcard Test, named `*`, will be applied to all results that are not explicitly listed. If the wildcard Test is not used, the results of unlisted tests are ignored accordingly.

## Endpoint configuration - endpoint.properties file

The endpoint.properties file is located in the ddt root directory.

- endpoint : URL to test instance
- username (optional)
- password (optional)

## Run tests

Run the Gradle `test` task in your IDE or execute on the command line:

```
./gradlew test
```

## Future work

- Service Tests have not been tested
- `maxDurationMs` could be implemented on the _Tests Suite_ level to check the overall execution time
