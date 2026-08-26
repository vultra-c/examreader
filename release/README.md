# 安装包

本目录只存放当前版本的可安装构建产物，避免在各个源码目录中查找。

| 文件 | 平台 | 包名 | 版本 | 说明 |
|------|------|------|------|------|
| [`com.whyy.snapnotes.release.V26.8.30.BAND.rpk`](com.whyy.snapnotes.release.V26.8.30.BAND.rpk) | 小米 Vela | `com.whyy.snapnotes` | `V26.8.30.BAND` | 考点阅读器（闪念小抄） |
| [`com.whyy.chemcalc.release.V26.8.39.CALC.rpk`](com.whyy.chemcalc.release.V26.8.39.CALC.rpk) | 小米 Vela | `com.whyy.chemcalc` | `V26.8.39.CALC` | 化学工具箱 |
| [`com.whyy.snapnotes.android.v1.0.1-debug.apk`](com.whyy.snapnotes.android.v1.0.1-debug.apk) | Android | `com.whyy.snapnotes` | `1.0.1 (2)` | 手机端配套应用 |

## 安装说明

- `.rpk` 为已签名的 Vela 安装包，请使用 Vela 开发工具或设备调试工具安装。
- `.apk` 为已完成本地构建并通过 v1/v2 签名校验的 Android Debug 安装包。

## 源码位置

| 应用 | 源码目录 |
|------|----------|
| 考点阅读器 | [`../考点阅读器/`](../考点阅读器/) |
| 化学工具箱 | [`../化学计算器/`](../化学计算器/) |
| Android 手机端 | [`../android_src/snapnotes-android/`](../android_src/snapnotes-android/) |

## 重新构建

```bash
# Vela 应用
cd ../考点阅读器 && npm install && npm run release
cp dist/*.rpk ../release/

cd ../化学计算器 && npm install && npm run release
cp dist/*.rpk ../release/
```
