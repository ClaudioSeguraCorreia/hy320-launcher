#!/bin/sh
# Instala o launcher no projetor HY320 pelo ADB por Wi-Fi (Linux/macOS).
# Mesmos passos do instalar.ps1: cada ajuste opcional é perguntado antes e o
# valor original fica anotado em alteracoes.txt para o desfazer.sh.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
PKG=dev.hy320.launcher
STATE="$HERE/alteracoes.txt"

if [ -x "$HERE/adb" ]; then ADB="$HERE/adb"; elif command -v adb >/dev/null; then ADB=adb; else
    echo "ADB não encontrado. Instale o Android SDK Platform-Tools:"
    echo "  https://developer.android.com/tools/releases/platform-tools"
    exit 1
fi

ask() { printf '%s [s/N] ' "$1"; read -r a; case "$a" in [sSyY]*) return 0 ;; *) return 1 ;; esac; }
sh_() { "$ADB" -s "$DEV" shell "$@" | tr -d '\r'; }
# Anota o valor original só na primeira vez, para não perder o de fábrica ao rodar de novo
save_original() { grep -q "^$1=" "$STATE" 2>/dev/null || echo "$1=$2" >> "$STATE"; }

echo
echo "=== Launcher HY320: instalação ==="
echo "No projetor, a depuração por rede (ADB) precisa estar ligada. Veja o README."
printf 'IP do projetor (ex.: 192.168.0.50): '; read -r IP
case "$IP" in *:*) DEV=$IP ;; *) DEV=$IP:5555 ;; esac
"$ADB" connect "$DEV"
echo 'Se o projetor perguntar "Permitir depuração", escolha Permitir (marque "Sempre").'
i=0
until [ "$("$ADB" -s "$DEV" get-state 2>/dev/null)" = device ]; do
    i=$((i + 1)); [ $i -ge 30 ] && { echo "Não foi possível conectar em $DEV."; exit 1; }
    sleep 1
done

echo "Conectado: $(sh_ getprop ro.product.model) (plataforma $(sh_ getprop ro.board.platform), Android API $(sh_ getprop ro.build.version.sdk))"
if [ "$(sh_ getprop ro.build.version.sdk)" != 30 ]; then
    echo "Atenção: o launcher foi feito para Android 11 (API 30)."
    ask "Continuar mesmo assim?" || exit 1
fi

# 1. Instalar o APK
APK=$(ls -t "$HERE"/*.apk 2>/dev/null | head -1)
[ -n "$APK" ] || { echo "Coloque o arquivo .apk do launcher nesta pasta."; exit 1; }
echo; echo "1. Instalando $(basename "$APK")..."
OUT=$("$ADB" -s "$DEV" install -r "$APK" 2>&1 || true)
echo "$OUT"
case "$OUT" in
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*) echo "Há uma versão com outra assinatura instalada. Rode o desfazer.sh (passo de remover o launcher) e tente de novo."; exit 1 ;;
    *Success*) ;;
    *) exit 1 ;;
esac

# 2. Permissões: ler as imagens próprias e manter o Filtro do controle ligado
echo "2. Concedendo permissões (imagens e Filtro do controle)..."
sh_ pm grant $PKG android.permission.READ_EXTERNAL_STORAGE
sh_ pm grant $PKG android.permission.WRITE_SECURE_SETTINGS

# 3. Tela inicial padrão
echo "3. Definindo como tela inicial..."
CURRENT_HOME=$(sh_ cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -1)
case "$CURRENT_HOME" in *$PKG*|"") ;; *) save_original home "$CURRENT_HOME" ;; esac
sh_ cmd package set-home-activity $PKG/.MainActivity >/dev/null

echo; echo "--- Ajustes opcionais (recomendados no HY320) ---"
SOUND=$(sh_ settings get system sound_effects_enabled)
if [ "$SOUND" != 0 ] && ask "Desligar o som de clique das teclas? (reduz travadas do controle)"; then
    save_original sound_effects_enabled "$SOUND"
    sh_ settings put system sound_effects_enabled 0
fi
CEC=$(sh_ settings get global hdmi_control_enabled)
if [ "$CEC" != 0 ] && ask "Desligar o HDMI-CEC? (o projetor deixa de consultar o HDMI a cada 30 s)"; then
    save_original hdmi_control_enabled "$CEC"
    sh_ settings put global hdmi_control_enabled 0
fi
USB=$(sh_ getprop persist.sys.usb0device)
if [ "$USB" = 1 ] && ask "Corrigir a porta USB para reconhecer pendrives? (vem de fábrica em modo dispositivo)"; then
    save_original persist.sys.usb0device "$USB"
    sh_ setprop persist.sys.usb0device 0
fi

DISABLED=
ENABLED_APPS=$(sh_ pm list packages -e)
for entry in \
    "com.htc.eventuploadservice|telemetria da HTC" \
    "android.rockchip.update.service|atualizador do sistema (OTA)" \
    "com.htc.htcotaupdate|\"Atualização on-line\" da HTC (reinicia sozinho)" \
    "com.htc.storeos|loja/propaganda da HTC" \
    "com.htc.htclauncherhighenglishd08|launcher original da HTC (libera ~90 MB de RAM)"; do
    app=${entry%%|*}; label=${entry#*|}
    echo "$ENABLED_APPS" | grep -qx "package:$app" || continue
    if ask "Desativar $label ($app)?"; then
        save_original "disabled:$app" enabled
        sh_ pm disable-user --user 0 "$app"
        DISABLED=1
    fi
done

# Abre o launcher (ele liga o Filtro do controle sozinho com a permissão concedida)
sh_ input keyevent KEYCODE_HOME

echo; echo "Pronto!"
echo "Recomendado: nas configurações de Projeção do projetor, desligue o keystone automático."
echo 'Ele é a principal causa dos "pulos" do controle.'
[ -f "$STATE" ] && echo "Valores originais anotados em: $STATE (guarde para o desfazer)"
if ask "Reiniciar o projetor agora? (necessário para a correção do USB)"; then
    if [ -n "$DISABLED" ]; then
        # O Android grava os apps desativados com atraso; reiniciar antes perde a mudança
        echo "Aguardando 15 s para o sistema gravar as mudanças..."
        sleep 15
    fi
    "$ADB" -s "$DEV" reboot
    echo 'Se aparecer a escolha de tela inicial, escolha "Início" e "Sempre".'
fi
