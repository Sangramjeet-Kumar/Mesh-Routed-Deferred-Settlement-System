$keyPath = Join-Path $PSScriptRoot '../.local/bridge.psd1'
if (Test-Path -LiteralPath $keyPath) {
    Write-Output 'Local bridge key already exists; it was not replaced.'
    return
}
New-Item -ItemType Directory -Path (Split-Path $keyPath) -Force | Out-Null
$key = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
Set-Content -LiteralPath $keyPath -Value "@{ BRIDGE_API_KEY = '$key' }" -Encoding utf8
Write-Output 'Generated bridge key in ignored .local/bridge.psd1. The key was not printed.'
