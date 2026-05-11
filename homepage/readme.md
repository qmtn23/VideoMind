# 项目首页模块

## 模块简介

`homepage` 是这个项目的官网展示模块，使用静态页面技术栈实现，提供项目介绍、功能展示和技术栈说明。

## 功能特性

### 界面特性

- 响应式布局，适配桌面端与移动端
- 深色/浅色主题切换
- 基于动画和粒子效果的视觉呈现

### 展示能力

- 展示项目核心功能和流程
- 展示后端、前端及 AI 相关技术栈
- 展示系统架构与页面模块

## 技术栈

- HTML5
- Tailwind CSS
- JavaScript (ES6+)
- GSAP
- Typed.js
- Particles.js

## 目录结构

```bash
homepage/
├── index.html
├── index.js
├── readme.md
├── package.json
├── assets/
├── css/
├── fonts/
├── public/
└── scripts/
```

## 本地开发

```bash
cd homepage
pnpm i
pnpm run tw:dev
pnpm run dev
```

## 构建与预览

```bash
pnpm run tw:build
pnpm run build
pnpm run preview
```

## 浏览器兼容性

- 现代浏览器：Chrome / Firefox / Safari / Edge
- 移动端：iOS Safari / Chrome Mobile

## 说明

本文档聚焦模块开发与维护，不包含任何商业推广信息。
