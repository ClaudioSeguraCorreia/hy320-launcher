# Instala o launcher no projetor HY320 pelo ADB por Wi-Fi.
# Cada ajuste opcional é perguntado antes; o valor original fica anotado em
# alteracoes.txt (nesta pasta) para o desfazer.ps1 poder voltar atrás.
#
# Uso: clique duas vezes em instalar.bat, ou rode
#   powershell -ExecutionPolicy Bypass -File instalar.ps1

$ErrorActionPreference = 'Stop'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Pkg = 'dev.hy320.launcher'
$StateFile = Join-Path $Here 'alteracoes.txt'

function Find-Adb {
    $local = Join-Path $Here 'adb.exe'
    if (Test-Path $local) { return $local }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    Write-Host 'ADB não encontrado. Baixe o "SDK Platform-Tools" do Android e coloque o adb.exe nesta pasta:' -ForegroundColor Red
    Write-Host '  https://developer.android.com/tools/releases/platform-tools'
    exit 1
}

function Ask([string]$question) {
    $answer = Read-Host "$question [s/N]"
    return $answer -match '^[sSyY]'
}

function Adb { & $script:AdbExe -s $script:Device @args }
function Shell([string]$command) { (Adb shell $command | Out-String).Trim() }

# Anota o valor original só na primeira vez, para não perder o de fábrica ao rodar de novo
function Save-Original([string]$key, [string]$value) {
    if ((Test-Path $StateFile) -and (Select-String -Path $StateFile -Pattern "^$([regex]::Escape($key))=" -Quiet)) { return }
    Add-Content -Path $StateFile -Value "$key=$value" -Encoding UTF8
}

$AdbExe = Find-Adb
Write-Host ''
Write-Host '=== Launcher HY320: instalação ===' -ForegroundColor Cyan
Write-Host 'No projetor, a depuração por rede (ADB) precisa estar ligada. Veja o README.'
Write-Host ''

$ip = Read-Host 'IP do projetor (ex.: 192.168.0.50)'
$Device = if ($ip -match ':') { $ip } else { "${ip}:5555" }
& $AdbExe connect $Device | Out-Host
Write-Host 'Se o projetor perguntar "Permitir depuração", escolha Permitir (marque "Sempre").'
for ($i = 0; $i -lt 30; $i++) {
    $state = (& $AdbExe -s $Device get-state 2>$null | Out-String).Trim()
    if ($state -eq 'device') { break }
    Start-Sleep -Seconds 1
}
if ($state -ne 'device') { Write-Host "Não foi possível conectar em $Device." -ForegroundColor Red; exit 1 }

$model = Shell 'getprop ro.product.model'
$platform = Shell 'getprop ro.board.platform'
$sdk = Shell 'getprop ro.build.version.sdk'
Write-Host "Conectado: $model (plataforma $platform, Android API $sdk)"
if ($sdk -ne '30') {
    Write-Host 'Atenção: o launcher foi feito para Android 11 (API 30).' -ForegroundColor Yellow
    if (-not (Ask 'Continuar mesmo assim?')) { exit 1 }
}

# 1. Instalar o APK
$apk = Get-ChildItem -Path $Here -Filter '*.apk' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { Write-Host 'Coloque o arquivo .apk do launcher nesta pasta.' -ForegroundColor Red; exit 1 }
Write-Host "`n1. Instalando $($apk.Name)..."
$out = (Adb install -r $apk.FullName 2>&1 | Out-String)
Write-Host $out.Trim()
if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
    Write-Host 'Há uma versão com outra assinatura instalada. Rode o desfazer.ps1 (passo de remover o launcher) e tente de novo.' -ForegroundColor Red
    exit 1
}
if ($out -notmatch 'Success') { exit 1 }

# 2. Permissões: ler as imagens próprias e manter o Filtro do controle ligado
Write-Host '2. Concedendo permissões (imagens e Filtro do controle)...'
Shell "pm grant $Pkg android.permission.READ_EXTERNAL_STORAGE" | Out-Null
Shell "pm grant $Pkg android.permission.WRITE_SECURE_SETTINGS" | Out-Null

# 3. Tela inicial padrão
Write-Host '3. Definindo como tela inicial...'
$homeOut = Shell 'cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN'
$currentHome = ($homeOut -split "`n")[-1].Trim()
if ($currentHome -and $currentHome -notmatch $Pkg) { Save-Original 'home' $currentHome }
Shell "cmd package set-home-activity $Pkg/.MainActivity" | Out-Null

Write-Host "`n--- Ajustes opcionais (recomendados no HY320) ---" -ForegroundColor Cyan

$sound = Shell 'settings get system sound_effects_enabled'
if ($sound -ne '0' -and (Ask 'Desligar o som de clique das teclas? (reduz travadas do controle)')) {
    Save-Original 'sound_effects_enabled' $sound
    Shell 'settings put system sound_effects_enabled 0' | Out-Null
}
$cec = Shell 'settings get global hdmi_control_enabled'
if ($cec -ne '0' -and (Ask 'Desligar o HDMI-CEC? (o projetor deixa de consultar o HDMI a cada 30 s)')) {
    Save-Original 'hdmi_control_enabled' $cec
    Shell 'settings put global hdmi_control_enabled 0' | Out-Null
}
$usb = Shell 'getprop persist.sys.usb0device'
if ($usb -eq '1' -and (Ask 'Corrigir a porta USB para reconhecer pendrives? (vem de fábrica em modo dispositivo)')) {
    Save-Original 'persist.sys.usb0device' $usb
    Shell 'setprop persist.sys.usb0device 0' | Out-Null
}

$disabled = $false
$apps = [ordered]@{
    'com.htc.eventuploadservice'        = 'telemetria da HTC'
    'android.rockchip.update.service'   = 'atualizador do sistema (OTA)'
    'com.htc.htcotaupdate'              = '"Atualização on-line" da HTC (reinicia sozinho)'
    'com.htc.storeos'                   = 'loja/propaganda da HTC'
    'com.htc.htclauncherhighenglishd08' = 'launcher original da HTC (libera ~90 MB de RAM)'
}
$installed = Shell 'pm list packages -e'
foreach ($app in $apps.Keys) {
    if ($installed -notmatch [regex]::Escape("package:$app")) { continue }
    if (Ask "Desativar $($apps[$app]) ($app)?") {
        Save-Original "disabled:$app" 'enabled'
        Shell "pm disable-user --user 0 $app" | Out-Null
        $disabled = $true
    }
}

# Abre o launcher (ele liga o Filtro do controle sozinho com a permissão concedida)
Shell 'input keyevent KEYCODE_HOME' | Out-Null

Write-Host "`nPronto!" -ForegroundColor Green
Write-Host 'Recomendado: nas configurações de Projeção do projetor, desligue o keystone automático.'
Write-Host 'Ele é a principal causa dos "pulos" do controle.'
if (Test-Path $StateFile) { Write-Host "Valores originais anotados em: $StateFile (guarde para o desfazer)" }
if (Ask 'Reiniciar o projetor agora? (necessário para a correção do USB)') {
    if ($disabled) {
        # O Android grava os apps desativados com atraso; reiniciar antes perde a mudança
        Write-Host 'Aguardando 15 s para o sistema gravar as mudanças...'
        Start-Sleep -Seconds 15
    }
    Adb reboot
    Write-Host 'Se aparecer a escolha de tela inicial, escolha "Início" e "Sempre".'
}
