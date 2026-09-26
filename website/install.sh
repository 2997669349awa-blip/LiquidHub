#!/data/data/com.termux/files/usr/bin/sh
# Modified by AI Hello World on 2026-09-26.
# This file is part of LiquidHub, a fork of RikkaHub.
# Licensed under AGPL-3.0.
#
# Termux 一键安装 script:
#   curl -sL https://2997669349awa-blip.github.io/LiquidHub/install.sh | sh
set -e

REPO="2997669349awa-blip/LiquidHub"
PKG_URL="https://${REPO%%/*}.github.io/${REPO##*/}/likkahub-cli.tgz"

echo "== likkahub 安装 =="
if ! command -v pkg >/dev/null 2>&1; then
  echo "未检测到 pkg，请在 Termux 中运行本脚本。"
  exit 1
fi

echo ">> 安装 nodejs 与 ollama"
pkg install -y nodejs ollama

echo ">> 安装 likkahub"
if npm install -g "$PKG_URL"; then
  echo ">> 已从 $PKG_URL 安装"
else
  echo ">> 包地址不可用，回退到 GitHub 仓库安装"
  pkg install -y git
  tmp="$(mktemp -d)"
  git clone --depth 1 "https://github.com/${REPO}.git" "$tmp/liquidhub"
  npm install -g "$tmp/liquidhub/cli"
  rm -rf "$tmp"
fi

likkahub init

echo
echo "安装完成。示例："
echo "  likkahub model pull qwen2.5:1.5b"
echo "  likkahub go"
