#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# 机械臂上下左右 —— 手动测试脚本
# ═══════════════════════════════════════════════════════════════════════════
# 用法：
#   终端 A: mvn spring-boot:run          （我们：9988 HTTP + 10001 回调）
#   终端 B: mvn exec:java                 （模拟算法端：10000）—— 真实 C# 就绪后不用起
#   终端 C: bash scripts/test-arm-move.sh
#
# 脚本会对 4 个方向各发一次 MoveTask，并打印对端是否真的收到了。
# ═══════════════════════════════════════════════════════════════════════════

BASE="${BASE:-http://127.0.0.1:9988}"
SPEED="${SPEED:-5}"

echo "════════════════════════════════════════════"
echo " 机械臂移动测试  →  $BASE"
echo " 速度档位        →  $SPEED"
echo "════════════════════════════════════════════"

echo ""
echo "【第 0 步】连通性自检（不通就别往下走了）"
echo "────────────────────────────────────────────"
CHECK=$(curl -s "$BASE/api/ice/check")
echo "$CHECK"

if echo "$CHECK" | grep -q '"allUp":true'; then
    echo "✅ 算法端(10000) 可达"
else
    echo "❌ 算法端(10000) 不可达 —— 先看上面的 status 字段："
    echo "   CONNECTION_REFUSED  → 对端没启动，或端口不是 10000"
    echo "   IDENTITY_NOT_FOUND  → 暗号不一致，改 application.yaml 的 ice.client.*-identity"
    echo "   TYPE_MISMATCH       → 对端实现的是别的接口，看 ids 字段"
    echo ""
    read -p "仍然继续测试？(y/N) " ans
    [ "$ans" = "y" ] || [ "$ans" = "Y" ] || exit 1
fi

echo ""
echo "【第 1 步】四个方向各发一次 MoveTask"
echo "────────────────────────────────────────────"
for DIR in Up Down Left Right; do
    echo ""
    echo ">>> $DIR (speed=$SPEED)"
    RESP=$(curl -s -X POST "$BASE/api/arm/move?direction=$DIR&speed=$SPEED")
    echo "    响应: $RESP"
    if echo "$RESP" | grep -q 'EECSuccess'; then
        echo "    ✅ 对端 servant 已收到 MoveTask($DIR, $SPEED)"
    else
        echo "    ❌ 失败 —— 对端没收到或返回了错误码"
    fi
done

echo ""
echo "【第 2 步】本地回调端口(10001)自检"
echo "────────────────────────────────────────────"
curl -s "$BASE/api/ice/check/local"
echo ""

echo ""
echo "【第 3 步】查看收到的回调事件"
echo "────────────────────────────────────────────"
curl -s "$BASE/api/events"
echo ""

echo ""
echo "════════════════════════════════════════════"
echo " 完成。"
echo " 若对端是真实 C# 算法端，请同时看它的控制台，"
echo " 确认它打印出了对应的 MoveTask 指令。"
echo "════════════════════════════════════════════"
