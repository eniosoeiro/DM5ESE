# Run with 32-bit Windows PowerShell. Creates the installed COM object only;
# does not call AutoConnect, TestConnection, ReceiveFile or instrument setters.
$ErrorActionPreference = 'Stop'
if ([Environment]::Is64BitProcess) {
    throw 'Use C:\Windows\SysWOW64\WindowsPowerShell\v1.0\powershell.exe -NoProfile -File scripts\inspect-ge-component.ps1'
}
$driver = New-Object -ComObject DM5EFileSrv.DM5EInstDrv.1
try {
    [pscustomobject]@{
        ProcessBits = 32
        ProgId = 'DM5EFileSrv.DM5EInstDrv.1'
        Created = $true
        Members = @($driver | Get-Member | Select-Object Name, MemberType, Definition)
    } | ConvertTo-Json -Depth 4
} finally {
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($driver)
}
