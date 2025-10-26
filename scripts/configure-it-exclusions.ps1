Param([string]$PomPath = "D:\code\zizireader\pom.xml")
[xml]$xml = Get-Content -LiteralPath $PomPath
$ns = $xml.DocumentElement.NamespaceURI
function NewN($name){$xml.CreateElement($name,$ns)}
function GetChild($parent,$local){ foreach($n in $parent.ChildNodes){ if($n.LocalName -eq $local){ return $n } } return $null }
$build = GetChild $xml.project 'build'; if(-not $build){ $build = NewN 'build'; $xml.project.AppendChild($build) | Out-Null }
$plugins = GetChild $build 'plugins'; if(-not $plugins){ $plugins = NewN 'plugins'; $build.AppendChild($plugins) | Out-Null }
function EnsurePlugin($gid,$aid,$ver){
  foreach($pl in $plugins.ChildNodes){ if($pl.LocalName -ne 'plugin'){continue}
    $g = GetChild $pl 'groupId'; $a = GetChild $pl 'artifactId'; if($g -and $a -and $g.InnerText -eq $gid -and $a.InnerText -eq $aid){ return $pl }
  }
  $pl = NewN 'plugin'; $g = NewN 'groupId'; $g.InnerText=$gid; $pl.AppendChild($g)|Out-Null; $a = NewN 'artifactId'; $a.InnerText=$aid; $pl.AppendChild($a)|Out-Null; if($ver){ $v=NewN 'version'; $v.InnerText=$ver; $pl.AppendChild($v)|Out-Null }
  $plugins.AppendChild($pl)|Out-Null; return $pl
}
# Configure surefire excludes ITs
$sure = EnsurePlugin 'org.apache.maven.plugins' 'maven-surefire-plugin' '3.2.5'
$cfg = GetChild $sure 'configuration'; if(-not $cfg){ $cfg = NewN 'configuration'; $sure.AppendChild($cfg)|Out-Null }
$excludes = GetChild $cfg 'excludes'; if(-not $excludes){ $excludes = NewN 'excludes'; $cfg.AppendChild($excludes)|Out-Null }
function EnsureExclude($pattern){ foreach($e in $excludes.ChildNodes){ if($e.LocalName -eq 'exclude' -and $e.InnerText -eq $pattern){ return } } $x=NewN 'exclude'; $x.InnerText=$pattern; $excludes.AppendChild($x)|Out-Null }
EnsureExclude '**/*IT.java'
EnsureExclude '**/*ITCase.java'
# Configure failsafe includes ITs
$fail = EnsurePlugin 'org.apache.maven.plugins' 'maven-failsafe-plugin' '3.2.5'
$fCfg = GetChild $fail 'configuration'; if(-not $fCfg){ $fCfg = NewN 'configuration'; $fail.AppendChild($fCfg)|Out-Null }
$includes = GetChild $fCfg 'includes'; if(-not $includes){ $includes = NewN 'includes'; $fCfg.AppendChild($includes)|Out-Null }
function EnsureInclude($pattern){ foreach($e in $includes.ChildNodes){ if($e.LocalName -eq 'include' -and $e.InnerText -eq $pattern){ return } } $x=NewN 'include'; $x.InnerText=$pattern; $includes.AppendChild($x)|Out-Null }
EnsureInclude '**/*IT.java'
EnsureInclude '**/*ITCase.java'
# Ensure executions goals
$execs = GetChild $fail 'executions'; if(-not $execs){ $execs = NewN 'executions'; $fail.AppendChild($execs)|Out-Null }
$execution = GetChild $execs 'execution'; if(-not $execution){ $execution = NewN 'execution'; $execs.AppendChild($execution)|Out-Null }
$goals = GetChild $execution 'goals'; if(-not $goals){ $goals = NewN 'goals'; $execution.AppendChild($goals)|Out-Null }
function EnsureGoal($name){ foreach($g in $goals.ChildNodes){ if($g.LocalName -eq 'goal' -and $g.InnerText -eq $name){ return } } $n=NewN 'goal'; $n.InnerText=$name; $goals.AppendChild($n)|Out-Null }
EnsureGoal 'integration-test'
EnsureGoal 'verify'
$xml.Save($PomPath)
Write-Host 'pom.xml updated: surefire excludes and failsafe includes configured.'

