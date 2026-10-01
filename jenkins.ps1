#requires -Version 5.1
<#
.SYNOPSIS
    Minimal Jenkins API client for this project.

.DESCRIPTION
    Auths with a Jenkins API token (Basic). The token is stored DPAPI-encrypted
    in jenkins-creds.xml next to this script (override with -StorePath or
    $env:JENKINS_CREDS) and is never committed. API-token auth is exempt from
    CSRF crumb protection, so POSTs need no crumb.

.PARAMETER Tests
    Run unit tests, lint, and the API 34-36 emulator matrix.

.PARAMETER NoTests
    Build and sign only: skip every test stage. Given to the job as
    RUN_TESTS=false.

    With neither switch the parameter is not sent at all, so the job's own
    default applies -- which is what you want when you have not an opinion.

.EXAMPLE
    .\jenkins.ps1 login
    .\jenkins.ps1 status
    .\jenkins.ps1 build -NoTests
    .\jenkins.ps1 build -NoWait
#>
[CmdletBinding()]
param(
    [ValidateSet('login', 'status', 'build')]
    [string] $Command = 'status',

    [string] $JenkinsUrl = $(if ($env:JENKINS_URL) { $env:JENKINS_URL } else { 'https://jenkins.smccloud.com' }),
    [string] $Job = 'Hot-Death-Uno',
    [switch] $NoWait,
    # The default matches jenkins.sh's 10800s. The emulator matrix boots three
    # AVDs in sequence against an 8 GB controller, so a run with tests is a
    # half-hour job and 30 minutes used to time out on a green build.
    [int] $TimeoutMinutes = 180,
    [int] $PollSeconds = 10,

    # RUN_TESTS, tri-state: neither switch means "do not send the parameter".
    [switch] $Tests,
    [switch] $NoTests,

    [string] $StorePath = $(if ($env:JENKINS_CREDS) { $env:JENKINS_CREDS } else { Join-Path $PSScriptRoot 'jenkins-creds.xml' })
)

# The job declares one parameter, RUN_TESTS: off means build and sign only, with
# no unit tests, no lint and no emulator matrix. This posts to buildWithParameters
# rather than /build, because a parameterized job rejects a bare POST to /build
# with "HTTP 400 Nothing is submitted" -- so the endpoint change and the switches
# are the same fix, not two.
if ($Tests -and $NoTests) {
    Write-Error 'Pass at most one of -Tests and -NoTests.' -ErrorAction Continue
    exit 2
}

$ErrorActionPreference = 'Stop'

$JenkinsUrl = $JenkinsUrl.TrimEnd('/')
$Base = "$JenkinsUrl/job/$Job"
$Store = $StorePath

# PowerShell 5.1 runs on .NET Framework, whose default protocol set on an older
# box may still be SSL3/TLS1.0. Modern Jenkins refuses those.
if ($JenkinsUrl -like 'https://*') {
    [Net.ServicePointManager]::SecurityProtocol =
        [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
}

function Get-ApiHeader {
    $cred = $null
    if (Test-Path -LiteralPath $Store) {
        # This file ships as a comment-only placeholder and is later overwritten
        # by Export-Clixml. A serialized PSCredential always has an <Objs> root,
        # so strip comments and test for one: otherwise an empty or comment-only
        # file throws out of Import-Clixml, and a comment that merely mentions
        # the sentinel would make this file look configured.
        $raw = (Get-Content -LiteralPath $Store -Raw) -replace '(?s)<!--.*?-->', ''
        if ($raw -match '<Objs') {
            try { $cred = Import-Clixml -LiteralPath $Store }
            catch { Write-Warning "Ignoring unreadable credential store $Store" }
        }
    }
    if (-not $cred) {
        Write-Host 'Enter the Jenkins user name, and paste the API token as the password.' -ForegroundColor Yellow
        $cred = Get-Credential -Message 'Jenkins API token (user name / token)'
        if (-not $cred) { throw 'No credential entered.' }
        $cred | Export-Clixml -LiteralPath $Store -Depth 2
        Write-Host "Saved to $Store" -ForegroundColor Green
    }
    $pair = '{0}:{1}' -f $cred.UserName, $cred.GetNetworkCredential().Password
    @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair)) }
}

function Get-BuildSummary($b) {
    $when = if ($b.building) { 'in progress' } else { [string]$b.result }
    '{0,6}  {1,-11} {2}' -f $b.number, $when, (Format-Duration $b.duration)
}

