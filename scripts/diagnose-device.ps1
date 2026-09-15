# Read-only inventory; does not open ports or send commands.
$ErrorActionPreference = 'Stop'
Get-PnpDevice -PresentOnly |
    Where-Object { $_.InstanceId -match '^USB' -or $_.Class -eq 'Ports' } |
    Select-Object Status, Class, FriendlyName, InstanceId |
    Format-Table -AutoSize
