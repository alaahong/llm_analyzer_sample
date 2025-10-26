Param(
  [string]$PomPath = "D:\code\zizireader\pom.xml"
)

[xml]$xml = Get-Content -LiteralPath $PomPath
$ns = $xml.DocumentElement.NamespaceURI
function New-Node($name) { return $xml.CreateElement($name, $ns) }
function Get-ChildByLocalName($parent, $localName) {
  foreach ($n in $parent.ChildNodes) { if ($n.LocalName -eq $localName) { return $n } }
  return $null
}

# Ensure properties and cucumber.version
$properties = Get-ChildByLocalName $xml.project 'properties'
if (-not $properties) { $properties = New-Node 'properties'; $xml.project.AppendChild($properties) | Out-Null }
$cukeProp = Get-ChildByLocalName $properties 'cucumber.version'
if (-not $cukeProp) { $cukeProp = New-Node 'cucumber.version'; $cukeProp.InnerText = '7.15.0'; $properties.AppendChild($cukeProp) | Out-Null }

# Ensure dependencies and add cucumber deps if missing
$dependencies = Get-ChildByLocalName $xml.project 'dependencies'
if (-not $dependencies) { $dependencies = New-Node 'dependencies'; $xml.project.AppendChild($dependencies) | Out-Null }
function Ensure-Dependency($g,$a,$v,$scope) {
  foreach ($dep in $dependencies.ChildNodes) {
    if ($dep.LocalName -ne 'dependency') { continue }
    $gid = Get-ChildByLocalName $dep 'groupId'
    $aid = Get-ChildByLocalName $dep 'artifactId'
    if ($gid -and $aid -and $gid.InnerText -eq $g -and $aid.InnerText -eq $a) { return }
  }
  $d = New-Node 'dependency'
  foreach ($pair in @(@('groupId',$g), @('artifactId',$a), @('version',$v), @('scope',$scope))) {
    $n = New-Node $pair[0]; $n.InnerText = $pair[1]; $d.AppendChild($n) | Out-Null
  }
  $dependencies.AppendChild($d) | Out-Null
}
Ensure-Dependency 'io.cucumber' 'cucumber-java' '${cucumber.version}' 'test'
Ensure-Dependency 'io.cucumber' 'cucumber-junit-platform-engine' '${cucumber.version}' 'test'
Ensure-Dependency 'io.cucumber' 'cucumber-spring' '${cucumber.version}' 'test'

# Ensure failsafe plugin with executions
$build = Get-ChildByLocalName $xml.project 'build'
if (-not $build) { $build = New-Node 'build'; $xml.project.AppendChild($build) | Out-Null }
$plugins = Get-ChildByLocalName $build 'plugins'
if (-not $plugins) { $plugins = New-Node 'plugins'; $build.AppendChild($plugins) | Out-Null }

function Find-Plugin($groupId,$artifactId) {
  foreach ($pl in $plugins.ChildNodes) {
    if ($pl.LocalName -ne 'plugin') { continue }
    $gid = Get-ChildByLocalName $pl 'groupId'
    $aid = Get-ChildByLocalName $pl 'artifactId'
    if ($gid -and $aid -and $gid.InnerText -eq $groupId -and $aid.InnerText -eq $artifactId) { return $pl }
  }
  return $null
}
$fail = Find-Plugin 'org.apache.maven.plugins' 'maven-failsafe-plugin'
if (-not $fail) {
  $fail = New-Node 'plugin'
  foreach ($pair in @(@('groupId','org.apache.maven.plugins'), @('artifactId','maven-failsafe-plugin'), @('version','3.2.5'))) {
    $n = New-Node $pair[0]; $n.InnerText = $pair[1]; $fail.AppendChild($n) | Out-Null
  }
  $plugins.AppendChild($fail) | Out-Null
}
$executions = Get-ChildByLocalName $fail 'executions'
if (-not $executions) { $executions = New-Node 'executions'; $fail.AppendChild($executions) | Out-Null }
$execution = Get-ChildByLocalName $executions 'execution'
if (-not $execution) { $execution = New-Node 'execution'; $executions.AppendChild($execution) | Out-Null }
$goals = Get-ChildByLocalName $execution 'goals'
if (-not $goals) { $goals = New-Node 'goals'; $execution.AppendChild($goals) | Out-Null }
function Ensure-Goal($goalName) {
  foreach ($g in $goals.ChildNodes) { if ($g.LocalName -eq 'goal' -and $g.InnerText -eq $goalName) { return } }
  $g = New-Node 'goal'; $g.InnerText = $goalName; $goals.AppendChild($g) | Out-Null
}
Ensure-Goal 'integration-test'
Ensure-Goal 'verify'

$xml.Save($PomPath)
Write-Host 'pom.xml updated for Cucumber (xmlsafe).'