function Format-Duration($ms) {
    if ($ms -le 0) { return '-' }
    $ts = [TimeSpan]::FromMilliseconds($ms)
    if ($ts.TotalMinutes -lt 1) { return ('{0}s' -f [int]$ts.TotalSeconds) }
    ('{0}m{1:00}s' -f [int]$ts.TotalMinutes, $ts.Seconds)
}

$hdr = Get-ApiHeader
# .fullName, not .name: /me/api/json has no "name" member, so .name was empty.
Write-Host "connected to $JenkinsUrl as $((Invoke-RestMethod "$JenkinsUrl/me/api/json" -Headers $hdr).fullName)"

switch ($Command) {

    'login' { break }

    'status' {
        # Not $job. PowerShell variable names are case-insensitive, so a local
        # $job here is the same variable as the [string] $Job parameter -- and
        # that constraint is enforced on every assignment, which silently turned
        # this response into a System.String and made $job.lastBuild null. So
        # `status` reported "no builds yet" against a job with 69 of them.
        $jobInfo = Invoke-RestMethod "$Base/api/json?tree=inQueue,lastBuild[number,result,building,duration,url]" -Headers $hdr
        Write-Host ''
        if ($jobInfo.inQueue) { Write-Host 'queue: a build of this job is waiting to start' -ForegroundColor Yellow }
        if ($jobInfo.lastBuild) {
            Get-BuildSummary $jobInfo.lastBuild
            Write-Host "console: $($jobInfo.lastBuild.url)console"
        } else {
            Write-Host 'no builds yet'
        }
        break
    }

    'build' {
        # buildWithParameters, always -- see the note above the param block. A
        # job that declares a parameter refuses a bare POST to /build, and one
        # that declares none accepts this just the same, so there is no need to
        # detect which kind this is.
        $body = $null
        $label = ''
        if ($Tests) {
            $body = @{ 'RUN_TESTS' = 'true' }
            $label = ' (RUN_TESTS=true)'
        }
        elseif ($NoTests) {
            $body = @{ 'RUN_TESTS' = 'false' }
            $label = ' (RUN_TESTS=false)'
        }

        if ($body) {
            # ContentType stated rather than left to PS 5.1 to guess: a hashtable
            # body is what carries RUN_TESTS, and Jenkins reads it as form fields.
            $resp = Invoke-WebRequest -Uri "$Base/buildWithParameters" -Method Post -Body $body `
                -ContentType 'application/x-www-form-urlencoded' -Headers $hdr -UseBasicParsing
        }
        else {
            $resp = Invoke-WebRequest -Uri "$Base/buildWithParameters" -Method Post -Headers $hdr -UseBasicParsing
        }
        $queueUrl = $resp.Headers['Location']
        Write-Host "queued: $queueUrl$label" -ForegroundColor Green

        $deadline = (Get-Date).AddMinutes($TimeoutMinutes)
        $queue = $null
        while ((Get-Date) -lt $deadline) {
            $queue = Invoke-RestMethod "${queueUrl}api/json?tree=id,why,cancelled,executable[number,url]" -Headers $hdr
            if ($queue.cancelled) { throw 'Build was cancelled before it started.' }
            if ($queue.executable) { break }
            Write-Host "waiting in queue: $($queue.why)" -ForegroundColor DarkGray
            Start-Sleep -Seconds 2
        }
        if (-not $queue.executable) { throw "Timed out after $TimeoutMinutes min waiting for the build to start." }

        $url = $queue.executable.url
        if ($NoWait) {
            Write-Host "build #$($queue.executable.number) started: $url" -ForegroundColor Green
            break
        }

        Write-Host "build #$($queue.executable.number) started" -ForegroundColor Green
        $last = ''
        while ((Get-Date) -lt $deadline) {
            $b = Invoke-RestMethod "${url}api/json?tree=number,result,building,duration,estimatedDuration" -Headers $hdr
            $line = Get-BuildSummary $b
            if ($line -ne $last) { Write-Host $line; $last = $line }
            if (-not $b.building) {
                Write-Host "console: ${url}console"
                if ($b.result -ne 'SUCCESS') { exit 1 }
                break
            }
            if ($b.estimatedDuration -gt 0) {
                $pct = [math]::Min(100, [math]::Round(100 * $b.duration / $b.estimatedDuration))
                Write-Host ("  {0}% of estimate" -f $pct) -ForegroundColor DarkGray
            }
            Start-Sleep -Seconds $PollSeconds
        }
        if ($b.building) { throw "Timed out after $TimeoutMinutes min; build is still running." }
        break
    }
}
