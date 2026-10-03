#Requires -RunAsAdministrator
<#
.SYNOPSIS
    Allows EzTransfer through Windows Defender Firewall on Private and Domain networks.

.DESCRIPTION
    jpackage-built MSIs cannot create firewall rules, so this script is meant to run from an installer
    wrapper (Inno Setup [Run] entry, Conveyor, or similar) or manually once after installing.

    Rules are scoped to the EzTransfer executable and to Private/Domain profiles only. Public networks stay
    blocked on purpose; the app warns the user to switch the network to Private instead.

.PARAMETER ProgramPath
    Full path to the installed EzTransfer.exe.

.PARAMETER Remove
    Deletes the rules instead of creating them (for uninstall).

.EXAMPLE
    .\add-firewall-rules.ps1 -ProgramPath "C:\Program Files\EzTransfer\EzTransfer.exe"
#>
param(
    [Parameter(Mandatory = $true)][string]$ProgramPath,
    [switch]$Remove
)

$ErrorActionPreference = 'Stop'
$port = 53318
$names = @('EzTransfer (TCP-In)', 'EzTransfer (UDP-In)')

# Recreate rather than update so re-running after an upgrade never leaves stale paths behind.
foreach ($name in $names) {
    Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue | Remove-NetFirewallRule
}
if ($Remove) { return }

if (-not (Test-Path -LiteralPath $ProgramPath)) {
    throw "Program not found: $ProgramPath"
}

# TCP carries the HTTPS API and transfers; UDP carries discovery announcements.
New-NetFirewallRule -DisplayName $names[0] -Direction Inbound -Action Allow -Protocol TCP `
    -LocalPort $port -Program $ProgramPath -Profile Private, Domain | Out-Null
New-NetFirewallRule -DisplayName $names[1] -Direction Inbound -Action Allow -Protocol UDP `
    -LocalPort $port -Program $ProgramPath -Profile Private, Domain | Out-Null

Write-Host "Firewall rules added for $ProgramPath on port $port."
