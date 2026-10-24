#!/bin/sh
# Desfaz o que o instalar.sh mudou no projetor, perguntando passo a passo.
# Usa os valores originais anotados em alteracoes.txt; sem esse arquivo,
# usa os valores de fábrica conhecidos do HY320.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
PKG=dev.hy320.launcher
STATE="$HERE/alteracoes.txt"

if [ -x "$HERE/adb" ]; then ADB="$HERE/adb"; elif command -v adb >/dev/null; then ADB=adb; else
    echo "ADB não encontrado."; exit 1
fi

ask() { printf '%s [s/N] ' "$1"; read -r a; case "$a" in [sSyY]*) return 0 ;; *) return 1 ;; esac; }
sh_() { "$ADB" -s "$DEV" shell "$@" | tr -d '\r'; }

echo
echo "=== Launcher HY320: desfazer ==="
printf 'IP do projetor: '; read -r IP
case "$IP" in *:*) DEV=$IP ;; *) DEV=$IP:5555 ;; esac
"$ADB" connect "$DEV"
[ "$("$ADB" -s "$DEV" get-state 2>/dev/null)" = device ] || { echo "Não foi possível conectar em $DEV."; exit 1; }

# Valores originais (ou os de fábrica do HY320)
SOUND=1; CEC=1; USB=1
HOME_ACTIVITY=com.htc.htclauncherhighenglishd08/com.htc.activity.HomeActivity
if [ -f "$STATE" ]; then
    echo "Usando os valores anotados em $STATE"
    value() { grep "^$1=" "$STATE" | head -1 | cut -d= -f2-; }
    SOUND=$(value sound_effects_enabled); SOUND=${SOUND:-1}
    CEC=$(value hdmi_control_enabled); CEC=${CEC:-1}
    USB=$(value persist.sys.usb0device); USB=${USB:-1}
    H=$(value home); HOME_ACTIVITY=${H:-$HOME_ACTIVITY}
    APPS=$(grep '^disabled:' "$STATE" | cut -d= -f1 | cut -d: -f2)
else
    echo "alteracoes.txt não encontrado: usando os valores de fábrica do HY320."
    APPS=$(sh_ pm list packages -d | sed 's/^package://' | grep -xE 'com\.htc\.eventuploadservice|android\.rockchip\.update\.service|com\.htc\.htcotaupdate|com\.htc\.storeos|com\.htc\.htclauncherhighenglishd08' || true)
fi

for app in $APPS; do
    ask "Reativar $app?" && sh_ pm enable "$app"
done
[ "$(sh_ settings get system sound_effects_enabled)" != "$SOUND" ] && ask "Voltar o som de clique das teclas?" &&
    sh_ settings put system sound_effects_enabled "$SOUND"
[ "$(sh_ settings get global hdmi_control_enabled)" != "$CEC" ] && ask "Religar o HDMI-CEC?" &&
    sh_ settings put global hdmi_control_enabled "$CEC"
[ "$(sh_ getprop persist.sys.usb0device)" != "$USB" ] && ask "Voltar a porta USB ao modo de fábrica? (pendrives deixam de funcionar)" &&
    sh_ setprop persist.sys.usb0device "$USB"
if sh_ pm list packages | grep -qx "package:$PKG" && ask "Remover o launcher e voltar à tela inicial original?"; then
    sh_ cmd package set-home-activity "$HOME_ACTIVITY" >/dev/null
    # Desligar o filtro antes de remover, preservando outros serviços de acessibilidade
    SERVICES=$(sh_ settings get secure enabled_accessibility_services | tr ':' '\n' | grep -v "$PKG" | grep -vx null | paste -sd: - || true)
    if [ -n "$SERVICES" ]; then
        sh_ settings put secure enabled_accessibility_services "$SERVICES"
    else
        sh_ settings delete secure enabled_accessibility_services >/dev/null
        sh_ settings put secure accessibility_enabled 0
    fi
    "$ADB" -s "$DEV" uninstall $PKG
fi

echo; echo "Pronto."
if ask "Reiniciar o projetor agora?"; then
    echo "Aguardando 15 s para o sistema gravar as mudanças..."
    sleep 15
    "$ADB" -s "$DEV" reboot
fi
