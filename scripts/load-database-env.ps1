# Optional local setup helper. Keep .local/database.psd1 out of source control.
$configPath = Join-Path $PSScriptRoot '../.local/database.psd1'
if (-not (Test-Path -LiteralPath $configPath)) {
    throw 'Local database settings not found. Set DB_URL, DB_USERNAME, DB_PASSWORD and optional PG_TEST_* environment variables, or create .local/database.psd1.'
}
$settings = Import-PowerShellDataFile -LiteralPath $configPath
foreach ($key in @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'PG_TEST_URL', 'PG_TEST_USERNAME', 'PG_TEST_PASSWORD',
        'REDIS_HOST', 'REDIS_PORT', 'REDIS_DATABASE', 'REDIS_NAMESPACE', 'REDIS_USERNAME', 'REDIS_PASSWORD')) {
    if ($settings.ContainsKey($key)) {
        [Environment]::SetEnvironmentVariable($key, [string]$settings[$key], 'Process')
    }
}
Write-Output 'Database environment loaded for this terminal. Credentials were not printed.'
$bridgePath = Join-Path $PSScriptRoot '../.local/bridge.psd1'
if (Test-Path -LiteralPath $bridgePath) {
    $bridgeSettings = Import-PowerShellDataFile -LiteralPath $bridgePath
    [Environment]::SetEnvironmentVariable('BRIDGE_API_KEY', [string]$bridgeSettings.BRIDGE_API_KEY, 'Process')
    Write-Output 'Bridge key loaded for this terminal. The key was not printed.'
}
