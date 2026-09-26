$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

Write-Host "Running unit tests (testDebugUnitTest)..."

cmd /c ".\gradlew.bat testDebugUnitTest" > test_output.txt 2>&1
$gradleExit = $LASTEXITCODE

# Gradle writes one JUnit XML per test class; sum them for a summary that does not need the log.
$resultsDir = Join-Path $PSScriptRoot "app\build\test-results\testDebugUnitTest"
$total = 0; $failures = 0; $errors = 0; $skipped = 0; $classes = 0
$failed = @()
if (Test-Path $resultsDir) {
    foreach ($file in Get-ChildItem $resultsDir -Filter "TEST-*.xml") {
        [xml]$xml = Get-Content $file.FullName
        $suite = $xml.testsuite
        $classes++
        $total += [int]$suite.tests
        $failures += [int]$suite.failures
        $errors += [int]$suite.errors
        $skipped += [int]$suite.skipped
        foreach ($case in $suite.testcase) {
            if ($case.failure -or $case.error) {
                $failed += "$($case.classname) > $($case.name)"
            }
        }
    }
}

Write-Host ""
Write-Host "Unit tests: $total in $classes classes, $failures failed, $errors errors, $skipped skipped"
foreach ($name in $failed) { Write-Host "  FAILED: $name" }

if ($gradleExit -eq 0 -and $failures -eq 0 -and $errors -eq 0) {
    Write-Host "Tests passed!"
}
else {
    Write-Host "Tests failed! (gradle exit $gradleExit)"
}

Write-Host "Output saved to test_output.txt; reports in app\build\reports\tests\testDebugUnitTest\index.html"
exit $gradleExit
