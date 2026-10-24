# Desfaz o que o instalar.ps1 mudou no projetor, perguntando passo a passo.
# Usa os valores originais anotados em alteracoes.txt; sem esse arquivo,
# usa os valores de fábrica conhecidos do HY320.
#
# Uso: clique duas vezes em desfazer.bat, ou rode
#   powershell -ExecutionPolicy Bypass -File desfazer.ps1

$ErrorActionPreference = 'Stop'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Pkg = 'dev.hy320.launcher'
$StateFile = Join-Path $Here 'alteracoes.txt'

function Find-Adb {
    $local = Join-Path $Here 'adb.exe'
    if (Test-Path $local) { return $local }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    Write-Host 'ADB não encontrado. Coloque o adb.exe nesta pasta.' -ForegroundColor Red
    exit 1
}

function Ask([string]$question) {
    $answer = Read-Host "$question [s/N]"
    return $answer -match '^[sSyY]'
}

function Adb { & $script:AdbExe -s $script:Device @args }
function Shell([string]$command) { (Adb shell $command | Out-String).Trim() }

$AdbExe = Find-Adb
Write-Host ''
Write-Host '=== Launcher HY320: desfazer ===' -ForegroundColor Cyan

$ip = Read-Host 'IP do projetor'
$Device = if ($ip -match ':') { $ip } else { "${ip}:5555" }
& $AdbExe connect $Device | Out-Host
if ((& $AdbExe -s $Device get-state 2>$null | Out-String).Trim() -ne 'device') {
    Write-Host "Não foi possível conectar em $Device." -ForegroundColor Red
    exit 1
}

# Valores originais (ou os de fábrica do HY320)
$original = @{
    'sound_effects_enabled'  = '1'
    'hdmi_control_enabled'   = '1'
    'persist.sys.usb0device' = '1'
    'home'                   = 'com.htc.htclauncherhighenglishd08/com.htc.activity.HomeActivity'
}
$disabledApps = @()
if (Test-Path $StateFile) {
    Write-Host "Usando os valores anotados em $StateFile"
    foreach ($line in Get-Content $StateFile -Encoding UTF8) {
        if (-not $line) { continue }
        $key, $value = $line -split '=', 2
        if ($key -like 'disabled:*') { $disabledApps += $key.Substring(9) } else { $original[$key] = $value }
    }
} else {
    Write-Host 'alteracoes.txt não encontrado: usando os valores de fábrica do HY320.' -ForegroundColor Yellow
    $disabledApps = (Shell 'pm list packages -d') -split "`n" |
        ForEach-Object { $_.Trim() -replace '^package:', '' } |
        Where-Object { $_ -in 'com.htc.eventuploadservice', 'android.rockchip.update.service',
            'com.htc.htcotaupdate', 'com.htc.storeos', 'com.htc.htclauncherhighenglishd08' }
}

foreach ($app in $disabledApps) {
    if (Ask "Reativar $app?") { Shell "pm enable $app" | Out-Null }
}
if ((Shell 'settings get system sound_effects_enabled') -ne $original['sound_effects_enabled'] -and (Ask 'Voltar o som de clique das teclas?')) {
    Shell "settings put system sound_effects_enabled $($original['sound_effects_enabled'])" | Out-Null
}
if ((Shell 'settings get global hdmi_control_enabled') -ne $original['hdmi_control_enabled'] -and (Ask 'Religar o HDMI-CEC?')) {
    Shell "settings put global hdmi_control_enabled $($original['hdmi_control_enabled'])" | Out-Null
}
if ((Shell 'getprop persist.sys.usb0device') -ne $original['persist.sys.usb0device'] -and
    (Ask 'Voltar a porta USB ao modo de fábrica? (pendrives deixam de funcionar)')) {
    Shell "setprop persist.sys.usb0device $($original['persist.sys.usb0device'])" | Out-Null
}
if ((Shell 'pm list packages') -match [regex]::Escape("package:$Pkg") -and (Ask 'Remover o launcher e voltar à tela inicial original?')) {
    Shell "cmd package set-home-activity $($original['home'])" | Out-Null
    # Desligar o filtro antes de remover, preservando outros serviços de acessibilidade
    $services = (Shell 'settings get secure enabled_accessibility_services') -split ':' |
        Where-Object { $_ -and $_ -notmatch [regex]::Escape($Pkg) }
    if ($services) { Shell "settings put secure enabled_accessibility_services $($services -join ':')" | Out-Null }
    else {
        Shell 'settings delete secure enabled_accessibility_services' | Out-Null
        Shell 'settings put secure accessibility_enabled 0' | Out-Null
    }
    Adb uninstall $Pkg | Out-Host
}

Write-Host "`nPronto." -ForegroundColor Green
if (Ask 'Reiniciar o projetor agora?') {
    Write-Host 'Aguardando 15 s para o sistema gravar as mudanças...'
    Start-Sleep -Seconds 15
    Adb reboot
}
