Param(
  [string]$PomPath = "D:\code\zizireader\pom.xml"
)

[xml]$xml = Get-Content -LiteralPath $PomPath
$ns = $xml.DocumentElement.NamespaceURI

function New-Node($name) { return $xml.CreateElement($name, $ns) }

# Ensure <properties>
$props = $xml.project.properties
if (-not $props) {
  $props = New-Node 'properties'
  $xml.project.AppendChild($props) | Out-Null
}
if (-not $props.SelectSingleNode('m:cucumber.version', @{m=$ns})) {
  $p = New-Node 'cucumber.version'
  $p.InnerText = '7.15.0'
  $props.AppendChild($p) | Out-Null
}

# Ensure <dependencies>
$deps = $xml.project.dependencies
if (-not $deps) {
  $deps = New-Node 'dependencies'
  $xml.project.AppendChild($deps) | Out-Null
}

function Ensure-Dep($groupId, $artifactId, $version, $scope) {
  $exists = $deps.SelectSingleNode("m:dependency[m:groupId='$groupId' and m:artifactId='$artifactId']", @{m=$ns})
  if (-not $exists) {
    $dep = New-Node 'dependency'
    foreach ($pair in @{'groupId'=$groupId; 'artifactId'=$artifactId; 'version'=$version; 'scope'=$scope}) {
      $node = New-Node ($pair.Keys | Select-Object -First 1)
      $node.InnerText = $pair.Values | Select-Object -First 1
      $dep.AppendChild($node) | Out-Null
    }
    $deps.AppendChild($dep) | Out-Null
  }
}

Ensure-Dep 'io.cucumber' 'cucumber-java' '${cucumber.version}' 'test'
Ensure-Dep 'io.cucumber' 'cucumber-junit-platform-engine' '${cucumber.version}' 'test'
Ensure-Dep 'io.cucumber' 'cucumber-spring' '${cucumber.version}' 'test'

# Ensure failsafe plugin with executions
$build = $xml.project.build
if (-not $build) { $build = New-Node 'build'; $xml.project.AppendChild($build) | Out-Null }
$plugins = $build.plugins
if (-not $plugins) { $plugins = New-Node 'plugins'; $build.AppendChild($plugins) | Out-Null }

$fail = $plugins.SelectSingleNode("m:plugin[m:groupId='org.apache.maven.plugins' and m:artifactId='maven-failsafe-plugin']", @{m=$ns})
if (-not $fail) {
  $fail = New-Node 'plugin'
  foreach ($pair in @{'groupId'='org.apache.maven.plugins'; 'artifactId'='maven-failsafe-plugin'; 'version'='3.2.5'}) {
    $node = New-Node ($pair.Keys | Select-Object -First 1)
    $node.InnerText = $pair.Values | Select-Object -First 1
    $fail.AppendChild($node) | Out-Null
  }
  $plugins.AppendChild($fail) | Out-Null
}
$executions = $fail.executions
if (-not $executions) { $executions = New-Node 'executions'; $fail.AppendChild($executions) | Out-Null }
$execution = $executions.execution
if (-not $execution) { $execution = New-Node 'execution'; $executions.AppendChild($execution) | Out-Null }
$goals = $execution.goals
if (-not $goals) { $goals = New-Node 'goals'; $execution.AppendChild($goals) | Out-Null }

function Ensure-Goal($goalName) {
  $exists = $goals.SelectSingleNode("m:goal[text()='$goalName']", @{m=$ns})
  if (-not $exists) { $g = New-Node 'goal'; $g.InnerText = $goalName; $goals.AppendChild($g) | Out-Null }
}
Ensure-Goal 'integration-test'
Ensure-Goal 'verify'

$xml.Save($PomPath)
Write-Host 'pom.xml updated for Cucumber.'

